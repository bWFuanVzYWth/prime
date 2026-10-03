//! GPU arena ranges and shader record packing. Spatial planning lives in prime_scene.
use crate::{float4, uint};
pub(crate) use prime_scene::translation::{ObjectKey, Placement, Planner, ScenePlan};
use std::collections::BTreeMap;

pub(crate) const OBJECT_BIT: u32 = 0x0080_0000;
pub(crate) const INHERIT: u32 = u32::MAX;
pub(crate) const MATERIAL_BYTES: u64 = 96;

#[cfg(test)]
pub(crate) fn translation([x, y, z]: [f32; 3]) -> [f32; 12] {
    [1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z]
}

// SPIR-V permits 32-bit byte offsets within one physical buffer. Keep each pointed
// primitive range within 4 GiB; total scene size spans any number of arena pages.
pub(crate) const MAX_MATERIAL_RECORDS: u32 = crate::packing::MAX_RECORDS;
pub(crate) fn validate_material_count(count: u32) -> Result<(), String> {
    if count == 0 || count > MAX_MATERIAL_RECORDS {
        return Err(format!(
            "Single BLAS material range requires {count} records; maximum is {MAX_MATERIAL_RECORDS}. Split this resource into multiple BLAS."
        ));
    }
    Ok(())
}

/// Geometric growth must stay within the caller's local resource limit.
pub(crate) fn arena_capacity(required: u32, limit: u32) -> Result<u32, String> {
    if required == 0 || required > limit {
        return Err(format!(
            "Triangle range requires {required} records, local limit is {limit}"
        ));
    }
    Ok(required
        .checked_next_power_of_two()
        .unwrap_or(limit)
        .min(limit))
}

#[derive(Clone)]
pub(crate) struct Slots {
    pub(crate) end: u32,
    free: BTreeMap<u32, u32>,
    limit: u32,
}
impl Default for Slots {
    fn default() -> Self {
        Self::with_limit(u32::MAX)
    }
}
impl Slots {
    pub(crate) fn with_limit(limit: u32) -> Self {
        Self {
            end: 0,
            free: BTreeMap::new(),
            limit,
        }
    }
    pub(crate) fn limit_value(&self) -> u32 {
        self.limit
    }
    #[cfg(test)]
    pub(crate) fn limit(&self) -> u32 {
        self.limit
    }
    pub(crate) fn allocate(&mut self, count: u32) -> Result<u32, String> {
        if let Some((first, available)) = self
            .free
            .iter()
            .find(|(_, n)| **n >= count)
            .map(|(&p, &n)| (p, n))
        {
            self.free.remove(&first);
            if available > count {
                self.free.insert(first + count, available - count);
            }
            return Ok(first);
        }
        let first = self.end;
        let end = first
            .checked_add(count)
            .filter(|end| *end <= self.limit)
            .ok_or_else(|| self.allocation_error(count))?;
        self.end = end;
        Ok(first)
    }
    fn allocation_error(&self, count: u32) -> String {
        let holes = self.free.values().map(|&size| u64::from(size)).sum::<u64>();
        let live = u64::from(self.end) - holes;
        let free = u64::from(self.limit) - live;
        format!(
            "Triangle arena has no contiguous range: end={} request={count} free={free} live={live} limit={} holes={holes}",
            self.end, self.limit
        )
    }
    pub(crate) fn release(&mut self, mut first: u32, mut count: u32) {
        if let Some((&previous, &length)) = self.free.range(..first).next_back()
            && previous + length == first
        {
            self.free.remove(&previous);
            first = previous;
            count += length;
        }
        if let Some(length) = self.free.remove(&(first + count)) {
            count += length;
        }
        if first + count == self.end {
            self.end = first;
        } else {
            self.free.insert(first, count);
        }
    }
}

