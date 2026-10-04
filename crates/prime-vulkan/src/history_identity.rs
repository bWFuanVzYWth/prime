//! Current scene-slot identities for accepted ReSTIR history. Only semantic slot edits
//! advance the watermark; acceleration relocation and camera-relative rebasing do not.
use crate::{Buffer, Context, arena::Arena};
use ash::vk;
use std::{collections::BTreeSet, sync::Arc};

#[derive(Clone, Copy, Debug)]
pub(crate) struct HistoryIdentityInput {
    pub addresses: [u64; 3],
    pub counts: [u32; 3],
    pub revision: u32,
}

#[derive(Default)]
struct Table {
    records: Vec<[u32; 2]>,
    dirty: Vec<usize>,
    gpu: Option<Buffer>,
}

impl Table {
    fn set(&mut self, index: usize, count: u32, revision: u32) {
        self.records
            .resize(self.records.len().max(index + 1), [0; 2]);
        let value = [revision, count];
        if self.records[index] != value {
            self.records[index] = value;
            self.dirty.push(index);
        }
    }

    fn truncate(&mut self, count: usize) {
        self.records.truncate(count);
        self.dirty.retain(|&index| index < count);
    }

    fn copies(&mut self, source: u64) -> Vec<vk::BufferCopy> {
        self.dirty.sort_unstable();
        self.dirty.dedup();
        let mut copies: Vec<vk::BufferCopy> = Vec::new();
        for (i, &index) in self.dirty.iter().enumerate() {
            let src = source + i as u64 * 8;
            let dst = index as u64 * 8;
            if let Some(last) = copies.last_mut().filter(|last| {
                last.src_offset + last.size == src && last.dst_offset + last.size == dst
            }) {
                last.size += 8;
            } else {
                copies.push(
                    vk::BufferCopy::default()
                        .src_offset(src)
                        .dst_offset(dst)
                        .size(8),
                );
            }
        }
        copies
    }

    fn upload(&mut self, context: &Arc<Context>, uploads: &mut Arena) -> Result<u64, String> {
        let bytes = (self.records.len() as u64 * 8).max(8);
        if self.gpu.as_ref().is_none_or(|gpu| gpu.size < bytes) {
            let usage =
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS | vk::BufferUsageFlags::TRANSFER_DST;
            #[cfg(all(test, feature = "shader-tests"))]
            let usage = usage | vk::BufferUsageFlags::TRANSFER_SRC;
            let next = Buffer::new(
                context,
                bytes
                    .checked_next_power_of_two()
                    .ok_or("History identity capacity overflow")?,
                usage,
                false,
            )?;
            self.gpu = Some(next);
            self.dirty.clear();
            self.dirty.extend(0..self.records.len());
        }
        let mut copies = self.copies(0);
        if !self.dirty.is_empty() {
            let mut source = uploads.allocate(context, self.dirty.len() as u64 * 8, 8)?;
            source.write_with(|output| {
                for (target, &index) in output.as_chunks_mut::<8>().0.iter_mut().zip(&self.dirty) {
                    for (out, byte) in target
                        .iter_mut()
                        .zip(self.records[index].into_iter().flat_map(u32::to_le_bytes))
                    {
                        out.write(byte);
                    }
                }
                Ok(())
            })?;
            for copy in &mut copies {
                copy.src_offset += source.offset;
            }
            context.submit_named("history_identity", |command| unsafe {
                crate::geometry::transfer_write_barrier(context, command);
                context.device.cmd_copy_buffer(
                    command,
                    source.buffer.buffer,
                    self.gpu.as_ref().unwrap().buffer,
                    &copies,
                );
                crate::geometry::transfer_barrier(context, command);
            })?;
            uploads.retire(source);
            self.dirty.clear();
        }
        Ok(self.gpu.as_ref().unwrap().address())
    }
}

#[derive(Default)]
pub(super) struct HistoryIdentity {
    revision: u32,
    tables: [Table; 3],
    emitter_keys: Vec<Option<u64>>,
}

