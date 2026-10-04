//! Independent ReSTIR PT Enhanced point profile. Scratch is queue-local GPU memory;
//! only completed descriptor slots have mapped uniforms and temporal commits advance on acceptance.
use super::{Buffer, Context, FRAME_SLOTS, Image, LightSampling, RenderMode, ShaderModule, error};
use ash::vk;
use std::{io::Cursor, sync::Arc};

pub(super) const UNIFORM_BYTES: u64 = 400;
pub(super) const RAW_PROFILE_MESSAGE: &str = "ReSTIR PT Enhanced point profile uses native raw output; PSR, ray reconstruction and frame generation are not integrated";

const SCENE_FEATURES: [[u32; 3]; 6] = [
    [0, 0, 0],
    [1, 0, 0],
    [2, 0, 0],
    [2, 1, 0],
    [2, 0, 1],
    [2, 1, 1],
];

pub(super) struct Pipelines {
    context: Arc<Context>,
    pub generate: [vk::Pipeline; 6],
    pub workload: [vk::Pipeline; 6],
    pub retrace: [vk::Pipeline; 6],
    pub shift: [vk::Pipeline; 6],
    pub indirect: vk::Pipeline,
    pub temporal: [vk::Pipeline; 6],
    pub spatial: vk::Pipeline,
    pub resolve: [vk::Pipeline; 6],
}

impl Drop for Pipelines {
    fn drop(&mut self) {
        if self.context.can_destroy() {
            for pipeline in self
                .generate
                .into_iter()
                .chain(self.workload)
                .chain(self.retrace)
                .chain(self.shift)
                .chain(self.temporal)
                .chain(self.resolve)
                .chain([self.indirect, self.spatial])
            {
                unsafe { self.context.device.destroy_pipeline(pipeline, None) };
            }
        }
    }
}

impl Pipelines {
    pub fn new(
        context: &Arc<Context>,
        layout: vk::PipelineLayout,
        method: LightSampling,
        mode: RenderMode,
    ) -> Result<Self, String> {
        let mut result = Self {
            context: context.clone(),
            generate: [vk::Pipeline::null(); 6],
            workload: [vk::Pipeline::null(); 6],
            retrace: [vk::Pipeline::null(); 6],
            shift: [vk::Pipeline::null(); 6],
            indirect: vk::Pipeline::null(),
            temporal: [vk::Pipeline::null(); 6],
            spatial: vk::Pipeline::null(),
            resolve: [vk::Pipeline::null(); 6],
        };
        let create_group = |bytes: &[u8]| -> Result<[vk::Pipeline; 6], String> {
            let module = shader_module(context, bytes)?;
            let mut pipelines = [vk::Pipeline::null(); 6];
            for (pipeline, features) in pipelines.iter_mut().zip(SCENE_FEATURES) {
                match create(
                    context,
                    layout,
                    module.handle,
                    Some(features),
                    mode == RenderMode::Offline,
                ) {
                    Ok(created) => *pipeline = created,
                    Err(message) => {
                        for created in pipelines {
                            unsafe { context.device.destroy_pipeline(created, None) };
                        }
                        return Err(message);
                    }
                }
            }
            Ok(pipelines)
        };
        macro_rules! sampler_binary {
            ($name:literal) => {
                match method {
                    LightSampling::Grid => {
                        include_bytes!(concat!(env!("OUT_DIR"), "/", $name, ".spv")).as_slice()
                    }
                    LightSampling::Tree => {
                        include_bytes!(concat!(env!("OUT_DIR"), "/", $name, "_tree.spv")).as_slice()
                    }
                    LightSampling::TreeSphere => {
                        include_bytes!(concat!(env!("OUT_DIR"), "/", $name, "_tree_sphere.spv"))
                            .as_slice()
                    }
                }
            };
        }
        result.generate = create_group(sampler_binary!("restir_generate"))?;
        result.workload = create_group(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/restir_workload.spv"
        )))?;
        result.retrace = create_group(sampler_binary!("restir_retrace"))?;
        result.shift = create_group(sampler_binary!("restir_shift"))?;
        if mode == RenderMode::Realtime {
            result.temporal = create_group(sampler_binary!("restir_temporal"))?;
        }
        result.resolve = create_group(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/restir_resolve.spv"
        )))?;
        for (target, bytes) in [
            (
                &mut result.indirect,
                include_bytes!(concat!(env!("OUT_DIR"), "/restir_indirect.spv")).as_slice(),
            ),
            (
                &mut result.spatial,
                include_bytes!(concat!(env!("OUT_DIR"), "/restir_spatial.spv")).as_slice(),
            ),
        ] {
            let module = shader_module(context, bytes)?;
            *target = create(context, layout, module.handle, None, false)?;
        }
        Ok(result)
    }
}

