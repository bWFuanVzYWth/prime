//! A small host simulator for measuring the production recording path. No pixels
//! leave the device. Two submissions may be in flight on one queue/timeline.
use super::*;
use prime_scene::incremental::SceneInput;
use std::{collections::BTreeMap, time::Instant};

const IN_FLIGHT: usize = 2;

#[derive(Clone, Copy, Debug)]
pub struct HostSample {
    pub serial: u64,
    /// Includes slot retirement, recording and enqueue, but not frame completion.
    pub wall_ns: u64,
    pub slot_wait_ns: u64,
    pub record_ns: u64,
    pub submit_ns: u64,
}

#[derive(Clone, Copy, Debug)]
pub struct CompletedSample {
    pub serial: u64,
    /// Completed Vulkan timestamps, including incremental copies/AS builds.
    pub gpu_ns: u64,
    /// Optional renderer intervals; require PRIME_PROFILE. Include dependencies,
    /// and must not be interpreted as isolated AS or ray-tracing active time.
    pub preparation_ns: Option<u64>,
    pub render_ns: Option<u64>,
}

struct HostState {
    owner: Arc<Context>,
    renderer: Option<Renderer>,
    image: Option<Image>,
    image_extent: (u32, u32),
    retired_targets: Vec<(u64, Image)>,
    timeline: vk::Semaphore,
    query: vk::QueryPool,
    pools: [vk::CommandPool; IN_FLIGHT],
    commands: [vk::CommandBuffer; IN_FLIGHT],
}

impl Drop for HostState {
    fn drop(&mut self) {
        // HostBenchmark drains and permanently closes borrowed recording first.
        // Renderer and final Context drops now free locally without another
        // fallible host timeline query. Keep host handles alive until then.
        self.renderer.take();
        self.image.take();
        self.retired_targets.clear();
        if self.owner.can_destroy() {
            unsafe {
                self.owner.device.destroy_query_pool(self.query, None);
                for pool in self.pools {
                    self.owner.device.destroy_command_pool(pool, None);
                }
                self.owner.device.destroy_semaphore(self.timeline, None);
            }
        }
    }
}

/// Isolated GPU-only benchmark, using the same borrowed-device recording API as
/// a host application. It simulates submission/retirement, not the rest of a frame.
pub struct HostBenchmark {
    state: Option<HostState>,
    width: u32,
    height: u32,
    next_serial: u64,
    pending: [u64; IN_FLIGHT],
    completed: BTreeMap<u64, CompletedSample>,
    poisoned: bool,
}

