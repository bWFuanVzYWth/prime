//! One immutable micromap per resource generation; terrain construction only binds indices.
use crate::arena::{Arena, Lease};
use crate::omm_cpu::{Block, Data, UNKNOWN};
use crate::resources::{Context, error};
use ash::vk;
use std::{
    collections::{BTreeMap, BTreeSet},
    rc::Rc,
    sync::Arc,
};

struct Shared {
    context: Arc<Context>,
    handle: vk::MicromapEXT,
    storage: Option<Lease>,
    blocks: Vec<Block>,
    packed_bytes: u64,
}
impl Drop for Shared {
    fn drop(&mut self) {
        self.context.retire_micromap(self.handle);
    }
}
impl Shared {
    fn retire(mut self, arena: &mut Arena) {
        if let Some(storage) = self.storage.take() {
            arena.retire(storage);
        }
    }
}

/// A BLAS keeps its generation alive. Unloading a cell never removes the pool's owner.
pub(crate) struct Micromap {
    _shared: Option<Rc<Shared>>,
}
impl Micromap {
    pub fn retire(self, _arena: &mut Arena) {
        drop(self);
    }
}

#[derive(Default)]
pub(crate) struct Pool {
    current: Option<Rc<Shared>>,
    retired: Vec<Rc<Shared>>,
    builds: u64,
}
impl Pool {
    pub fn new() -> Self {
        Self::default()
    }
    /// Replace all resource templates before recording any new BLAS bindings.
    /// Old BLAS references remain valid while the replacement build is in flight.
    pub fn replace(
        &mut self,
        context: &Arc<Context>,
        arena: &mut Arena,
        uploads: &mut Arena,
        data: &Data,
    ) -> Result<(), String> {
        let replacement = if data.triangles.is_empty() {
            None
        } else {
            let prepared = PreparedShared::new(context, arena, uploads, data)?;
            context.submit_named("omm_resource_templates", |command| {
                prepared.record_copy(command);
                upload_barrier(context, command);
                prepared.record_build(command);
                build_barrier(context, command);
            })?;
            self.builds += 1;
            Some(Rc::new(prepared.finish(arena, uploads)))
        };
        if let Some(old) = std::mem::replace(&mut self.current, replacement) {
            self.retired.push(old);
        }
        self.collect(arena);
        Ok(())
    }
    /// Only this BLAS's global template index array is uploaded.
    pub fn bind(
        &self,
        context: &Arc<Context>,
        arena: &mut Arena,
        uploads: &mut Arena,
        indices: &[u32],
    ) -> Result<Option<Prepared>, String> {
        let mut counts = BTreeMap::new();
        let mut blocks = BTreeSet::new();
        let mut stats = [0; 4];
        let mut useful = false;
        for &index in indices {
            if index >= 0xffff_fffc {
                stats[2] += 1;
                useful |= index != UNKNOWN;
                continue;
            }
            let block = self
                .current
                .as_ref()
                .and_then(|s| s.blocks.get(index as usize))
                .ok_or("OMM binding references a template outside its resource generation")?;
            *counts
                .entry((u32::from(block.format), u32::from(block.level)))
                .or_default() += 1;
            useful = true;
            if blocks.insert(index) {
                stats[usize::from(block.format == 2)] += 1;
                let bits = if block.format == 2 { 2 } else { 1 };
                stats[3] += ((1_u64 << (2 * block.level)) * bits).div_ceil(8);
            }
        }
        if !useful {
            return Ok(None);
        }
        let binding_usage = counted_usage(counts);
        let shared = (!binding_usage.is_empty()).then(|| self.current.as_ref().unwrap().clone());
        let handle = shared
            .as_ref()
            .map_or(vk::MicromapEXT::null(), |s| s.handle);
        let bytes = indices.len() as u64 * 4;
        let input = arena.allocate(context, bytes, 4)?;
        let mut upload = uploads.allocate(context, bytes, 16)?;
        upload.write_with(|output| {
            for (word, &index) in output.as_chunks_mut::<4>().0.iter_mut().zip(indices) {
                for (destination, byte) in word.iter_mut().zip(index.to_le_bytes()) {
                    destination.write(byte);
                }
            }
            Ok(())
        })?;
        let mut attachment = Box::new(
            vk::AccelerationStructureTrianglesOpacityMicromapEXT::default()
                .index_type(vk::IndexType::UINT32)
                .index_buffer(vk::DeviceOrHostAddressConstKHR {
                    device_address: input.address(),
                })
                .index_stride(4)
                .micromap(handle),
        );
        attachment.usage_counts_count = binding_usage.len() as u32;
        attachment.p_usage_counts = if binding_usage.is_empty() {
            std::ptr::null()
        } else {
            binding_usage.as_ptr()
        };
        Ok(Some(Prepared {
            context: context.clone(),
            owner: Micromap { _shared: shared },
            input,
            upload,
            attachment,
            stats,
            _binding_usage: binding_usage,
        }))
    }
    /// Resource builds, current unique blocks, packed bytes, and actual shared storage bytes.
    pub fn stats(&self) -> [u64; 4] {
        let mut result = [self.builds, 0, 0, 0];
        if let Some(current) = &self.current {
            result[1] = current.blocks.len() as u64;
            result[2] = current.packed_bytes;
            result[3] = current.storage.as_ref().unwrap().size;
        }
        result
    }
    /// Referenced unique two/four blocks, special primitives, and referenced packed bytes.
    /// These describe coverage; the pool owns actual shared storage only once.
    #[cfg(test)]
    pub fn binding_stats(&self, indices: &[u32]) -> [u64; 4] {
        let mut result = [0; 4];
        let mut blocks = BTreeSet::new();
        for &index in indices {
            if index >= 0xffff_fffc {
                result[2] += 1;
            } else {
                blocks.insert(index);
            }
        }
        if let Some(current) = &self.current {
            for index in blocks {
                let block = current.blocks[index as usize];
                result[usize::from(block.format == 2)] += 1;
                let bits = if block.format == 2 { 2 } else { 1 };
                result[3] += ((1_u64 << (2 * block.level)) * bits).div_ceil(8);
            }
        }
        result
    }
    /// Only the pool's reference remains; retire handle and storage against completion.
    pub fn collect(&mut self, arena: &mut Arena) {
        let mut index = 0;
        while index < self.retired.len() {
            if Rc::strong_count(&self.retired[index]) == 1 {
                let shared = self.retired.swap_remove(index);
                Rc::try_unwrap(shared).ok().unwrap().retire(arena);
            } else {
                index += 1;
            }
        }
    }
}

