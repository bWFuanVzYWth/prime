//! Current scene-slot identities for accepted ReSTIR history. Only semantic slot edits
//! advance the watermark; acceleration relocation and camera-relative rebasing do not.
//! Prime identity/support adaptation: RA-009 in docs/restir-adaptations.md.
use super::static_history::QuadState;
use crate::plan::Slots;
use crate::{Buffer, Context, arena::Arena};
use ash::vk;
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Arc,
};

#[derive(Clone, Copy, Debug)]
pub(crate) struct HistoryIdentityInput {
    pub addresses: [u64; 3],
    pub counts: [u32; 3],
    pub revision: u32,
    pub quad_address: u64,
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

pub(super) struct HistoryIdentity {
    revision: u32,
    tables: [Table; 3],
    emitter_keys: Vec<Option<u64>>,
    quad_records: Table,
    quad_slots: Slots,
    quad_pages: BTreeMap<u32, (u32, u32)>,
}

impl Default for HistoryIdentity {
    fn default() -> Self {
        Self {
            revision: 0,
            tables: Default::default(),
            emitter_keys: Vec::new(),
            quad_records: Table::default(),
            quad_slots: Slots::with_limit(0x8000_0000),
            quad_pages: BTreeMap::new(),
        }
    }
}

// Update-local certificate. It is restored only after the caller proves that
// the source and canonical primitive mapping survived a derived rebuild.
pub(super) struct StaticHistory {
    first: u32,
    records: Vec<[u32; 2]>,
}

impl HistoryIdentity {
    pub fn after(accepted: u32) -> Result<Self, String> {
        let mut identity = Self {
            revision: accepted,
            ..Default::default()
        };
        identity.advance()?;
        Ok(identity)
    }

    pub fn revision(&self) -> u32 {
        self.revision
    }

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
        for index in first..first + count {
            if let Some((offset, count)) = self.quad_pages.remove(&index) {
                self.quad_slots.release(offset, count);
            }
        }
        self.suspend_static(first, count);
    }

    pub fn suspend_static(&mut self, first: u32, count: u32) {
        self.static_range(first, std::iter::repeat_n(0, count as usize));
    }

    pub fn static_quads(
        &mut self,
        page: u32,
        states: &[QuadState],
        retain: bool,
    ) -> Result<(), String> {
        let count =
            u32::try_from(states.len()).map_err(|_| "Static quad identity count overflow")?;
        let previous = self.quad_pages.get(&page).copied();
        let old = previous.filter(|_| retain).map(|(first, count)| {
            self.quad_records.records[first as usize..(first + count) as usize].to_vec()
        });
        let offset = if let Some((first, old_count)) =
            previous.filter(|(_, old_count)| *old_count == count)
        {
            let _ = old_count;
            first
        } else {
            if let Some((first, count)) = previous {
                self.quad_slots.release(first, count);
            }
            let offset = self.quad_slots.allocate(count)?;
            self.quad_pages.insert(page, (offset, count));
            offset
        };
        for (index, state) in states.iter().enumerate() {
            let revision = if !state.changed {
                old.as_ref()
                    .and_then(|records| records.get(index))
                    .filter(|old| old[1] == state.live)
                    .map_or(self.revision, |old| old[0])
            } else {
                self.revision
            };
            self.quad_records
                .set(offset as usize + index, state.live, revision);
        }
        self.tables[0].set(page as usize, count * 2 | 0x8000_0000, offset);
        Ok(())
    }

    pub fn invalidate_static_quads(&mut self, page: u32, quads: impl IntoIterator<Item = usize>) {
        let Some(&(offset, count)) = self.quad_pages.get(&page) else {
            return;
        };
        for quad in quads {
            if quad < count as usize {
                let at = offset as usize + quad;
                self.quad_records
                    .set(at, self.quad_records.records[at][1], self.revision);
            }
        }
    }
    pub fn relocate_static_quads(
        &mut self,
        previous: u32,
        next: u32,
        count: u32,
    ) -> Result<(), String> {
        for index in 0..count {
            if self.quad_pages.contains_key(&(next + index)) {
                return Err("Static quad identity alias overlaps another live page".into());
            }
        }
        for index in 0..count {
            if let Some(range) = self.quad_pages.remove(&(previous + index)) {
                self.quad_pages.insert(next + index, range);
            }
        }
        Ok(())
    }
    pub fn alias_static(&mut self, previous: u32, next: u32, count: u32, current_count: u32) {
        for index in 0..count {
            let record = if index < current_count {
                self.tables[0]
                    .records
                    .get((next + index) as usize)
                    .copied()
                    .unwrap_or([0; 2])
            } else {
                [0; 2]
            };
            self.tables[0].set((previous + index) as usize, record[1], record[0]);
        }
    }

