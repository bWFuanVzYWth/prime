//! Stable static instance and material-directory slots. Only changed cells touch CPU records.
use crate::plan::{OBJECT_BIT, Slots};
use ash::vk;
use prime_scene::spatial::Cell;
use std::collections::BTreeMap;

pub(crate) const BYTES: usize = crate::surface::PAGE_BYTES;
fn empty_instance() -> vk::AccelerationStructureInstanceKHR {
    vk::AccelerationStructureInstanceKHR {
        transform: vk::TransformMatrixKHR { matrix: [0.; 12] },
        instance_custom_index_and_mask: vk::Packed24_8::new(0, 0),
        instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(0, 0),
        acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
            device_handle: 0,
        },
    }
}
struct Entry {
    instance: u32,
    first: u32,
    count: u32,
    capacity: u32,
    history: bool,
    aliases: Vec<(u32, u32, u32)>, // first, capacity, originally published count
}
pub(crate) struct StaticDirectory {
    entries: BTreeMap<Cell, Entry>,
    records: Slots,
    slots: Slots,
    pub bytes: Vec<[u8; BYTES]>,
    pub instances: Vec<vk::AccelerationStructureInstanceKHR>,
    pub dirty_records: Vec<usize>,
    pub dirty_instances: Vec<usize>,
}
impl Default for StaticDirectory {
    fn default() -> Self {
        let mut records = Slots::with_limit(OBJECT_BIT);
        records.allocate(1).expect("global light header");
        Self {
            entries: BTreeMap::new(),
            records,
            slots: Slots::default(),
            bytes: vec![[0; BYTES]],
            instances: Vec::new(),
            dirty_records: vec![0],
            dirty_instances: Vec::new(),
        }
    }
}
impl StaticDirectory {
    pub fn slot(&self, key: Cell) -> Option<(u32, u32, u32)> {
        self.entries
            .get(&key)
            .map(|e| (e.instance, e.first, e.count))
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub fn limit_history_capacity_for_test(&mut self, key: Cell, capacity: u32) {
        let entry = self.entries.get_mut(&key).expect("published test Cell");
        assert!(entry.history && entry.aliases.is_empty());
        assert!(capacity >= entry.count && capacity <= entry.capacity);
        // Only unused reservation is released. No accepted endpoint addresses
        // this tail; the next real row growth follows the production alias path.
        self.records
            .release(entry.first + capacity, entry.capacity - capacity);
        entry.capacity = capacity;
        self.bytes.truncate(self.records.end as usize);
    }

    pub fn remove(&mut self, key: Cell) {
        if let Some(entry) = self.entries.remove(&key) {
            self.records.release(entry.first, entry.capacity);
            for (first, capacity, _) in entry.aliases {
                self.records.release(first, capacity);
            }
            self.slots.release(entry.instance, 1);
            self.instances[entry.instance as usize] = empty_instance();
            self.dirty_instances.push(entry.instance as usize);
            self.instances.truncate(self.slots.end as usize);
            self.bytes.truncate(self.records.end as usize);
        }
    }
    pub fn publish(
        &mut self,
        key: Cell,
        rows: &[[u8; BYTES]],
        mut instance: vk::AccelerationStructureInstanceKHR,
    ) -> Result<(), String> {
        let count = u32::try_from(rows.len()).map_err(|_| "Static directory count overflow")?;
        if count == 0 {
            return Err("Empty static directory range".into());
        }
        let previous_count = self.entries.get(&key).map_or(0, |entry| entry.count);
        self.reserve(key, count, count)?;
        let entry = self.entries.get(&key).unwrap();
        self.bytes.resize(self.records.end as usize, [0; BYTES]);
        for (i, row) in rows.iter().enumerate() {
            let at = entry.first as usize + i;
            if self.bytes[at] != *row {
                self.bytes[at] = *row;
                self.dirty_records.push(at);
            }
        }
        for index in count..previous_count {
            let at = (entry.first + index) as usize;
            if self.bytes[at] != [0; BYTES] {
                self.bytes[at] = [0; BYTES];
                self.dirty_records.push(at);
            }
        }
        for &(first, _, count) in &entry.aliases {
            for index in 0..count as usize {
                let row = rows.get(index).copied().unwrap_or([0; BYTES]);
                let at = first as usize + index;
                if self.bytes[at] != row {
                    self.bytes[at] = row;
                    self.dirty_records.push(at);
                }
            }
        }
        instance.instance_custom_index_and_mask = vk::Packed24_8::new(entry.first, 0xff);
        self.instances[entry.instance as usize] = instance;
        self.dirty_instances.push(entry.instance as usize);
        Ok(())
    }

    // Keep the custom-index base stable while historical rows append. Ordinary
    // PT requests exactly count; ReSTIR reserves unused CPU/GPU directory rows.
    pub fn reserve(&mut self, key: Cell, count: u32, capacity: u32) -> Result<u32, String> {
        self.reserve_impl(key, count, capacity, false)
    }
    pub fn reserve_stable(&mut self, key: Cell, count: u32, capacity: u32) -> Result<u32, String> {
        self.reserve_impl(key, count, capacity, true)
    }
    fn reserve_impl(
        &mut self,
        key: Cell,
        count: u32,
        capacity: u32,
        history: bool,
    ) -> Result<u32, String> {
        if count == 0 {
            return Err("Empty static directory range".into());
        }
        let capacity = capacity.max(count);
        let entry = if let Some(entry) = self.entries.get_mut(&key) {
            entry.history |= history;
            if entry.capacity < count {
                let capacity = if entry.history {
                    capacity.max(
                        entry
                            .capacity
                            .checked_mul(2)
                            .ok_or("Static history directory capacity overflow")?,
                    )
                } else {
                    capacity
                };
                let mut records = self.records.clone();
                if !entry.history {
                    records.release(entry.first, entry.capacity);
                }
                let first = records.allocate(capacity)?;
                if entry.history {
                    entry
                        .aliases
                        .push((entry.first, entry.capacity, entry.count));
                }
                self.records = records;
                entry.first = first;
                entry.capacity = capacity;
            }
            for index in count..entry.count {
                let at = (entry.first + index) as usize;
                if self.bytes[at] != [0; BYTES] {
                    self.bytes[at] = [0; BYTES];
                    self.dirty_records.push(at);
                }
            }
            entry.count = count;
            entry
        } else {
            let first = self.records.allocate(capacity)?;
            let instance = match self.slots.allocate(1) {
                Ok(instance) => instance,
                Err(e) => {
                    self.records.release(first, capacity);
                    return Err(e);
                }
            };
            let entry = Entry {
                instance,
                first,
                count,
                capacity,
                history,
                aliases: Vec::new(),
            };
            self.entries.entry(key).or_insert(entry)
        };
        self.bytes.resize(self.records.end as usize, [0; BYTES]);
        self.instances
            .resize(self.slots.end as usize, empty_instance());
        Ok(entry.first)
    }
    pub fn history_ranges(&self, key: Cell) -> Vec<(u32, u32)> {
        self.entries.get(&key).map_or_else(Vec::new, |entry| {
            std::iter::once((entry.first, entry.count))
                .chain(
                    entry
                        .aliases
                        .iter()
                        .map(|&(first, _, count)| (first, count)),
                )
                .collect()
        })
    }
    pub fn aliases(&self, key: Cell) -> impl Iterator<Item = (u32, u32)> + '_ {
        self.entries.get(&key).into_iter().flat_map(|entry| {
            entry
                .aliases
                .iter()
                .map(|&(first, _, count)| (first, count))
        })
    }
    /// Replace sampler fields on existing material rows without changing their TLAS instances.
    /// Each value is (first emitter, emitter buffer address, emitter format).
    #[cfg(test)]
    pub fn light_fields(&mut self, key: Cell, values: &[(u32, u64, u32)]) -> Result<(), String> {
        let entry = self
            .entries
            .get(&key)
            .ok_or("Unpublished static light cell")?;
        if values.len() != entry.count as usize {
            return Err("Static light row count mismatch".into());
        }
        for (i, &(first, address, format)) in values.iter().enumerate() {
            let at = entry.first as usize + i;
            let row = &mut self.bytes[at];
            let first = first.to_le_bytes();
            let address = address.to_le_bytes();
            let format = format.to_le_bytes();
            if row[16..20] != first || row[24..32] != address || row[44..48] != format {
                row[16..20].copy_from_slice(&first);
                row[24..32].copy_from_slice(&address);
                row[44..48].copy_from_slice(&format);
                self.dirty_records.push(at);
            }
        }
        for &(first, _, count) in &entry.aliases {
            for index in 0..count.min(entry.count) as usize {
                let row = self.bytes[entry.first as usize + index];
                let at = first as usize + index;
                if self.bytes[at] != row {
                    self.bytes[at] = row;
                    self.dirty_records.push(at);
                }
            }
        }
        Ok(())
    }
    pub fn light_header(&mut self, count: u32, address: u64) {
        let row = &mut self.bytes[0];
        if row[12..16] != count.to_le_bytes() || row[32..40] != address.to_le_bytes() {
            row[12..16].copy_from_slice(&count.to_le_bytes());
            row[32..40].copy_from_slice(&address.to_le_bytes());
            self.dirty_records.push(0);
        }
    }
    pub fn acceleration(&mut self, key: Cell, address: u64) {
        let entry = self.entries.get(&key).expect("published static BLAS");
        self.instances[entry.instance as usize].acceleration_structure_reference =
            vk::AccelerationStructureReferenceKHR {
                device_handle: address,
            };
        self.dirty_instances.push(entry.instance as usize);
    }
    pub fn finish_upload(&mut self) {
        self.dirty_records.clear();
        self.dirty_instances.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn cell(x: f64) -> Cell {
        Cell::containing([x, 0., 0.]).unwrap()
    }
    #[test]
    fn shrinking_a_stable_tail_clears_current_and_alias_rows_without_moving_prefix() {
        let mut directory = StaticDirectory::default();
        let key = cell(0.);
        directory.reserve_stable(key, 2, 2).unwrap();
        directory
            .publish(key, &[[1; BYTES], [2; BYTES]], empty_instance())
            .unwrap();
        let alias = directory.slot(key).unwrap().1;
        directory
            .publish(cell(64.), &[[9; BYTES]], empty_instance())
            .unwrap();
        directory.reserve_stable(key, 3, 3).unwrap();
        directory
            .publish(key, &[[3; BYTES], [4; BYTES], [5; BYTES]], empty_instance())
            .unwrap();
        let first = directory.slot(key).unwrap().1;
        directory.reserve_stable(key, 1, 12).unwrap();
        directory
            .publish(key, &[[6; BYTES]], empty_instance())
            .unwrap();
        assert_eq!(directory.slot(key).unwrap().1, first);
        assert_eq!(directory.bytes[first as usize], [6; BYTES]);
        assert_eq!(directory.bytes[alias as usize], [6; BYTES]);
        assert_eq!(directory.bytes[first as usize + 1], [0; BYTES]);
        assert_eq!(directory.bytes[first as usize + 2], [0; BYTES]);
        assert_eq!(directory.bytes[alias as usize + 1], [0; BYTES]);
        assert_eq!(directory.slot(cell(64.)).unwrap().1, 3);
    }
    #[test]
    fn stable_directory_growth_keeps_old_prefix_aliases_until_cell_unload() {
        let mut directory = StaticDirectory::default();
        let key = cell(0.);
        directory.reserve_stable(key, 1, 1).unwrap();
        directory
            .publish(key, &[[1; BYTES]], empty_instance())
            .unwrap();
        let original = directory.slot(key).unwrap().1;
        directory
            .publish(cell(64.), &[[9; BYTES]], empty_instance())
            .unwrap();
        directory.reserve_stable(key, 2, 2).unwrap();
        directory
            .publish(key, &[[2; BYTES], [3; BYTES]], empty_instance())
            .unwrap();
        let middle = directory.slot(key).unwrap().1;
        assert_ne!(original, middle);
        assert_eq!(
            directory.bytes[original as usize],
            directory.bytes[middle as usize]
        );
        directory.reserve_stable(key, 3, 3).unwrap();
        directory
            .publish(key, &[[4; BYTES], [5; BYTES], [6; BYTES]], empty_instance())
            .unwrap();
        let current = directory.slot(key).unwrap().1;
        assert_eq!(
            directory.entries[&key].capacity, 4,
            "growth must be geometric"
        );
        assert_eq!(
            directory.aliases(key).collect::<Vec<_>>(),
            [(original, 1), (middle, 2)]
        );
        assert_eq!(
            directory.bytes[original as usize],
            directory.bytes[current as usize]
        );
        assert_eq!(
            directory.bytes[middle as usize + 1],
            directory.bytes[current as usize + 1]
        );
        directory
            .light_fields(key, &[(7, 100, 1), (8, 200, 2), (9, 300, 3)])
            .unwrap();
        assert_eq!(
            directory.bytes[original as usize],
            directory.bytes[current as usize]
        );
        assert_eq!(
            directory.bytes[middle as usize + 1],
            directory.bytes[current as usize + 1]
        );
        let ranges = directory.history_ranges(key);
        assert_eq!(ranges.len(), 3);
        directory.remove(key);
        assert!(directory.history_ranges(key).is_empty());
        directory
            .publish(cell(128.), &[[8; BYTES]], empty_instance())
            .unwrap();
        assert_eq!(
            directory.slot(cell(128.)).unwrap().1,
            original,
            "aliases release only after their Cell identity is withdrawn"
        );
    }
    #[test]
    fn local_edits_retain_unaffected_slots_and_removals_reuse_holes() {
        let mut directory = StaticDirectory::default();
        for x in [0., 64., 128.] {
            directory
                .publish(cell(x), &[[x as u8; BYTES]], empty_instance())
                .unwrap();
        }
        let stable = directory.instances[2]
            .instance_custom_index_and_mask
            .low_24();
        directory.finish_upload();
        directory
            .publish(cell(64.), &[[7; BYTES]], empty_instance())
            .unwrap();
        assert_eq!(directory.dirty_records, [2]);
        assert_eq!(directory.dirty_instances, [1]);
        assert_eq!(
            directory.instances[2]
                .instance_custom_index_and_mask
                .low_24(),
            stable
        );
        directory.remove(cell(64.));
        assert_eq!(
            unsafe {
                directory.instances[1]
                    .acceleration_structure_reference
                    .device_handle
            },
            0
        );
        directory
            .publish(cell(192.), &[[9; BYTES]], empty_instance())
            .unwrap();
        assert_eq!(directory.instances.len(), 3);
        assert_eq!(
            directory.instances[1]
                .instance_custom_index_and_mask
                .low_24(),
            2
        );
    }
    #[test]
    fn sampler_changes_only_patch_light_rows_and_preserve_instances_and_slots() {
        let mut directory = StaticDirectory::default();
        let mut instance = empty_instance();
        instance.transform.matrix = [1., 0., 0., 3., 0., 1., 0., 4., 0., 0., 1., 5.];
        instance.instance_shader_binding_table_record_offset_and_flags = vk::Packed24_8::new(7, 3);
        instance.acceleration_structure_reference = vk::AccelerationStructureReferenceKHR {
            device_handle: 0x1234,
        };
        directory
            .publish(cell(0.), &[[0x5a; BYTES], [0x6b; BYTES]], instance)
            .unwrap();
        directory
            .publish(cell(64.), &[[8; BYTES]], empty_instance())
            .unwrap();
        directory.finish_upload();
        let slot = directory.slot(cell(0.));
        let ends = (directory.records.end, directory.slots.end);
        let before = directory.bytes.clone();
        let identity = |value: &vk::AccelerationStructureInstanceKHR| {
            (
                value.transform.matrix.map(f32::to_bits),
                value.instance_custom_index_and_mask.low_24(),
                value.instance_custom_index_and_mask.high_8(),
                value
                    .instance_shader_binding_table_record_offset_and_flags
                    .low_24(),
                value
                    .instance_shader_binding_table_record_offset_and_flags
                    .high_8(),
                unsafe { value.acceleration_structure_reference.device_handle },
            )
        };
        let instances: Vec<_> = directory.instances.iter().map(identity).collect();
        let values = [(17, 0xabcdef, 2), (0, 0, 1)];
        directory.light_fields(cell(0.), &values).unwrap();
        let mut expected = before;
        for (i, &(first, address, format)) in values.iter().enumerate() {
            expected[1 + i][16..20].copy_from_slice(&first.to_le_bytes());
            expected[1 + i][24..32].copy_from_slice(&address.to_le_bytes());
            expected[1 + i][44..48].copy_from_slice(&format.to_le_bytes());
        }
        assert_eq!(directory.bytes, expected);
        assert_eq!(directory.dirty_records, [1, 2]);
        assert!(directory.dirty_instances.is_empty());
        assert_eq!(directory.slot(cell(0.)), slot);
        assert_eq!((directory.records.end, directory.slots.end), ends);
        assert_eq!(
            directory.instances.iter().map(identity).collect::<Vec<_>>(),
            instances
        );

        directory.finish_upload();
        directory.light_fields(cell(0.), &values).unwrap();
        assert!(directory.dirty_records.is_empty());
        assert!(directory.dirty_instances.is_empty());
        assert!(directory.light_fields(cell(0.), &values[..1]).is_err());
        assert!(directory.light_fields(cell(128.), &values).is_err());
        assert_eq!(directory.bytes, expected);
        assert_eq!(directory.slot(cell(0.)), slot);
        assert_eq!((directory.records.end, directory.slots.end), ends);
        assert_eq!(
            directory.instances.iter().map(identity).collect::<Vec<_>>(),
            instances
        );
        assert!(directory.dirty_records.is_empty());
        assert!(directory.dirty_instances.is_empty());
    }
    #[test]
    fn resize_and_late_allocation_failure_preserve_existing_directory() {
        let mut directory = StaticDirectory {
            records: Slots::with_limit(5),
            ..Default::default()
        };
        directory.records.allocate(1).unwrap();
        directory
            .publish(cell(0.), &[[1; BYTES]; 2], empty_instance())
            .unwrap();
        directory
            .publish(cell(64.), &[[2; BYTES]; 2], empty_instance())
            .unwrap();
        directory.finish_upload();
        let before = directory.bytes.clone();
        assert!(
            directory
                .publish(cell(0.), &[[3; BYTES]; 3], empty_instance())
                .is_err()
        );
        assert_eq!(directory.bytes, before);
        assert_eq!(directory.entries[&cell(0.)].first, 1);
        assert_eq!(directory.entries[&cell(0.)].count, 2);
        assert_eq!(directory.records.end, 5);
        assert!(directory.dirty_records.is_empty());
        assert!(directory.dirty_instances.is_empty());
        directory.remove(cell(64.));
        directory
            .publish(cell(0.), &[[3; BYTES]; 3], empty_instance())
            .unwrap();
        assert_eq!(directory.entries[&cell(0.)].first, 1);
        assert_eq!(directory.instances.len(), 1);
        assert_eq!(directory.records.end, 4);
        assert_eq!(&directory.bytes[1..4], &[[3; BYTES]; 3]);
    }
    #[test]
    fn new_entry_rolls_back_record_range_when_instance_limit_fails() {
        let mut directory = StaticDirectory {
            slots: Slots::with_limit(1),
            ..Default::default()
        };
        directory
            .publish(cell(0.), &[[1; BYTES]], empty_instance())
            .unwrap();
        directory.finish_upload();
        assert!(
            directory
                .publish(cell(64.), &[[2; BYTES]; 2], empty_instance())
                .is_err()
        );
        assert_eq!(directory.records.end, 2);
        assert_eq!(directory.entries.len(), 1);
        assert_eq!(directory.bytes.len(), 2);
        assert!(directory.dirty_records.is_empty());
        directory.remove(cell(0.));
        assert!(directory.instances.is_empty());
        assert_eq!(directory.bytes.len(), 1);
        directory
            .publish(cell(64.), &[[2; BYTES]; 2], empty_instance())
            .unwrap();
        assert_eq!(directory.instances.len(), 1);
        assert_eq!(directory.entries[&cell(64.)].first, 1);
    }
}