pub(crate) struct Prepared {
    context: Arc<Context>,
    owner: Micromap,
    input: Lease,
    upload: Lease,
    pub attachment: Box<vk::AccelerationStructureTrianglesOpacityMicromapEXT<'static>>,
    /// Referenced unique two/four blocks, special primitives, and referenced packed bytes.
    pub stats: [u64; 4],
    // Stable pointee through all BLAS size queries and command recording.
    _binding_usage: Vec<vk::MicromapUsageEXT>,
}
impl Prepared {
    pub fn record_copy(&self, command: vk::CommandBuffer) {
        copy(&self.context, command, &self.upload, &self.input);
    }
    pub fn finish(self, arena: &mut Arena, uploads: &mut Arena) -> Micromap {
        arena.retire(self.input);
        uploads.retire(self.upload);
        self.owner
    }
}

struct PreparedShared {
    owner: Shared,
    input: Lease,
    upload: Lease,
    scratch: Lease,
    descriptors_offset: u64,
    build_usage: Vec<vk::MicromapUsageEXT>,
}
impl PreparedShared {
    fn new(
        context: &Arc<Context>,
        arena: &mut Arena,
        uploads: &mut Arena,
        data: &Data,
    ) -> Result<Self, String> {
        let support = context
            .opacity_micromap
            .as_ref()
            .expect("OMM capability checked");
        let build_usage = usage(
            data.triangles
                .iter()
                .map(|b| (u32::from(b.format), u32::from(b.level))),
        );
        let descriptors_offset = (data.blocks.len() as u64).div_ceil(256) * 256;
        let mut bytes = data.blocks.clone();
        bytes.resize(descriptors_offset as usize, 0);
        for block in &data.triangles {
            bytes.extend(block.offset.to_le_bytes());
            bytes.extend(block.level.to_le_bytes());
            bytes.extend(block.format.to_le_bytes());
        }
        let input = arena.allocate(context, bytes.len() as u64, 256)?;
        let upload = uploads.allocate(context, bytes.len() as u64, 16)?;
        upload.write(&bytes)?;
        let info = vk::MicromapBuildInfoEXT::default()
            .ty(vk::MicromapTypeEXT::OPACITY_MICROMAP)
            .flags(vk::BuildMicromapFlagsEXT::PREFER_FAST_TRACE)
            .mode(vk::BuildMicromapModeEXT::BUILD)
            .usage_counts(&build_usage);
        let mut sizes = vk::MicromapBuildSizesInfoEXT::default();
        unsafe {
            (support.loader.fp().get_micromap_build_sizes_ext)(
                context.device.handle(),
                vk::AccelerationStructureBuildTypeKHR::DEVICE,
                &info,
                &mut sizes,
            );
        }
        let storage = arena.allocate(context, sizes.micromap_size, 256)?;
        let scratch =
            arena.allocate(context, sizes.build_scratch_size, context.scratch_alignment)?;
        let create = vk::MicromapCreateInfoEXT::default()
            .ty(vk::MicromapTypeEXT::OPACITY_MICROMAP)
            .buffer(storage.buffer.buffer)
            .offset(storage.offset)
            .size(sizes.micromap_size);
        let mut handle = vk::MicromapEXT::null();
        let result = unsafe {
            (support.loader.fp().create_micromap_ext)(
                context.device.handle(),
                &create,
                std::ptr::null(),
                &mut handle,
            )
        };
        if result != vk::Result::SUCCESS {
            return Err(error("Create resource opacity micromap", result));
        }
        Ok(Self {
            owner: Shared {
                context: context.clone(),
                handle,
                storage: Some(storage),
                blocks: data.triangles.clone(),
                packed_bytes: data.blocks.len() as u64,
            },
            input,
            upload,
            scratch,
            descriptors_offset,
            build_usage,
        })
    }
    fn record_copy(&self, command: vk::CommandBuffer) {
        copy(&self.owner.context, command, &self.upload, &self.input);
    }
    fn record_build(&self, command: vk::CommandBuffer) {
        let info = vk::MicromapBuildInfoEXT::default()
            .ty(vk::MicromapTypeEXT::OPACITY_MICROMAP)
            .flags(vk::BuildMicromapFlagsEXT::PREFER_FAST_TRACE)
            .mode(vk::BuildMicromapModeEXT::BUILD)
            .dst_micromap(self.owner.handle)
            .usage_counts(&self.build_usage)
            .data(vk::DeviceOrHostAddressConstKHR {
                device_address: self.input.address(),
            })
            .triangle_array(vk::DeviceOrHostAddressConstKHR {
                device_address: self.input.address() + self.descriptors_offset,
            })
            .triangle_array_stride(8)
            .scratch_data(vk::DeviceOrHostAddressKHR {
                device_address: self.scratch.address(),
            });
        unsafe {
            (self
                .owner
                .context
                .opacity_micromap
                .as_ref()
                .unwrap()
                .loader
                .fp()
                .cmd_build_micromaps_ext)(command, 1, &info);
        }
    }
    fn finish(self, arena: &mut Arena, uploads: &mut Arena) -> Shared {
        arena.retire(self.input);
        arena.retire(self.scratch);
        uploads.retire(self.upload);
        self.owner
    }
}
fn usage(entries: impl Iterator<Item = (u32, u32)>) -> Vec<vk::MicromapUsageEXT> {
    let mut counts = BTreeMap::<_, u32>::new();
    for entry in entries {
        *counts.entry(entry).or_default() += 1;
    }
    counted_usage(counts)
}
fn counted_usage(counts: BTreeMap<(u32, u32), u32>) -> Vec<vk::MicromapUsageEXT> {
    counts
        .into_iter()
        .map(|((format, level), count)| {
            vk::MicromapUsageEXT::default()
                .format(format)
                .subdivision_level(level)
                .count(count)
        })
        .collect()
}
fn copy(context: &Context, command: vk::CommandBuffer, upload: &Lease, input: &Lease) {
    unsafe {
        context.device.cmd_copy_buffer(
            command,
            upload.buffer.buffer,
            input.buffer.buffer,
            &[vk::BufferCopy::default()
                .src_offset(upload.offset)
                .dst_offset(input.offset)
                .size(input.size)],
        );
    }
}

