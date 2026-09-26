use super::*;

struct HostRecordScope(Arc<Context>);
impl Drop for HostRecordScope {
    fn drop(&mut self) {
        self.0.end_host_record();
    }
}

pub(super) struct Output {
    width: u32,
    height: u32,
    // These exist only for explicit offline image diagnostics.
    image: Option<Image>,
    readback: Option<Buffer>,
    accumulation: Buffer,
}

impl Output {
    fn new(context: &Arc<Context>, width: u32, height: u32) -> Result<Self, String> {
        if width == 0 || height == 0 || width > 4096 || height > 4096 {
            return Err("Render dimensions must be within 1..4096".into());
        }
        let bytes = u64::from(width) * u64::from(height);
        let offline = !context.is_borrowed();
        Ok(Self {
            width,
            height,
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
            accumulation: Buffer::new(
                context,
                bytes * 16,
                vk::BufferUsageFlags::STORAGE_BUFFER,
                false,
            )?,
        })
    }
}

impl Renderer {
    pub fn new() -> Result<Self, String> {
        Self::from_context(Context::new()?)
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
        Self::from_context(unsafe {
            Context::borrowed(instance, physical, device, queue, family, timeline)?
        })
    }

    fn from_context(context: Arc<Context>) -> Result<Self, String> {
        let pipeline = Pipeline::new(&context)?;
        let mut result = Self {
            context,
            pipeline,
            geometry: None,
            output: None,
            camera: None,
            samples: 0,
            failed: false,
            host_serials: [0; FRAME_SLOTS],
            query_serials: [0; FRAME_SLOTS],
            host_query: vk::QueryPool::null(),
            last_gpu_ns: 0,
            last_gpu_serial: 0,
            descriptor_keys: [[0; 6]; FRAME_SLOTS],
        };
        if result.context.is_borrowed()
            && result.context.timestamp_bits > 0
            && std::env::var_os("PRIME_PROFILE").is_some_and(|v| v != "0")
        {
            result.host_query = unsafe {
                result.context.device.create_query_pool(
                    &vk::QueryPoolCreateInfo::default()
                        .query_type(vk::QueryType::TIMESTAMP)
                        .query_count((FRAME_SLOTS * 2) as u32),
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
    pub fn profile_snapshot(&self) -> Option<GpuProfile> {
        self.context.profile_snapshot()
    }
    pub fn last_gpu_time_ns(&self) -> u64 {
        self.last_gpu_ns
    }
    pub fn shutdown(&mut self) -> Result<(), String> {
        if self.context.is_borrowed() {
            self.context.finish_host()?;
        }
        self.failed = true;
        Ok(())
    }

    /// Explicit image diagnostic only; production uses record_host without readback.
    pub fn render(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
    ) -> Result<Vec<u8>, String> {
        if self.failed || self.context.is_borrowed() {
            return Err(
                "Readback requires a healthy, independently owned diagnostic renderer".into(),
            );
        }
        self.failed = true;
        let result = self.render_offline(scene, camera, width, height, sample_index);
        if result.is_ok() {
            self.failed = false;
        }
        result
    }

    fn prepare(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
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
        if self.geometry.as_ref().is_none_or(|g| g.needs_update(scene)) {
            self.descriptor_keys = [[0; 6]; FRAME_SLOTS];
            if let Some(geometry) = &mut self.geometry {
                geometry.update(&self.context, scene)?;
            } else {
                self.geometry = Some(Geometry::new(&self.context, scene)?);
            }
            self.samples = 0;
        }
        if self
            .output
            .as_ref()
            .is_none_or(|o| o.width != width || o.height != height)
        {
            self.descriptor_keys = [[0; 6]; FRAME_SLOTS];
            self.output = Some(Output::new(&self.context, width, height)?);
            self.samples = 0;
        }
        if self.camera != Some(*camera) || sample_index == 0 {
            self.samples = 0;
        }
        self.camera = Some(*camera);
        Ok(())
    }

    fn descriptors(&mut self, slot: usize, view: vk::ImageView) {
        let geometry = self.geometry.as_ref().unwrap();
        let output = self.output.as_ref().unwrap();
        let key = [
            geometry.top.as_ref().unwrap().handle.as_raw(),
            geometry.triangles.buffer.as_raw(),
            geometry.textures.metadata.buffer.as_raw(),
            geometry.textures.texels.buffer.as_raw(),
            output.accumulation.buffer.as_raw(),
            view.as_raw(),
        ];
        let descriptor = self.pipeline.descriptors[slot];
        let image = [vk::DescriptorImageInfo::default()
            .image_view(view)
            .image_layout(vk::ImageLayout::GENERAL)];
        if self.descriptor_keys[slot] == key {
            // The host may recreate a view with the same raw handle. Refresh its
            // binding even when renderer-owned immutable bindings remain cached.
            unsafe {
                self.context.device.update_descriptor_sets(
                    &[vk::WriteDescriptorSet::default()
                        .dst_set(descriptor)
                        .dst_binding(4)
                        .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                        .image_info(&image)],
                    &[],
                );
            }
            return;
        }
        let handles = [geometry.top.as_ref().unwrap().handle];
        let mut acceleration = vk::WriteDescriptorSetAccelerationStructureKHR::default()
            .acceleration_structures(&handles);
        let buffers = [
            &geometry.triangles,
            &geometry.textures.metadata,
            &geometry.textures.texels,
            &output.accumulation,
        ];
        let infos = buffers.map(|b| {
            [vk::DescriptorBufferInfo::default()
                .buffer(b.buffer)
                .range(b.size)]
        });
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(0)
                .descriptor_count(1)
                .descriptor_type(vk::DescriptorType::ACCELERATION_STRUCTURE_KHR)
                .push_next(&mut acceleration),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&infos[0]),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(2)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&infos[1]),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(3)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&infos[2]),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(4)
                .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .image_info(&image),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(5)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&infos[3]),
        ];
        unsafe {
            self.context.device.update_descriptor_sets(&writes, &[]);
        }
        self.descriptor_keys[slot] = key;
    }

    fn dispatch(&self, command: vk::CommandBuffer, slot: usize, bottom_up: bool) {
        let camera = self.camera.unwrap();
        let output = self.output.as_ref().unwrap();
        let mut push = [0u8; 96];
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
            0.0,
            camera.up[0],
            camera.up[1],
            camera.up[2],
            0.0,
        ];
        for (destination, value) in push[..64].as_chunks_mut::<4>().0.iter_mut().zip(values) {
            *destination = value.to_le_bytes();
        }
        let integers = [
            output.width,
            output.height,
            self.samples,
            self.geometry.as_ref().unwrap().triangle_count,
            u32::from(bottom_up),
            0,
            0,
            0,
        ];
        for (destination, value) in push[64..].as_chunks_mut::<4>().0.iter_mut().zip(integers) {
            *destination = value.to_le_bytes();
        }
        unsafe {
            self.context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                self.pipeline.pipeline,
            );
            self.context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                self.pipeline.layout,
                0,
                &[self.pipeline.descriptors[slot]],
                &[],
            );
            self.context.device.cmd_push_constants(
                command,
                self.pipeline.layout,
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

    fn render_offline(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
    ) -> Result<Vec<u8>, String> {
        self.prepare(scene, camera, width, height, sample_index)?;
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
        self.samples = self.samples.saturating_add(1);
        readback.read(width as usize * height as usize * 4)
    }

    /// Records into a host-owned command buffer and image; no submission or pixel transfer.
    /// Descriptor-slot exhaustion can wait for an older serial, never this new frame.
    /// # Safety
    /// The begun command buffer and RGBA8_UNORM STORAGE image/view must belong to this
    /// device/graphics family. Target stays GENERAL. The caller submits this buffer in
    /// host order, signals the attached timeline at serial, and retains the target until then.
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
        if self.failed
            || !self.context.is_borrowed()
            || command == 0
            || image == 0
            || view == 0
            || serial == 0
        {
            return Err("Invalid host recording state or Vulkan handles".into());
        }
        self.failed = true;
        // A contained FFI panic must still release the recording scope.
        let _scope = HostRecordScope(self.context.clone());
        let result = self.record_host_frame(
            scene,
            camera,
            width,
            height,
            sample_index,
            vk::CommandBuffer::from_raw(command),
            vk::ImageView::from_raw(view),
            serial,
        );
        if result.is_ok() {
            self.failed = false;
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    fn record_host_frame(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        width: u32,
        height: u32,
        sample_index: u32,
        command: vk::CommandBuffer,
        view: vk::ImageView,
        serial: u64,
    ) -> Result<(), String> {
        self.context.begin_host_record(command, serial)?;
        let mut completed = self.context.completed_serial()?;
        self.collect_timing(completed)?;
        if self.host_serials.contains(&serial) {
            return Err("A PT frame is already recorded into this host submission".into());
        }
        if self.host_serials.iter().all(|v| *v > completed) {
            // Only pool exhaustion adds backpressure, never an unconditional frame wait.
            self.context
                .wait_host_serial(*self.host_serials.iter().min().unwrap())?;
            completed = self.context.completed_serial()?;
            self.collect_timing(completed)?;
        }
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
                    slot as u32 * 2,
                    2,
                );
                self.context.device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::TOP_OF_PIPE,
                    self.host_query,
                    slot as u32 * 2,
                );
            }
        }
        self.before_frame(command);
        self.prepare(scene, camera, width, height, sample_index)?;
        self.descriptors(slot, view);
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
                    slot as u32 * 2 + 1,
                );
                self.query_serials[slot] = serial;
            }
        }
        self.host_serials[slot] = serial;
        self.samples = self.samples.saturating_add(1);
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
            let mut stamps = [0u64; 2];
            unsafe {
                self.context.device.get_query_pool_results(
                    self.host_query,
                    slot as u32 * 2,
                    &mut stamps,
                    vk::QueryResultFlags::TYPE_64,
                )
            }
            .map_err(|e| error("Read completed host timestamps", e))?;
            let mask = u64::MAX
                .checked_shr(64 - self.context.timestamp_bits)
                .unwrap_or(0);
            if serial > self.last_gpu_serial {
                self.last_gpu_ns = ((stamps[1].wrapping_sub(stamps[0]) & mask) as f64
                    * f64::from(self.context.timestamp_period))
                    as u64;
                self.last_gpu_serial = serial;
            }
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
