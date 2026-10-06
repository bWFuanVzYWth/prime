use super::*;
use crate::cpu_profile::{CpuProfile, FrameCpu, Stage};
use crate::gpu_timing::{QUERY_COUNT, Stage as GpuStage};
use crate::temporal_reset::{GlobalReset, StorageCold};
use prime_scene::incremental::SceneInput;

struct HostRecordScope(Arc<Context>);
impl Drop for HostRecordScope {
    fn drop(&mut self) {
        self.0.end_host_record();
    }
}

pub(super) struct Output {
    width: u32,
    height: u32,
    log2_resolution: u32,
    // These exist only for explicit offline image diagnostics.
    image: Option<Image>,
    readback: Option<Buffer>,
    accumulation: Option<Buffer>,
    scratch: Option<realtime::Scratch>,
    linear: Option<Image>,
    hdr_world: Option<Image>,
    sdr_baseline: Option<Image>,
    hdr_extent: [u32; 2],
    snapshot_extent: [u32; 2],
    fg_hudless: Option<Image>,
    fg_alpha: Option<Image>,
    fg_extent: [u32; 2],
    fg_hdr: bool,
}

impl Output {
    fn prepare_display(
        &mut self,
        context: &Arc<Context>,
        mode: RenderMode,
        needed: bool,
        hdr: bool,
        fg: bool,
    ) -> Result<(), String> {
        if !needed && !hdr {
            self.linear = None;
            self.hdr_world = None;
            self.sdr_baseline = None;
            self.hdr_extent = [0; 2];
            self.snapshot_extent = [0; 2];
            self.fg_hudless = None;
            self.fg_alpha = None;
            self.fg_extent = [0; 2];
            return Ok(());
        }
        if needed && mode == RenderMode::Realtime && self.linear.is_none() {
            self.linear = Some(Image::with_format(
                context,
                self.width,
                self.height,
                vk::Format::R32G32B32A32_SFLOAT,
            )?);
        }
        let hdr_extent = if hdr {
            [self.width, self.height]
        } else {
            [1, 1]
        };
        if hdr_extent != self.hdr_extent {
            self.hdr_world = Some(Image::with_format(
                context,
                hdr_extent[0],
                hdr_extent[1],
                vk::Format::R16G16B16A16_SFLOAT,
            )?);
            self.hdr_extent = hdr_extent;
        }
        let snapshot_extent = if hdr || fg {
            [self.width, self.height]
        } else {
            [1, 1]
        };
        if self.snapshot_extent != snapshot_extent {
            self.sdr_baseline = Some(Image::new(context, snapshot_extent[0], snapshot_extent[1])?);
            self.snapshot_extent = snapshot_extent;
        }
        let fg_extent = if fg {
            [self.width, self.height]
        } else {
            [1, 1]
        };
        if self.fg_extent != fg_extent || self.fg_hdr != hdr {
            self.fg_alpha = Some(Image::with_format(
                context,
                fg_extent[0],
                fg_extent[1],
                vk::Format::R8_UNORM,
            )?);
            self.fg_hudless = if fg {
                Some(Image::with_format(
                    context,
                    self.width,
                    self.height,
                    if hdr {
                        vk::Format::R16G16B16A16_SFLOAT
                    } else {
                        vk::Format::R8G8B8A8_UNORM
                    },
                )?)
            } else {
                None
            };
            self.fg_extent = fg_extent;
            self.fg_hdr = hdr;
        }
        Ok(())
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn linear_buffer(&self) -> &Buffer {
        self.accumulation.as_ref().unwrap()
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn linear_image(&self) -> &Image {
        self.linear.as_ref().unwrap()
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn has_path_trace_scratch(&self) -> bool {
        self.scratch.is_some()
    }
    fn new(
        context: &Arc<Context>,
        width: u32,
        height: u32,
        mode: RenderMode,
    ) -> Result<Self, String> {
        let extent = context.render_extent(width, height)?;
        let bytes = extent.pixels();
        let offline = !context.is_borrowed();
        Ok(Self {
            width,
            height,
            log2_resolution: extent.log2_resolution(),
            scratch: None,
            linear: None,
            hdr_world: None,
            sdr_baseline: None,
            hdr_extent: [0; 2],
            snapshot_extent: [0; 2],
            fg_hudless: None,
            fg_alpha: None,
            fg_extent: [0; 2],
            fg_hdr: false,
            image: if offline {
                Some(Image::new(context, width, height)?)
            } else {
                None
            },
            readback: if offline {
                Some(Buffer::new_readback(context, bytes * 4)?)
            } else {
                None
            },
            accumulation: if mode == RenderMode::Offline {
                Some(Buffer::new(
                    context,
                    bytes * 16,
                    vk::BufferUsageFlags::STORAGE_BUFFER
                        | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                        | if cfg!(all(test, feature = "shader-tests")) {
                            vk::BufferUsageFlags::TRANSFER_SRC
                        } else {
                            vk::BufferUsageFlags::empty()
                        },
                    false,
                )?)
            } else {
                None
            },
        })
    }
}

impl Renderer {
    pub fn new() -> Result<Self, String> {
        Self::with_mode(RenderMode::Offline)
    }

    pub fn with_mode(mode: RenderMode) -> Result<Self, String> {
        Self::with_settings_and_workers(
            RenderSettings {
                mode,
                ..Default::default()
            },
            Arc::new(prime_scene::workers::CpuWorkers::configured()?),
        )
    }

    pub fn with_settings_and_workers(
        settings: RenderSettings,
        workers: Arc<prime_scene::workers::CpuWorkers>,
    ) -> Result<Self, String> {
        Self::from_context(Context::new()?, settings, workers)
    }

    /// # Safety
    /// Handles must belong to one live Vulkan device with ray query, acceleration
    /// structure, buffer device address, timeline semaphore and scalar block layout features enabled.
    /// The caller must flush its encoder before destruction and keep the host alive.
    pub unsafe fn borrowed(
        instance: u64,
        physical: u64,
        device: u64,
        queue: u64,
        family: u32,
        timeline: u64,
    ) -> Result<Self, String> {
        unsafe {
            Self::borrowed_mode(
                instance,
                physical,
                device,
                queue,
                family,
                timeline,
                RenderMode::Offline,
            )
        }
    }

    /// # Safety
    /// Same host ownership, feature and completion contract as `borrowed`.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn borrowed_mode(
        instance: u64,
        physical: u64,
        device: u64,
        queue: u64,
        family: u32,
        timeline: u64,
        mode: RenderMode,
    ) -> Result<Self, String> {
        Self::from_context(
            unsafe { Context::borrowed(instance, physical, device, queue, family, timeline)? },
            RenderSettings {
                mode,
                ..Default::default()
            },
            Arc::new(prime_scene::workers::CpuWorkers::configured()?),
        )
    }

    /// # Safety
    /// Same contract as borrowed_mode. Capability bit 0 additionally certifies
    /// enabled EXT opacity micromap, micromap and synchronization2 features.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn borrowed_mode_with_capabilities(
        instance: u64,
        physical: u64,
        device: u64,
        queue: u64,
        family: u32,
        timeline: u64,
        mode: RenderMode,
        capabilities: u32,
    ) -> Result<Self, String> {
        unsafe {
            Self::borrowed_with_settings_and_workers(
                instance,
                physical,
                device,
                queue,
                family,
                timeline,
                capabilities,
                RenderSettings {
                    mode,
                    ..Default::default()
                },
                Arc::new(prime_scene::workers::CpuWorkers::configured()?),
            )
        }
    }

    /// # Safety
    /// Same borrowed-device and completion contract as borrowed_mode_with_capabilities.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn borrowed_with_settings_and_workers(
        instance: u64,
        physical: u64,
        device: u64,
        queue: u64,
        family: u32,
        timeline: u64,
        capabilities: u32,
        settings: RenderSettings,
        workers: Arc<prime_scene::workers::CpuWorkers>,
    ) -> Result<Self, String> {
        Self::from_context(
            unsafe {
                Context::borrowed_with_capabilities(
                    instance,
                    physical,
                    device,
                    queue,
                    family,
                    timeline,
                    capabilities,
                )?
            },
            settings,
            workers,
        )
    }

    fn from_context(
        context: Arc<Context>,
        settings: RenderSettings,
        workers: Arc<prime_scene::workers::CpuWorkers>,
    ) -> Result<Self, String> {
        settings.validate()?;
        let cpu_profile = CpuProfile::default();
        let reconstruction_error = None;
        let reconstruction = (settings.mode == RenderMode::Realtime
            && !settings.native_noisy_output)
            .then(|| reconstruction::Reconstruction::new(&context))
            .transpose()?;
        let energy_lut = openpbr::EnergyLut::new(&context)?;
        let pipeline = Some(Pipeline::new(
            &context,
            settings.integrator,
            settings.mode,
            reconstruction.is_some(),
            settings.frame_generation
                && reconstruction
                    .as_ref()
                    .is_some_and(|rr| rr.frame_generation_supported()),
            settings.light_sampling,
            &energy_lut,
        )?);
        let mut result = Self {
            context,
            pipeline,
            energy_lut,
            reconstruction,
            reconstruction_error,
            geometry: None,
            scene_resources: None,
            atmosphere: None,
            atmosphere_scene_revision: 0,
            environment: Default::default(),
            workers,
            output: None,
            restir: None,
            camera: None,
            samples: 0,
            frame_seed: 0,
            display: PrimeDrtSettings {
                exposure_multiplier: settings.exposure,
                hue_compensation: settings.hue,
                saturation_compensation: settings.saturation,
            }
            .prepare(1.0)?,
            linear_display: None,
            exposure: None,
            exposure_reset: true,
            exposure_frozen: false,
            exposure_time: None,
            hdr_calibration: None,
            hdr_present: None,
            fg_present: None,
            starmap: None,
            stars: None,
            settings,
            scene_frozen: false,
            failed: false,
            host_serials: [0; FRAME_SLOTS],
            hdr_serials: [0; FRAME_SLOTS],
            fg_serials: [0; FRAME_SLOTS],
            fg_prepared_serial: 0,
            fg_prepared: false,
            pending_temporal_serial: 0,
            query_frames: std::array::from_fn(|_| Default::default()),
            gpu_intervals: [GpuIntervals::default(); FRAME_SLOTS],
            host_query: vk::QueryPool::null(),
            gpu_error_reported: false,
            last_gpu: GpuIntervals::default(),
            descriptor_keys: [[0; 7]; FRAME_SLOTS],
            cpu_profile,
        };
        result.set_history_reset_policy(settings.ignore_global_history_resets);
        result.set_diagnostics(
            crate::cpu_profile::enabled() || result.context.diagnostics_enabled(),
        )?;
        eprintln!(
            "[Prime PT] Vulkan hardware ray queries: {}; host recording={}",
            result.context.name,
            result.context.is_borrowed()
        );
        if !result.context.is_borrowed() {
            result.prepare_fixed_resources()?;
        }
        Ok(result)
    }