pub(crate) fn upload_barrier(context: &Context, command: vk::CommandBuffer) {
    barrier(
        context,
        command,
        vk::PipelineStageFlags2::TRANSFER,
        vk::AccessFlags2::TRANSFER_WRITE,
        vk::PipelineStageFlags2::MICROMAP_BUILD_EXT
            | vk::PipelineStageFlags2::ACCELERATION_STRUCTURE_BUILD_KHR,
        vk::AccessFlags2::SHADER_READ
            | vk::AccessFlags2::MICROMAP_READ_EXT
            | vk::AccessFlags2::ACCELERATION_STRUCTURE_READ_KHR,
    );
}
fn build_barrier(context: &Context, command: vk::CommandBuffer) {
    barrier(
        context,
        command,
        vk::PipelineStageFlags2::MICROMAP_BUILD_EXT,
        vk::AccessFlags2::MICROMAP_WRITE_EXT,
        vk::PipelineStageFlags2::ACCELERATION_STRUCTURE_BUILD_KHR,
        vk::AccessFlags2::MICROMAP_READ_EXT,
    );
}
fn barrier(
    context: &Context,
    command: vk::CommandBuffer,
    source: vk::PipelineStageFlags2,
    source_access: vk::AccessFlags2,
    destination: vk::PipelineStageFlags2,
    destination_access: vk::AccessFlags2,
) {
    let barriers = [vk::MemoryBarrier2::default()
        .src_stage_mask(source)
        .src_access_mask(source_access)
        .dst_stage_mask(destination)
        .dst_access_mask(destination_access)];
    unsafe {
        context
            .opacity_micromap
            .as_ref()
            .unwrap()
            .synchronization
            .cmd_pipeline_barrier2(
                command,
                &vk::DependencyInfo::default().memory_barriers(&barriers),
            );
    }
}

