//! Reusable TLAS storage and completed-slot upload buffers.
use super::FRAME_SLOTS;
use super::resources::{Acceleration, Buffer, Context, PreparedAcceleration};
use ash::vk;
use std::sync::Arc;

#[derive(Default)]
pub(super) struct TopLevel {
    build: Option<PreparedAcceleration<'static>>,
    inputs: [Option<Buffer>; FRAME_SLOTS],
    capacity: u32,
}

impl TopLevel {
    pub fn rebuild(
        &mut self,
        context: &Arc<Context>,
        instances: &[vk::AccelerationStructureInstanceKHR],
        slot: usize,
    ) -> Result<bool, String> {
        let count = u32::try_from(instances.len()).map_err(|_| "TLAS instance overflow")?;
        let capacity = count
            .max(1)
            .checked_next_power_of_two()
            .ok_or("TLAS capacity overflow")?;
        let input = slot_buffer(
            context,
            &mut self.inputs[slot],
            u64::from(capacity) * 64,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
        )?;
        // Vulkan defines each instance as its packed 64-byte structure; ash uses repr(C).
        let bytes = unsafe {
            std::slice::from_raw_parts(instances.as_ptr().cast::<u8>(), instances.len() * 64)
        };
        input.write(bytes)?;
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
            self.build = Some(Acceleration::prepare_with_flags(
                context,
                geometry,
                capacity,
                vk::AccelerationStructureTypeKHR::TOP_LEVEL,
                vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_BUILD,
            )?);
            self.capacity = capacity;
        }
        context.submit_named("tlas_batch", |command| {
            self.build
                .as_ref()
                .unwrap()
                .record_geometry(command, geometry, count);
            Acceleration::read_barrier(context, command);
        })?;
        Ok(replaced)
    }

    pub fn handle(&self) -> vk::AccelerationStructureKHR {
        self.build.as_ref().unwrap().acceleration().handle
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