    /// Optional observability changes at a host frame boundary. Disabling never
    /// waits for pending work; retirement uses the existing serial completion proof.
    pub fn set_diagnostics(&mut self, enabled: bool) -> Result<(), String> {
        if enabled == self.context.diagnostics_enabled() {
            return Ok(());
        }
        self.gpu_error_reported = false;
        if !enabled {
            self.finish_diagnostics_capture()?;
            if self.host_query != vk::QueryPool::null() {
                self.context.retire_query_pool(std::mem::replace(
                    &mut self.host_query,
                    vk::QueryPool::null(),
                ));
            }
            self.query_frames = std::array::from_fn(|_| Default::default());
        } else if self.context.is_borrowed() && self.context.timestamp_bits > 0 {
            match unsafe {
                self.context.device.create_query_pool(
                    &vk::QueryPoolCreateInfo::default()
                        .query_type(vk::QueryType::TIMESTAMP)
                        .query_count(FRAME_SLOTS as u32 * QUERY_COUNT),
                    None,
                )
            } {
                Ok(pool) => self.host_query = pool,
                Err(vk::Result::ERROR_DEVICE_LOST) => {
                    return Err(error(
                        "Create diagnostic timestamps",
                        vk::Result::ERROR_DEVICE_LOST,
                    ));
                }
                Err(error) => {
                    self.gpu_error_reported = true;
                    eprintln!("[Prime PT] GPU diagnostics unavailable: {error:?}");
                }
            }
        }
        self.context.set_diagnostics(enabled);
        self.cpu_profile.set_enabled(enabled);
        self.gpu_intervals.fill(GpuIntervals::default());
        self.last_gpu = GpuIntervals::default();
        Ok(())
    }

    /// Finish the capture without stopping ordinary diagnostics. Completed ticks
    /// are exported, and unresolved recordings retain an explicit missing status.
    pub fn finish_diagnostics_capture(&mut self) -> Result<(), String> {
        if self.host_query == vk::QueryPool::null() {
            return Ok(());
        }
        self.collect_timing(self.context.completed_serial()?)?;
        for frame in &mut self.query_frames {
            if frame.context.is_some() {
                for stage in GpuStage::ALL {
                    if frame.begun(stage) {
                        frame.emit(
                            stage,
                            None,
                            self.context.queue.as_raw(),
                            self.context.timestamp_bits,
                            f64::from(self.context.timestamp_period),
                            if frame.accepted {
                                "pending"
                            } else {
                                "unsubmitted"
                            },
                        );
                    }
                }
                frame.context = None;
            }
        }
        Ok(())
    }

    fn timestamp(
        &self,
        command: vk::CommandBuffer,
        slot: usize,
        index: u32,
        stage: vk::PipelineStageFlags,
    ) {
        if let Some(index) = self.query_frames[slot].mark(self.host_query, index) {
            unsafe {
                self.context.device.cmd_write_timestamp(
                    command,
                    stage,
                    self.host_query,
                    slot as u32 * QUERY_COUNT + index,
                );
            }
        }
    }

    fn stage_timestamp(&self, command: vk::CommandBuffer, slot: usize, stage: GpuStage, end: bool) {
        self.timestamp(
            command,
            slot,
            stage.indices()[usize::from(end)],
            vk::PipelineStageFlags::BOTTOM_OF_PIPE,
        );
    }

    fn prepare_fixed_resources(&mut self) -> Result<(), String> {
        self.energy_lut.prepare()?;
        if self.atmosphere.is_none() {
            self.atmosphere = Some(atmosphere::Atmosphere::new(&self.context)?);
        }
        if self.settings.stars > 0.0 && self.starmap.is_none() {
            self.starmap = Some(starmap::Starmap::new(&self.context)?);
            let map = self.starmap.as_mut().unwrap();
            let mut result = Ok(Vec::new());
            self.context
                .submit_named("starmap_prepare", |command| result = map.prepare(command))?;
            drop(result?);
        }
        if self.needs_linear_display() && self.linear_display.is_none() {
            self.linear_display = Some(display_pipeline::LinearDisplay::new(&self.context)?);
        }
        if self.settings.auto_exposure_compensation > 0.0 && self.exposure.is_none() {
            self.exposure = Some(exposure::Exposure::new(&self.context)?);
            self.exposure_reset = true;
        }
        if self.reconstruction.is_some() && self.settings.stars > 0.0 && self.stars.is_none() {
            self.stars = Some(starmap::Stars::new(&self.context)?);
        }
        if self.hdr_calibration.is_some() && self.hdr_present.is_none() {
            self.hdr_present = Some(hdr::HdrPresent::new(&self.context)?);
        }
        if self.frame_generation_active() && self.fg_present.is_none() {
            self.fg_present = Some(hdr::FrameGenerationPresent::new(&self.context)?);
        }
        self.context.save_pipeline_cache();
        Ok(())
    }

    fn needs_linear_display(&self) -> bool {
        self.effective_view() == prime_scene::settings::DiagnosticView::Output
            && (self.settings.auto_exposure_compensation > 0.0
                || self.hdr_calibration.is_some()
                || self.frame_generation_active()
                || self.reconstruction.is_some() && self.settings.stars > 0.0)
    }

    fn frame_generation_active(&self) -> bool {
        self.settings.frame_generation
            && self.effective_view() == prime_scene::settings::DiagnosticView::Output
            && self
                .reconstruction
                .as_ref()
                .is_some_and(|rr| rr.frame_generation_supported())
    }

    fn effective_view(&self) -> prime_scene::settings::DiagnosticView {
        if self.settings.integrator == Integrator::RestirPt && self.settings.restir.debug_view != 0
        {
            prime_scene::settings::DiagnosticView::NoisyColor
        } else {
            self.settings.view
        }
    }

    pub fn display_output(&mut self, active: bool, peak: f32, white: f32) -> Result<(), String> {
        if self.failed || self.pending_temporal_serial != 0 {
            return Err(
                "Display reconfiguration requires a healthy accepted frame boundary".into(),
            );
        }
        let calibration = if active && self.settings.hdr {
            Some(hdr::HdrCalibration::new(
                peak,
                white,
                self.settings.hdr_reference_white,
            )?)
        } else {
            None
        };
        if calibration != self.hdr_calibration {
            if let Some(rr) = &mut self.reconstruction {
                rr.suspend_frame_generation()?;
            }
            self.hdr_calibration = calibration;
        }
        Ok(())
    }

    fn prepare_scene_resources(
        &mut self,
        scene: SceneInput<'_>,
        completed: u64,
    ) -> Result<(), String> {
        self.prepare_fixed_resources()?;
        if let Some(owner) = &self.scene_resources {
            let mut resources = owner.borrow_mut();
            let previous = resources.revision;
            resources.begin(&self.context, completed);
            resources.prepare(&self.context, scene)?;
            if resources.revision != previous {
                self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
                if let Some(restir) = &mut self.restir {
                    restir.lighting_changed();
                }
            }
        } else {
            self.scene_resources =
                Some(scene_resources::SceneResources::new(&self.context, scene)?);
            self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
        }
        Ok(())
    }

    /// Prepare immutable assets and the published texture/OMM resource generation without
    /// constructing terrain or a TLAS. This may precede a PT frame in the same submission.
    /// # Safety
    /// `command` is an active host command buffer on the attached queue. The caller submits
    /// it in order and signals `serial`; host ownership and cancellation rules match record_host.
    pub unsafe fn prepare_host_resources(
        &mut self,
        scene: SceneInput<'_>,
        command: u64,
        serial: u64,
    ) -> Result<(), String> {
        if self.failed || !self.context.is_borrowed() || command == 0 || serial == 0 {
            return Err("Invalid host resource preparation state".into());
        }
        self.failed = true;
        let command = vk::CommandBuffer::from_raw(command);
        let completed = self.context.begin_host_record(command, serial)?;
        let _scope = HostRecordScope(self.context.clone());
        self.before_frame(command);
        self.prepare_scene_resources(scene, completed)?;
        self.before_frame(command);
        self.failed = false;
        Ok(())
    }

    pub fn device_name(&self) -> &str {
        &self.context.name
    }

    /// Changes only the display transform. Scene-linear history remains valid.
    /// The current host target is SDR RGBA8, so surface headroom is fixed at one.
    pub fn set_prime_drt(&mut self, settings: PrimeDrtSettings) -> Result<(), String> {
        self.display = settings.prepare(1.0)?;
        Ok(())
    }