impl HistoryIdentity {
    pub fn advance(&mut self) -> Result<(), String> {
        self.revision = self
            .revision
            .checked_add(1)
            .ok_or("History identity revision exhausted")?;
        Ok(())
    }

    pub fn static_range(&mut self, first: u32, counts: impl IntoIterator<Item = u32>) {
        for (i, count) in counts.into_iter().enumerate() {
            self.tables[0].set(first as usize + i, count, self.revision);
        }
    }

    pub fn remove_static(&mut self, first: u32, count: u32) {
        self.static_range(first, std::iter::repeat_n(0, count as usize));
    }

    pub fn dynamic_slot(&mut self, index: usize, primitive_count: u32) {
        self.tables[1].set(index, primitive_count, self.revision);
    }

    pub fn synchronize_emitters(
        &mut self,
        pages: impl ExactSizeIterator<Item = Option<(u64, u32)>>,
    ) {
        let count = pages.len();
        self.emitter_keys.resize(count, None);
        for (index, page) in pages.enumerate() {
            let key = page.map(|(key, _)| key);
            let primitives = page.map_or(0, |(_, count)| count);
            if self.emitter_keys[index] != key
                || self.tables[2]
                    .records
                    .get(index)
                    .is_none_or(|record| record[1] != primitives)
            {
                self.tables[2].set(index, primitives, self.revision);
                self.emitter_keys[index] = key;
            }
        }
        self.tables[2].truncate(count);
    }

    // A texture edit can change the support of the same packed emitter page. Its
    // key/count need not change, and it does not require rebuilding the proposal.
    pub fn invalidate_emitter_keys(&mut self, keys: &BTreeSet<u64>) {
        for (index, key) in self.emitter_keys.iter().enumerate() {
            if key.is_some_and(|key| keys.contains(&key)) {
                let count = self.tables[2].records[index][1];
                self.tables[2].set(index, count, self.revision);
            }
        }
    }

    #[cfg(all(test, feature = "shader-tests"))]
    pub fn buffers_for_test(&self) -> [&Buffer; 3] {
        std::array::from_fn(|index| self.tables[index].gpu.as_ref().unwrap())
    }

