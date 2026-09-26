//! A small host simulator for measuring the production recording path. No pixels
//! leave the device. Two submissions may be in flight on one queue/timeline.
use super::*;
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
    completed: BTreeMap<u64, u64>,
    poisoned: bool,
}

impl HostBenchmark {
    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        if width == 0 || height == 0 || width > 4096 || height > 4096 {
            return Err("Benchmark dimensions must be within 1..4096".into());
        }
        let owner = Context::new()?;
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

    pub fn profile_snapshot(&self) -> Option<GpuProfile> {
        self.state
            .as_ref()
            .unwrap()
            .renderer
            .as_ref()
            .unwrap()
            .profile_snapshot()
    }

    /// Apply a new target extent in the next recording, without draining prior
    /// frames. The old image is retained until its last submitted serial retires.
    pub fn resize(&mut self, width: u32, height: u32) -> Result<(), String> {
        if self.poisoned {
            return Err("Benchmark host is quarantined".into());
        }
        if width == 0 || height == 0 || width > 4096 || height > 4096 {
            return Err("Benchmark dimensions must be within 1..4096".into());
        }
        self.width = width;
        self.height = height;
        Ok(())
    }

    /// Wait only when the two-frame ring is full; never waits for this new frame.
    pub fn enqueue(
        &mut self,
        scene: &Scene,
        camera: &Camera,
        sample: u32,
    ) -> Result<HostSample, String> {
        if self.poisoned {
            return Err("Benchmark host is quarantined".into());
        }
        let result = self.enqueue_inner(scene, camera, sample);
        if result.is_err() {
            self.poisoned = true;
        }
        result
    }

    fn enqueue_inner(
        &mut self,
        scene: &Scene,
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
            state.renderer.as_mut().unwrap().record_host(
                scene,
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
            self.completed.insert(serial, gpu_ns);
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
            Ok(std::mem::take(&mut self.completed)
                .into_iter()
                .map(|(serial, gpu_ns)| CompletedSample { serial, gpu_ns })
                .collect())
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

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; do not run alongside game performance tests"]
    fn host_benchmark_retires_slots_and_reports_every_serial() {
        let mut host = HostBenchmark::new(64, 48).unwrap();
        let mut scene = Scene {
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
        scene.meshes.get_mut(&(1, 0)).unwrap().origin[0] -= 256.0;
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
        if let Some(profile) = host.profile_snapshot() {
            assert_eq!(profile.readback_bytes, 0);
            assert_eq!(
                profile.submissions, 0,
                "borrowed renderer submitted outside the host"
            );
        }
    }
}