    /// A mode change is a frame-boundary operation. The host must submit its pending encoder first.
    /// Shared geometry survives; exclusive old pipelines/images retire before the new pipeline is created.
    pub fn configure(&mut self, settings: RenderSettings) -> Result<(), String> {
        settings.validate()?;
        let mode_changed = settings.mode != self.settings.mode;
        let integrator_changed = settings.integrator != self.settings.integrator;
        let rr_changed = settings.native_noisy_output != self.settings.native_noisy_output;
        let transport_changed = !settings.transport_matches(self.settings);
        let lighting_changed = settings.astronomy != self.settings.astronomy
            || settings.sun != self.settings.sun
            || settings.sky != self.settings.sky
            || settings.stars != self.settings.stars;
        if self.failed || self.pending_temporal_serial != 0 {
            return Err("Cannot configure a failed renderer".into());
        }
        self.set_history_reset_policy(settings.ignore_global_history_resets);
        let display = PrimeDrtSettings {
            exposure_multiplier: settings.exposure,
            hue_compensation: settings.hue,
            saturation_compensation: settings.saturation,
        }
        .prepare(1.0)?;
        if settings.view != self.settings.view {
            // The next prepare can replace HUDless/UI images even when the world extent
            // and RR quality stay unchanged. Present consumers outlive the world serial.
            if let Some(rr) = &mut self.reconstruction {
                rr.suspend_frame_generation()?;
            }
            self.fg_prepared = false;
        }
        if integrator_changed
            || mode_changed
            || settings.mode == RenderMode::Realtime && rr_changed
            || settings.frame_generation != self.settings.frame_generation
        {
            self.failed = true;
            self.context.wait_host_idle()?;
            if let Some(rr) = &mut self.reconstruction {
                // Present consumers can outlive completed world work even if RR itself stays.
                rr.suspend_frame_generation()?;
                if mode_changed || rr_changed {
                    rr.reset(GlobalReset::RenderDomainChanged);
                    rr.storage_cold(StorageCold::RenderDomainReleased);
                    drop(self.reconstruction.take());
                }
            }
            drop(self.output.take());
            drop(self.pipeline.take());
            if mode_changed || integrator_changed {
                if let Some(restir) = &mut self.restir {
                    restir.invalidate(GlobalReset::RenderDomainChanged);
                    restir.storage_cold(StorageCold::RenderDomainReleased);
                }
                drop(self.restir.take());
            }
            self.fg_prepared = false;
            self.context.completed_serial()?; // Drain the retired images/buffers using completed host work.
            if self.reconstruction.is_none()
                && settings.mode == RenderMode::Realtime
                && !settings.native_noisy_output
            {
                self.reconstruction_error = None;
                self.reconstruction = Some(reconstruction::Reconstruction::new(&self.context)?);
            }
            self.pipeline = Some(Pipeline::new(
                &self.context,
                settings.integrator,
                settings.mode,
                self.reconstruction.is_some(),
                settings.frame_generation
                    && self
                        .reconstruction
                        .as_ref()
                        .is_some_and(|rr| rr.frame_generation_supported()),
                settings.light_sampling,
                &self.energy_lut,
            )?);
            if let Some(rr) = &mut self.reconstruction {
                rr.invalidate_descriptors();
            }
            self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
            self.samples = 0;
            self.failed = false;
        } else if transport_changed
            || settings.mode == RenderMode::Realtime
                && settings.reconstruction_quality != self.settings.reconstruction_quality
        {
            self.samples = 0;
        }
        if let Some(geometry) = &mut self.geometry {
            geometry.set_omm(settings.opacity_micromap && self.context.opacity_micromap.is_some());
            geometry.set_stable_history(settings.integrator == Integrator::RestirPt);
            if mode_changed {
                geometry.objects.reset_motion();
            }
        }
        if (lighting_changed || settings.bounces != self.settings.bounces)
            && let Some(restir) = &mut self.restir
        {
            restir.lighting_changed();
        }
        self.display = display;
        if settings.mode != self.settings.mode {
            self.exposure_frozen =
                settings.mode == RenderMode::Offline && self.exposure_time.is_some();
            if settings.mode == RenderMode::Realtime {
                self.exposure_reset = true;
                self.exposure_time = None;
            }
        }
        if settings.auto_exposure_compensation != self.settings.auto_exposure_compensation {
            self.exposure_reset = true;
            self.exposure_frozen = false;
        }
        if self.settings.hdr && !settings.hdr {
            if let Some(rr) = &mut self.reconstruction {
                rr.suspend_frame_generation()?;
            }
            self.hdr_calibration = None;
        }
        self.settings = settings;
        self.set_history_reset_policy(settings.ignore_global_history_resets);
        Ok(())
    }

    fn samples_per_frame(&self) -> u32 {
        if self.settings.mode == RenderMode::Offline {
            self.settings.offline_samples
        } else {
            1
        }
    }

    /// The source stays immutable until thawed; queued geometry may finish publishing under its budget.
    pub fn set_scene_frozen(&mut self, frozen: bool) {
        self.scene_frozen = frozen;
    }

    /// Release the old world's geometry and frame storage without rebuilding device resources.
    /// The host must submit and complete its previous work before this world boundary.
    pub fn reset_world(&mut self) -> Result<(), String> {
        if self.failed || self.pending_temporal_serial != 0 {
            return Err("World reset requires an accepted or cancelled host recording".into());
        }
        self.failed = true;
        let completed = self.context.completed_serial()?;
        if completed < self.context.retirement_serial() {
            return Err("World reset requires completed host work".into());
        }
        if let Some(rr) = &mut self.reconstruction {
            rr.suspend_frame_generation()?;
        }
        drop(self.geometry.take());
        drop(self.output.take());
        if let Some(restir) = &mut self.restir {
            restir.reset_world();
        }
        if let Some(rr) = &mut self.reconstruction {
            rr.reset(GlobalReset::WorldReplaced);
        }
        if let Some(resources) = &self.scene_resources {
            let mut resources = resources.borrow_mut();
            resources.collect();
            resources.begin(&self.context, completed);
        }
        // Resource drops enqueue native destruction. Drain it now, even if no later world is drawn.
        self.context.completed_serial()?;
        self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
        self.host_serials = [0; FRAME_SLOTS];
        self.hdr_serials = [0; FRAME_SLOTS];
        self.fg_serials = [0; FRAME_SLOTS];
        self.fg_prepared_serial = 0;
        self.fg_prepared = false;
        self.pending_temporal_serial = 0;
        self.exposure_reset = true;
        self.exposure_frozen = false;
        self.exposure_time = None;
        self.collect_timing(completed)?;
        self.query_frames = std::array::from_fn(|_| Default::default());
        self.gpu_intervals = [GpuIntervals::default(); FRAME_SLOTS];
        self.last_gpu = GpuIntervals::default();
        self.camera = None;
        self.samples = 0;
        self.frame_seed = 0;
        self.scene_frozen = false;
        self.atmosphere_scene_revision = self.atmosphere_scene_revision.wrapping_add(1);
        self.failed = false;
        Ok(())
    }

    pub fn set_environment(
        &mut self,
        environment: prime_scene::environment::Environment,
    ) -> Result<(), String> {
        let environment = environment.validate()?;
        if self.environment != environment {
            self.samples = 0;
            let restir_changed = self.environment.sun_direction != environment.sun_direction
                || self.environment.eye_radius_km() != environment.eye_radius_km();
            if restir_changed && let Some(restir) = &mut self.restir {
                restir.lighting_changed();
            }
            self.environment = environment;
        }
        Ok(())
    }
    /// Last attempted host recording; fixed-size counters are retained, formatting is on demand.
    pub fn cpu_diagnostics(&self) -> String {
        let omm = self.geometry.as_ref().map_or([0; 4], Geometry::omm_stats);
        let omm_prepare = self.geometry.as_ref().map_or([0; 3], |g| g.omm_prepare_ns);
        let omm_work = self.geometry.as_ref().map_or([0; 2], |g| g.omm_work_counts);
        let omm_pool = self
            .geometry
            .as_ref()
            .map_or([0; 4], Geometry::omm_pool_stats);
        let (rr_evaluated, rr_input, rr_output) = self.reconstruction.as_ref().map_or(
            (false, [0; 2], [0; 2]),
            reconstruction::Reconstruction::diagnostics,
        );
        let rr_error = self
            .reconstruction
            .as_ref()
            .and_then(reconstruction::Reconstruction::last_error)
            .or(self.reconstruction_error.as_deref())
            .unwrap_or("");
        format!(
            "{} gpu_supported={} gpu_enabled={} gpu_available={} gpu_completed_serial={} gpu_preparation_ms={:.3} gpu_render_ms={:.3} gpu_total_ms={:.3} gpu_sample=delayed_completed_interval omm_capable={} omm_enabled={} omm_two_blocks={} omm_four_blocks={} omm_special_triangles={} omm_referenced_packed_bytes={} omm_template_ms={:.3} omm_bind_ms={:.3} omm_resource_record_ms={:.3} omm_template_preparations={} omm_bound_primitives={} omm_resource_builds={} omm_resource_blocks={} omm_resource_packed_bytes={} omm_resource_storage_bytes={} atmosphere_sky_updates={} atmosphere_camera_t_updates={} atmosphere_aerial_s_updates={} atmosphere_aerial_t_updates={} rr_requested={} rr_capable={} rr_ready={} rr_evaluation_succeeded={} rr_input={}x{} rr_output={}x{} rr_error={:?} fg_requested={} fg_capable={} fg_last_prepare_succeeded={} fg_prepare_serial={}",
            self.cpu_profile.last_report(),
            self.context.timestamp_bits > 0,
            self.context.diagnostics_enabled(),
            self.host_query != vk::QueryPool::null() && self.last_gpu.serial != 0,
            self.last_gpu.serial,
            self.last_gpu.preparation_ns as f64 / 1e6,
            self.last_gpu.render_ns as f64 / 1e6,
            self.last_gpu.total_ns as f64 / 1e6,
            self.context.opacity_micromap.is_some(),
            self.geometry.as_ref().is_some_and(|g| g.opacity_micromap),
            omm[0],
            omm[1],
            omm[2],
            omm[3],
            omm_prepare[0] as f64 / 1e6,
            omm_prepare[1] as f64 / 1e6,
            omm_prepare[2] as f64 / 1e6,
            omm_work[0],
            omm_work[1],
            omm_pool[0],
            omm_pool[1],
            omm_pool[2],
            omm_pool[3],
            self.atmosphere.as_ref().map_or(0, |a| a.sky_updates),
            self.atmosphere
                .as_ref()
                .map_or(0, |a| a.transmittance_updates),
            self.atmosphere.as_ref().map_or(0, |a| a.aerial_updates),
            self.atmosphere.as_ref().map_or(0, |a| a.aerial_t_updates),
            !self.settings.native_noisy_output && self.settings.mode == RenderMode::Realtime,
            self.context.streamline_capable,
            self.reconstruction.as_ref().is_some_and(|rr| rr.ready()),
            rr_evaluated,
            rr_input[0],
            rr_input[1],
            rr_output[0],
            rr_output[1],
            rr_error,
            self.settings.frame_generation,
            self.reconstruction
                .as_ref()
                .is_some_and(|rr| rr.frame_generation_supported()),
            !self.failed && self.frame_generation_active() && self.fg_prepared,
            self.fg_prepared_serial,
        )
    }