impl HostBenchmark {
    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        let owner = Context::new()?;
        owner.render_extent(width, height)?;
        if owner.timestamp_bits == 0 {
            return Err("Benchmark queue does not support GPU timestamps".into());
        }
        let mut state = HostState {
            owner,
            renderer: None,
            image: None,
            image_extent: (width, height),
            retired_targets: Vec::new(),
            timeline: vk::Semaphore::null(),
            query: vk::QueryPool::null(),
            pools: [vk::CommandPool::null(); IN_FLIGHT],
            commands: [vk::CommandBuffer::null(); IN_FLIGHT],
        };
        unsafe {
            let mut timeline = vk::SemaphoreTypeCreateInfo::default()
                .semaphore_type(vk::SemaphoreType::TIMELINE)
                .initial_value(0);
            state.timeline = state
                .owner
                .device
                .create_semaphore(
                    &vk::SemaphoreCreateInfo::default().push_next(&mut timeline),
                    None,
                )
                .map_err(|e| error("Create benchmark timeline", e))?;
            state.query = state
                .owner
                .device
                .create_query_pool(
                    &vk::QueryPoolCreateInfo::default()
                        .query_type(vk::QueryType::TIMESTAMP)
                        .query_count((IN_FLIGHT * 2) as u32),
                    None,
                )
                .map_err(|e| error("Create benchmark GPU queries", e))?;
            for slot in 0..IN_FLIGHT {
                state.pools[slot] = state
                    .owner
                    .device
                    .create_command_pool(
                        &vk::CommandPoolCreateInfo::default()
                            .queue_family_index(state.owner.queue_family)
                            .flags(vk::CommandPoolCreateFlags::TRANSIENT),
                        None,
                    )
                    .map_err(|e| error("Create benchmark host command pool", e))?;
                state.commands[slot] = state
                    .owner
                    .device
                    .allocate_command_buffers(
                        &vk::CommandBufferAllocateInfo::default()
                            .command_pool(state.pools[slot])
                            .level(vk::CommandBufferLevel::PRIMARY)
                            .command_buffer_count(1),
                    )
                    .map_err(|e| error("Allocate benchmark host command", e))?[0];
            }
            state.image = Some(Image::new(&state.owner, width, height)?);
            state.renderer = Some(Renderer::borrowed(
                state.owner.instance_handle(),
                state.owner.physical.as_raw(),
                state.owner.device.handle().as_raw(),
                state.owner.queue.as_raw(),
                state.owner.queue_family,
                state.timeline.as_raw(),
            )?);
        }
        Ok(Self {
            state: Some(state),
            width,
            height,
            next_serial: 1,
            pending: [0; IN_FLIGHT],
            completed: BTreeMap::new(),
            poisoned: false,
        })
    }

    pub fn device_name(&self) -> &str {
        &self.state.as_ref().unwrap().owner.name
    }

    pub fn triangle_count(&self) -> u64 {
        self.state
            .as_ref()
            .unwrap()
            .renderer
            .as_ref()
            .unwrap()
            .geometry
            .as_ref()
            .map_or(0, |g| g.triangle_count)
    }

    pub fn profile_snapshot(&self) -> Option<GpuProfile> {
        self.state
            .as_ref()
            .unwrap()
            .renderer
            .as_ref()
            .unwrap()
            .profile_snapshot()
    }

    pub fn instance_work(&self) -> InstanceWork {
        self.state
            .as_ref()
            .unwrap()
            .renderer
            .as_ref()
            .unwrap()
            .instance_work()
    }

    /// Apply a new target extent in the next recording, without draining prior
    /// frames. The old image is retained until its last submitted serial retires.
    pub fn resize(&mut self, width: u32, height: u32) -> Result<(), String> {
        if self.poisoned {
            return Err("Benchmark host is quarantined".into());
        }
        self.state
            .as_ref()
            .unwrap()
            .owner
            .render_extent(width, height)?;
        self.width = width;
        self.height = height;
        Ok(())
    }

    pub fn enqueue(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        sample: u32,
    ) -> Result<HostSample, String> {
        self.enqueue_with_instances(scene, &InstanceScene::default(), camera, sample)
    }

    /// Wait only when the two-frame ring is full; never waits for this new frame.
    pub fn enqueue_with_instances<'a>(
        &mut self,
        scene: impl Into<SceneInput<'a>>,
        instances: impl Into<InstanceInput<'a>>,
        camera: &Camera,
        sample: u32,
    ) -> Result<HostSample, String> {
        if self.poisoned {
            return Err("Benchmark host is quarantined".into());
        }
        let result = self.enqueue_inner(scene.into(), instances.into(), camera, sample);
        if result.is_err() {
            self.poisoned = true;
        }
        result
    }

    fn enqueue_inner(
        &mut self,
        scene: SceneInput<'_>,
        instances: InstanceInput<'_>,
        camera: &Camera,
        sample: u32,
    ) -> Result<HostSample, String> {
        let start = Instant::now();
        let serial = self.next_serial;
        let slot = (serial % IN_FLIGHT as u64) as usize;
        let mut slot_wait_ns = 0;
        if self.pending[slot] != 0 {
            let state = self.state.as_ref().unwrap();
            let completed = unsafe {
                state
                    .owner
                    .device
                    .get_semaphore_counter_value(state.timeline)
            }
            .map_err(|e| error("Query benchmark completion", e))?;
            if self.pending[slot] > completed {
                let wait = Instant::now();
                self.wait_serial(self.pending[slot])?;
                slot_wait_ns = nanos(wait);
            }
        }
        self.harvest()?;
        let record = Instant::now();
        let state = self.state.as_mut().unwrap();
        let command = state.commands[slot];
        unsafe {
            state
                .owner
                .device
                .reset_command_pool(state.pools[slot], vk::CommandPoolResetFlags::empty())
                .map_err(|e| error("Reset retired benchmark pool", e))?;
            state
                .owner
                .device
                .begin_command_buffer(
                    command,
                    &vk::CommandBufferBeginInfo::default()
                        .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
                )
                .map_err(|e| error("Begin benchmark command", e))?;
            state
                .owner
                .device
                .cmd_reset_query_pool(command, state.query, slot as u32 * 2, 2);
            state.owner.device.cmd_write_timestamp(
                command,
                vk::PipelineStageFlags::TOP_OF_PIPE,
                state.query,
                slot as u32 * 2,
            );
            if state.image_extent != (self.width, self.height) {
                let context = &state.renderer.as_ref().unwrap().context;
                context.begin_host_record(command, serial)?;
                // Initialize GENERAL in this same host command, never with an
                // owned-context submit/wait that would silently drain the ring.
                let replacement = Image::new(context, self.width, self.height);
                context.end_host_record();
                let previous = state.image.replace(replacement?).unwrap();
                state.retired_targets.push((serial - 1, previous));
                state.image_extent = (self.width, self.height);
            }
            let image = state.image.as_ref().unwrap();
            state
                .renderer
                .as_mut()
                .unwrap()
                .record_host_with_instances(
                    scene,
                    instances,
                    camera,
                    self.width,
                    self.height,
                    sample,
                    command.as_raw(),
                    image.image.as_raw(),
                    image.view.as_raw(),
                    serial,
                )?;
            state.owner.device.cmd_write_timestamp(
                command,
                vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                state.query,
                slot as u32 * 2 + 1,
            );
            state
                .owner
                .device
                .end_command_buffer(command)
                .map_err(|e| error("End benchmark command", e))?;
        }
        let record_ns = nanos(record);
        let submit = Instant::now();
        unsafe {
            let values = [serial];
            let signals = [state.timeline];
            let commands = [command];
            let mut timeline =
                vk::TimelineSemaphoreSubmitInfo::default().signal_semaphore_values(&values);
            let info = [vk::SubmitInfo::default()
                .command_buffers(&commands)
                .signal_semaphores(&signals)
                .push_next(&mut timeline)];
            state
                .owner
                .device
                .queue_submit(state.owner.queue, &info, vk::Fence::null())
                .map_err(|e| error("Enqueue benchmark host submission", e))?;
        }
        let submit_ns = nanos(submit);
        self.pending[slot] = serial;
        self.next_serial += 1;
        Ok(HostSample {
            serial,
            wall_ns: nanos(start),
            slot_wait_ns,
            record_ns,
            submit_ns,
        })
    }

    fn wait_serial(&self, serial: u64) -> Result<(), String> {
        let state = self.state.as_ref().unwrap();
        unsafe {
            state
                .owner
                .device
                .wait_semaphores(
                    &vk::SemaphoreWaitInfo::default()
                        .semaphores(&[state.timeline])
                        .values(&[serial]),
                    5_000_000_000,
                )
                .map_err(|e| error("Benchmark submission did not retire within 5 seconds", e))
        }
    }

    fn harvest(&mut self) -> Result<(), String> {
        let state = self.state.as_mut().unwrap();
        let completed = unsafe {
            state
                .owner
                .device
                .get_semaphore_counter_value(state.timeline)
        }
        .map_err(|e| error("Query benchmark timeline", e))?;
        state
            .retired_targets
            .retain(|(serial, _)| *serial > completed);
        let renderer = state.renderer.as_mut().unwrap();
        renderer.collect_timing(completed)?;
        for slot in 0..IN_FLIGHT {
            let serial = self.pending[slot];
            if serial == 0 || serial > completed {
                continue;
            }
            let mut stamps = [0u64; 2];
            unsafe {
                state.owner.device.get_query_pool_results(
                    state.query,
                    slot as u32 * 2,
                    &mut stamps,
                    vk::QueryResultFlags::TYPE_64,
                )
            }
            .map_err(|e| error("Read completed benchmark timestamps", e))?;
            let mask = u64::MAX
                .checked_shr(64 - state.owner.timestamp_bits)
                .unwrap_or(0);
            let gpu_ns = ((stamps[1].wrapping_sub(stamps[0]) & mask) as f64
                * f64::from(state.owner.timestamp_period)) as u64;
            let intervals = renderer
                .gpu_intervals
                .iter()
                .find(|value| value.serial == serial);
            self.completed.insert(
                serial,
                CompletedSample {
                    serial,
                    gpu_ns,
                    preparation_ns: intervals.map(|value| value.preparation_ns),
                    render_ns: intervals.map(|value| value.render_ns),
                },
            );
            self.pending[slot] = 0;
        }
        Ok(())
    }

    /// Drain at phase boundaries or shutdown, returning each completed serial once.
    /// These waits are outside steady per-frame CPU enqueue measurements.
    pub fn drain(&mut self) -> Result<Vec<CompletedSample>, String> {
        if self.poisoned {
            return Err("Benchmark host is quarantined".into());
        }
        let result = (|| {
            if self.next_serial > 1 {
                self.wait_serial(self.next_serial - 1)?;
            }
            self.harvest()?;
            let state = self.state.as_mut().unwrap();
            state
                .renderer
                .as_mut()
                .unwrap()
                .collect_timing(self.next_serial - 1)?;
            Ok(std::mem::take(&mut self.completed).into_values().collect())
        })();
        if result.is_err() {
            self.poisoned = true;
        }
        result
    }
}

