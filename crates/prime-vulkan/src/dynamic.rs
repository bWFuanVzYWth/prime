//! One dynamic BLAS per snapshot. GPU storage/scratch retain capacity; CPU-written
//! staging is reused only through a descriptor slot with proven host completion.
use super::resources::{Acceleration, Buffer, Context, PreparedAcceleration};
use super::textures::Textures;
use super::{FRAME_SLOTS, float, float4, uint};
use ash::vk;
use prime_scene::Triangle;
use std::sync::Arc;

pub(super) const DYNAMIC_INSTANCE: u32 = 0x00ff_ffff;

pub(super) struct DynamicGeometry {
    build: PreparedAcceleration<'static>,
    pub data: Buffer,
    uploads: [Option<Buffer>; FRAME_SLOTS],
    bytes: Vec<u8>,
    pub capacity: u32,
}

impl DynamicGeometry {
    pub fn new(context: &Arc<Context>, count: u32) -> Result<Self, String> {
        let capacity = count
            .max(16)
            .checked_next_power_of_two()
            .ok_or("Dynamic capacity overflow")?;
        let data = Buffer::new(
            context,
            u64::from(capacity) * 140,
            vk::BufferUsageFlags::STORAGE_BUFFER
                | vk::BufferUsageFlags::TRANSFER_DST
                | vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            false,
        )?;
        let geometry = triangle_geometry(data.address(), capacity);
        let build = Acceleration::prepare_with_flags(
            context,
            geometry,
            capacity,
            vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL,
            vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_BUILD,
        )?;
        Ok(Self {
            build,
            data,
            uploads: std::array::from_fn(|_| None),
            bytes: Vec::new(),
            capacity,
        })
    }

    pub fn upload(
        &mut self,
        context: &Arc<Context>,
        triangles: &[Triangle],
        textures: &Textures,
        slot: usize,
    ) -> Result<(), String> {
        let count =
            u32::try_from(triangles.len()).map_err(|_| "Dynamic triangle count overflow")?;
        if count == 0 || count > self.capacity {
            return Err("Invalid dynamic upload capacity".into());
        }
        self.bytes.clear();
        for triangle in triangles {
            if triangle.flags > 2 {
                return Err("Unsupported dynamic material flags".into());
            }
            for position in triangle.positions {
                if position.iter().any(|value| !value.is_finite()) {
                    return Err("Non-finite dynamic position".into());
                }
                float4(
                    &mut self.bytes,
                    [position[0], position[1], position[2], 0.0],
                );
            }
            for color in triangle.colors {
                if color
                    .iter()
                    .any(|value| !value.is_finite() || !(0.0..=1.0).contains(value))
                {
                    return Err("Invalid dynamic vertex color".into());
                }
                float4(&mut self.bytes, color);
            }
            for uv in triangle.uvs {
                for value in uv {
                    if !value.is_finite() {
                        return Err("Non-finite dynamic UV".into());
                    }
                    float(&mut self.bytes, value);
                }
            }
            uint(&mut self.bytes, textures.index(triangle.texture_id)?);
            uint(&mut self.bytes, triangle.flags);
        }
        // Positions are the first three float4s of each 128B material record.
        // Indexed AS input avoids repacking a second copy of vertex positions.
        for triangle in 0..count {
            for vertex in 0..3 {
                uint(&mut self.bytes, triangle * 8 + vertex);
            }
        }
        let upload = slot_buffer(
            context,
            &mut self.uploads[slot],
            u64::from(self.capacity) * 140,
            vk::BufferUsageFlags::TRANSFER_SRC,
        )?;
        upload.write(&self.bytes)?;
        let geometry = triangle_geometry(self.data.address(), self.capacity);
        context.submit_named("dynamic_batch", |command| unsafe {
            context.device.cmd_copy_buffer(
                command,
                upload.buffer,
                self.data.buffer,
                &[
                    vk::BufferCopy::default().size(u64::from(count) * 128),
                    vk::BufferCopy::default()
                        .src_offset(u64::from(count) * 128)
                        .dst_offset(u64::from(self.capacity) * 128)
                        .size(u64::from(count) * 12),
                ],
            );
            let barrier = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(
                    vk::AccessFlags::SHADER_READ | vk::AccessFlags::ACCELERATION_STRUCTURE_READ_KHR,
                )];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::ACCELERATION_STRUCTURE_BUILD_KHR
                    | vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &barrier,
                &[],
                &[],
            );
            self.build.record_geometry(command, geometry, count);
            Acceleration::read_barrier(context, command);
        })
    }

    pub fn address(&self) -> u64 {
        self.build.acceleration().address()
    }
}

fn triangle_geometry(address: u64, capacity: u32) -> vk::AccelerationStructureGeometryKHR<'static> {
    let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
        .vertex_format(vk::Format::R32G32B32_SFLOAT)
        .vertex_data(vk::DeviceOrHostAddressConstKHR {
            device_address: address,
        })
        .vertex_stride(16)
        .max_vertex(capacity * 8 - 1)
        .index_type(vk::IndexType::UINT32)
        .index_data(vk::DeviceOrHostAddressConstKHR {
            device_address: address + u64::from(capacity) * 128,
        });
    vk::AccelerationStructureGeometryKHR::default()
        .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
        .geometry(vk::AccelerationStructureGeometryDataKHR { triangles })
}

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

fn slot_buffer<'a>(
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