    pub fn profile_snapshot(&self) -> Option<GpuProfile> {
        self.context.profile_snapshot()
    }
    pub fn instance_work(&self) -> InstanceWork {
        self.geometry
            .as_ref()
            .map_or(InstanceWork::default(), |geometry| InstanceWork {
                resident_blas: geometry.objects.count() as u32,
                rebuilt_blas: geometry.objects.rebuilt,
                instances: geometry.objects.instances.len() as u32,
            })
    }
    pub fn last_gpu_time_ns(&self) -> u64 {
        self.last_gpu.total_ns
    }
    pub fn shutdown(&mut self) -> Result<(), String> {
        if self.context.is_borrowed() {
            self.context.finish_host()?;
        }
        if let Some(rr) = &mut self.reconstruction {
            rr.suspend_frame_generation()?;
        }
        self.failed = true;
        Ok(())
    }

    /// Compatibility diagnostic with no persistent object instances.
    pub fn render(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
    ) -> Result<Vec<u8>, String> {
        self.render_with_instances(
            scene,
            &InstanceScene::default(),
            camera,
            width,
            height,
            sample_index,
        )
    }

    /// Compatibility host recording with no persistent object instances.
    /// # Safety
    /// The same handle, ordering and completion contract as record_host_with_instances applies.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn record_host(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
        command: u64,
        image: u64,
        view: u64,
        serial: u64,
    ) -> Result<(), String> {
        unsafe {
            self.record_host_with_instances(
                scene,
                &InstanceScene::default(),
                camera,
                width,
                height,
                sample_index,
                command,
                image,
                view,
                serial,
            )
        }
    }

    /// Explicit image diagnostic only; production uses record_host without readback.
    #[allow(clippy::too_many_arguments)]
    pub fn render_with_instances<'a>(
        &mut self,
        scene: impl Into<SceneInput<'a>>,
        instances: impl Into<InstanceInput<'a>>,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
    ) -> Result<Vec<u8>, String> {
        let scene = scene.into();
        let instances = instances.into();
        if self.failed || self.context.is_borrowed() {
            return Err(
                "Readback requires a healthy, independently owned diagnostic renderer".into(),
            );
        }
        self.failed = true;
        let result = self.render_offline(scene, instances, camera, width, height, sample_index);
        if result.is_ok() {
            self.failed = false;
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    fn set_history_reset_policy(&mut self, ignore: bool) {
        if let Some(restir) = &mut self.restir {
            restir.set_history_reset_policy(ignore);
        }
        if let Some(rr) = &mut self.reconstruction {
            rr.set_history_reset_policy(ignore);
        }
    }

    fn prepare(
        &mut self,
        scene: SceneInput<'_>,
        instances: InstanceInput<'_>,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
        slot: usize,
        completed: u64,
        cpu: &mut FrameCpu,
    ) -> Result<(), String> {
        if camera
            .position
            .iter()
            .chain(camera.forward.iter())
            .chain(camera.right.iter())
            .chain(camera.up.iter())
            .any(|v| !v.is_finite())
            || !camera.vertical_fov_radians.is_finite()
            || !(0.01..3.13).contains(&camera.vertical_fov_radians)
        {
            return Err("Camera contains invalid vectors or vertical FOV".into());
        }
        self.set_history_reset_policy(self.settings.ignore_global_history_resets);
        self.prepare_scene_resources(scene, completed)?;
        if let Some(geometry) = &mut self.geometry {
            geometry.begin_frame(&self.context, completed);
        }
        if !self.scene_frozen || self.geometry.as_ref().is_none_or(|g| g.needs_update(scene)) {
            let started = cpu.start();
            if self.geometry.as_ref().is_none_or(|g| g.needs_update(scene)) {
                cpu.static_updates += 1;
                let source_changed = self
                    .geometry
                    .as_ref()
                    .is_none_or(|g| g.source_changed(scene));
                let resource_occlusion_changed = self
                    .geometry
                    .as_ref()
                    .is_none_or(|g| g.occlusion_resources_changed());
                if self
                    .geometry
                    .as_ref()
                    .is_some_and(|g| !g.history_owner_matches(scene))
                {
                    if let Some(restir) = &mut self.restir {
                        restir.invalidate(GlobalReset::SceneDomainReplaced);
                    }
                    if let Some(rr) = &mut self.reconstruction {
                        rr.reset(GlobalReset::SceneDomainReplaced);
                    }
                }
                self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
                let published_changed = if let Some(geometry) = &mut self.geometry
                    && geometry.same_owner(scene)
                {
                    geometry.update_prepared(
                        &self.context,
                        scene,
                        self.settings.terrain_batches_per_frame as usize,
                    )?
                } else {
                    // End the previous CPU owner before constructing another cache domain.
                    // An existing domain replacement was handled above; first creation is cold.
                    // Borrowed GPU resources still retire by their recorded completion serials.
                    self.geometry.take();
                    self.geometry = Some(Geometry::new_with_resources(
                        &self.context,
                        scene,
                        self.workers.clone(),
                        self.settings.opacity_micromap,
                        self.settings.terrain_batches_per_frame as usize,
                        self.scene_resources.as_ref().unwrap().clone(),
                        self.settings.light_sampling,
                        self.settings.integrator == Integrator::RestirPt,
                    )?);
                    true
                };
                if source_changed || published_changed {
                    self.samples = 0;
                }
                if published_changed && let Some(restir) = &mut self.restir {
                    restir.lighting_changed();
                }
                if self.geometry.as_ref().unwrap().static_occlusion_changed
                    || resource_occlusion_changed
                {
                    self.atmosphere_scene_revision = self.atmosphere_scene_revision.wrapping_add(1);
                }
            }
            cpu.finish(Stage::Static, started);
            self.geometry
                .as_mut()
                .unwrap()
                .objects
                .set_motion_enabled(self.reconstruction.is_some());
            let (dynamic_changed, occlusion_changed, bindings_changed) = self
                .geometry
                .as_mut()
                .unwrap()
                .prepare_dynamic(&self.context, &scene, instances, slot, cpu)?;
            if dynamic_changed {
                self.samples = 0;
                if let Some(restir) = &mut self.restir {
                    restir.lighting_changed();
                }
            }
            if occlusion_changed {
                self.atmosphere_scene_revision = self.atmosphere_scene_revision.wrapping_add(1);
            }
            if bindings_changed {
                // A freed raw handle can reappear in an older descriptor slot's key.
                self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
            }
        }
        let started = cpu.start();
        if self
            .output
            .as_ref()
            .is_none_or(|o| o.width != width || o.height != height)
        {
            if let Some(rr) = &mut self.reconstruction {
                rr.suspend_frame_generation()?;
            }
            self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
            self.output = Some(Output::new(
                &self.context,
                width,
                height,
                self.settings.mode,
            )?);
            self.samples = 0;
        }
        // Float accumulation loses unit sample precision beyond 2^24; start a
        // fresh history before then, without overflowing sample + 1 in the shader.
        if self.camera != Some(*camera)
            || sample_index == 0
            || self.samples > (1 << 24) - self.samples_per_frame()
            || self.settings.mode == RenderMode::Realtime
        {
            self.samples = 0;
        }
        self.camera = Some(*camera);
        self.frame_seed = if self.settings.mode == RenderMode::Offline {
            self.samples
        } else {
            sample_index
        };
        if let Some(rr) = &mut self.reconstruction {
            rr.set_history_reset_policy(self.settings.ignore_global_history_resets);
            rr.prepare(
                slot,
                *camera,
                scene.anchor,
                scene.epoch,
                [width, height],
                sample_index,
                self.settings.reconstruction_quality,
                completed,
                self.settings.frame_generation,
            )?;
        }
        if self.settings.integrator == Integrator::PathTrace
            && self.settings.mode == RenderMode::Realtime
        {
            // RR setup/failure has now selected the actual render extent. Scratch is GPU-only,
            // reused on this queue and retired through Buffer's host serial proof on resize.
            let extent = self
                .reconstruction
                .as_ref()
                .map_or([width, height], |rr| rr.input_extent());
            let output = self.output.as_mut().unwrap();
            let reconstruction = self.reconstruction.is_some();
            let optics = self
                .geometry
                .as_ref()
                .is_some_and(|g| g.shader_variant() >= 4);
            if output.scratch.as_ref().is_none_or(|scratch| {
                scratch.extent != extent
                    || scratch.reconstruction != reconstruction
                    || scratch.optics != optics
            }) {
                output.scratch = Some(realtime::Scratch::new(
                    &self.context,
                    extent,
                    reconstruction,
                    optics,
                )?);
            }
            output.scratch.as_mut().unwrap().set_diagnostic(
                &self.context,
                self.reconstruction.is_none() && realtime::needs_diagnostic(self.settings.view),
            )?;
        }
        cpu.finish(Stage::Output, started);
        let needs_display = self.needs_linear_display();
        let fg = self.frame_generation_active();
        self.output.as_mut().unwrap().prepare_display(
            &self.context,
            self.settings.mode,
            needs_display,
            self.hdr_calibration.is_some(),
            fg,
        )?;
        let star_controls = [
            (self.settings.astronomy.latitude_degrees as f32).to_radians(),
            (self.settings.astronomy.solar_longitude_degrees as f32).to_radians(),
            0.025 * self.settings.stars,
            0.0,
        ];
        self.atmosphere.as_mut().unwrap().set_starmap(
            slot,
            self.starmap.as_ref(),
            star_controls,
        )?;
        self.atmosphere.as_mut().unwrap().prepare(
            self.environment,
            camera,
            width as f32 / height as f32,
            slot,
            self.geometry
                .as_ref()
                .map(|g| (g, self.atmosphere_scene_revision)),
        )?;
        Ok(())
    }

    fn descriptors(&mut self, slot: usize, view: vk::ImageView) {
        let geometry = self.geometry.as_ref().unwrap();
        let textures = geometry.textures();
        let output = self.output.as_ref().unwrap();
        let accumulation = output.accumulation.as_ref();
        let key = [
            geometry.top.handle().as_raw(),
            textures.metadata.buffer.as_raw(),
            textures.texels.buffer.as_raw(),
            view.as_raw(),
            geometry.objects.metadata.buffer.as_raw(),
            geometry.static_bases.buffer.as_raw(),
            accumulation.map_or(0, |b| b.buffer.as_raw()),
        ];
        let descriptor = self.pipeline.as_ref().unwrap().descriptors[slot];
        if let Some(restir) = &self.restir {
            restir.descriptors(
                &self.context,
                descriptor,
                slot,
                output.linear.as_ref().map(|image| image.view),
            );
        }
        if let Some(rr) = &mut self.reconstruction {
            rr.descriptors(descriptor, slot);
        }
        let image_info = |view| {
            [vk::DescriptorImageInfo::default()
                .image_view(view)
                .image_layout(vk::ImageLayout::GENERAL)]
        };
        let host_image = image_info(view);
        let host_write = vk::WriteDescriptorSet::default()
            .dst_set(descriptor)
            .dst_binding(4)
            .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
            .image_info(&host_image);
        if self.descriptor_keys[slot] == key {
            // Refresh even when the host reuses a raw view handle after a resize.
            unsafe {
                self.context
                    .device
                    .update_descriptor_sets(&[host_write], &[]);
            }
            return;
        }
        let handles = [geometry.top.handle()];
        let mut acceleration = vk::WriteDescriptorSetAccelerationStructureKHR::default()
            .acceleration_structures(&handles);
        let buffers = [
            &textures.metadata,
            &textures.texels,
            &geometry.objects.metadata,
            &geometry.static_bases,
        ];
        let infos = buffers.map(|b| {
            [vk::DescriptorBufferInfo::default()
                .buffer(b.buffer)
                .range(b.size)]
        });
        let mut writes = Vec::with_capacity(7);
        writes.push(
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(0)
                .descriptor_count(1)
                .descriptor_type(vk::DescriptorType::ACCELERATION_STRUCTURE_KHR)
                .push_next(&mut acceleration),
        );
        writes.push(host_write);
        for (binding, info) in [2, 3, 7, 8].into_iter().zip(&infos) {
            writes.push(
                vk::WriteDescriptorSet::default()
                    .dst_set(descriptor)
                    .dst_binding(binding)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(info),
            );
        }
        let accumulation_info = accumulation.map(|b| {
            [vk::DescriptorBufferInfo::default()
                .buffer(b.buffer)
                .range(b.size)]
        });
        if let Some(info) = &accumulation_info
            && self.settings.integrator == Integrator::PathTrace
        {
            writes.push(
                vk::WriteDescriptorSet::default()
                    .dst_set(descriptor)
                    .dst_binding(5)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(info),
            );
        }
        unsafe {
            self.context.device.update_descriptor_sets(&writes, &[]);
        }
        self.descriptor_keys[slot] = key;
    }

    fn prepare_restir(&mut self, slot: usize, bottom_up: bool) -> Result<(), String> {
        if self.settings.integrator != Integrator::RestirPt {
            return Ok(());
        }
        let frame = self.transport_frame(bottom_up);
        let output = self.output.as_ref().unwrap();
        let extent = self
            .reconstruction
            .as_ref()
            .map_or([output.width, output.height], |rr| rr.input_extent());
        let accumulation = output.accumulation.as_ref().map_or(0, Buffer::address);
        let linear = self.needs_linear_display();
        let (instances, terrain_count) = self
            .geometry
            .as_mut()
            .unwrap()
            .shader_instance_input(&self.context, slot)?;
        let geometry = self.geometry.as_mut().unwrap();
        geometry.set_history_revision_floor(
            self.restir
                .as_ref()
                .map_or(0, |state| state.accepted_revision()),
        );
        let identity = geometry.shader_history_identity(&self.context, slot)?;
        let anchor = geometry.anchor();
        let jitter = self
            .reconstruction
            .as_ref()
            .map_or([0.; 2], |rr| rr.jitter(self.frame_seed));
        if self.restir.is_none() {
            self.restir = Some(restir::State::new(&self.context)?);
        }
        self.restir
            .as_mut()
            .unwrap()
            .set_history_reset_policy(self.settings.ignore_global_history_resets);
        self.restir.as_mut().unwrap().prepare(
            &self.context,
            slot,
            frame,
            extent,
            terrain_count,
            instances,
            accumulation,
            self.settings.mode == RenderMode::Realtime,
            self.settings.restir_spatial_only,
            self.settings.restir,
            self.reconstruction.is_some(),
            linear,
            jitter,
            anchor,
            identity,
        )?;
        let pipeline = self.pipeline.as_mut().unwrap();
        pipeline.restir.as_mut().unwrap().ensure_optional(
            pipeline.layout,
            self.settings.restir,
            self.restir.as_ref().unwrap().rr_statistics_this_frame,
            self.restir.as_ref().unwrap().duplicate_this_frame,
        )
    }

    fn transport_frame(&self, bottom_up: bool) -> [u8; 128] {
        let camera = self.camera.unwrap();
        let output = self.output.as_ref().unwrap();
        let samples_this_dispatch = self.samples_per_frame();
        let [render_width, render_height] = self
            .reconstruction
            .as_ref()
            .map_or([output.width, output.height], |rr| rr.input_extent());
        let log2_resolution = if self.reconstruction.is_some() {
            prime_scene::extent::RenderExtent::new(render_width, render_height)
                .unwrap()
                .log2_resolution()
        } else {
            output.log2_resolution
        };
        let mut push = [0u8; 128];
        let values = [
            camera.position[0],
            camera.position[1],
            camera.position[2],
            (camera.vertical_fov_radians * 0.5).tan(),
            camera.forward[0],
            camera.forward[1],
            camera.forward[2],
            output.width as f32 / output.height as f32,
            camera.right[0],
            camera.right[1],
            camera.right[2],
            self.settings.sun,
            camera.up[0],
            camera.up[1],
            camera.up[2],
            self.settings.sky,
        ];
        for (destination, value) in push[..64].as_chunks_mut::<4>().0.iter_mut().zip(values) {
            *destination = value.to_le_bytes();
        }
        let integers = [
            render_width,
            render_height,
            self.samples,
            samples_this_dispatch,
            u32::from(bottom_up),
            self.frame_seed,
            log2_resolution,
            self.settings.seed,
        ];
        for (destination, value) in push[64..96].as_chunks_mut::<4>().0.iter_mut().zip(integers) {
            *destination = value.to_le_bytes();
        }
        for (destination, value) in push[96..]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(self.display.values)
        {
            *destination = value.to_le_bytes();
        }
        push[116..120].copy_from_slice(&self.settings.depth_range.to_le_bytes());
        push[120..124].copy_from_slice(&self.settings.bounces.to_le_bytes());
        let view_flags =
            self.effective_view() as u32 | (u32::from(self.needs_linear_display()) << 31);
        push[124..128].copy_from_slice(&view_flags.to_le_bytes());
        push
    }

    fn dispatch(&self, command: vk::CommandBuffer, slot: usize, bottom_up: bool) {
        if self.settings.integrator == Integrator::RestirPt {
            self.dispatch_restir(command, slot);
            return;
        }
        if self.settings.mode == RenderMode::Realtime {
            self.dispatch_realtime(command, slot, bottom_up);
            return;
        }
        let pipeline = self.pipeline.as_ref().unwrap();
        let samples_this_dispatch = self.samples_per_frame();
        let output = self.output.as_ref().unwrap();
        let [render_width, render_height] = self
            .reconstruction
            .as_ref()
            .map_or([output.width, output.height], |rr| rr.input_extent());
        let push = self.transport_frame(bottom_up);
        self.stage_timestamp(command, slot, GpuStage::Offline, false);
        unsafe {
            self.context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.for_dispatch(
                    self.geometry.as_ref().map_or(0, Geometry::shader_variant),
                    samples_this_dispatch,
                ),
            );
            self.context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.layout,
                0,
                &[
                    pipeline.descriptors[slot],
                    self.atmosphere.as_ref().unwrap().descriptor(slot),
                ],
                &[],
            );
            self.context.device.cmd_push_constants(
                command,
                pipeline.layout,
                vk::ShaderStageFlags::COMPUTE,
                0,
                &push,
            );
            self.context.device.cmd_dispatch(
                command,
                render_width.div_ceil(8),
                render_height.div_ceil(8),
                1,
            );
        }
        self.stage_timestamp(command, slot, GpuStage::Offline, true);
    }

    fn dispatch_realtime(&self, command: vk::CommandBuffer, slot: usize, bottom_up: bool) {
        let pipeline = self.pipeline.as_ref().unwrap();
        let output = self.output.as_ref().unwrap();
        let scratch = output.scratch.as_ref().unwrap();
        let input = scratch.extent;
        let variant = self.geometry.as_ref().map_or(0, Geometry::shader_variant);
        let pushes = realtime::PushInputs {
            camera: self.camera.unwrap(),
            input,
            output: [output.width, output.height],
            sequence: self.frame_seed,
            sobol_r: prime_scene::extent::RenderExtent::new(input[0], input[1])
                .unwrap()
                .log2_resolution(),
            settings: self.settings,
            display: self.display,
            addresses: scratch.addresses(),
            jitter: self
                .reconstruction
                .as_ref()
                .map(|_| reconstruction_history::jitter(self.frame_seed, input[0], output.width)),
            bottom_up,
        };
        unsafe {
            self.context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.layout,
                0,
                &[
                    pipeline.descriptors[slot],
                    self.atmosphere.as_ref().unwrap().descriptor(slot),
                ],
                &[],
            );
            let dispatch = |selected, push: &[u8]| {
                self.context.device.cmd_bind_pipeline(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    selected,
                );
                self.context.device.cmd_push_constants(
                    command,
                    pipeline.layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    push,
                );
                self.context.device.cmd_dispatch(
                    command,
                    input[0].div_ceil(8),
                    input[1].div_ceil(8),
                    1,
                );
            };
            self.stage_timestamp(command, slot, GpuStage::Primary, false);
            dispatch(
                pipeline.primary_pipelines.as_ref().unwrap()[realtime::PRIMARY_VARIANT[variant]],
                &pushes.primary(),
            );
            self.stage_timestamp(command, slot, GpuStage::Primary, true);
            self.realtime_barrier(command);
            self.stage_timestamp(command, slot, GpuStage::Transport, false);
            dispatch(pipeline.pipelines[variant], &pushes.transport(variant >= 2));
            self.stage_timestamp(command, slot, GpuStage::Transport, true);
            self.realtime_barrier(command);
            let post = if self.reconstruction.is_none() && self.needs_linear_display() {
                pipeline.realtime_linear_post.unwrap()
            } else {
                pipeline.realtime_post.unwrap()
            };
            self.stage_timestamp(command, slot, GpuStage::Post, false);
            dispatch(post, &pushes.post());
            self.stage_timestamp(command, slot, GpuStage::Post, true);
        }
    }

    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn dispatch_restir_for_test(&self, command: vk::CommandBuffer) {
        self.dispatch_restir(command, 0);
    }

    fn dispatch_restir(&self, command: vk::CommandBuffer, slot: usize) {
        let pipeline = self.pipeline.as_ref().unwrap();
        let passes = pipeline.restir.as_ref().unwrap();
        let state = self.restir.as_ref().unwrap();
        let output = self.output.as_ref().unwrap();
        let variant = self.geometry.as_ref().map_or(0, Geometry::shader_variant);
        let extent = self
            .reconstruction
            .as_ref()
            .map_or([output.width, output.height], |rr| rr.input_extent());
        let groups = [extent[0].div_ceil(8), extent[1].div_ceil(8)];
        let realtime = self.settings.mode == RenderMode::Realtime;
        let queue = state.queue_buffer();
        if !realtime {
            self.stage_timestamp(command, slot, GpuStage::Offline, false);
        }
        unsafe {
            self.context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.layout,
                0,
                &[
                    pipeline.descriptors[slot],
                    self.atmosphere.as_ref().unwrap().descriptor(slot),
                ],
                &[],
            );
            let bind = |selected, phase: u32, sample: u32, round: u32| {
                self.context.device.cmd_bind_pipeline(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    selected,
                );
                let push = [
                    phase,
                    sample,
                    u32::from(phase == 0 && state.dynamic_update_this_frame),
                    round,
                ]
                .map(u32::to_le_bytes);
                self.context.device.cmd_push_constants(
                    command,
                    pipeline.layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    push.as_flattened(),
                );
            };
            let dispatch = |selected, phase, sample, round| {
                bind(selected, phase, sample, round);
                self.context
                    .device
                    .cmd_dispatch(command, groups[0], groups[1], 1);
            };
            let workload = |phase, sample, round| {
                // Last indirect and shader consumers finish before resetting the shared GPU counter.
                self.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::DRAW_INDIRECT,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(
                            vk::AccessFlags::SHADER_READ
                                | vk::AccessFlags::SHADER_WRITE
                                | vk::AccessFlags::INDIRECT_COMMAND_READ,
                        )
                        .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)],
                    &[],
                    &[],
                );
                self.context
                    .device
                    .cmd_fill_buffer(command, queue, 0, 16, 0);
                self.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                        .dst_access_mask(
                            vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                        )],
                    &[],
                    &[],
                );
                dispatch(passes.workload[variant], phase, sample, round);
                self.realtime_barrier(command);
                bind(passes.indirect, phase, sample, round);
                self.context.device.cmd_dispatch(command, 1, 1, 1);
                self.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::DRAW_INDIRECT,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                        .dst_access_mask(
                            vk::AccessFlags::SHADER_READ
                                | vk::AccessFlags::SHADER_WRITE
                                | vk::AccessFlags::INDIRECT_COMMAND_READ,
                        )],
                    &[],
                    &[],
                );
                bind(passes.retrace[variant], phase, sample, round);
                self.context.device.cmd_dispatch_indirect(command, queue, 4);
                self.realtime_barrier(command);
                if phase == 1 {
                    dispatch(passes.shift[variant], phase, sample, round);
                    self.realtime_barrier(command);
                }
            };
            // Offline disables temporal reuse. Each offset selects a new path/RNG stream,
            // then resolves its correlated spatial estimator into the existing online mean.
            for sample in 0..self.samples_per_frame() {
                if realtime {
                    self.stage_timestamp(command, slot, GpuStage::Primary, false);
                }
                dispatch(passes.generate[variant], 0, sample, 0);
                if realtime {
                    self.stage_timestamp(command, slot, GpuStage::Primary, true);
                }
                self.realtime_barrier(command);
                if realtime {
                    self.stage_timestamp(command, slot, GpuStage::Transport, false);
                }
                if state.temporal_this_frame {
                    workload(0, sample, 0);
                    dispatch(passes.temporal[variant], 0, sample, 0);
                    self.realtime_barrier(command);
                }
                let rounds = if state.settings.spatial_reuse {
                    state.settings.spatial_iterations
                } else {
                    0
                };
                for round in 0..rounds {
                    workload(1, sample, round);
                    dispatch(passes.spatial, 1, sample, round);
                    if round + 1 < rounds {
                        self.realtime_barrier(command);
                    }
                }
                if state.duplicate_this_frame {
                    self.realtime_barrier(command);
                    bind(passes.sample_ids, 1, sample, rounds);
                    self.context.device.cmd_dispatch(
                        command,
                        extent[0].div_ceil(16),
                        extent[1].div_ceil(16),
                        1,
                    );
                    self.realtime_barrier(command);
                    bind(passes.duplicate_map, 1, sample, rounds);
                    self.context.device.cmd_dispatch(
                        command,
                        extent[0].div_ceil(16),
                        extent[1].div_ceil(16),
                        1,
                    );
                }
                if state.rr_statistics_this_frame {
                    self.realtime_barrier(command);
                    dispatch(passes.rr_statistics, 1, sample, rounds);
                }
                if realtime {
                    self.stage_timestamp(command, slot, GpuStage::Transport, true);
                }
                self.realtime_barrier(command);
                if realtime {
                    self.stage_timestamp(command, slot, GpuStage::Post, false);
                }
                dispatch(passes.resolve[variant], 1, sample, rounds);
                if realtime {
                    self.stage_timestamp(command, slot, GpuStage::Post, true);
                }
                self.realtime_barrier(command);
            }
        }
        if !realtime {
            self.stage_timestamp(command, slot, GpuStage::Offline, true);
        }
    }

    fn realtime_barrier(&self, command: vk::CommandBuffer) {
        // Covers hot write->read, tail write->read, and all earlier K1 prefix/guide writes.
        unsafe {
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)],
                &[],
                &[],
            );
        }
    }

    fn restir_debug_display(&self, command: vk::CommandBuffer, slot: usize) {
        if self.settings.integrator != Integrator::RestirPt || self.settings.restir.debug_view == 0
        {
            return;
        }
        let pipeline = self.pipeline.as_ref().unwrap();
        let output = self.output.as_ref().unwrap();
        self.realtime_barrier(command);
        unsafe {
            self.context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.restir.as_ref().unwrap().debug_display,
            );
            self.context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.layout,
                0,
                &[pipeline.descriptors[slot]],
                &[],
            );
            self.context.device.cmd_dispatch(
                command,
                output.width.div_ceil(8),
                output.height.div_ceil(8),
                1,
            );
        }
    }

    fn record_display(
        &mut self,
        command: vk::CommandBuffer,
        slot: usize,
        host: vk::ImageView,
        bottom_up: bool,
        linear_output: bool,
    ) -> Result<(), String> {
        // The destination was chosen before RR evaluated. SDK failure can disable FG
        // during this recording, but the frame must still consume its bound linear image.
        if !linear_output {
            return Ok(());
        }
        let output = self.output.as_ref().unwrap();
        let extent = [output.width, output.height];
        let hdr_world = output.hdr_world.as_ref().unwrap().view;
        let baseline = output.sdr_baseline.as_ref().unwrap().view;
        let input = output.linear.as_ref().map_or(hdr_world, |image| image.view);
        let history = output.accumulation.as_ref().map_or(0, Buffer::address);
        let linear709 = self.reconstruction.is_some();
        if let (Some(stars), Some(map), Some(rr)) =
            (&self.stars, &self.starmap, &self.reconstruction)
            && self.settings.stars > 0.0
        {
            let camera = self.camera.unwrap();
            let input_extent = rr.input_extent();
            let radius = self.environment.eye_radius_km();
            let horizon = -(1.0 - (6360.0 / radius) * (6360.0 / radius))
                .max(0.0)
                .sqrt();
            self.stage_timestamp(command, slot, GpuStage::Stars, false);
            stars.record(
                command,
                slot,
                input,
                rr.status_view(),
                self.atmosphere.as_ref().unwrap().transmittance_view(),
                map,
                &starmap::StarsParameters {
                    forward: [
                        camera.forward[0],
                        camera.forward[1],
                        camera.forward[2],
                        (camera.vertical_fov_radians * 0.5).tan(),
                    ],
                    right: [
                        camera.right[0],
                        camera.right[1],
                        camera.right[2],
                        output.width as f32 / output.height as f32,
                    ],
                    up: [camera.up[0], camera.up[1], camera.up[2], 0.0],
                    sun: [
                        self.environment.sun_direction[0],
                        self.environment.sun_direction[1],
                        self.environment.sun_direction[2],
                        horizon,
                    ],
                    settings: [
                        (self.settings.astronomy.latitude_degrees as f32).to_radians(),
                        (self.settings.astronomy.solar_longitude_degrees as f32).to_radians(),
                        self.settings.stars * 0.025,
                        1.0,
                    ],
                    dimensions: [
                        output.width,
                        output.height,
                        input_extent[0],
                        input_extent[1],
                    ],
                    jitter: reconstruction_history::jitter(
                        self.frame_seed,
                        input_extent[0],
                        output.width,
                    ),
                },
            );
            self.stage_timestamp(command, slot, GpuStage::Stars, true);
        }
        let exposure_address = if self.settings.auto_exposure_compensation > 0.0 {
            if !self.exposure_frozen {
                let now = std::time::Instant::now();
                let delta = self
                    .exposure_time
                    .map_or(0.0, |previous| now.duration_since(previous).as_secs_f32());
                self.stage_timestamp(command, slot, GpuStage::Exposure, false);
                self.exposure.as_mut().unwrap().record(
                    command,
                    slot,
                    input,
                    history,
                    extent,
                    linear709,
                    delta,
                    self.exposure_reset,
                    self.settings.mode == RenderMode::Offline,
                    self.settings.auto_exposure_compensation,
                )?;
                self.stage_timestamp(command, slot, GpuStage::Exposure, true);
                self.exposure_time = Some(now);
                self.exposure_reset = false;
                self.exposure_frozen = self.settings.mode == RenderMode::Offline;
            }
            self.exposure.as_ref().unwrap().state_address()
        } else {
            0
        };
        let hdr = PrimeDrtSettings {
            exposure_multiplier: self.display.values[0],
            hue_compensation: self.display.values[3],
            saturation_compensation: self.display.values[4],
        }
        .prepare(
            self.hdr_calibration
                .map_or(1.0, |calibration| calibration.headroom),
        )?;
        self.stage_timestamp(command, slot, GpuStage::Display, false);
        self.linear_display.as_ref().unwrap().record(
            command,
            slot,
            [input, host, hdr_world, baseline],
            history,
            extent,
            linear709,
            bottom_up,
            self.hdr_calibration.is_some(),
            exposure_address,
            self.display,
            hdr,
            self.frame_generation_active(),
        )?;
        self.stage_timestamp(command, slot, GpuStage::Display, true);
        Ok(())
    }

    /// # Safety
    /// The active host command and borrowed sampled RGBA8 UI / storage FP16 output views are
    /// on the attached device/queue in GENERAL, and remain live through serial completion.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn present_hdr(
        &mut self,
        command: u64,
        ui: u64,
        output: u64,
        serial: u64,
        extent: [u32; 2],
    ) -> Result<(), String> {
        if self.failed
            || [command, ui, output, serial].contains(&0)
            || self.hdr_serials.contains(&serial)
        {
            return Err("Invalid or duplicate HDR presentation state".into());
        }
        let calibration = self
            .hdr_calibration
            .ok_or("HDR surface calibration is inactive")?;
        let frame = self.output.as_ref().ok_or("No HDR world frame available")?;
        if extent != [frame.width, frame.height] {
            return Err("HDR presentation extent differs from the world snapshot".into());
        }
        let command = vk::CommandBuffer::from_raw(command);
        self.failed = true;
        let completed = self.context.begin_host_record(command, serial)?;
        let _scope = HostRecordScope(self.context.clone());
        let slot = match self
            .hdr_serials
            .iter()
            .position(|value| *value <= completed)
        {
            Some(slot) => slot,
            None => {
                let completed = self
                    .context
                    .wait_host_serial(*self.hdr_serials.iter().min().unwrap())?;
                self.hdr_serials
                    .iter()
                    .position(|value| *value <= completed)
                    .ok_or("HDR descriptor slot did not complete")?
            }
        };
        let fg = self.frame_generation_active();
        self.hdr_present
            .as_ref()
            .ok_or("HDR presentation pipeline is absent")?
            .record(
                command,
                slot,
                [
                    frame.hdr_world.as_ref().unwrap().view,
                    frame.sdr_baseline.as_ref().unwrap().view,
                    vk::ImageView::from_raw(ui),
                    vk::ImageView::from_raw(output),
                    frame
                        .fg_hudless
                        .as_ref()
                        .map_or(frame.hdr_world.as_ref().unwrap().view, |image| image.view),
                    frame.fg_alpha.as_ref().unwrap().view,
                ],
                extent,
                self.needs_linear_display(),
                true,
                calibration.sc_rgb_scale,
                fg,
            )?;
        self.hdr_serials[slot] = serial;
        self.failed = false;
        Ok(())
    }

    /// # Safety
    /// Views and command belong to the borrowed host device, are in GENERAL, and stay
    /// live through the SDK's published input-consumer completion as well as host serial.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn prepare_frame_generation(
        &mut self,
        command: u64,
        ui: u64,
        serial: u64,
        extent: [u32; 2],
        back_buffers: u32,
        format: u32,
    ) -> Result<bool, String> {
        if !self.frame_generation_active() {
            self.fg_prepared = false;
            return Ok(false);
        }
        if self.failed
            || [command, ui, serial].contains(&0)
            || serial <= self.fg_prepared_serial
            || back_buffers == 0
        {
            return Err("Invalid or duplicate frame generation presentation".into());
        }
        let format =
            vk::Format::from_raw(i32::try_from(format).map_err(|_| "Invalid back buffer format")?);
        let hdr = self.hdr_calibration.is_some();
        if (hdr && format != vk::Format::R16G16B16A16_SFLOAT)
            || (!hdr && ![vk::Format::R8G8B8A8_UNORM, vk::Format::B8G8R8A8_UNORM].contains(&format))
        {
            return Err(
                "Frame generation back buffer colorspace/format differs from the displayed world"
                    .into(),
            );
        }
        let frame = self
            .output
            .as_ref()
            .ok_or("No frame generation world frame")?;
        if extent != [frame.width, frame.height] || frame.fg_extent != extent || frame.fg_hdr != hdr
        {
            return Err("Frame generation presentation extent or calibration differs from the world snapshot".into());
        }
        if hdr && !self.hdr_serials.contains(&serial) {
            return Err(
                "HDR frame generation requires its same-submission scRGB composite first".into(),
            );
        }
        self.failed = true;
        let command = vk::CommandBuffer::from_raw(command);
        let completed = self.context.begin_host_record(command, serial)?;
        let _scope = HostRecordScope(self.context.clone());
        if !hdr {
            let slot = match self.fg_serials.iter().position(|value| *value <= completed) {
                Some(slot) => slot,
                None => {
                    let completed = self
                        .context
                        .wait_host_serial(*self.fg_serials.iter().min().unwrap())?;
                    self.fg_serials
                        .iter()
                        .position(|value| *value <= completed)
                        .ok_or("Frame generation descriptor slot did not complete")?
                }
            };
            self.fg_present
                .as_ref()
                .ok_or("Frame generation display pipeline is absent")?
                .record(
                    command,
                    slot,
                    [
                        frame.sdr_baseline.as_ref().unwrap().view,
                        vk::ImageView::from_raw(ui),
                        frame.fg_hudless.as_ref().unwrap().view,
                        frame.fg_alpha.as_ref().unwrap().view,
                    ],
                    extent,
                    true,
                )?;
            self.fg_serials[slot] = serial;
        }
        let prepared = self
            .reconstruction
            .as_mut()
            .unwrap()
            .prepare_frame_generation(
                command,
                frame.fg_hudless.as_ref().unwrap(),
                frame.fg_alpha.as_ref().unwrap(),
                back_buffers,
                format,
                serial,
            )?;
        self.fg_prepared_serial = serial;
        self.fg_prepared = prepared;
        self.failed = false;
        Ok(prepared)
    }

    // Covers prior host color writes, previous trace reads, material arena copies,
    // reused accumulation and AS input reads before this frame's incremental work.
    fn before_frame(&self, command: vk::CommandBuffer) {
        unsafe {
            let memory = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
                .dst_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)];
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::DependencyFlags::empty(),
                &memory,
                &[],
                &[],
            );
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn render_offline(
        &mut self,
        scene: SceneInput<'_>,
        instances: InstanceInput<'_>,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
    ) -> Result<Vec<u8>, String> {
        // Offline preparation also rebuilds retained dynamic buffers/AS. The
        // production caller has already inserted this dependency in its command.
        self.context
            .submit_named("diagnostic_prepare_barrier", |command| {
                self.before_frame(command)
            })?;
        self.prepare(
            scene,
            instances,
            camera,
            width,
            height,
            sample_index,
            0,
            u64::MAX,
            &mut FrameCpu::default(),
        )?;
        let view = self.output.as_ref().unwrap().image.as_ref().unwrap().view;
        self.prepare_restir(0, false)?;
        let linear_output = self.needs_linear_display();
        let selected = if (self.settings.integrator == Integrator::PathTrace
            || self.reconstruction.is_some())
            && self.settings.mode == RenderMode::Realtime
            && linear_output
        {
            self.output.as_ref().unwrap().linear.as_ref().unwrap().view
        } else {
            view
        };
        self.descriptors(0, selected);
        let image = self.output.as_ref().unwrap().image.as_ref().unwrap().image;
        let readback_buffer = self
            .output
            .as_ref()
            .unwrap()
            .readback
            .as_ref()
            .unwrap()
            .buffer;
        let context = self.context.clone();
        let mut recorded = Ok(());
        let display_view = self.effective_view();
        context.submit_named("diagnostic_render", |command| unsafe {
            self.before_frame(command);
            self.dispatch(command, 0, false);
            if let Some(rr) = &mut self.reconstruction {
                recorded = rr.evaluate_and_display(
                    command,
                    self.pipeline.as_ref().unwrap(),
                    0,
                    0,
                    self.display,
                    display_view,
                    self.settings.depth_range,
                    false,
                    linear_output,
                );
            }
            if recorded.is_ok() {
                self.restir_debug_display(command, 0);
                recorded = self.record_display(command, 0, view, false, linear_output);
            }
            let barrier = [vk::ImageMemoryBarrier::default()
                .image(image)
                .old_layout(vk::ImageLayout::GENERAL)
                .new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ)
                .subresource_range(target::color_range())];
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &barrier,
            );
            self.context.device.cmd_copy_image_to_buffer(
                command,
                image,
                vk::ImageLayout::GENERAL,
                readback_buffer,
                &[vk::BufferImageCopy::default()
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .layer_count(1),
                    )
                    .image_extent(vk::Extent3D {
                        width,
                        height,
                        depth: 1,
                    })],
            );
            let host = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)];
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &host,
                &[],
                &[],
            );
        })?;
        recorded?;
        self.samples = self.samples.saturating_add(self.samples_per_frame());
        let result = self
            .output
            .as_ref()
            .unwrap()
            .readback
            .as_ref()
            .unwrap()
            .read(width as usize * height as usize * 4);
        self.commit_temporal();
        result
    }

    /// Records into a host-owned command buffer and image; no submission or pixel transfer.
    /// Descriptor-slot exhaustion can wait for an older serial, never this new frame.
    /// # Safety
    /// The begun command buffer and RGBA8_UNORM STORAGE image/view must belong to this
    /// device/graphics family. Target stays GENERAL. The caller submits this buffer in
    /// host order, signals the attached timeline at serial, and retains the target until then.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn record_host_with_instances<'a>(
        &mut self,
        scene: impl Into<SceneInput<'a>>,
        instances: impl Into<InstanceInput<'a>>,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
        command: u64,
        image: u64,
        view: u64,
        serial: u64,
    ) -> Result<(), String> {
        let scene = scene.into();
        let instances = instances.into();
        if self.failed
            || self.pending_temporal_serial != 0
            || !self.context.is_borrowed()
            || command == 0
            || image == 0
            || view == 0
            || serial == 0
        {
            return Err("Invalid host recording state or Vulkan handles".into());
        }
        let mut cpu = FrameCpu::new(self.context.diagnostics_enabled());
        let started = cpu.start();
        let uploaded_before = if self.context.diagnostics_enabled() {
            self.context.cpu_upload_bytes()
        } else {
            0
        };
        self.failed = true;
        // A contained FFI panic must still release the recording scope.
        let _scope = HostRecordScope(self.context.clone());
        let result = self.record_host_frame(
            scene,
            instances,
            camera,
            width,
            height,
            sample_index,
            vk::CommandBuffer::from_raw(command),
            vk::ImageView::from_raw(view),
            serial,
            &mut cpu,
        );
        drop(_scope);
        let load = if self.context.diagnostics_enabled() {
            let uploaded = self.context.cpu_upload_bytes() - uploaded_before;
            self.geometry.as_ref().map_or([0; 14], |geometry| {
                geometry.cpu_load(&scene, &instances, &cpu, uploaded)
            })
        } else {
            [0; 14]
        };
        cpu.finish_record(started, &load, result.is_ok());
        self.cpu_profile.observe(&cpu, load, serial, result.is_ok());
        if result.is_ok() {
            self.failed = false;
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    fn record_host_frame(
        &mut self,
        scene: SceneInput<'_>,
        instances: InstanceInput<'_>,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
        command: vk::CommandBuffer,
        view: vk::ImageView,
        serial: u64,
        cpu: &mut FrameCpu,
    ) -> Result<(), String> {
        let started = cpu.start();
        let mut completed = self.context.begin_host_record(command, serial)?;
        cpu.finish(Stage::BeginRetire, started);
        let started = cpu.start();
        self.collect_timing(completed)?;
        cpu.finish(Stage::Collect, started);
        if self.host_serials.contains(&serial) {
            return Err("A PT frame is already recorded into this host submission".into());
        }
        if self.host_serials.iter().all(|v| *v > completed) {
            let started = cpu.start();
            // Only pool exhaustion adds backpressure, never an unconditional frame wait.
            completed = self
                .context
                .wait_host_serial(*self.host_serials.iter().min().unwrap())?;
            cpu.finish(Stage::SlotWait, started);
            let started = cpu.start();
            self.collect_timing(completed)?;
            cpu.finish(Stage::Collect, started);
        }
        let slot = self
            .host_serials
            .iter()
            .position(|v| *v <= completed)
            .ok_or("Host descriptor slot did not retire")?;
        if self.host_query != vk::QueryPool::null() {
            // Bind GPU work to the complete CPU recording scope, before entering
            // its short setup child. Completion will occur in a later CPU frame.
            self.query_frames[slot].begin(serial);
        }
        let started = cpu.start();
        unsafe {
            if self.host_query != vk::QueryPool::null() {
                self.context.device.cmd_reset_query_pool(
                    command,
                    self.host_query,
                    slot as u32 * QUERY_COUNT,
                    QUERY_COUNT,
                );
                self.timestamp(command, slot, 0, vk::PipelineStageFlags::TOP_OF_PIPE);
            }
        }
        self.before_frame(command);
        cpu.finish(Stage::FrameSetup, started);
        self.prepare(
            scene,
            instances,
            camera,
            width,
            height,
            sample_index,
            slot,
            completed,
            cpu,
        )?;
        let started = cpu.start();
        let linear_output = self.needs_linear_display();
        self.prepare_restir(slot, true)?;
        let selected = if (self.settings.integrator == Integrator::PathTrace
            || self.reconstruction.is_some())
            && linear_output
            && self.settings.mode == RenderMode::Realtime
        {
            self.output.as_ref().unwrap().linear.as_ref().unwrap().view
        } else {
            view
        };
        self.descriptors(slot, selected);
        cpu.finish(Stage::Descriptors, started);
        let started = cpu.start();
        // This boundary includes upload/AS dependencies, rather than shader-active cycles.
        self.timestamp(command, slot, 1, vk::PipelineStageFlags::BOTTOM_OF_PIPE);
        self.dispatch(command, slot, true);
        let display_view = self.effective_view();
        if self.reconstruction.is_some() {
            self.stage_timestamp(command, slot, GpuStage::Reconstruction, false);
        }
        if let Some(rr) = &mut self.reconstruction {
            rr.evaluate_and_display(
                command,
                self.pipeline.as_ref().unwrap(),
                slot,
                serial,
                self.display,
                display_view,
                self.settings.depth_range,
                true,
                linear_output,
            )?;
        }
        if self.reconstruction.is_some() {
            self.stage_timestamp(command, slot, GpuStage::Reconstruction, true);
        }
        self.restir_debug_display(command, slot);
        self.record_display(command, slot, view, true, linear_output)?;
        unsafe {
            let after = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)];
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::DependencyFlags::empty(),
                &after,
                &[],
                &[],
            );
            self.timestamp(command, slot, 2, vk::PipelineStageFlags::BOTTOM_OF_PIPE);
        }
        self.host_serials[slot] = serial;
        self.pending_temporal_serial = serial;
        self.samples = self.samples.saturating_add(self.samples_per_frame());
        cpu.finish(Stage::Dispatch, started);
        Ok(())
    }

    fn commit_temporal(&mut self) {
        if let Some(restir) = &mut self.restir {
            restir.commit();
        }
        if let Some(geometry) = &mut self.geometry {
            geometry.objects.commit_motion();
        }
        if let Some(rr) = &mut self.reconstruction {
            rr.commit();
        }
    }

    /// Host queue acceptance, distinct from recording and from GPU completion.
    pub fn submission_accepted(&mut self, serial: u64) -> Result<(), String> {
        if self.failed || serial == 0 || serial != self.pending_temporal_serial {
            return Err("Submission acceptance does not match the pending PT frame".into());
        }
        self.commit_temporal();
        if let Some(frame) = self
            .query_frames
            .iter_mut()
            .find(|frame| frame.serial == serial)
        {
            frame.accepted = true;
        }
        self.pending_temporal_serial = 0;
        Ok(())
    }

    pub(super) fn collect_timing(&mut self, completed: u64) -> Result<(), String> {
        if self.host_query == vk::QueryPool::null() {
            return Ok(());
        }
        for slot in 0..FRAME_SLOTS {
            let serial = self.query_frames[slot].serial;
            if serial == 0 || serial > completed {
                continue;
            }
            let frame = &self.query_frames[slot];
            // One nonblocking read for the fixed slot. Unwritten optional queries
            // legitimately return NOT_READY; availability is authoritative per marker.
            let mut results = [[0u64; 2]; QUERY_COUNT as usize];
            let read = unsafe {
                self.context.device.get_query_pool_results(
                    self.host_query,
                    slot as u32 * QUERY_COUNT,
                    &mut results,
                    vk::QueryResultFlags::TYPE_64 | vk::QueryResultFlags::WITH_AVAILABILITY,
                )
            };
            match read {
                Ok(()) | Err(vk::Result::NOT_READY) => {}
                Err(vk::Result::ERROR_DEVICE_LOST) => {
                    return Err(error(
                        "Read diagnostic timestamps",
                        vk::Result::ERROR_DEVICE_LOST,
                    ));
                }
                Err(_) => results.fill([0; 2]),
            }
            let available = results.map(|value| value[1] != 0);
            let stamps = results.map(|value| value[0]);
            let mut read_failed = false;
            for stage in GpuStage::ALL {
                let indices = stage.indices();
                if !frame.begun(stage) {
                    continue;
                }
                for index in indices {
                    if frame.written(index) && !available[index as usize] {
                        read_failed = true;
                    }
                }
                let start = available[indices[0] as usize].then(|| stamps[indices[0] as usize]);
                let end = available[indices[1] as usize].then(|| stamps[indices[1] as usize]);
                frame.emit_raw(
                    stage,
                    start,
                    end,
                    self.context.queue.as_raw(),
                    self.context.timestamp_bits,
                    f64::from(self.context.timestamp_period),
                    if start.is_some() && end.is_some() {
                        "complete"
                    } else if start.is_some() && !frame.has(indices) {
                        "incomplete"
                    } else {
                        "unavailable"
                    },
                    true,
                );
            }
            if read_failed && !self.gpu_error_reported {
                self.gpu_error_reported = true;
                eprintln!("[Prime PT] Completed GPU diagnostics unavailable at serial {serial}");
            }
            if available[..3].iter().all(|v| *v) {
                let elapsed = |first: usize, last: usize| {
                    crate::gpu_timing::elapsed_ns(
                        stamps[first],
                        stamps[last],
                        self.context.timestamp_bits,
                        self.context.timestamp_period,
                    )
                };
                let sample = GpuIntervals {
                    serial,
                    preparation_ns: elapsed(0, 1),
                    render_ns: elapsed(1, 2),
                    total_ns: elapsed(0, 2),
                };
                self.gpu_intervals[(serial % FRAME_SLOTS as u64) as usize] = sample;
                self.last_gpu.retain_latest(sample);
            }
            self.query_frames[slot] = Default::default();
        }
        Ok(())
    }
}

impl Drop for Renderer {
    fn drop(&mut self) {
        if self.context.is_borrowed()
            && self.context.can_destroy()
            && let Err(error) = self.context.wait_host_idle()
        {
            eprintln!("[Prime PT] Renderer resources quarantined: {error}");
        }
        if self.context.can_destroy() && self.host_query != vk::QueryPool::null() {
            if let Err(error) = self.finish_diagnostics_capture() {
                eprintln!("[Prime PT] Final GPU diagnostics unavailable: {error}");
            }
            self.context.retire_query_pool(self.host_query);
        }
    }
}

#[cfg(test)]
#[path = "mode_tests.rs"]
pub(crate) mod tests;
