//! Reusable TLAS storage and completed-slot upload buffers.
use super::FRAME_SLOTS;
use super::resources::{Acceleration, Buffer, Context, PreparedAcceleration};
use ash::vk;
use std::sync::Arc;

#[derive(Default)]
struct PendingInstances {
    indices: Vec<usize>,
    present: Vec<u64>,
}
impl PendingInstances {
    fn extend(&mut self, changes: &[usize]) {
        for &index in changes {
            let word = index / 64;
            if word >= self.present.len() {
                self.present.resize(word + 1, 0);
            }
            let mask = 1_u64 << (index % 64);
            if self.present[word] & mask == 0 {
                self.present[word] |= mask;
                self.indices.push(index);
            }
        }
    }
    fn sorted(&mut self) -> &[usize] {
        self.indices.sort_unstable();
        &self.indices
    }
    fn clear(&mut self) {
        // Touch only dirty indices, retaining both capacities. Dormant GPU slots
        // coalesce repeated edits without an unbounded journal or a full bit scan.
        for &index in &self.indices {
            self.present[index / 64] &= !(1_u64 << (index % 64));
        }
        self.indices.clear();
    }
}

#[derive(Default)]
pub(super) struct TopLevel {
    build: Option<PreparedAcceleration<'static>>,
    inputs: [Option<Buffer>; FRAME_SLOTS],
    capacity: u32,
    pending: [PendingInstances; FRAME_SLOTS],
    full: [bool; FRAME_SLOTS],
    generation: u64,
    applied: [u64; FRAME_SLOTS],
}

impl TopLevel {
    #[allow(clippy::too_many_arguments)]
    pub fn rebuild(
        &mut self,
        context: &Arc<Context>,
        terrain: &[vk::AccelerationStructureInstanceKHR],
        objects: &[vk::AccelerationStructureInstanceKHR],
        changed: &[usize],
        terrain_changed: bool,
        slot: usize,
        builds: &mut crate::arena::Arena,
    ) -> Result<bool, String> {
        self.generation = self
            .generation
            .checked_add(1)
            .ok_or("TLAS upload generation exhausted")?;
        for index in 0..FRAME_SLOTS {
            self.pending[index].extend(changed);
            self.full[index] |= terrain_changed;
        }
        let count =
            u32::try_from(terrain.len() + objects.len()).map_err(|_| "TLAS instance overflow")?;
        let capacity = count
            .max(1)
            .checked_next_power_of_two()
            .ok_or("TLAS capacity overflow")?;
        self.full[slot] |= self.inputs[slot]
            .as_ref()
            .is_none_or(|b| b.size < u64::from(capacity) * 64);
        let input = slot_buffer(
            context,
            &mut self.inputs[slot],
            u64::from(capacity) * 64,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
        )?;
        if self.full[slot] {
            write_instances(input, 0, terrain)?;
            write_instances(input, terrain.len(), objects)?;
        } else {
            // Consecutive dirty identities become one memory copy. Skipped slots retain
            // the union of edits until their own GPU completion permits a write.
            let mut pending = self.pending[slot]
                .sorted()
                .iter()
                .copied()
                .filter(|&i| i < objects.len())
                .peekable();
            while let Some(start) = pending.next() {
                let mut end = start + 1;
                while pending.peek() == Some(&end) {
                    pending.next();
                    end += 1;
                }
                write_instances(input, terrain.len() + start, &objects[start..end])?;
            }
        }
        self.pending[slot].clear();
        self.full[slot] = false;
        self.applied[slot] = self.generation;
        let data = vk::AccelerationStructureGeometryInstancesDataKHR::default().data(
            vk::DeviceOrHostAddressConstKHR {
                device_address: input.address(),
            },
        );
        let geometry = vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::INSTANCES)
            .geometry(vk::AccelerationStructureGeometryDataKHR { instances: data });
        let replaced = self.build.is_none() || capacity > self.capacity;
        if replaced {
            if let Some(old) = self.build.take() {
                old.retire(builds);
            }
            self.build = Some(Acceleration::prepare_with_flags(
                context,
                builds,
                geometry,
                capacity,
                vk::AccelerationStructureTypeKHR::TOP_LEVEL,
                vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_BUILD,
            )?);
            self.capacity = capacity;
        }
        self.build.as_mut().unwrap().ensure_scratch(builds)?;
        context.submit_named("tlas_batch", |command| {
            self.build
                .as_ref()
                .unwrap()
                .record_geometry(command, geometry, count);
            Acceleration::read_barrier(context, command);
        })?;
        self.build.as_mut().unwrap().release_scratch(builds);
        Ok(replaced)
    }

    pub fn handle(&self) -> vk::AccelerationStructureKHR {
        self.build.as_ref().unwrap().acceleration().handle
    }

    #[cfg(test)]
    pub fn generation(&self) -> u64 {
        self.generation
    }

    #[cfg(test)]
    pub fn assert_current_input(
        &self,
        terrain: &[vk::AccelerationStructureInstanceKHR],
        objects: &[vk::AccelerationStructureInstanceKHR],
    ) {
        let slot = self
            .applied
            .iter()
            .position(|&value| value == self.generation)
            .unwrap();
        let bytes = self.inputs[slot]
            .as_ref()
            .unwrap()
            .read((terrain.len() + objects.len()) * 64)
            .unwrap();
        let expected: Vec<u8> = terrain
            .iter()
            .chain(objects)
            .flat_map(|instance| {
                // SAFETY: ash's repr(C) instance records have no padding, exactly 64 initialized bytes.
                unsafe { std::slice::from_raw_parts(std::ptr::from_ref(instance).cast::<u8>(), 64) }
            })
            .copied()
            .collect();
        assert_eq!(
            bytes, expected,
            "completed upload slots must accumulate all skipped generations"
        );
    }
}

pub(crate) fn slot_buffer<'a>(
    context: &Arc<Context>,
    buffer: &'a mut Option<Buffer>,
    bytes: u64,
    usage: vk::BufferUsageFlags,
) -> Result<&'a Buffer, String> {
    if buffer.as_ref().is_none_or(|old| old.size < bytes) {
        *buffer = Some(Buffer::new(
            context,
            bytes
                .max(16)
                .checked_next_power_of_two()
                .ok_or("Upload capacity overflow")?,
            usage,
            true,
        )?);
    }
    Ok(buffer.as_ref().unwrap())
}

fn write_instances(
    input: &Buffer,
    first: usize,
    instances: &[vk::AccelerationStructureInstanceKHR],
) -> Result<(), String> {
    // ash uses repr(C); the Vulkan instance structure is exactly 64 bytes.
    let bytes = unsafe {
        std::slice::from_raw_parts(
            instances.as_ptr().cast::<u8>(),
            std::mem::size_of_val(instances),
        )
    };
    input.write_at(first as u64 * 64, bytes)
}
