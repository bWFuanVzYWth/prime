//! Static BLAS size queries and bounded address maintenance, owned by the render thread.
use crate::resources::{Context, error};
use ash::vk;
use prime_scene::spatial::Cell;
use std::{collections::VecDeque, sync::Arc};

pub(crate) const MAX_COPIES: usize = 32;
const TARGET_BYTES: u64 = 8 * 1024 * 1024;

pub(crate) struct QueryPool {
    context: Arc<Context>,
    handle: vk::QueryPool,
    count: usize,
}
impl QueryPool {
    pub fn new(context: &Arc<Context>, count: usize) -> Result<Self, String> {
        assert!((1..=MAX_COPIES).contains(&count));
        let handle = unsafe {
            context.device.create_query_pool(
                &vk::QueryPoolCreateInfo::default()
                    .query_type(vk::QueryType::ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR)
                    .query_count(count as u32),
                None,
            )
        }
        .map_err(|e| error("Create BLAS compaction queries", e))?;
        Ok(Self {
            context: context.clone(),
            handle,
            count,
        })
    }
    /// The caller orders completed BLAS writes before these AS reads.
    pub fn record(&self, command: vk::CommandBuffer, handles: &[vk::AccelerationStructureKHR]) {
        assert_eq!(handles.len(), self.count);
        unsafe {
            self.context
                .device
                .cmd_reset_query_pool(command, self.handle, 0, self.count as u32);
            self.context
                .acceleration
                .cmd_write_acceleration_structures_properties(
                    command,
                    handles,
                    vk::QueryType::ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                    self.handle,
                    0,
                );
        }
    }
    fn results(&self) -> Result<Vec<u64>, String> {
        let mut sizes = vec![0_u64; self.count];
        // The batch serial is proven complete before this call. WAIT would hide
        // a broken completion contract and add a new render-thread stall.
        unsafe {
            self.context.device.get_query_pool_results(
                self.handle,
                0,
                &mut sizes,
                vk::QueryResultFlags::TYPE_64,
            )
        }
        .map_err(|e| error("Read completed BLAS compaction queries", e))?;
        Ok(sizes)
    }
}
impl Drop for QueryPool {
    fn drop(&mut self) {
        self.context.retire_query_pool(self.handle);
    }
}