#[cfg(all(test, feature = "shader-tests"))]
mod tests {
    use super::*;
    use crate::omm_cpu::{OPAQUE, Stats, TRANSPARENT};

    #[test]
    #[ignore = "requires OMM-capable Vulkan hardware; validates shared generation binding and retirement"]
    fn gpu_omm_pool_retains_templates_across_bindings_and_resource_replacement() {
        let context = Context::new().unwrap();
        context.opacity_micromap.as_ref().expect(
            "OMM pool lifetime test requires VK_EXT_opacity_micromap; unsupported is unverified",
        );
        let mut arena = Arena::new(&context, false);
        let mut uploads = Arena::new(&context, true);
        let data = Data {
            blocks: vec![0x55, 0xaa, 0xe4],
            triangles: vec![
                Block {
                    offset: 0,
                    level: 2,
                    format: 1,
                },
                Block {
                    offset: 2,
                    level: 1,
                    format: 2,
                },
            ],
            indices: Vec::new(),
            textures: BTreeSet::new(),
            stats: Stats::default(),
        };
        let mut pool = Pool::new();
        pool.replace(&context, &mut arena, &mut uploads, &data)
            .unwrap();
        let original = pool.stats();
        assert_eq!(original[..3], [1, 2, 3]);
        assert!(original[3] > 0);
        let handle = pool.current.as_ref().unwrap().handle;
        let indices = [1, 0, 1, UNKNOWN, TRANSPARENT];
        assert_eq!(pool.binding_stats(&indices), [1, 1, 2, 3]);
        let prepared = pool
            .bind(&context, &mut arena, &mut uploads, &indices)
            .unwrap()
            .unwrap();
        assert_eq!(prepared.attachment.micromap, handle);
        let usage = &prepared._binding_usage;
        assert_eq!(usage.iter().find(|u| u.format == 1).unwrap().count, 1);
        assert_eq!(usage.iter().find(|u| u.format == 2).unwrap().count, 2);
        context
            .submit_named("omm_pool_binding", |command| {
                prepared.record_copy(command);
                upload_barrier(&context, command);
            })
            .unwrap();
        let binding = prepared.finish(&mut arena, &mut uploads);
        assert_eq!(pool.stats(), original, "binding rebuilt resource templates");
        pool.collect(&mut arena);
        assert_eq!(pool.current.as_ref().unwrap().handle, handle);
        pool.replace(&context, &mut arena, &mut uploads, &data)
            .unwrap();
        assert_eq!(pool.stats()[0], 2);
        assert_eq!(pool.retired.len(), 1);
        assert_eq!(
            pool.retired[0].handle, handle,
            "bound old generation was discarded"
        );
        binding.retire(&mut arena);
        pool.collect(&mut arena);
        assert!(
            pool.retired.is_empty(),
            "detached old generation was retained"
        );
        let current = pool.stats();
        let detached = pool
            .bind(&context, &mut arena, &mut uploads, &[0])
            .unwrap()
            .unwrap();
        drop(detached.finish(&mut arena, &mut uploads));
        pool.collect(&mut arena);
        assert_eq!(
            pool.stats(),
            current,
            "unloading the last cell destroyed resource templates"
        );
        assert!(
            pool.bind(&context, &mut arena, &mut uploads, &[UNKNOWN; 2])
                .unwrap()
                .is_none()
        );
        let special = pool
            .bind(&context, &mut arena, &mut uploads, &[TRANSPARENT, OPAQUE])
            .unwrap()
            .unwrap();
        assert_eq!(special.attachment.micromap, vk::MicromapEXT::null());
        assert_eq!(special.attachment.usage_counts_count, 0);
        assert!(special.attachment.p_usage_counts.is_null());
        drop(special.finish(&mut arena, &mut uploads));
        eprintln!(
            "[OMM pool validation] builds={} global_blocks={} packed_bytes={} storage_bytes={}",
            current[0], current[1], current[2], current[3]
        );
    }
}
