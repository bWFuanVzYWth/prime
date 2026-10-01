use super::*;
use crate::cpu_profile::{CpuProfile, FrameCpu, Stage};
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
}

impl Output {
    #[cfg(all(test, feature = "shader-tests"))]
    pub(super) fn linear_buffer(&self) -> &Buffer {
        self.accumulation.as_ref().unwrap()
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
                    vk::BufferUsageFlags::STORAGE_BUFFER,
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
        Self::from_context(Context::new()?, mode)
    }

    /// # Safety
    /// Handles must belong to one live Vulkan device with ray query, acceleration
    /// structure, buffer device address and timeline semaphore features enabled.
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
            mode,
        )
    }

    fn from_context(context: Arc<Context>, mode: RenderMode) -> Result<Self, String> {
        let cpu_profile = CpuProfile::default();
        let pipeline = Some(Pipeline::new(&context, mode)?);
        let mut result = Self {
            context,
            pipeline,
            geometry: None,
            atmosphere: None,
            atmosphere_scene_revision: 0,
            environment: Default::default(),
            workers: Arc::new(prime_scene::workers::CpuWorkers::configured()?),
            output: None,
            camera: None,
            samples: 0,
            frame_seed: 0,
            display: PrimeDrtSettings::default().prepare(1.0)?,
            settings: RenderSettings {
                mode,
                ..Default::default()
            },
            scene_frozen: false,
            failed: false,
            host_serials: [0; FRAME_SLOTS],
            query_serials: [0; FRAME_SLOTS],
            gpu_intervals: [GpuIntervals::default(); FRAME_SLOTS],
            host_query: vk::QueryPool::null(),
            last_gpu: GpuIntervals::default(),
            descriptor_keys: [[0; 7]; FRAME_SLOTS],
            cpu_profile,
        };
        if result.context.is_borrowed() && result.context.timestamp_bits > 0 {
            result.host_query = unsafe {
                result.context.device.create_query_pool(
                    &vk::QueryPoolCreateInfo::default()
                        .query_type(vk::QueryType::TIMESTAMP)
                        .query_count((FRAME_SLOTS * 3) as u32),
                    None,
                )
            }
            .map_err(|e| error("Create host timing queries", e))?;
        }
        eprintln!(
            "[Prime PT] Vulkan hardware ray queries: {}; host recording={}",
            result.context.name,
            result.context.is_borrowed()
        );
        Ok(result)
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
        if self.failed {
            return Err("Cannot configure a failed renderer".into());
        }
        let display = PrimeDrtSettings {
            exposure_multiplier: settings.exposure,
            hue_compensation: settings.hue,
            saturation_compensation: settings.saturation,
        }
        .prepare(1.0)?;
        if settings.mode != self.settings.mode {
            self.failed = true;
            self.context.wait_host_idle()?;
            drop(self.output.take());
            drop(self.pipeline.take());
            self.context.completed_serial()?; // Drain the retired images/buffers using completed host work.
            self.pipeline = Some(Pipeline::new(&self.context, settings.mode)?);
            self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
            self.samples = 0;
            self.failed = false;
        } else if !settings.transport_matches(self.settings) {
            self.samples = 0;
        }
        self.display = display;
        self.settings = settings;
        Ok(())
    }

    fn samples_per_frame(&self) -> u32 {
        if self.settings.mode == RenderMode::Offline {
            self.settings.offline_samples
        } else {
            1
        }
    }

    /// The scene owner must prohibit mutations until thawed. Geometry is reused without planning/scanning.
    pub fn set_scene_frozen(&mut self, frozen: bool) {
        self.scene_frozen = frozen;
    }

    pub fn set_environment(
        &mut self,
        environment: prime_scene::environment::Environment,
    ) -> Result<(), String> {
        let environment = environment.validate()?;
        if self.environment != environment {
            self.samples = 0;
            self.environment = environment;
        }
        Ok(())
    }
    /// Last attempted host recording; fixed-size counters are retained, formatting is on demand.
    pub fn cpu_diagnostics(&self) -> String {
        format!(
            "{} gpu_supported={} gpu_completed_serial={} gpu_preparation_ms={:.3} gpu_render_ms={:.3} gpu_total_ms={:.3} gpu_sample=delayed_completed_interval atmosphere_sky_updates={} atmosphere_camera_t_updates={} atmosphere_aerial_s_updates={} atmosphere_aerial_t_updates={}",
            self.cpu_profile.last_report(),
            self.host_query != vk::QueryPool::null(),
            self.last_gpu.serial,
            self.last_gpu.preparation_ns as f64 / 1e6,
            self.last_gpu.render_ns as f64 / 1e6,
            self.last_gpu.total_ns as f64 / 1e6,
            self.atmosphere.as_ref().map_or(0, |a| a.sky_updates),
            self.atmosphere
                .as_ref()
                .map_or(0, |a| a.transmittance_updates),
            self.atmosphere.as_ref().map_or(0, |a| a.aerial_updates),
            self.atmosphere.as_ref().map_or(0, |a| a.aerial_t_updates)
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
        if let Some(geometry) = &mut self.geometry {
            geometry.begin_frame(&self.context, completed);
        }
        if !self.scene_frozen || self.geometry.is_none() {
            let started = cpu.start();
            if self.geometry.as_ref().is_none_or(|g| g.needs_update(scene)) {
                cpu.static_updates += 1;
                self.atmosphere_scene_revision = self.atmosphere_scene_revision.wrapping_add(1);
                self.descriptor_keys = [[0; 7]; FRAME_SLOTS];
                if let Some(geometry) = &mut self.geometry
                    && geometry.same_owner(scene)
                {
                    geometry.update(&self.context, scene)?;
                } else {
                    // End the previous CPU owner before constructing another cache domain.
                    // Borrowed GPU resources still retire by their recorded completion serials.
                    self.geometry.take();
                    self.geometry =
                        Some(Geometry::new(&self.context, scene, self.workers.clone())?);
                }
                self.samples = 0;
            }
            cpu.finish(Stage::Static, started);
            let (dynamic_changed, bindings_changed) = self
                .geometry
                .as_mut()
                .unwrap()
                .prepare_dynamic(&self.context, &scene, instances, slot, cpu)?;
            if dynamic_changed {
                self.atmosphere_scene_revision = self.atmosphere_scene_revision.wrapping_add(1);
                self.samples = 0;
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
        cpu.finish(Stage::Output, started);
        if self.atmosphere.is_none() {
            self.atmosphere = Some(crate::atmosphere::Atmosphere::new(&self.context)?);
        }
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
        let output = self.output.as_ref().unwrap();
        let accumulation = output.accumulation.as_ref();
        let key = [
            geometry.top.handle().as_raw(),
            geometry.textures.metadata.buffer.as_raw(),
            geometry.textures.texels.buffer.as_raw(),
            view.as_raw(),
            geometry.objects.metadata.buffer.as_raw(),
            geometry.static_bases.buffer.as_raw(),
            accumulation.map_or(0, |b| b.buffer.as_raw()),
        ];
        let descriptor = self.pipeline.as_ref().unwrap().descriptors[slot];
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
            &geometry.textures.metadata,
            &geometry.textures.texels,
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
        if let Some(info) = &accumulation_info {
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

    fn dispatch(&self, command: vk::CommandBuffer, slot: usize, bottom_up: bool) {
        let pipeline = self.pipeline.as_ref().unwrap();
        let camera = self.camera.unwrap();
        let output = self.output.as_ref().unwrap();
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
            output.width,
            output.height,
            self.samples,
            self.samples_per_frame(),
            u32::from(bottom_up),
            self.frame_seed,
            output.log2_resolution,
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
        push[124..128].copy_from_slice(&(self.settings.view as u32).to_le_bytes());
        unsafe {
            self.context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.pipelines[self.geometry.as_ref().map_or(0, Geometry::shader_variant)],
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
                output.width.div_ceil(8),
                output.height.div_ceil(8),
                1,
            );
        }
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
        self.descriptors(0, view);
        let output = self.output.as_ref().unwrap();
        let image = output.image.as_ref().unwrap();
        let readback = output.readback.as_ref().unwrap();
        self.context
            .submit_named("diagnostic_render", |command| unsafe {
                self.before_frame(command);
                self.dispatch(command, 0, false);
                let barrier = [vk::ImageMemoryBarrier::default()
                    .image(image.image)
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
                    image.image,
                    vk::ImageLayout::GENERAL,
                    readback.buffer,
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
        self.samples = self.samples.saturating_add(self.samples_per_frame());
        readback.read(width as usize * height as usize * 4)
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
            || !self.context.is_borrowed()
            || command == 0
            || image == 0
            || view == 0
            || serial == 0
        {
            return Err("Invalid host recording state or Vulkan handles".into());
        }
        let mut cpu = FrameCpu::default();
        let started = cpu.start();
        let uploaded_before = self.context.cpu_upload_bytes();
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
        cpu.finish(Stage::Total, started);
        let uploaded = self.context.cpu_upload_bytes() - uploaded_before;
        let load = self.geometry.as_ref().map_or([0; 14], |geometry| {
            geometry.cpu_load(&scene, &instances, &cpu, uploaded)
        });
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
        let started = cpu.start();
        let slot = self
            .host_serials
            .iter()
            .position(|v| *v <= completed)
            .ok_or("Host descriptor slot did not retire")?;
        unsafe {
            if self.host_query != vk::QueryPool::null() {
                self.context.device.cmd_reset_query_pool(
                    command,
                    self.host_query,
                    slot as u32 * 3,
                    3,
                );
                self.context.device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::TOP_OF_PIPE,
                    self.host_query,
                    slot as u32 * 3,
                );
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
        self.descriptors(slot, view);
        cpu.finish(Stage::Descriptors, started);
        let started = cpu.start();
        if self.host_query != vk::QueryPool::null() {
            // An interval boundary after upload/AS dependencies. This is elapsed
            // queue time, not a sum of shader-active cycles or an isolated AS timer.
            unsafe {
                self.context.device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                    self.host_query,
                    slot as u32 * 3 + 1,
                );
            }
        }
        self.dispatch(command, slot, true);
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
            if self.host_query != vk::QueryPool::null() {
                self.context.device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                    self.host_query,
                    slot as u32 * 3 + 2,
                );
                self.query_serials[slot] = serial;
            }
        }
        self.host_serials[slot] = serial;
        self.samples = self.samples.saturating_add(self.samples_per_frame());
        cpu.finish(Stage::Dispatch, started);
        Ok(())
    }

    pub(super) fn collect_timing(&mut self, completed: u64) -> Result<(), String> {
        if self.host_query == vk::QueryPool::null() {
            return Ok(());
        }
        for slot in 0..FRAME_SLOTS {
            let serial = self.query_serials[slot];
            if serial == 0 || serial > completed {
                continue;
            }
            let mut stamps = [0u64; 3];
            unsafe {
                self.context.device.get_query_pool_results(
                    self.host_query,
                    slot as u32 * 3,
                    &mut stamps,
                    vk::QueryResultFlags::TYPE_64,
                )
            }
            .map_err(|e| error("Read completed host timestamps", e))?;
            let mask = u64::MAX
                .checked_shr(64 - self.context.timestamp_bits)
                .unwrap_or(0);
            let elapsed = |first: usize, last: usize| {
                ((stamps[last].wrapping_sub(stamps[first]) & mask) as f64
                    * f64::from(self.context.timestamp_period)) as u64
            };
            let sample = GpuIntervals {
                serial,
                preparation_ns: elapsed(0, 1),
                render_ns: elapsed(1, 2),
                total_ns: elapsed(0, 2),
            };
            self.gpu_intervals[(serial % FRAME_SLOTS as u64) as usize] = sample;
            self.last_gpu.retain_latest(sample);
            self.query_serials[slot] = 0;
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
            unsafe {
                self.context
                    .device
                    .destroy_query_pool(self.host_query, None);
            }
        }
    }
}

#[cfg(test)]
#[path = "mode_tests.rs"]
pub(crate) mod tests;