    pub fn snapshot_static(&self, first: u32, count: u32) -> Option<StaticHistory> {
        Some(StaticHistory {
            first,
            records: self.tables[0]
                .records
                .get(first as usize..first as usize + count as usize)?
                .to_vec(),
        })
    }

    pub fn restore_static(
        &mut self,
        first: u32,
        counts: impl ExactSizeIterator<Item = u32>,
        saved: &StaticHistory,
    ) -> bool {
        if saved.first != first
            || saved.records.len() != counts.len()
            || saved
                .records
                .iter()
                .zip(counts)
                .any(|(old, count)| old[1] != count)
        {
            return false;
        }
        for (i, &[revision, count]) in saved.records.iter().enumerate() {
            self.tables[0].set(first as usize + i, count, revision);
        }
        true
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

    #[cfg(all(test, feature = "shader-tests"))]
    pub fn quad_buffer_for_test(&self) -> Option<&Buffer> {
        self.quad_records.gpu.as_ref()
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
        let mut event = prime_diagnostics::scope("restir.identity.frame");
        event.count("revision", u64::from(self.revision));
        for (name, table) in ["static", "dynamic", "emitters"]
            .into_iter()
            .zip(&self.tables)
        {
            event.count(name, table.dirty.len() as u64);
        }
        let mut addresses = [0; 3];
        let mut counts = [0; 3];
        for ((address, count), table) in addresses.iter_mut().zip(&mut counts).zip(&mut self.tables)
        {
            *address = table.upload(context, uploads)?;
            *count = u32::try_from(table.records.len())
                .map_err(|_| "History identity count overflow")?;
        }
        self.quad_records.truncate(self.quad_slots.end as usize);
        let quad_address = if self.quad_records.records.is_empty() {
            0
        } else {
            self.quad_records.upload(context, uploads)?
        };
        Ok(HistoryIdentityInput {
            addresses,
            counts,
            revision: self.revision,
            quad_address,
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
    fn replacement_domain_is_newer_than_accepted_history_even_with_reused_numeric_ids() {
        let mut identity = HistoryIdentity::after(41).unwrap();
        identity.static_range(0, [6]);
        identity.dynamic_slot(0, 8);
        identity.synchronize_emitters([Some((20, 4))].into_iter());
        assert_eq!(identity.revision(), 42);
        for table in &identity.tables {
            assert!(!valid(table, 0, 0, 41));
            assert!(valid(table, 0, 0, 42));
        }
        assert!(HistoryIdentity::after(u32::MAX).is_err());
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
    fn derived_rebuild_restores_only_the_same_range_and_current_support_stamps() {
        let mut identity = HistoryIdentity::after(0).unwrap();
        identity.static_range(2, [4, 6]);
        let accepted = identity.revision();
        identity.advance().unwrap();
        // A genuine alpha edit precedes the derived rebuild. Its fresh stamp
        // must survive restoration, while the neighboring page remains usable.
        identity.static_range(3, [6]);
        let saved = identity.snapshot_static(2, 2).unwrap();
        identity.remove_static(2, 2);
        assert!(!valid(&identity.tables[0], 2, 0, accepted));
        assert!(!identity.restore_static(1, [4, 6].into_iter(), &saved));
        assert!(!identity.restore_static(2, [4, 8].into_iter(), &saved));
        assert!(!identity.restore_static(2, [4].into_iter(), &saved));
        assert!(!valid(&identity.tables[0], 2, 0, accepted));
        assert!(identity.restore_static(2, [4, 6].into_iter(), &saved));
        assert!(valid(&identity.tables[0], 2, 3, accepted));
        assert!(!valid(&identity.tables[0], 3, 0, accepted));
        assert!(valid(&identity.tables[0], 3, 5, identity.revision()));
        assert!(identity.snapshot_static(2, 3).is_none());
    }

    #[test]
    fn quad_edits_and_page_alias_growth_keep_unmodified_endpoints_and_shared_storage() {
        let state = |live, changed| QuadState { live, changed };
        let mut identity = HistoryIdentity::after(0).unwrap();
        identity
            .static_quads(1, &[state(3, true), state(3, true), state(1, true)], false)
            .unwrap();
        let accepted = identity.revision();
        let old_offset = identity.quad_pages[&1].0;
        identity.advance().unwrap();
        identity.invalidate_static_quads(1, [2]);
        identity.suspend_static(1, 1);
        assert!(!valid(&identity.tables[0], 1, 0, accepted));
        identity
            .static_quads(1, &[state(3, false), state(0, true), state(1, false)], true)
            .unwrap();
        assert_eq!(
            identity.quad_records.records[old_offset as usize],
            [accepted, 3]
        );
        assert_eq!(
            identity.quad_records.records[old_offset as usize + 1],
            [identity.revision(), 0]
        );
        assert_eq!(
            identity.quad_records.records[old_offset as usize + 2],
            [identity.revision(), 1]
        );
        identity.relocate_static_quads(1, 20, 1).unwrap();
        identity
            .static_quads(
                20,
                &[state(3, false), state(3, true), state(1, false)],
                true,
            )
            .unwrap();
        identity.alias_static(1, 20, 1, 1);
        assert_eq!(
            identity.tables[0].records[1],
            identity.tables[0].records[20]
        );
        assert_eq!(identity.quad_pages[&20].0, old_offset);
        assert!(!identity.quad_pages.contains_key(&1));
        assert_eq!(
            identity.quad_slots.end, 3,
            "aliases must not duplicate quad identity storage"
        );
        assert_eq!(
            identity.quad_records.records[old_offset as usize],
            [accepted, 3]
        );
        assert_eq!(
            identity.quad_records.records[old_offset as usize + 1],
            [identity.revision(), 3]
        );
        identity.remove_static(1, 1);
        assert_eq!(identity.quad_slots.end, 3);
        identity.remove_static(20, 1);
        assert_eq!(identity.quad_slots.end, 0);
        assert!(!valid(&identity.tables[0], 1, 0, identity.revision()));
        assert!(!valid(&identity.tables[0], 20, 0, identity.revision()));
    }

    #[test]
    fn dead_tail_shrink_preserves_live_quad_stamps_and_clears_every_page_alias() {
        let state = |live, changed| QuadState { live, changed };
        let mut identity = HistoryIdentity::after(0).unwrap();
        identity
            .static_quads(4, &[state(3, true); 3], false)
            .unwrap();
        identity.static_quads(5, &[state(3, true)], false).unwrap();
        identity.static_quads(6, &[state(3, true)], false).unwrap();
        identity.static_quads(9, &[state(1, true)], false).unwrap();
        let accepted = identity.revision();
        let neighbor = identity.tables[0].records[9];
        identity.advance().unwrap();
        identity.static_quads(4, &[state(3, false)], true).unwrap();
        let first = identity.quad_pages[&4].0;
        assert_eq!(identity.quad_records.records[first as usize], [accepted, 3]);
        assert_eq!(identity.tables[0].records[4][1], 0x8000_0002);
        identity.relocate_static_quads(4, 20, 3).unwrap();
        identity.remove_static(22, 1);
        identity.alias_static(4, 20, 3, 2);
        assert_eq!(
            identity.tables[0].records[4],
            identity.tables[0].records[20]
        );
        assert_eq!(
            identity.tables[0].records[5],
            identity.tables[0].records[21]
        );
        assert_eq!(identity.tables[0].records[6], [0; 2]);
        assert_eq!(identity.tables[0].records[22][1], 0);
        assert!(!identity.quad_pages.contains_key(&22));
        assert_eq!(identity.quad_records.records[first as usize], [accepted, 3]);
        assert_eq!(identity.tables[0].records[9], neighbor);
        identity
            .static_quads(30, &[state(3, true); 2], false)
            .unwrap();
        assert_eq!(
            identity.quad_pages[&30].0, 1,
            "released quad suffix reuses storage without moving live records"
        );
        assert_eq!(identity.quad_records.records[first as usize], [accepted, 3]);
        assert_eq!(identity.tables[0].records[9], neighbor);
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