pub(crate) fn pack_material(
    bytes: &mut Vec<u8>,
    address: u64,
    texture: u32,
    placement: &Placement,
    previous: Option<[f32; 12]>,
) {
    bytes.extend_from_slice(&address.to_le_bytes());
    uint(bytes, texture);
    uint(bytes, placement.flags);
    uint(bytes, u32::from_le_bytes(placement.tint));
    uint(bytes, u32::from(previous.is_some()));
    bytes.extend_from_slice(&[0; 8]);
    float4(bytes, placement.uv);
    for row in previous.unwrap_or([0.0; 12]).as_chunks::<4>().0 {
        float4(bytes, *row);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placement_metadata_abi_is_independent_of_cpu_spatial_membership() {
        let placement = Placement {
            key: ObjectKey::Prototype(7),
            transform: translation([0.0; 3]),
            texture_id: INHERIT,
            flags: 2,
            tint: [1, 2, 3, 4],
            uv: [0.5, 0.25, 0.1, 0.2],
        };
        let mut bytes = Vec::new();
        pack_material(&mut bytes, 0x1234567890abcdef, 42, &placement, None);
        assert_eq!(bytes.len(), MATERIAL_BYTES as usize);
        assert_eq!(
            u64::from_le_bytes(bytes[0..8].try_into().unwrap()),
            0x1234567890abcdef
        );
        assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 42);
        assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 2);
        assert_eq!(&bytes[16..20], &[1, 2, 3, 4]);
        assert_eq!(&bytes[20..32], &[0; 12]);
        assert_eq!(f32::from_le_bytes(bytes[32..36].try_into().unwrap()), 0.5);
        assert!(bytes[48..].iter().all(|&v| v == 0));
        bytes.clear();
        let previous = translation([1.0, 2.0, 3.0]);
        pack_material(&mut bytes, 0, 42, &placement, Some(previous));
        assert_eq!(u32::from_le_bytes(bytes[20..24].try_into().unwrap()), 1);
        for (word, expected) in bytes[48..].as_chunks::<4>().0.iter().zip(previous) {
            assert_eq!(f32::from_le_bytes(*word), expected);
        }
    }

    #[test]
    fn pointed_resource_byte_offsets_and_growth_remain_locally_bounded() {
        assert!(validate_material_count(0).is_err());
        assert!(validate_material_count(MAX_MATERIAL_RECORDS).is_ok());
        assert!(validate_material_count(MAX_MATERIAL_RECORDS + 1).is_err());
        assert!(validate_material_count(u32::MAX).is_err());
        for format in 0..crate::packing::FORMATS {
            let size = crate::packing::stride(format) as u64;
            assert!(u64::from(MAX_MATERIAL_RECORDS) * size <= (1_u64 << 32));
        }
        assert!(u64::from(MAX_MATERIAL_RECORDS + 1) * 432 > (1_u64 << 32));
        assert_eq!(arena_capacity(17, 23).unwrap(), 23);
        assert!(arena_capacity(24, 23).is_err());
    }

    #[test]
    fn slots_fragmentation_is_distinct_from_total_capacity_and_failure_preserves_ranges() {
        let mut slots = Slots::with_limit(8);
        assert_eq!(slots.limit(), 8);
        let a = slots.allocate(2).unwrap();
        let b = slots.allocate(2).unwrap();
        let c = slots.allocate(2).unwrap();
        assert_eq!([a, b, c], [0, 2, 4]);
        slots.release(b, 2);
        assert_eq!(slots.free, BTreeMap::from([(2, 2)]));
        let before = slots.free.clone();
        let error = slots.allocate(3).unwrap_err();
        assert_eq!(
            error,
            "Triangle arena has no contiguous range: end=6 request=3 free=4 live=4 limit=8 holes=2"
        );
        assert_eq!(slots.end, 6);
        assert_eq!(slots.free, before);
        assert_eq!(slots.allocate(2).unwrap(), b);
        let d = slots.allocate(2).unwrap();
        assert_eq!(d, 6);
        assert!(slots.allocate(1).is_err());
        // Release only complete live allocations; every free range remains disjoint.
        slots.release(a, 2);
        slots.release(c, 2);
        assert_eq!(slots.free, BTreeMap::from([(0, 2), (4, 2)]));
        slots.release(b, 2);
        assert_eq!(slots.free, BTreeMap::from([(0, 6)]));
        slots.release(d, 2);
        assert_eq!(slots.end, 0);
        assert!(slots.free.is_empty());
        assert_eq!(slots.allocate(8).unwrap(), 0);
    }

    #[test]
    fn explicitly_bounded_slots_coalesce_at_the_complete_boundary() {
        let mut slots = Slots::with_limit(OBJECT_BIT);
        assert_eq!(slots.limit(), OBJECT_BIT);
        let body = slots.allocate(OBJECT_BIT - 2).unwrap();
        let tail = slots.allocate(2).unwrap();
        assert_eq!(body, 0);
        assert_eq!(tail, OBJECT_BIT - 2);
        assert!(slots.allocate(1).is_err());
        // Even overflowing requests report the allocator state and do not mutate it.
        let error = slots.allocate(u32::MAX).unwrap_err();
        assert!(error.contains("request=4294967295 free=0 live=8388608 limit=8388608"));
        assert_eq!(slots.end, OBJECT_BIT);
        slots.release(tail, 2);
        assert_eq!(slots.allocate(2).unwrap(), tail);
        slots.release(body, OBJECT_BIT - 2);
        slots.release(tail, 2);
        assert_eq!(slots.end, 0);
        assert!(slots.free.is_empty());
    }
}