fn nanos(start: Instant) -> u64 {
    start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
}

impl Drop for HostBenchmark {
    fn drop(&mut self) {
        let finished = self.drain().and_then(|_| {
            self.state
                .as_ref()
                .unwrap()
                .renderer
                .as_ref()
                .unwrap()
                .context
                .finish_host()
        });
        if finished.is_err() {
            // A recording/submit failure may leave an unsignalled serial. Never
            // destroy host handles or borrowed resources whose lifetime is unknown.
            if let Some(state) = self.state.take() {
                std::mem::forget(state);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::scene::{SceneMesh, Texture, Triangle};

    fn packet(op: u32) -> Vec<u8> {
        let mut bytes = Vec::new();
        for value in [
            prime_scene::protocol::MAGIC,
            prime_scene::protocol::ABI_VERSION,
            op,
            0,
        ] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend(1_u64.to_le_bytes());
        bytes
    }
    fn instance_batch(
        sequence: u64,
        ids: &[u64],
        prototype: bool,
        texture: u32,
        offset: f32,
        old: &[u64],
    ) -> Vec<u8> {
        let mut bytes = packet(7);
        bytes.extend(sequence.to_le_bytes());
        for count in [
            if prototype { ids.len() } else { 0 },
            old.len(),
            ids.len(),
            old.len(),
        ] {
            bytes.extend((count as u32).to_le_bytes());
        }
        if prototype {
            for &id in ids {
                bytes.extend(id.to_le_bytes());
                bytes.extend(sequence.to_le_bytes());
                bytes.extend(1_u32.to_le_bytes());
                bytes.extend(0_u32.to_le_bytes());
                for n in [0_u32, 0, 4, 96, 24, 0, 12, 16] {
                    bytes.extend(n.to_le_bytes());
                }
                for face in 0..24 {
                    for [x, y] in [[0_f32, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]] {
                        // Distinct actual geometry, not just distinct prototype IDs.
                        for v in [
                            x * (1.0 + (id % 11) as f32 * 0.01),
                            y,
                            face as f32 * 0.01 + id as f32 * 0.000001,
                        ] {
                            bytes.extend(v.to_le_bytes());
                        }
                        bytes.extend([255; 4]);
                        bytes.extend([0_u8; 8]);
                    }
                }
            }
        }
        for &id in old {
            bytes.extend(id.to_le_bytes());
            bytes.extend(sequence.to_le_bytes());
        }
        for &id in ids {
            for n in [id, sequence, id] {
                bytes.extend(n.to_le_bytes());
            }
            for v in [
                ((id - 1) % 32) as f64 * 2.0,
                ((id - 1) / 32 % 32) as f64 * 2.0,
                0.0,
            ] {
                bytes.extend(v.to_le_bytes());
            }
            for v in [
                1_f32, 0.0, 0.0, offset, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
            ] {
                bytes.extend(v.to_le_bytes());
            }
            bytes.extend(texture.to_le_bytes());
            bytes.extend(0_u32.to_le_bytes());
            bytes.extend([255; 4]);
            bytes.extend(0_u32.to_le_bytes());
            for v in [1_f32, 1.0, 0.0, 0.0] {
                bytes.extend(v.to_le_bytes());
            }
        }
        for &id in old {
            bytes.extend(id.to_le_bytes());
            bytes.extend(sequence.to_le_bytes());
        }
        bytes
    }
    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; native-1080p, two host submissions in flight"]
    fn host_typed_deltas_texture_and_unique_prototype_churn_reuse_completed_pages() {
        use prime_scene::{SourceScene, incremental::TranslatedScene};
        let mut host = HostBenchmark::new(1920, 1080).unwrap();
        let mut source = SourceScene::default();
        source.submit(&packet(1)).unwrap();
        let mut translated = TranslatedScene::default();
        let camera = Camera {
            position: [10.0, 10.0, 15.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        let mut ids: Vec<u64> = (1..=1000).collect();
        let mut old = Vec::new();
        let mut peak_allocations = 0;
        let mut peak_bytes = 0;
        let mut peak_pages = 0;
        let mut samples = Vec::new();
        for frame in 0..40_u64 {
            let texture = frame as u32 + 2;
            let mut image = packet(4);
            for n in [texture, 2, 2, 0] {
                image.extend(n.to_le_bytes());
            }
            image.extend([texture as u8, 80, 100, 255].repeat(4));
            source.submit(&image).unwrap();
            if frame > 0 {
                let mut retire = packet(9);
                for n in [1, 0, texture - 1] {
                    retire.extend(n.to_le_bytes());
                }
                source.submit(&retire).unwrap();
            }
            let replace = frame % 8 == 0;
            if frame > 0 && replace {
                old = std::mem::take(&mut ids);
                ids = old.iter().map(|id| id + 1000).collect();
            }
            source
                .submit(&instance_batch(
                    frame * 2 + 1,
                    &ids,
                    replace,
                    texture,
                    0.0,
                    if replace { &old } else { &[] },
                ))
                .unwrap();
            // Mixed material-only changes above and sparse/full pose changes below share the same sealed publication.
            let touched = [0, 1, 10, 1000][frame as usize % 4];
            if touched > 0 {
                source
                    .submit(&instance_batch(
                        frame * 2 + 2,
                        &ids[..touched],
                        false,
                        texture,
                        (frame + 1) as f32 * 0.001,
                        &[],
                    ))
                    .unwrap();
            }
            translated.update(&mut source, [0.0; 3]).unwrap();
            let sample = host
                .enqueue_with_instances(
                    translated.input(),
                    source.instance_input(),
                    &camera,
                    324478056,
                )
                .unwrap();
            samples.push((frame, touched, replace, sample));
            let renderer = host.state.as_ref().unwrap().renderer.as_ref().unwrap();
            let geometry = renderer.geometry.as_ref().unwrap();
            let (pages, bytes) = geometry.assert_incremental_workspaces();
            peak_pages = peak_pages.max(pages);
            peak_bytes = peak_bytes.max(bytes);
            peak_allocations = peak_allocations.max(
                renderer
                    .context
                    .live_allocations
                    .load(std::sync::atomic::Ordering::Relaxed),
            );
            assert_eq!(
                geometry.textures.retained_slots(),
                (2, 5),
                "retired texture slots reused across 40 distinct IDs"
            );
            assert_eq!(geometry.objects.instances.len(), 1000);
            assert_eq!(geometry.objects.rebuilt, if replace { 1000 } else { 0 });
        }
        let completed: BTreeMap<_, _> = host
            .drain()
            .unwrap()
            .into_iter()
            .map(|s| (s.serial, s))
            .collect();
        let profiled = host
            .state
            .as_ref()
            .unwrap()
            .renderer
            .as_ref()
            .unwrap()
            .host_query
            != vk::QueryPool::null();
        for sample in completed.values() {
            assert_eq!(sample.preparation_ns.is_some(), profiled);
            assert_eq!(sample.render_ns.is_some(), profiled);
            if profiled {
                assert!(
                    sample.preparation_ns.unwrap() + sample.render_ns.unwrap() <= sample.gpu_ns + 2
                );
            }
        }
        if let Some(path) = std::env::var_os("PRIME_TYPED_HOST_CSV") {
            use std::fmt::Write;
            let mut csv = String::from(
                "frame,serial,instances,changed_pose,rebuilt_blas,width,height,seed,cpu_record_ns,cpu_submit_ns,cpu_slot_wait_ns,gpu_ns,gpu_preparation_ns,gpu_render_ns\n",
            );
            for (frame, touched, replace, sample) in samples {
                let gpu = &completed[&sample.serial];
                writeln!(
                    csv,
                    "{frame},{},1000,{touched},{},1920,1080,324478056,{},{},{},{},{},{}",
                    sample.serial,
                    if replace { 1000 } else { 0 },
                    sample.record_ns,
                    sample.submit_ns,
                    sample.slot_wait_ns,
                    gpu.gpu_ns,
                    gpu.preparation_ns
                        .map_or_else(String::new, |v| v.to_string()),
                    gpu.render_ns.map_or_else(String::new, |v| v.to_string())
                )
                .unwrap();
            }
            std::fs::write(path, csv).unwrap();
        }
        assert!(
            peak_allocations < 80,
            "physical allocations must not scale with 1000 BLAS: {peak_allocations}"
        );
        assert!(
            peak_pages <= 6,
            "churn cannot accumulate one page per generation: {peak_pages}"
        );
        println!(
            "typed_host unique_blas=1000 triangles_per_blas=48 texture_ids=40 resident_textures=1 frames=40 in_flight=2 peak_vk_allocations={peak_allocations} peak_working_pages={peak_pages} peak_working_bytes={peak_bytes}"
        );
    }

    #[test]
    #[ignore = "requires an exclusive Vulkan ray-query GPU; run with synchronization validation"]
    fn host_instanced_prototypes_retire_across_growth_rebase_epoch_and_removal() {
        use crate::plan::{INHERIT, translation};
        use prime_scene::scene::{Instance, Prototype};
        let mut host = HostBenchmark::new(32, 24).unwrap();
        let mut scene = Scene {
            ready_terrain: [[0.0; 3], [128.0, 0.0, 0.0]]
                .map(|origin| prime_scene::spatial::Cell::containing(origin).unwrap())
                .into(),
            epoch: 1,
            ..Default::default()
        };
        let mut source = InstanceScene {
            epoch: 1,
            resource_revision: 1,
            instance_revision: 1,
            ..Default::default()
        };
        let mut camera = Camera {
            position: [0.0, 0.0, 5.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        for (frame, count) in [2, 4096, 16, 8192, 3, 32, 2, 0].into_iter().enumerate() {
            if count == 0 {
                source.prototypes.clear();
                source.instances.clear();
            } else {
                let triangles: Vec<_> = (0..count)
                    .map(|i| {
                        let x = (i % 64) as f32 * 0.01;
                        let y = (i / 64) as f32 * 0.01;
                        Triangle {
                            positions: [[x, y, 0.0], [x + 0.005, y, 0.0], [x, y + 0.005, 0.0]],
                            colors: [[1.0; 4]; 3],
                            uvs: [[0.0; 2]; 3],
                            texture_id: 0,
                            flags: 0,
                        }
                    })
                    .collect();
                source.prototypes.insert(
                    1,
                    Prototype {
                        revision: frame as u64 + 1,
                        triangles: triangles.into(),
                        bounds: [[0.0; 3], [1.0, 2.0, 0.0]],
                    },
                );
                for id in 0..(frame + 1) as u64 {
                    source.instances.insert(
                        id,
                        Instance {
                            revision: frame as u64 + 1,
                            prototype_id: 1,
                            origin: [id as f64, 0.0, 0.0],
                            transform: translation([0.0; 3]),
                            texture_id: INHERIT,
                            flags: INHERIT,
                            tint: [255; 4],
                            uv_transform: [1.0, 1.0, 0.0, 0.0],
                        },
                    );
                }
            }
            if frame == 3 {
                scene.anchor[0] = 256.0;
                camera.position[0] -= 256.0;
                host.resize(48, 32).unwrap();
            }
            if frame == 5 {
                scene.epoch += 1;
                source.epoch = scene.epoch;
            }
            source.resource_revision += 1;
            source.instance_revision += 1;
            host.enqueue_with_instances(&scene, &source, &camera, frame as u32)
                .unwrap();
        }
        assert_eq!(host.drain().unwrap().len(), 8);
        let geometry = host
            .state
            .as_ref()
            .unwrap()
            .renderer
            .as_ref()
            .unwrap()
            .geometry
            .as_ref()
            .unwrap();
        assert!(geometry.objects.instances.is_empty());
        assert!(geometry.objects.addresses().is_empty());
        if let Some(profile) = host.profile_snapshot() {
            assert_eq!(profile.readback_bytes, 0);
            assert_eq!(profile.submissions, 0);
        }
    }

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; do not run alongside game performance tests"]
    fn host_benchmark_retires_slots_and_reports_every_serial() {
        let mut host = HostBenchmark::new(64, 48).unwrap();
        let mut scene = Scene {
            ready_terrain: [[0.0; 3], [128.0, 0.0, 0.0]]
                .map(|origin| prime_scene::spatial::Cell::containing(origin).unwrap())
                .into(),
            revision: 1,
            epoch: 1,
            ..Scene::default()
        };
        let make_mesh = |count, revision| SceneMesh {
            revision,
            flags: 0,
            origin: [0.0; 3],
            triangles: (0..count)
                .map(|i| {
                    let x = (i % 64) as f32 * 0.06 - 2.0;
                    let y = (i / 64) as f32 * 0.06 - 1.0;
                    Triangle {
                        positions: [[x, y, 0.0], [x + 0.05, y, 0.0], [x, y + 0.05, 0.0]],
                        colors: [[1.0; 4]; 3],
                        uvs: [[0.0; 2]; 3],
                        texture_id: 7,
                        flags: 0,
                    }
                })
                .collect::<Vec<_>>()
                .into(),
        };
        scene.meshes.insert((1, 0), make_mesh(32, 1));
        scene.textures.insert(
            7,
            Texture {
                width: 1,
                height: 1,
                pixels: vec![255, 0, 0, 255].into(),
            },
        );
        let mut camera = Camera {
            position: [0.0, 1.0, 2.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        assert_eq!(host.enqueue(&scene, &camera, 0).unwrap().serial, 1);
        // No drain between mutations: old AS, arena, textures and accumulation
        // may still be referenced by the immediately preceding GPU submission.
        // 4096 triangles exceed the initial 1024-record material arena and cover
        // the grow-copy -> dirty-copy WRITE_AFTER_WRITE regression.
        scene.meshes.insert((1, 0), make_mesh(4096, 2));
        scene.textures.get_mut(&7).unwrap().pixels = vec![0, 255, 0, 255].into();
        scene.revision += 1;
        assert_eq!(host.enqueue(&scene, &camera, 0).unwrap().serial, 2);
        host.resize(97, 61).unwrap();
        assert_eq!(host.enqueue(&scene, &camera, 0).unwrap().serial, 3);
        scene.anchor[0] += 256.0;
        camera.position[0] -= 256.0;
        scene.revision += 1;
        assert_eq!(host.enqueue(&scene, &camera, 0).unwrap().serial, 4);
        scene.epoch += 1;
        scene.textures.get_mut(&7).unwrap().pixels = vec![0, 0, 255, 255].into();
        assert_eq!(host.enqueue(&scene, &camera, 0).unwrap().serial, 5);
        host.resize(64, 48).unwrap();
        scene.meshes.clear();
        scene.revision += 1;
        assert_eq!(host.enqueue(&scene, &camera, 0).unwrap().serial, 6);
        let completed = host.drain().unwrap();
        assert_eq!(
            completed.iter().map(|s| s.serial).collect::<Vec<_>>(),
            [1, 2, 3, 4, 5, 6]
        );
        assert!(host.drain().unwrap().is_empty());
        assert!(host.state.as_ref().unwrap().retired_targets.is_empty());
        // Batch sizes grow/shrink while previous snapshots are still in flight.
        // Static geometry stays absent and its identity does not change.
        for (frame, count) in [2, 4096, 17, 8192, 0, 32, 2, 0].into_iter().enumerate() {
            scene.dynamic.revision += 1;
            scene.dynamic.triangles = Arc::new(make_mesh(count, 1).triangles.iter().collect());
            scene.dynamic.origin = [frame as f64 * 0.01, 0.0, 0.0];
            host.enqueue(&scene, &camera, frame as u32).unwrap();
        }
        assert_eq!(host.drain().unwrap().len(), 8);
        let renderer = host.state.as_ref().unwrap().renderer.as_ref().unwrap();
        if crate::cpu_profile::enabled() {
            let cpu = &renderer.cpu_profile;
            assert_eq!(cpu.pending_counters().0, 14);
            assert!(cpu.pending_counters().1 > 0);
        }
        assert_eq!(
            renderer.host_query != vk::QueryPool::null(),
            renderer.context.timestamp_bits > 0,
            "coarse GPU timing does not require profile flags"
        );
        if let Some(profile) = host.profile_snapshot() {
            assert_eq!(profile.readback_bytes, 0);
            assert_eq!(
                profile.submissions, 0,
                "borrowed renderer submitted outside the host"
            );
        }
    }
    #[test]
    #[ignore = "requires Vulkan with synchronization validation; realtime guide retirement under in-flight resize"]
    fn host_realtime_resize_and_offline_switch_retire_exclusive_resources() {
        let mut host = HostBenchmark::new(64, 48).unwrap();
        use crate::plan::{INHERIT, translation};
        use prime_scene::scene::{Instance, Prototype};
        let scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let mut instances = InstanceScene {
            epoch: 1,
            resource_revision: 1,
            instance_revision: 1,
            ..Default::default()
        };
        instances.prototypes.insert(
            1,
            Prototype {
                revision: 1,
                bounds: [[-1.0, -1.0, -2.0], [1.0, 1.0, -2.0]],
                triangles: vec![Triangle {
                    positions: [[-1.0, -1.0, -2.0], [1.0, -1.0, -2.0], [0.0, 1.0, -2.0]],
                    colors: [[1.0; 4]; 3],
                    uvs: [[0.0; 2]; 3],
                    texture_id: 0,
                    flags: 0,
                }]
                .into(),
            },
        );
        for id in 0..100 {
            instances.instances.insert(
                id,
                Instance {
                    revision: 1,
                    prototype_id: 1,
                    origin: [id as f64 * 0.01, 0.0, 0.0],
                    transform: translation([0.0; 3]),
                    texture_id: INHERIT,
                    flags: INHERIT,
                    tint: [255; 4],
                    uv_transform: [1.0, 1.0, 0.0, 0.0],
                },
            );
        }
        let camera = Camera {
            position: [0.0; 3],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        host.state
            .as_mut()
            .unwrap()
            .renderer
            .as_mut()
            .unwrap()
            .configure(RenderSettings::default())
            .unwrap();
        for (frame, (width, height)) in
            [(64, 48), (97, 61), (31, 71), (1920, 1080), (1, 1), (64, 48)]
                .into_iter()
                .enumerate()
        {
            host.resize(width, height).unwrap();
            host.enqueue_with_instances(&scene, &instances, &camera, frame as u32)
                .unwrap();
        }
        // All encoders have been submitted; native retirement itself waits for the timeline.
        let renderer = host.state.as_mut().unwrap().renderer.as_mut().unwrap();
        let addresses = renderer.geometry.as_ref().unwrap().objects.addresses();
        renderer
            .configure(RenderSettings {
                mode: RenderMode::Offline,
                ..Default::default()
            })
            .unwrap();
        renderer.set_scene_frozen(true);
        host.enqueue_with_instances(&scene, &instances, &camera, 0)
            .unwrap();
        host.enqueue_with_instances(&scene, &instances, &camera, 1)
            .unwrap();
        let renderer = host.state.as_mut().unwrap().renderer.as_mut().unwrap();
        assert_eq!(renderer.samples, 2);
        assert_eq!(
            renderer.geometry.as_ref().unwrap().objects.addresses(),
            addresses
        );
        assert_eq!(renderer.instance_work().instances, 100);
        renderer.configure(RenderSettings::default()).unwrap();
        renderer.set_scene_frozen(false);
        host.enqueue_with_instances(&scene, &instances, &camera, 0)
            .unwrap();
        assert_eq!(host.drain().unwrap().len(), 9);
        if let Some(profile) = host.profile_snapshot() {
            assert_eq!(profile.readback_bytes, 0);
            assert_eq!(profile.submissions, 0);
        }
    }
}
