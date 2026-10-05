//! Independent ReSTIR PT Enhanced point profile. Scratch is queue-local GPU memory;
//! only completed descriptor slots have mapped uniforms and temporal commits advance on acceptance.
//! Prime interface/history adaptations are registered in docs/restir-adaptations.md (RA-001/009).
use super::{Buffer, Context, FRAME_SLOTS, Image, LightSampling, RenderMode, ShaderModule, error};
use crate::temporal_reset::{self, Backend, GlobalReset, StorageCold};
use ash::vk;
use prime_scene::restir_settings::RestirSettings;
use std::{io::Cursor, sync::Arc};

pub(super) const UNIFORM_BYTES: u64 = 720;

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
    pub sample_ids: vk::Pipeline,
    pub duplicate_map: vk::Pipeline,
    pub rr_statistics: vk::Pipeline,
    pub debug_display: vk::Pipeline,
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
                .chain([
                    self.indirect,
                    self.spatial,
                    self.sample_ids,
                    self.duplicate_map,
                    self.rr_statistics,
                    self.debug_display,
                ])
            {
                if pipeline != vk::Pipeline::null() {
                    unsafe { self.context.device.destroy_pipeline(pipeline, None) };
                }
            }
        }
    }
}

impl Pipelines {
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn install_baseline_for_test(
        &mut self,
        layout: vk::PipelineLayout,
        variant: usize,
        folder: &std::path::Path,
    ) {
        for (target, name) in [
            (&mut self.generate[variant], "restir_generate_tree"),
            (&mut self.workload[variant], "restir_workload"),
            (&mut self.retrace[variant], "restir_retrace_tree"),
            (&mut self.shift[variant], "restir_shift_tree"),
            (&mut self.temporal[variant], "restir_temporal_tree"),
            (&mut self.resolve[variant], "restir_resolve"),
            (&mut self.spatial, "restir_spatial"),
            (&mut self.indirect, "restir_indirect"),
        ] {
            let bytes = std::fs::read(folder.join(format!("{name}.spv"))).unwrap();
            let module = shader_module(&self.context, &bytes).unwrap();
            let narrow = matches!(name, "restir_spatial" | "restir_indirect");
            let next = create(
                &self.context,
                layout,
                module.handle,
                (!narrow).then_some(SCENE_FEATURES[variant]),
                false,
                0,
            )
            .unwrap();
            unsafe { self.context.device.destroy_pipeline(*target, None) };
            *target = next;
        }
    }
    pub fn new(
        context: &Arc<Context>,
        layout: vk::PipelineLayout,
        _method: LightSampling,
        mode: RenderMode,
        reconstruction: bool,
        frame_generation: bool,
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
            sample_ids: vk::Pipeline::null(),
            duplicate_map: vk::Pipeline::null(),
            rr_statistics: vk::Pipeline::null(),
            debug_display: vk::Pipeline::null(),
        };
        let create_group = |bytes: &[u8], motion: u32| -> Result<[vk::Pipeline; 6], String> {
            let module = shader_module(context, bytes)?;
            let mut pipelines = [vk::Pipeline::null(); 6];
            for (pipeline, features) in pipelines.iter_mut().zip(SCENE_FEATURES) {
                match create(
                    context,
                    layout,
                    module.handle,
                    Some(features),
                    mode == RenderMode::Offline,
                    motion,
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
        result.generate = create_group(
            if reconstruction {
                prime_shaders::restir_generate_rr_tree()
            } else {
                prime_shaders::restir_generate_tree()
            },
            if reconstruction {
                1 | (u32::from(frame_generation) << 1)
            } else {
                0
            },
        )?;
        result.workload = create_group(prime_shaders::restir_workload(), 0)?;
        result.retrace = create_group(prime_shaders::restir_retrace_tree(), 0)?;
        result.shift = create_group(prime_shaders::restir_shift_tree(), 0)?;
        if mode == RenderMode::Realtime {
            result.temporal = create_group(prime_shaders::restir_temporal_tree(), 0)?;
        }
        result.resolve = create_group(
            if reconstruction {
                prime_shaders::restir_resolve_rr()
            } else {
                prime_shaders::restir_resolve()
            },
            0,
        )?;
        for (target, bytes) in [
            (&mut result.indirect, prime_shaders::restir_indirect()),
            (&mut result.spatial, prime_shaders::restir_spatial()),
        ] {
            let module = shader_module(context, bytes)?;
            *target = create(context, layout, module.handle, None, false, 0)?;
        }
        Ok(result)
    }
    pub fn ensure_optional(
        &mut self,
        layout: vk::PipelineLayout,
        settings: RestirSettings,
        rr_statistics: bool,
    ) -> Result<(), String> {
        if settings.duplicate_map || settings.debug_view == 1 {
            for (target, bytes) in [
                (&mut self.sample_ids, prime_shaders::restir_sample_ids()),
                (
                    &mut self.duplicate_map,
                    prime_shaders::restir_duplicate_map(),
                ),
            ] {
                if *target == vk::Pipeline::null() {
                    let module = shader_module(&self.context, bytes)?;
                    *target = create(&self.context, layout, module.handle, None, false, 0)?;
                }
            }
        }
        if rr_statistics && self.rr_statistics == vk::Pipeline::null() {
            let module = shader_module(&self.context, prime_shaders::restir_rr_statistics())?;
            self.rr_statistics = create(&self.context, layout, module.handle, None, false, 0)?;
        }
        if settings.debug_view != 0 && self.debug_display == vk::Pipeline::null() {
            let module = shader_module(&self.context, prime_shaders::restir_debug_display())?;
            self.debug_display = create(&self.context, layout, module.handle, None, false, 0)?;
        }
        Ok(())
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
    motion: u32,
) -> Result<vk::Pipeline, String> {
    let entries = [0, 1, 2, 3, 4].map(|id| vk::SpecializationMapEntry {
        constant_id: id,
        offset: id * 4,
        size: 4,
    });
    let selected = features.unwrap_or_default();
    // Geometry::shader_variant indexes the same surface/local-light/optical variants as PT.
    // Only the RR primary observer consumes accepted-pose/visible-guide motion metadata.
    let data = [
        selected[0],
        selected[1],
        selected[2],
        motion,
        u32::from(offline),
    ]
    .map(u32::to_le_bytes);
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
    #[cfg(test)]
    fn new([width, height]: [u32; 2]) -> Result<Self, String> {
        Self::with_neighbors([width, height], 3)
    }
    fn with_neighbors([width, height]: [u32; 2], candidates: u32) -> Result<Self, String> {
        if width == 0 || height == 0 {
            return Err("ReSTIR PT extent must be positive".into());
        }
        let pixels = u64::from(width.div_ceil(16))
            .checked_mul(u64::from(height.div_ceil(16)))
            .and_then(|tiles| tiles.checked_mul(256))
            .ok_or("ReSTIR PT padded extent overflow")?;
        // Morton indexing and up to three candidate jobs remain within uint shader indexing.
        if pixels > u64::from(u32::MAX) / u64::from(candidates) {
            return Err("ReSTIR PT padded pixels exceed shader job indexing".into());
        }
        let candidates = u64::from(candidates);
        let strides = [
            80,
            80,
            20,
            20,
            16,
            8,
            8,
            60 * candidates,
            4 * candidates,
            20 * candidates,
            8 * candidates,
        ];
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
    jobs: Buffer,
    candidates: u32,
    layout: Layout,
    queue: Buffer,
}
impl Scratch {
    fn new(context: &Arc<Context>, extent: [u32; 2], candidates: u32) -> Result<Self, String> {
        let layout = Layout::with_neighbors(extent, candidates)?;
        Ok(Self {
            extent,
            storage: if cfg!(all(test, feature = "shader-tests")) {
                Buffer::new(
                    context,
                    layout.offsets[7],
                    vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                        | vk::BufferUsageFlags::TRANSFER_SRC,
                    false,
                )?
            } else {
                Buffer::new_address(context, layout.offsets[7])?
            },
            jobs: Buffer::new_address(context, layout.bytes - layout.offsets[7])?,
            candidates,
            layout,
            queue: Buffer::new(
                context,
                16,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                    | vk::BufferUsageFlags::INDIRECT_BUFFER
                    | vk::BufferUsageFlags::TRANSFER_DST
                    | if cfg!(all(test, feature = "shader-tests")) {
                        vk::BufferUsageFlags::TRANSFER_SRC
                    } else {
                        vk::BufferUsageFlags::empty()
                    },
                false,
            )?,
        })
    }
    fn resize_jobs(&mut self, context: &Arc<Context>, candidates: u32) -> Result<(), String> {
        if self.candidates != candidates {
            let layout = Layout::with_neighbors(self.extent, candidates)?;
            let jobs = Buffer::new_address(context, layout.bytes - layout.offsets[7])?;
            // RA-015: only ephemeral jobs change. Accepted reservoirs and primary banks
            // retain their owners, avoiding a copy/reset even if this recording is cancelled.
            self.jobs = jobs;
            self.layout = layout;
            self.candidates = candidates;
        }
        Ok(())
    }
    fn address(&self, region: usize) -> u64 {
        if region < 7 {
            self.storage.address() + self.layout.offsets[region]
        } else {
            self.jobs.address() + self.layout.offsets[region] - self.layout.offsets[7]
        }
    }
}

struct History {
    valid: bool,
    primary_bank: usize,
    reservoir_bank: usize,
    pending_reservoir_bank: usize,
    duplicate_generation: u64,
    age_generation: u64,
    pending_duplicate_generation: u64,
    pending_age_generation: u64,
    rr_generation: u64,
    pending_rr_generation: u64,
    rr_ready: bool,
    pending_rr_ready: bool,
    source_generation: u64,
    pending_source_generation: u64,
    source_thresholds: [u32; 4],
    pending_source_thresholds: [u32; 4],
    previous: [u8; 128],
    pending: Option<[u8; 128]>,
    pending_realtime: bool,
    jitter: [f32; 2],
    anchor: [f64; 3],
    revision: u32,
    pending_jitter: [f32; 2],
    pending_anchor: [f64; 3],
    pending_revision: u32,
}
impl Default for History {
    fn default() -> Self {
        Self {
            valid: false,
            primary_bank: 0,
            reservoir_bank: 1,
            pending_reservoir_bank: 1,
            duplicate_generation: 0,
            age_generation: 0,
            pending_duplicate_generation: 0,
            pending_age_generation: 0,
            rr_generation: 0,
            pending_rr_generation: 0,
            rr_ready: false,
            pending_rr_ready: false,
            source_generation: 0,
            pending_source_generation: 0,
            source_thresholds: super::restir_profiles::thresholds(RestirSettings::default()),
            pending_source_thresholds: super::restir_profiles::thresholds(RestirSettings::default()),
            previous: [0; 128],
            pending: None,
            pending_realtime: false,
            jitter: [0.; 2],
            anchor: [0.; 3],
            revision: 0,
            pending_jitter: [0.; 2],
            pending_anchor: [0.; 3],
            pending_revision: 0,
        }
    }
}
impl History {
    fn invalidate(&mut self) {
        self.valid = false;
        self.pending = None;
    }
    fn request_reset(&mut self, ignore: bool) -> bool {
        if ignore {
            return false;
        }
        self.invalidate();
        true
    }
    fn commit(&mut self) {
        if let Some(frame) = self.pending.take() {
            self.previous = frame;
            self.primary_bank ^= 1;
            self.reservoir_bank = self.pending_reservoir_bank;
            self.duplicate_generation = self.pending_duplicate_generation;
            self.age_generation = self.pending_age_generation;
            self.rr_generation = self.pending_rr_generation;
            self.rr_ready = self.pending_rr_ready;
            self.source_generation = self.pending_source_generation;
            self.source_thresholds = self.pending_source_thresholds;
            self.valid = self.pending_realtime;
            self.jitter = self.pending_jitter;
            self.anchor = self.pending_anchor;
            self.revision = self.pending_revision;
        }
    }
}

pub(super) struct State {
    uniforms: [Buffer; FRAME_SLOTS],
    pairing: super::restir_pairing::Pairing,
    auxiliary: super::restir_aux::Auxiliary,
    profiles: super::restir_profiles::Profiles,
    pub settings: RestirSettings,
    dummy_linear: Image,
    scratch: Option<Scratch>,
    history: History,
    pub temporal_this_frame: bool,
    pub dynamic_update_this_frame: bool,
    pub rr_statistics_this_frame: bool,
    lighting_changed: bool,
    ignore_global_history_resets: bool,
    #[cfg(all(test, feature = "shader-tests"))]
    next_test_jitter: Option<[f32; 2]>,
}
impl State {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
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
            pairing: super::restir_pairing::Pairing::new(context, 3, 30)?,
            auxiliary: Default::default(),
            profiles: Default::default(),
            settings: Default::default(),
            dummy_linear: Image::with_format(context, 1, 1, vk::Format::R32G32B32A32_SFLOAT)?,
            scratch: None,
            history: Default::default(),
            temporal_this_frame: false,
            dynamic_update_this_frame: false,
            rr_statistics_this_frame: false,
            lighting_changed: false,
            ignore_global_history_resets: false,
            #[cfg(all(test, feature = "shader-tests"))]
            next_test_jitter: None,
        })
    }
    pub fn set_history_reset_policy(&mut self, ignore: bool) {
        self.ignore_global_history_resets = ignore;
    }
    pub fn accepted_revision(&self) -> u32 {
        self.history.revision
    }
    pub fn invalidate(&mut self, reason: GlobalReset) {
        temporal_reset::record_global(
            Backend::Restir,
            reason,
            self.history.valid,
            self.ignore_global_history_resets,
        );
        if self
            .history
            .request_reset(self.ignore_global_history_resets)
        {
            self.temporal_this_frame = false;
        }
    }
    pub fn storage_cold(&mut self, reason: StorageCold) {
        temporal_reset::record_storage(Backend::Restir, reason, self.history.valid);
        self.history.invalidate();
        self.temporal_this_frame = false;
    }
    pub fn reset_world(&mut self) {
        self.invalidate(GlobalReset::WorldReplaced);
        if !self.ignore_global_history_resets {
            self.storage_cold(StorageCold::WorldReleased);
            self.scratch = None;
        }
    }
    pub fn commit(&mut self) {
        if self.history.pending.is_some() {
            self.lighting_changed = false;
        }
        self.history.commit();
    }
    pub fn lighting_changed(&mut self) {
        self.lighting_changed = true;
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
        spatial_only: bool,
        settings: RestirSettings,
        reconstruction: bool,
        linear: bool,
        jitter: [f32; 2],
        anchor: [f64; 3],
        identity: crate::geometry::HistoryIdentityInput,
    ) -> Result<(), String> {
        #[cfg(all(test, feature = "shader-tests"))]
        let jitter = self.next_test_jitter.take().unwrap_or(jitter);
        if self
            .scratch
            .as_ref()
            .is_none_or(|scratch| scratch.extent != extent)
        {
            let candidates = if settings.spatial_reuse && settings.spatial_iterations != 0 {
                settings.spatial_neighbors.max(2)
            } else {
                2
            };
            let scratch = Scratch::new(context, extent, candidates)?;
            if self.scratch.is_some() {
                self.invalidate(GlobalReset::InputExtentChanged);
            }
            self.storage_cold(StorageCold::InputExtentChanged);
            self.scratch = Some(scratch);
        }
        let candidates = if settings.spatial_reuse && settings.spatial_iterations != 0 {
            settings.spatial_neighbors.max(2)
        } else {
            2
        };
        self.scratch
            .as_mut()
            .unwrap()
            .resize_jobs(context, candidates)?;
        if settings.spatial_reuse
            && settings.spatial_iterations != 0
            && !self
                .pairing
                .matches(settings.spatial_neighbors, settings.pairing_radius)
        {
            self.pairing = super::restir_pairing::Pairing::new(
                context,
                settings.spatial_neighbors,
                settings.pairing_radius,
            )?;
        }
        let pixels = self.scratch.as_ref().unwrap().layout.pixels;
        let rr_consumer = realtime
            && !spatial_only
            && self.history.valid
            && reconstruction
            && settings.rr_decorrelation
            && settings.rr_mode as u32 != 0
            && settings.rr_factor > 0.0;
        self.auxiliary
            .prepare(context, pixels, settings, rr_consumer)?;
        let source_thresholds = super::restir_profiles::thresholds(settings);
        self.profiles.prepare(
            context,
            pixels,
            (realtime && !spatial_only && self.history.valid)
                .then_some(self.history.source_thresholds),
            source_thresholds,
        )?;
        self.settings = settings;
        let scratch = self.scratch.as_ref().unwrap();
        // RA-014, docs/restir-adaptations.md: disable the whole temporal pass group on the
        // host. Spatial still writes a complete result, so accepted bank/camera commits
        // remain valid for immediate reuse when this diagnostic is switched off.
        self.temporal_this_frame = realtime && !spatial_only && self.history.valid;
        self.dynamic_update_this_frame = self.temporal_this_frame
            && (self.lighting_changed || identity.revision != self.history.revision);
        let rr_output = rr_consumer && self.temporal_this_frame;
        self.rr_statistics_this_frame =
            rr_output && (settings.rr_mode as u32 == 2 || settings.rr_firefly);
        let mut event = prime_diagnostics::scope("restir.history.frame");
        event.count("temporal", u64::from(self.temporal_this_frame));
        event.count("spatial_only", u64::from(spatial_only));
        event.count("update", u64::from(self.dynamic_update_this_frame));
        event.count("revision", u64::from(identity.revision));
        event.count("accepted", u64::from(self.history.revision));
        let mut bytes = [0_u8; UNIFORM_BYTES as usize];
        bytes[440..448].copy_from_slice(&identity.quad_address.to_le_bytes());
        bytes[..128].copy_from_slice(&frame);
        if self.temporal_this_frame {
            let mut previous = self.history.previous;
            for i in 0..3 {
                let start = 4 * i;
                let position = f32::from_le_bytes(previous[start..start + 4].try_into().unwrap());
                previous[start..start + 4].copy_from_slice(
                    &((f64::from(position) + self.history.anchor[i] - anchor[i]) as f32)
                        .to_le_bytes(),
                );
            }
            bytes[128..256].copy_from_slice(&previous);
        } else {
            bytes[128..256].copy_from_slice(&frame);
        }
        for (target, value) in bytes[256..272].as_chunks_mut::<4>().0.iter_mut().zip([
            terrain_count,
            u32::from(self.temporal_this_frame),
            scratch.layout.pixels,
            u32::from(linear),
        ]) {
            *target = value.to_le_bytes();
        }
        let address = |region: usize| scratch.address(region);
        let primary = self.history.primary_bank;
        let initial = self.history.reservoir_bank ^ 1;
        let addresses = [
            address(initial),
            address(initial ^ 1),
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
            self.pairing.buffer.address(),
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
        let rounds = if settings.spatial_reuse {
            settings.spatial_iterations
        } else {
            0
        };
        let track_age = self.auxiliary.ages.is_some();
        let auxiliary_flags = u32::from(track_age)
            | (u32::from(
                track_age && self.history.age_generation == self.auxiliary.age_generation,
            ) << 1)
            | (u32::from(
                self.auxiliary.sample_ids.is_some()
                    && self.history.duplicate_generation == self.auxiliary.duplicate_generation,
            ) << 2)
            | (u32::from(self.profiles.active()) << 3)
            | (u32::from(
                self.profiles.active()
                    && self.history.source_generation == self.profiles.generation,
            ) << 4);
        let options = [
            settings.history_length,
            settings.spatial_neighbors,
            rounds,
            settings.initial_samples,
            u32::from(settings.stochastic_reprojection),
            u32::from(settings.duplicate_map),
            u32::from(settings.decoupled_shading && rounds != 0),
            settings.debug_view,
            self.pairing.sizes[0],
            self.pairing.sizes[1],
            self.pairing.sizes[2],
            self.pairing.sizes[3],
            self.pairing.sizes[4],
            scratch.candidates,
            65536,
            auxiliary_flags,
            (settings.distance_threshold / 100.0).to_bits(),
            settings.distance_sigma.to_bits(),
            settings.roughness_threshold.to_bits(),
            settings.roughness_sigma.to_bits(),
            settings.normal_threshold.to_bits(),
            settings.depth_threshold.to_bits(),
            settings.duplication_power.to_bits(),
            0,
        ];
        for (target, value) in bytes[464..560]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(options)
        {
            *target = value.to_le_bytes();
        }
        for (target, value) in bytes[560..600]
            .as_chunks_mut::<8>()
            .0
            .iter_mut()
            .zip(self.auxiliary.addresses(primary))
        {
            *target = value.to_le_bytes();
        }
        let rr_ready =
            self.history.rr_ready && self.history.rr_generation == self.auxiliary.rr_generation;
        let rr_options = [
            settings.rr_factor.to_bits(),
            settings.rr_stagnancy_exponent.to_bits(),
            settings.rr_ema.to_bits(),
            settings.rr_firefly_strength.to_bits(),
            settings.rr_multiply_bound.to_bits(),
            0,
            0,
            0,
            u32::from(rr_output) | (u32::from(rr_ready) << 1),
            settings.rr_mode as u32,
            u32::from(settings.rr_bias_reduction),
            u32::from(settings.rr_firefly),
        ];
        for (target, value) in bytes[608..656]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(rr_options)
        {
            *target = value.to_le_bytes();
        }
        for (target, value) in bytes[656..688]
            .as_chunks_mut::<8>()
            .0
            .iter_mut()
            .zip(self.auxiliary.rr_addresses(primary))
        {
            *target = value.to_le_bytes();
        }
        for (target, value) in bytes[688..712]
            .as_chunks_mut::<8>()
            .0
            .iter_mut()
            .zip(self.profiles.addresses(initial))
        {
            *target = value.to_le_bytes();
        }
        bytes[712..716].copy_from_slice(&self.profiles.current.to_le_bytes());
        let previous_jitter = if self.temporal_this_frame {
            self.history.jitter
        } else {
            jitter
        };
        for (target, value) in bytes[400..416]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(jitter.into_iter().chain(previous_jitter))
        {
            *target = value.to_le_bytes();
        }
        for (target, address) in bytes[416..440]
            .as_chunks_mut::<8>()
            .0
            .iter_mut()
            .zip(identity.addresses)
        {
            *target = address.to_le_bytes();
        }
        for (target, value) in bytes[448..464]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(identity.counts.into_iter().chain([self.history.revision]))
        {
            *target = value.to_le_bytes();
        }
        self.uniforms[slot].write(&bytes)?;
        self.history.pending = Some(frame);
        self.history.pending_realtime = realtime;
        self.history.pending_jitter = jitter;
        self.history.pending_anchor = anchor;
        self.history.pending_revision = identity.revision;
        self.history.pending_reservoir_bank = initial ^ (rounds as usize & 1);
        self.history.pending_duplicate_generation = self.auxiliary.duplicate_generation;
        self.history.pending_age_generation = self.auxiliary.age_generation;
        self.history.pending_rr_generation = self.auxiliary.rr_generation;
        self.history.pending_rr_ready =
            self.rr_statistics_this_frame && settings.rr_mode as u32 == 2;
        self.history.pending_source_generation = self.profiles.generation;
        self.history.pending_source_thresholds = source_thresholds;
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
    pub(super) fn auxiliary_for_test(&self) -> &super::restir_aux::Auxiliary {
        &self.auxiliary
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn profiles_for_test(&self) -> &super::restir_profiles::Profiles {
        &self.profiles
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub fn accepted_history(&self) -> (bool, usize) {
        (self.history.valid, self.history.primary_bank)
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn uniform_for_test(&self, slot: usize) -> &Buffer {
        &self.uniforms[slot]
    }
    /// Override one real prepare/dispatch/commit cycle; accepted previous jitter is untouched.
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn override_next_jitter_for_test(&mut self, jitter: [f32; 2]) {
        self.next_test_jitter = Some(jitter);
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn history_for_test(&self) -> (&Buffer, u64, u32) {
        let scratch = self.scratch.as_ref().unwrap();
        (
            &scratch.storage,
            scratch.layout.offsets[self.history.reservoir_bank],
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
        history.pending_jitter = [0.125, -0.25];
        history.pending_anchor = [32., 16., -64.];
        history.pending_revision = 42;
        assert!(!history.valid);
        assert_eq!(history.primary_bank, 0);
        history.commit();
        assert!(history.valid);
        assert_eq!(history.primary_bank, 1);
        assert_eq!(history.previous, [7; 128]);
        assert_eq!(history.jitter, [0.125, -0.25]);
        assert_eq!(history.anchor, [32., 16., -64.]);
        assert_eq!(history.revision, 42);
        history.pending = Some([9; 128]);
        history.pending_jitter = [-0.375, 0.333];
        history.pending_anchor = [64.; 3];
        history.pending_revision = 57;
        history.invalidate();
        history.commit();
        assert!(!history.valid);
        assert_eq!(history.previous, [7; 128]);
        assert_eq!(history.jitter, [0.125, -0.25]);
        assert_eq!(history.anchor, [32., 16., -64.]);
        assert_eq!(history.revision, 42);
        assert_eq!(history.primary_bank, 1);
        history.pending = Some([11; 128]);
        history.pending_realtime = false;
        history.commit();
        assert!(!history.valid);
        assert_eq!(history.primary_bank, 0);
    }
    #[test]
    fn ignored_request_keeps_accepted_and_pending_but_storage_cold_does_not() {
        let mut history = History::default();
        history.pending = Some([7; 128]);
        history.pending_realtime = true;
        history.pending_revision = 41;
        history.commit();
        let accepted_bank = history.primary_bank;
        history.pending = Some([9; 128]);
        history.pending_revision = 42;
        assert!(!history.request_reset(true));
        assert!(history.valid);
        assert_eq!(history.previous, [7; 128]);
        assert_eq!(history.primary_bank, accepted_bank);
        assert_eq!(history.revision, 41);
        history.commit();
        assert!(history.valid);
        assert_eq!(history.previous, [9; 128]);
        assert_eq!(history.revision, 42);
        assert_eq!(history.primary_bank, accepted_bank ^ 1);
        history.pending = Some([11; 128]);
        history.invalidate(); // Actual replacement calls this independently of the request policy.
        history.commit();
        assert!(!history.valid);
        assert_eq!(history.previous, [9; 128]);
        assert_eq!(history.revision, 42);
        history.pending = Some([13; 128]);
        history.pending_revision = 43;
        history.commit();
        assert!(history.valid);
        assert!(history.request_reset(false));
        assert!(!history.valid);
    }
}