    pub fn input(
        &mut self,
        context: &Arc<Context>,
        uploads: &mut Arena,
        static_count: usize,
        dynamic_count: usize,
    ) -> Result<HistoryIdentityInput, String> {
        self.tables[0].truncate(static_count);
        self.tables[1].truncate(dynamic_count);
        let mut addresses = [0; 3];
        let mut counts = [0; 3];
        for ((address, count), table) in addresses.iter_mut().zip(&mut counts).zip(&mut self.tables)
        {
            *address = table.upload(context, uploads)?;
            *count = u32::try_from(table.records.len())
                .map_err(|_| "History identity count overflow")?;
        }
        Ok(HistoryIdentityInput {
            addresses,
            counts,
            revision: self.revision,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid(table: &Table, index: usize, primitive: u32, accepted: u32) -> bool {
        table
            .records
            .get(index)
            .is_some_and(|record| record[0] != 0 && record[0] <= accepted && primitive < record[1])
    }

    #[test]
    fn unchanged_slots_survive_neighbor_replacement_removal_and_reuse() {
        let mut identity = HistoryIdentity::default();
        identity.advance().unwrap();
        identity.static_range(1, [4, 6]);
        let accepted = identity.revision;
        identity.advance().unwrap();
        identity.remove_static(1, 1);
        assert!(!valid(&identity.tables[0], 1, 0, accepted));
        assert!(valid(&identity.tables[0], 2, 5, accepted));
        identity.static_range(1, [8]);
        assert!(!valid(&identity.tables[0], 1, 0, accepted));
        assert!(valid(&identity.tables[0], 1, 7, identity.revision));
        assert!(!valid(&identity.tables[0], 1, 8, identity.revision));
        assert!(!valid(&identity.tables[0], 3, 0, identity.revision));
        assert_eq!(identity.tables[0].records[2], [accepted, 6]);
    }

    #[test]
    fn dense_dynamic_move_rejects_old_slot_and_acceptance_is_external() {
        let mut identity = HistoryIdentity::default();
        identity.advance().unwrap();
        identity.dynamic_slot(0, 12);
        identity.dynamic_slot(1, 18);
        let accepted = identity.revision;
        identity.advance().unwrap();
        identity.dynamic_slot(0, 18);
        identity.tables[1].truncate(1);
        assert!(!valid(&identity.tables[1], 0, 0, accepted));
        assert!(!valid(&identity.tables[1], 1, 0, accepted));
        assert!(valid(&identity.tables[1], 0, 17, identity.revision));
        assert!(!valid(&identity.tables[1], 0, 0, 0));
    }

    #[test]
    fn emitter_identity_ignores_anchor_and_rejects_same_slot_new_source() {
        let mut identity = HistoryIdentity::default();
        identity.advance().unwrap();
        identity.synchronize_emitters([Some((20, 4)), Some((30, 8))].into_iter());
        identity.tables[2].dirty.clear();
        let accepted = identity.revision;
        identity.advance().unwrap();
        identity.synchronize_emitters([Some((20, 4)), Some((30, 8))].into_iter());
        assert!(identity.tables[2].dirty.is_empty());
        identity.synchronize_emitters([Some((40, 4)), None].into_iter());
        assert_eq!(identity.tables[2].dirty, [0, 1]);
        assert!(!valid(&identity.tables[2], 0, 0, accepted));
        assert!(!valid(&identity.tables[2], 1, 0, identity.revision));
    }

    #[test]
    fn same_page_and_primitive_count_still_reject_changed_texture_support() {
        let pages = [Some((20, 4)), Some((30, 8))];
        let mut identity = HistoryIdentity::default();
        identity.advance().unwrap();
        identity.synchronize_emitters(pages.into_iter());
        identity.tables[2].dirty.clear();
        let accepted = identity.revision;
        identity.advance().unwrap();
        identity.invalidate_emitter_keys(&BTreeSet::from([20]));
        // The lighting proposal can stay identical after a coverage descriptor edit.
        identity.synchronize_emitters(pages.into_iter());
        assert_eq!(identity.tables[2].dirty, [0]);
        assert_eq!(identity.tables[2].records[0], [identity.revision, 4]);
        assert!(!valid(&identity.tables[2], 0, 0, accepted));
        assert!(valid(&identity.tables[2], 1, 7, accepted));
        assert!(valid(&identity.tables[2], 0, 3, identity.revision));
    }

    #[test]
    fn accumulated_support_events_survive_unchanged_prepare_and_reject_each_owner() {
        use crate::textures::history_support::placement_depends_on;
        let mut identity = HistoryIdentity::default();
        identity.advance().unwrap();
        identity.static_range(0, [4, 6, 8]);
        for slot in 0..3 {
            identity.dynamic_slot(slot, 4);
        }
        identity.synchronize_emitters([Some((70, 4)), Some((80, 6)), Some((90, 8))].into_iter());
        let accepted = identity.revision;
        // Resource preparation can publish several updates, followed by a no-op
        // render preparation. Consumers see the union, not just the final update.
        let mut pending = BTreeSet::new();
        pending.extend([7]);
        pending.extend(BTreeSet::<u32>::new());
        pending.extend([9]);
        let dependencies = [
            BTreeSet::from([7]),
            BTreeSet::from([8]),
            BTreeSet::from([9]),
        ];
        identity.advance().unwrap();
        for (slot, dependency) in dependencies.iter().enumerate() {
            if !dependency.is_disjoint(&pending) {
                identity.static_range(slot as u32, [identity.tables[0].records[slot][1]]);
            }
        }
        identity.invalidate_emitter_keys(&BTreeSet::from([70, 90]));
        // Static consumption does not discard events before dynamic consumption.
        for (slot, (texture, dependency)) in
            [u32::MAX, 8, 9].into_iter().zip(&dependencies).enumerate()
        {
            if placement_depends_on(texture, dependency, &pending) {
                identity.dynamic_slot(slot, 4);
            }
        }
        for table in &identity.tables {
            assert!(!valid(table, 0, 0, accepted));
            assert!(valid(table, 1, 0, accepted));
            assert!(!valid(table, 2, 0, accepted));
            assert!(valid(table, 0, 0, identity.revision));
        }
        pending.clear();
        assert!(pending.is_empty());
    }

    #[test]
    fn dirty_journal_copies_only_touched_spans_and_stable_records_do_not_upload() {
        let mut table = Table::default();
        for index in [7, 2, 3, 2, 8] {
            table.set(index, 20, 7);
        }
        let copies = table.copies(512);
        assert_eq!(table.dirty, [2, 3, 7, 8]);
        assert_eq!(copies.len(), 2);
        assert_eq!(
            (copies[0].src_offset, copies[0].dst_offset, copies[0].size),
            (512, 16, 16)
        );
        assert_eq!(
            (copies[1].src_offset, copies[1].dst_offset, copies[1].size),
            (528, 56, 16)
        );
        table.dirty.clear();
        for index in [2, 3, 7, 8] {
            table.set(index, 20, 7);
        }
        assert!(table.copies(512).is_empty());
        table.set(3, 20, 8);
        assert_eq!(table.dirty, [3]);
        assert_eq!(table.records[2], [7, 20]);
    }

    #[test]
    fn identity_revision_never_wraps_into_an_accepted_generation() {
        let mut identity = HistoryIdentity::default();
        identity.revision = u32::MAX;
        assert!(identity.advance().is_err());
        assert_eq!(identity.revision, u32::MAX);
    }

    #[test]
    fn actual_dense_planner_removals_only_touch_live_slots_and_keep_unmoved_identities() {
        use crate::plan::{OBJECT_BIT, Planner};
        use prime_scene::{
            scene::{Instance, InstanceScene, Prototype, Scene, Triangle},
            translation::BatchLimits,
        };
        let mut source = InstanceScene {
            epoch: 1,
            resource_revision: 1,
            instance_revision: 1,
            ..Default::default()
        };
        source.prototypes.insert(
            9,
            Prototype {
                revision: 1,
                triangles: vec![Triangle {
                    positions: [[0., 0., 0.], [1., 0., 0.], [0., 1., 0.]],
                    colors: [[1.; 4]; 3],
                    uvs: [[0.; 2]; 3],
                    texture_id: 0,
                    flags: 0,
                }]
                .into(),
                bounds: [[0.; 3], [1., 1., 0.]],
            },
        );
        for id in 0..5 {
            source.instances.insert(
                id,
                Instance {
                    revision: 1,
                    prototype_id: 9,
                    origin: [id as f64, 0., 0.],
                    transform: [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0.],
                    texture_id: u32::MAX,
                    flags: u32::MAX,
                    tint: [255; 4],
                    uv_transform: [1., 1., 0., 0.],
                },
            );
        }
        let scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let mut planner = Planner::new(BatchLimits {
            triangles: 1024,
            placements: OBJECT_BIT - 1,
        })
        .unwrap();
        let first = planner.plan(&scene, &source).unwrap();
        planner.recycle(first);
        let mut identity = HistoryIdentity::default();
        identity.advance().unwrap();
        for slot in 0..planner.placement_count() {
            identity.dynamic_slot(slot, 2);
        }
        for removed in [4, 0, 1, 3, 2] {
            let accepted = identity.revision;
            let old: Vec<_> = (0..planner.placement_count())
                .map(|slot| planner.placement_instance_id(slot))
                .collect();
            source.instances.remove(&removed);
            source.instance_revision += 1;
            let plan = planner.plan(&scene, &source).unwrap();
            assert!(plan.placements_changed);
            assert!(
                plan.changes
                    .iter()
                    .all(|change| change.slot < planner.placement_count())
            );
            identity.advance().unwrap();
            for change in &plan.changes {
                identity.dynamic_slot(change.slot, 2);
            }
            identity.tables[1].truncate(planner.placement_count());
            for (slot, previous) in old.iter().enumerate() {
                let same_owner = *previous == planner.placement_instance_id(slot);
                assert_eq!(valid(&identity.tables[1], slot, 0, accepted), same_owner);
            }
            planner.recycle(plan);
        }
        assert!(identity.tables[1].records.is_empty());
    }
}