#[derive(Clone, Copy)]
pub(crate) struct Source {
    pub cell: Cell,
    pub generation: u64,
    pub bytes: u64,
}
pub(crate) struct Candidate {
    pub source: Source,
    pub bytes: u64,
}
struct Pending {
    pool: QueryPool,
    sources: Vec<Source>,
    serial: u64,
}
#[derive(Default)]
pub(crate) struct Compactions {
    queries: VecDeque<Pending>,
    ready: VecDeque<Candidate>,
    retired: VecDeque<(u64, u64)>,
    pub copies: u64,
    pub completed: u64,
    pub reclaimed_bytes: u64,
}
impl Compactions {
    pub fn record_batch(&mut self, pool: QueryPool, sources: Vec<Source>, serial: u64) {
        assert_eq!(pool.count, sources.len());
        self.queries.push_back(Pending {
            pool,
            sources,
            serial,
        });
    }
    pub fn needs_update(&self, completed: u64) -> bool {
        !self.ready.is_empty()
            || self
                .queries
                .front()
                .is_some_and(|batch| batch.serial <= completed)
    }
    pub fn resolve(&mut self, completed: u64) -> Result<(), String> {
        while self
            .queries
            .front()
            .is_some_and(|batch| batch.serial <= completed)
        {
            let batch = self.queries.pop_front().unwrap();
            for (source, bytes) in batch.sources.iter().copied().zip(batch.pool.results()?) {
                if bytes > 0 && bytes < source.bytes {
                    self.ready.push_back(Candidate { source, bytes });
                }
            }
        }
        Ok(())
    }
    pub fn pop(&mut self) -> Option<Candidate> {
        self.ready.pop_front()
    }
    pub fn defer(&mut self, candidate: Candidate) {
        self.ready.push_front(candidate);
    }
    pub fn cancel_sources(&mut self) {
        // A world epoch replaces every source. Query pools still retire through
        // Context's timeline, while completed-copy retirement proofs stay intact.
        self.queries.clear();
        self.ready.clear();
    }
    /// Count savings only once the old AS's last copy/TLAS/trace consumer completes.
    pub fn published(&mut self, serial: u64, reclaimed: u64) {
        self.copies += 1;
        self.retired.push_back((serial, reclaimed));
    }
    pub fn complete(&mut self, completed: u64) -> bool {
        let mut changed = false;
        while self
            .retired
            .front()
            .is_some_and(|(serial, _)| *serial <= completed)
        {
            let (_, bytes) = self.retired.pop_front().unwrap();
            self.completed += 1;
            self.reclaimed_bytes += bytes;
            changed = true;
        }
        changed
    }
}
pub(crate) fn admit(count: usize, bytes: u64, next: u64, budget: usize) -> bool {
    count < budget.min(MAX_COPIES)
        && (count == 0
            || bytes
                .checked_add(next)
                .is_some_and(|total| total <= TARGET_BYTES))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        arena::Arena,
        resources::{Acceleration, Buffer},
    };
    use ash::vk::Handle;

    #[test]
    #[ignore = "windowless real compact copy and dependent TLAS held behind a host timeline gate"]
    fn gpu_compaction_source_and_ranges_wait_for_copy_tlas_last_consumer() {
        let owner = Context::new().unwrap();
        unsafe {
            let semaphore = || {
                let mut ty = vk::SemaphoreTypeCreateInfo::default()
                    .semaphore_type(vk::SemaphoreType::TIMELINE);
                owner
                    .device
                    .create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut ty), None)
                    .unwrap()
            };
            let timeline = semaphore();
            let gate = semaphore();
            let context = Context::borrowed(
                owner.instance_handle(),
                owner.physical.as_raw(),
                owner.device.handle().as_raw(),
                owner.queue.as_raw(),
                owner.queue_family,
                timeline.as_raw(),
            )
            .unwrap();
            let pool = owner
                .device
                .create_command_pool(
                    &vk::CommandPoolCreateInfo::default().queue_family_index(owner.queue_family),
                    None,
                )
                .unwrap();
            let commands = owner
                .device
                .allocate_command_buffers(
                    &vk::CommandBufferAllocateInfo::default()
                        .command_pool(pool)
                        .level(vk::CommandBufferLevel::PRIMARY)
                        .command_buffer_count(2),
                )
                .unwrap();
            let mut arena = Arena::new(&context, false);
            owner
                .device
                .begin_command_buffer(commands[0], &vk::CommandBufferBeginInfo::default())
                .unwrap();
            let completed = context.begin_host_record(commands[0], 1).unwrap();
            arena.begin(completed, 1);
            let bytes: Vec<_> = [[0_f32, 0., 0.], [1., 0., 0.], [0., 1., 0.]]
                .into_iter()
                .flatten()
                .flat_map(f32::to_le_bytes)
                .collect();
            let vertices = Buffer::upload_device(
                &context,
                &bytes,
                vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                    | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            )
            .unwrap();
            let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
                .vertex_format(vk::Format::R32G32B32_SFLOAT)
                .vertex_stride(12)
                .max_vertex(2)
                .vertex_data(vk::DeviceOrHostAddressConstKHR {
                    device_address: vertices.address(),
                })
                .index_type(vk::IndexType::NONE_KHR);
            let geometry = vk::AccelerationStructureGeometryKHR::default()
                .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
                .flags(vk::GeometryFlagsKHR::OPAQUE)
                .geometry(vk::AccelerationStructureGeometryDataKHR { triangles });
            let build = Acceleration::prepare_with_flags(
                &context,
                &mut arena,
                geometry,
                1,
                vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL,
                vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE
                    | vk::BuildAccelerationStructureFlagsKHR::ALLOW_COMPACTION,
            )
            .unwrap();
            let queries = QueryPool::new(&context, 1).unwrap();
            context
                .submit_named("test_blas_query", |command| {
                    build.record_unbarriered(command);
                    Acceleration::read_barrier(&context, command);
                    queries.record(command, &[build.acceleration().handle]);
                })
                .unwrap();
            let source = build.finish(&mut arena);
            let original = source.handle;
            let source_bytes = source.storage_bytes();
            context.end_host_record();
            owner.device.end_command_buffer(commands[0]).unwrap();
            let signal = [timeline];
            let values = [1];
            let mut timeline_info =
                vk::TimelineSemaphoreSubmitInfo::default().signal_semaphore_values(&values);
            owner
                .device
                .queue_submit(
                    owner.queue,
                    &[vk::SubmitInfo::default()
                        .command_buffers(&commands[..1])
                        .signal_semaphores(&signal)
                        .push_next(&mut timeline_info)],
                    vk::Fence::null(),
                )
                .unwrap();
            let completed = context.wait_host_serial(1).unwrap();
            arena.begin(completed, 2);
            let compacted = queries.results().unwrap()[0];
            assert!(compacted > 0);
            let target = source.compact_target(&mut arena, compacted).unwrap();
            assert_ne!(source.address(), target.address());
            owner
                .device
                .begin_command_buffer(commands[1], &vk::CommandBufferBeginInfo::default())
                .unwrap();
            context.begin_host_record(commands[1], 2).unwrap();
            let instance = vk::AccelerationStructureInstanceKHR {
                transform: vk::TransformMatrixKHR {
                    matrix: [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0.],
                },
                instance_custom_index_and_mask: vk::Packed24_8::new(0, 0xff),
                instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(0, 0),
                acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                    device_handle: target.address(),
                },
            };
            let input = Buffer::new(
                &context,
                64,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                    | vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR,
                true,
            )
            .unwrap();
            input
                .write(std::slice::from_raw_parts(
                    std::ptr::from_ref(&instance).cast(),
                    64,
                ))
                .unwrap();
            let instances = vk::AccelerationStructureGeometryInstancesDataKHR::default().data(
                vk::DeviceOrHostAddressConstKHR {
                    device_address: input.address(),
                },
            );
            let geometry = vk::AccelerationStructureGeometryKHR::default()
                .geometry_type(vk::GeometryTypeKHR::INSTANCES)
                .geometry(vk::AccelerationStructureGeometryDataKHR { instances });
            let tlas = Acceleration::prepare_with_flags(
                &context,
                &mut arena,
                geometry,
                1,
                vk::AccelerationStructureTypeKHR::TOP_LEVEL,
                vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_BUILD,
            )
            .unwrap();
            context
                .submit_named("test_compact_and_tlas", |command| {
                    source.record_compact_copy(command, &target);
                    Acceleration::read_barrier(&context, command);
                    tlas.record_unbarriered(command);
                    Acceleration::read_barrier(&context, command);
                })
                .unwrap();
            let top = tlas.finish(&mut arena);
            source.retire(&mut arena);
            context.end_host_record();
            owner.device.end_command_buffer(commands[1]).unwrap();
            let waits = [gate];
            let stages = [vk::PipelineStageFlags::ALL_COMMANDS];
            let wait_values = [1];
            let signal_values = [2];
            let mut timeline_info = vk::TimelineSemaphoreSubmitInfo::default()
                .wait_semaphore_values(&wait_values)
                .signal_semaphore_values(&signal_values);
            owner
                .device
                .queue_submit(
                    owner.queue,
                    &[vk::SubmitInfo::default()
                        .command_buffers(&commands[1..])
                        .wait_semaphores(&waits)
                        .wait_dst_stage_mask(&stages)
                        .signal_semaphores(&signal)
                        .push_next(&mut timeline_info)],
                    vk::Fence::null(),
                )
                .unwrap();
            let completed_before_copy = context.completed_serial().unwrap();
            arena.begin(completed_before_copy, 2);
            let source_pending = context.pending_acceleration_retirement(original);
            let ranges_pending = arena.retired_count();
            // Release the gate before assertions so a failing test cannot strand the queue.
            owner
                .device
                .signal_semaphore(&vk::SemaphoreSignalInfo::default().semaphore(gate).value(1))
                .unwrap();
            let completed = context.wait_host_serial(2).unwrap();
            arena.begin(completed, 2);
            assert_eq!(completed_before_copy, 1);
            assert!(
                source_pending,
                "old AS must survive until its actual compact-copy consumer"
            );
            assert!(
                ranges_pending >= 2,
                "source and TLAS scratch ranges stay unreusable"
            );
            assert!(!context.pending_acceleration_retirement(original));
            assert_eq!(arena.retired_count(), 0);
            eprintln!(
                "gate-controlled compact source={source_bytes} target={compacted} before={completed_before_copy} completed={completed}"
            );
            top.retire(&mut arena);
            target.retire(&mut arena);
            drop(queries);
            drop(vertices);
            drop(input);
            arena.begin(completed, 2);
            drop(arena);
            context.finish_host().unwrap();
            assert_eq!(
                context
                    .live_allocations
                    .load(std::sync::atomic::Ordering::Relaxed),
                0
            );
            drop(context);
            owner.device.destroy_command_pool(pool, None);
            owner.device.destroy_semaphore(gate, None);
            owner.device.destroy_semaphore(timeline, None);
        }
    }
    #[test]
    fn budget_bounds_copy_work_without_starving_one_large_source() {
        assert!(!admit(0, 0, 1, 0));
        assert!(admit(0, 0, TARGET_BYTES * 2, 1));
        assert!(!admit(1, TARGET_BYTES * 2, 1, 2));
        assert!(admit(1, TARGET_BYTES - 1, 1, 2));
        assert!(!admit(1, TARGET_BYTES, 1, 2));
        assert!(!admit(MAX_COPIES, 0, 1, usize::MAX));
        assert!(!admit(1, u64::MAX, 1, 2));
    }
    #[test]
    fn reclamation_counts_require_last_consumer_completion() {
        let mut work = Compactions::default();
        work.published(9, 4000);
        work.published(12, 7000);
        assert!(!work.needs_update(8));
        assert!(!work.complete(8));
        assert_eq!(
            (work.copies, work.completed, work.reclaimed_bytes),
            (2, 0, 0)
        );
        assert!(work.complete(9));
        assert_eq!((work.completed, work.reclaimed_bytes), (1, 4000));
        assert!(!work.complete(11));
        assert!(work.complete(12));
        assert_eq!((work.completed, work.reclaimed_bytes), (2, 11000));
    }
}