fn shader_module<'a>(context: &'a Context, bytes: &[u8]) -> Result<ShaderModule<'a>, String> {
    let spirv = ash::util::read_spv(&mut Cursor::new(bytes))
        .map_err(|e| format!("Read ReSTIR PT SPIR-V: {e}"))?;
    Ok(ShaderModule {
        context,
        handle: unsafe {
            context
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&spirv), None)
        }
        .map_err(|e| error("Create ReSTIR PT shader module", e))?,
    })
}

fn create(
    context: &Context,
    layout: vk::PipelineLayout,
    module: vk::ShaderModule,
    features: Option<[u32; 3]>,
    offline: bool,
) -> Result<vk::Pipeline, String> {
    let entries = [0, 1, 2, 3, 4].map(|id| vk::SpecializationMapEntry {
        constant_id: id,
        offset: id * 4,
        size: 4,
    });
    let selected = features.unwrap_or_default();
    // Geometry::shader_variant indexes the same surface/local-light/optical variants as PT.
    // ReSTIR reprojects primary hits directly and does not consume the RR motion-guide channel.
    let data = [selected[0], selected[1], selected[2], 0, u32::from(offline)].map(u32::to_le_bytes);
    let specialization = vk::SpecializationInfo::default()
        .map_entries(&entries)
        .data(data.as_flattened());
    let mut stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(module)
        .name(c"main");
    if features.is_some() {
        stage = stage.specialization_info(&specialization);
    }
    match unsafe {
        context.device.create_compute_pipelines(
            vk::PipelineCache::null(),
            &[vk::ComputePipelineCreateInfo::default()
                .stage(stage)
                .layout(layout)],
            None,
        )
    } {
        Ok(created) => Ok(created[0]),
        Err((partial, e)) => {
            for pipeline in partial {
                unsafe { context.device.destroy_pipeline(pipeline, None) };
            }
            Err(error("Create ReSTIR PT compute pipeline", e))
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct Layout {
    pixels: u32,
    // Reservoirs, primary hit banks, residual, reprojection, mask, replay, offsets, shifts, work items.
    offsets: [u64; 11],
    bytes: u64,
}
impl Layout {
    fn new([width, height]: [u32; 2]) -> Result<Self, String> {
        if width == 0 || height == 0 {
            return Err("ReSTIR PT extent must be positive".into());
        }
        let pixels = u64::from(width.div_ceil(16))
            .checked_mul(u64::from(height.div_ceil(16)))
            .and_then(|tiles| tiles.checked_mul(256))
            .ok_or("ReSTIR PT padded extent overflow")?;
        // Morton indexing and up to three candidate jobs remain within uint shader indexing.
        if pixels > u64::from(u32::MAX) / 3 {
            return Err("ReSTIR PT padded pixels exceed shader job indexing".into());
        }
        let strides = [80, 80, 20, 20, 16, 8, 8, 60 * 3, 4 * 3, 20 * 3, 8 * 3];
        let mut offsets = [0; 11];
        let mut bytes = 0_u64;
        for (offset, stride) in offsets.iter_mut().zip(strides) {
            *offset = bytes;
            bytes = bytes
                .checked_add(pixels * stride)
                .ok_or("ReSTIR PT scratch overflow")?;
            bytes = bytes
                .checked_add(15)
                .ok_or("ReSTIR PT scratch alignment overflow")?
                & !15;
        }
        Ok(Self {
            pixels: pixels as u32,
            offsets,
            bytes,
        })
    }
}

struct Scratch {
    extent: [u32; 2],
    storage: Buffer,
    layout: Layout,
    queue: Buffer,
}
impl Scratch {
    fn new(context: &Arc<Context>, extent: [u32; 2]) -> Result<Self, String> {
        let layout = Layout::new(extent)?;
        Ok(Self {
            extent,
            storage: if cfg!(all(test, feature = "shader-tests")) {
                Buffer::new(
                    context,
                    layout.bytes,
                    vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                        | vk::BufferUsageFlags::TRANSFER_SRC,
                    false,
                )?
            } else {
                Buffer::new_address(context, layout.bytes)?
            },
            layout,
            queue: Buffer::new(
                context,
                16,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                    | vk::BufferUsageFlags::INDIRECT_BUFFER
                    | vk::BufferUsageFlags::TRANSFER_DST,
                false,
            )?,
        })
    }
}

struct History {
    valid: bool,
    primary_bank: usize,
    previous: [u8; 128],
    pending: Option<[u8; 128]>,
    pending_realtime: bool,
}
impl Default for History {
    fn default() -> Self {
        Self {
            valid: false,
            primary_bank: 0,
            previous: [0; 128],
            pending: None,
            pending_realtime: false,
        }
    }
}
impl History {
    fn invalidate(&mut self) {
        self.valid = false;
        self.pending = None;
    }
    fn commit(&mut self) {
        if let Some(frame) = self.pending.take() {
            self.previous = frame;
            self.primary_bank ^= 1;
            self.valid = self.pending_realtime;
        }
    }
}

pub(super) struct State {
    uniforms: [Buffer; FRAME_SLOTS],
    pairing: Buffer,
    dummy_linear: Image,
    scratch: Option<Scratch>,
    history: History,
    pub temporal_this_frame: bool,
}
impl State {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        let source = include_bytes!("../assets/restir/paired-neighbors-3-16.bytes");
        if source.len() != 3 * 256 * 256 * 2 {
            return Err("Unexpected ReSTIR PT paired neighbor asset size".into());
        }
        let expanded: Vec<u8> = source
            .as_chunks::<2>()
            .0
            .iter()
            .flat_map(|word| u32::from(u16::from_le_bytes(*word)).to_le_bytes())
            .collect();
        let uniforms: Vec<_> = (0..FRAME_SLOTS)
            .map(|_| {
                Buffer::new(
                    context,
                    UNIFORM_BYTES,
                    vk::BufferUsageFlags::UNIFORM_BUFFER,
                    true,
                )
            })
            .collect::<Result<_, _>>()?;
        Ok(Self {
            uniforms: uniforms
                .try_into()
                .map_err(|_| "Invalid ReSTIR PT uniform slot count")?,
            pairing: Buffer::upload_device(
                context,
                &expanded,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            )?,
            dummy_linear: Image::with_format(context, 1, 1, vk::Format::R32G32B32A32_SFLOAT)?,
            scratch: None,
            history: Default::default(),
            temporal_this_frame: false,
        })
    }
    pub fn invalidate(&mut self) {
        self.history.invalidate();
        self.temporal_this_frame = false;
    }
    pub fn reset_world(&mut self) {
        self.invalidate();
        self.scratch = None;
    }
    pub fn commit(&mut self) {
        self.history.commit();
    }
    #[allow(clippy::too_many_arguments)]
    pub fn prepare(
        &mut self,
        context: &Arc<Context>,
        slot: usize,
        frame: [u8; 128],
        extent: [u32; 2],
        terrain_count: u32,
        instances: u64,
        accumulation: u64,
        realtime: bool,
        linear: bool,
        sequence: u32,
    ) -> Result<(), String> {
        if self
            .scratch
            .as_ref()
            .is_none_or(|scratch| scratch.extent != extent)
        {
            self.scratch = Some(Scratch::new(context, extent)?);
            self.invalidate();
        }
        if sequence == 0 {
            self.invalidate();
        }
        let scratch = self.scratch.as_ref().unwrap();
        self.temporal_this_frame = realtime && self.history.valid;
        let mut bytes = [0_u8; UNIFORM_BYTES as usize];
        bytes[..128].copy_from_slice(&frame);
        bytes[128..256].copy_from_slice(if self.temporal_this_frame {
            &self.history.previous
        } else {
            &frame
        });
        for (target, value) in bytes[256..272].as_chunks_mut::<4>().0.iter_mut().zip([
            terrain_count,
            u32::from(self.temporal_this_frame),
            scratch.layout.pixels,
            u32::from(linear),
        ]) {
            *target = value.to_le_bytes();
        }
        let base = scratch.storage.address();
        let address = |region: usize| base + scratch.layout.offsets[region];
        let primary = self.history.primary_bank;
        let addresses = [
            address(0),
            address(1),
            address(2 + primary),
            address(2 + (primary ^ 1)),
            address(4),
            address(5),
            address(6),
            address(7),
            address(8),
            address(9),
            address(10),
            scratch.queue.address(),
            self.pairing.address(),
            instances,
            accumulation,
        ];
        for (target, value) in bytes[272..392]
            .as_chunks_mut::<8>()
            .0
            .iter_mut()
            .zip(addresses)
        {
            *target = value.to_le_bytes();
        }
        self.uniforms[slot].write(&bytes)?;
        self.history.pending = Some(frame);
        self.history.pending_realtime = realtime;
        Ok(())
    }
    pub fn descriptors(
        &self,
        context: &Context,
        set: vk::DescriptorSet,
        slot: usize,
        linear: Option<vk::ImageView>,
    ) {
        let uniform = [vk::DescriptorBufferInfo::default()
            .buffer(self.uniforms[slot].buffer)
            .range(UNIFORM_BYTES)];
        let image = [vk::DescriptorImageInfo::default()
            .image_view(linear.unwrap_or(self.dummy_linear.view))
            .image_layout(vk::ImageLayout::GENERAL)];
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(23)
                .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                .buffer_info(&uniform),
            vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(24)
                .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .image_info(&image),
        ];
        unsafe { context.device.update_descriptor_sets(&writes, &[]) };
    }
    pub fn queue_buffer(&self) -> vk::Buffer {
        self.scratch.as_ref().unwrap().queue.buffer
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub fn accepted_history(&self) -> (bool, usize) {
        (self.history.valid, self.history.primary_bank)
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn uniform_for_test(&self, slot: usize) -> &Buffer {
        &self.uniforms[slot]
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn history_for_test(&self) -> (&Buffer, u64, u32) {
        let scratch = self.scratch.as_ref().unwrap();
        (
            &scratch.storage,
            scratch.layout.offsets[1],
            scratch.layout.pixels,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tiled_layout_preserves_record_strides_alignment_and_uint_job_range() {
        let layout = Layout::new([1920, 1080]).unwrap();
        assert_eq!(layout.pixels, 120 * 68 * 256);
        assert_eq!(layout.bytes, u64::from(layout.pixels) * 508);
        for offset in layout.offsets {
            assert_eq!(offset % 16, 0);
        }
        for extent in [[0, 1], [1, 0], [u32::MAX, u32::MAX]] {
            assert!(Layout::new(extent).is_err());
        }
        let odd = Layout::new([17, 1]).unwrap();
        assert_eq!(odd.pixels, 512);
    }
    #[test]
    fn temporal_state_advances_only_on_acceptance_and_resets_explicitly() {
        let mut history = History::default();
        history.pending = Some([7; 128]);
        history.pending_realtime = true;
        assert!(!history.valid);
        assert_eq!(history.primary_bank, 0);
        history.commit();
        assert!(history.valid);
        assert_eq!(history.primary_bank, 1);
        assert_eq!(history.previous, [7; 128]);
        history.pending = Some([9; 128]);
        history.invalidate();
        history.commit();
        assert!(!history.valid);
        assert_eq!(history.previous, [7; 128]);
        assert_eq!(history.primary_bank, 1);
        history.pending = Some([11; 128]);
        history.pending_realtime = false;
        history.commit();
        assert!(!history.valid);
        assert_eq!(history.primary_bank, 0);
    }
}
