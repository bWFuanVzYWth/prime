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
    #[cfg(test)]
    pub fn slot(&self, key: Cell) -> Option<(u32, u32, u32)> {
        self.entries
            .get(&key)
            .map(|e| (e.instance, e.first, e.count))
    }
    pub fn remove(&mut self, key: Cell) {
        if let Some(entry) = self.entries.remove(&key) {
            self.records.release(entry.first, entry.count);
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
        let entry = if let Some(entry) = self.entries.get_mut(&key) {
            if entry.count != count {
                let mut records = self.records.clone();
                records.release(entry.first, entry.count);
                let first = records.allocate(count)?;
                self.records = records;
                entry.first = first;
                entry.count = count;
            }
            entry
        } else {
            let first = self.records.allocate(count)?;
            let instance = match self.slots.allocate(1) {
                Ok(instance) => instance,
                Err(e) => {
                    self.records.release(first, count);
                    return Err(e);
                }
            };
            let entry = Entry {
                instance,
                first,
                count,
            };
            self.entries.entry(key).or_insert(entry)
        };
        self.bytes.resize(self.records.end as usize, [0; BYTES]);
        for (i, row) in rows.iter().enumerate() {
            let at = entry.first as usize + i;
            if self.bytes[at] != *row {
                self.bytes[at] = *row;
                self.dirty_records.push(at);
            }
        }
        instance.instance_custom_index_and_mask = vk::Packed24_8::new(entry.first, 0xff);
        self.instances
            .resize(self.slots.end as usize, empty_instance());
        self.instances[entry.instance as usize] = instance;
        self.dirty_instances.push(entry.instance as usize);
        Ok(())
    }
    /// Replace sampler fields on existing material rows without changing their TLAS instances.
    /// Each value is (first emitter, emitter buffer address, emitter format).
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
