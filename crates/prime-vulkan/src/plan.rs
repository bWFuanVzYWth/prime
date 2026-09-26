//! CPU scene planning. No Vulkan handles, uploads, submissions or device access.
use crate::{float, float4, uint};
use prime_scene::scene::{Instance, InstanceScene, Scene, Triangle};
use std::{collections::BTreeMap, sync::Arc};

pub(crate) const OBJECT_BIT: u32 = 0x0080_0000;
pub(crate) const INHERIT: u32 = u32::MAX;
const RAW_CELL: f64 = 16.0;

// SPIR-V permits 32-bit byte offsets within one physical buffer. Keep each pointed
// primitive range within 4 GiB; total scene size spans any number of arena pages.
pub(crate) const MAX_MATERIAL_RECORDS: u32 = 1 << 25;
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

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum ObjectKey {
    Prototype(u64),
    Raw([i32; 3], u32),
}

pub(crate) struct GeometryUpdate {
    pub key: ObjectKey,
    source: GeometrySource,
}

enum GeometrySource {
    Prototype(Arc<[Triangle]>),
    // The planner owns the bucket until synchronous execution has packed it.
    Raw,
}

#[derive(Default)]
struct RawBucket {
    triangles: Vec<Triangle>,
    cursor: usize,
    changed: bool,
}

pub(crate) struct Placement {
    pub key: ObjectKey,
    pub transform: [f32; 12],
    pub texture_id: u32,
    pub flags: u32,
    pub tint: [u8; 4],
    pub uv: [f32; 4],
}

pub(crate) struct ScenePlan {
    pub removed: Vec<ObjectKey>,
    pub geometry: Vec<GeometryUpdate>,
    pub placements_changed: bool,
    pub triangle_count: u64,
}

#[derive(Default)]
pub(crate) struct Planner {
    epoch: Option<u64>,
    resource_revision: Option<u64>,
    instance_revision: Option<u64>,
    dynamic_revision: Option<u64>,
    dynamic_origin: [f64; 3],
    anchor: [f64; 3],
    prototypes: BTreeMap<u64, u64>,
    raw: BTreeMap<ObjectKey, RawBucket>,
    placements: Vec<Placement>,
    prototype_placements: usize,
    prototype_triangles: u64,
    triangle_count: u64,
    spare_geometry: Vec<GeometryUpdate>,
    spare_removed: Vec<ObjectKey>,
}

impl Planner {
    /// Execution must consume this plan before planning another frame. Like GPU
    /// execution, a late error can leave private caches changed: the enclosing
    /// renderer remains poisoned and must be discarded, never retried in place.
    pub fn plan(&mut self, scene: &Scene, objects: &InstanceScene) -> Result<ScenePlan, String> {
        if objects.epoch != scene.epoch
            && (!objects.prototypes.is_empty() || !objects.instances.is_empty())
        {
            return Err("Instance scene epoch does not match the terrain scene".into());
        }
        let reset = self.epoch != Some(scene.epoch);
        let resources = reset || self.resource_revision != Some(objects.resource_revision);
        let origin = std::array::from_fn(|i| scene.anchor[i] + f64::from(scene.dynamic.origin[i]));
        let raw_changed = reset
            || self.dynamic_revision != Some(scene.dynamic.revision)
            || self.dynamic_origin != origin;
        let instances = reset
            || resources
            || self.instance_revision != Some(objects.instance_revision)
            || self.anchor != scene.anchor;
        let mut result = ScenePlan {
            removed: std::mem::take(&mut self.spare_removed),
            geometry: std::mem::take(&mut self.spare_geometry),
            placements_changed: false,
            triangle_count: self.triangle_count,
        };
        if reset {
            result
                .removed
                .extend(self.prototypes.keys().map(|id| ObjectKey::Prototype(*id)));
            result.removed.extend(self.raw.keys().copied());
            self.prototypes.clear();
            self.raw.clear();
        }
        if resources {
            self.prototypes.retain(|id, _| {
                if objects.prototypes.contains_key(id) {
                    true
                } else {
                    result.removed.push(ObjectKey::Prototype(*id));
                    false
                }
            });
            for (&id, prototype) in &objects.prototypes {
                if self.prototypes.get(&id) != Some(&prototype.revision) {
                    if prototype.triangles.is_empty() {
                        return Err("Empty prototype".into());
                    }
                    result.geometry.push(GeometryUpdate {
                        key: ObjectKey::Prototype(id),
                        source: GeometrySource::Prototype(prototype.triangles.clone()),
                    });
                    self.prototypes.insert(id, prototype.revision);
                }
            }
        }
        if raw_changed {
            update_raw_buckets(scene, &mut self.raw, &mut result)?;
        }
        let raw_geometry_changed = result
            .geometry
            .iter()
            .any(|item| matches!(item.key, ObjectKey::Raw(..)))
            || result
                .removed
                .iter()
                .any(|key| matches!(key, ObjectKey::Raw(..)));
        if instances {
            self.placements.clear();
            self.placements
                .reserve(objects.instances.len() + self.raw.len());
            let mut count = 0u64;
            for instance in objects.instances.values() {
                let prototype = objects
                    .prototypes
                    .get(&instance.prototype_id)
                    .ok_or("Instance references missing prototype")?;
                let transform = relative_transform(instance, scene.anchor)?;
                validate_transformed_bounds(transform, prototype.bounds)?;
                count = count
                    .checked_add(prototype.triangles.len() as u64)
                    .ok_or("Instanced triangle count overflow")?;
                self.placements.push(Placement {
                    key: ObjectKey::Prototype(instance.prototype_id),
                    transform,
                    texture_id: instance.texture_id,
                    flags: instance.flags,
                    tint: instance.tint,
                    uv: instance.uv_transform,
                });
            }
            self.prototype_placements = self.placements.len();
            self.prototype_triangles = count;
        }
        if instances || raw_geometry_changed {
            // Raw-only changes retain the already validated prototype prefix.
            // Do not repeat affine/bounds work for unrelated persistent models.
            self.placements.truncate(self.prototype_placements);
            self.placements.reserve(self.raw.len());
            for key in self.raw.keys() {
                let ObjectKey::Raw(cell, _) = key else {
                    unreachable!()
                };
                let origin = std::array::from_fn(|i| {
                    (f64::from(cell[i]) * RAW_CELL - scene.anchor[i]) as f32
                });
                self.placements.push(Placement {
                    key: *key,
                    transform: translation(origin),
                    texture_id: INHERIT,
                    flags: INHERIT,
                    tint: [255; 4],
                    uv: [1.0, 1.0, 0.0, 0.0],
                });
            }
            if self.placements.len() >= OBJECT_BIT as usize {
                return Err("Too many object instances".into());
            }
            result.triangle_count = self
                .prototype_triangles
                .checked_add(scene.dynamic.triangles.len() as u64)
                .ok_or("Instanced triangle count overflow")?;
            result.placements_changed = true;
        }
        self.epoch = Some(scene.epoch);
        self.resource_revision = Some(objects.resource_revision);
        self.instance_revision = Some(objects.instance_revision);
        self.dynamic_revision = Some(scene.dynamic.revision);
        self.dynamic_origin = origin;
        self.anchor = scene.anchor;
        self.triangle_count = result.triangle_count;
        Ok(result)
    }

    pub fn triangles<'a>(&'a self, update: &'a GeometryUpdate) -> &'a [Triangle] {
        match &update.source {
            GeometrySource::Prototype(triangles) => triangles,
            GeometrySource::Raw => &self.raw[&update.key].triangles,
        }
    }

    pub fn placements(&self) -> &[Placement] {
        &self.placements
    }

    /// Return owned CPU scratch after execution; the next delta reuses capacity.
    /// No GPU completion is needed: command recording has consumed the plan.
    pub fn recycle(&mut self, mut plan: ScenePlan) {
        plan.geometry.clear();
        plan.removed.clear();
        self.spare_geometry = plan.geometry;
        self.spare_removed = plan.removed;
    }
}

pub(crate) fn translation([x, y, z]: [f32; 3]) -> [f32; 12] {
    [1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z]
}

/// Slang Triangle ABI: position float4 x3, encoded tint float4 x3, UV x3,
/// resolved texture index and source flags. The buffer is owned CPU scratch.
pub(crate) fn pack_triangle(bytes: &mut Vec<u8>, triangle: &Triangle, texture: u32) {
    for [x, y, z] in triangle.positions {
        float4(bytes, [x, y, z, 0.0]);
    }
    for color in triangle.colors {
        float4(bytes, color);
    }
    for uv in triangle.uvs {
        for value in uv {
            float(bytes, value);
        }
    }
    uint(bytes, texture);
    uint(bytes, triangle.flags);
}

pub(crate) fn pack_material(
    bytes: &mut Vec<u8>,
    address: u64,
    texture: u32,
    placement: &Placement,
) {
    bytes.extend_from_slice(&address.to_le_bytes());
    uint(bytes, texture);
    uint(bytes, placement.flags);
    uint(bytes, u32::from_le_bytes(placement.tint));
    bytes.extend_from_slice(&[0; 12]);
    float4(bytes, placement.uv);
}

pub(crate) fn relative_transform(
    instance: &Instance,
    anchor: [f64; 3],
) -> Result<[f32; 12], String> {
    let mut matrix = instance.transform;
    for row in 0..3 {
        let value = instance.origin[row] - anchor[row] + f64::from(matrix[row * 4 + 3]);
        if !value.is_finite() || value.abs() > 1_048_576.0 {
            return Err("Instance exceeds camera-relative coordinate range".into());
        }
        matrix[row * 4 + 3] = value as f32;
    }
    Ok(matrix)
}

fn validate_transformed_bounds(matrix: [f32; 12], bounds: [[f32; 3]; 2]) -> Result<(), String> {
    for corner in 0..8 {
        let point: [f64; 3] = std::array::from_fn(|i| f64::from(bounds[(corner >> i) & 1][i]));
        for row in 0..3 {
            let value = (0..3)
                .map(|i| f64::from(matrix[row * 4 + i]) * point[i])
                .sum::<f64>()
                + f64::from(matrix[row * 4 + 3]);
            if !value.is_finite() || value.abs() > 1_048_576.0 {
                return Err(
                    "Transformed prototype exceeds camera-relative coordinate range".into(),
                );
            }
        }
    }
    Ok(())
}

// Each real triangle belongs to exactly one centroid cell. We do not clip or
// duplicate source triangles; a single long triangle can extend outside its cell.
fn update_raw_buckets(
    scene: &Scene,
    buckets: &mut BTreeMap<ObjectKey, RawBucket>,
    plan: &mut ScenePlan,
) -> Result<(), String> {
    let origin: [f64; 3] =
        std::array::from_fn(|i| scene.anchor[i] + f64::from(scene.dynamic.origin[i]));
    for bucket in buckets.values_mut() {
        bucket.cursor = 0;
        bucket.changed = false;
    }
    for triangle in scene.dynamic.triangles.iter() {
        let center: [f64; 3] = std::array::from_fn(|i| {
            origin[i]
                + triangle
                    .positions
                    .iter()
                    .map(|p| f64::from(p[i]))
                    .sum::<f64>()
                    / 3.0
        });
        if center
            .iter()
            .any(|v| !v.is_finite() || v.abs() > 33_000_000.0)
            || triangle.flags > 2
        {
            return Err("Invalid raw dynamic geometry".into());
        }
        let cell = center.map(|v| (v / RAW_CELL).floor() as i32);
        let mut local = *triangle;
        for position in &mut local.positions {
            for i in 0..3 {
                position[i] =
                    (origin[i] - f64::from(cell[i]) * RAW_CELL + f64::from(position[i])) as f32;
            }
        }
        let bucket = buckets
            .entry(ObjectKey::Raw(cell, triangle.flags))
            .or_default();
        if let Some(previous) = bucket.triangles.get_mut(bucket.cursor) {
            if !same_triangle(previous, &local) {
                *previous = local;
                bucket.changed = true;
            }
        } else {
            bucket.triangles.push(local);
            bucket.changed = true;
        }
        bucket.cursor += 1;
    }
    buckets.retain(|key, bucket| {
        if bucket.cursor == 0 {
            // Transient particle cells have no permanent scratch/history entry.
            plan.removed.push(*key);
            return false;
        }
        if bucket.cursor != bucket.triangles.len() {
            bucket.triangles.truncate(bucket.cursor);
            bucket.changed = true;
        }
        if bucket.changed {
            plan.geometry.push(GeometryUpdate {
                key: *key,
                source: GeometrySource::Raw,
            });
        }
        true
    });
    Ok(())
}

fn same_triangle(a: &Triangle, b: &Triangle) -> bool {
    a.texture_id == b.texture_id
        && a.flags == b.flags
        && a.positions
            .iter()
            .flatten()
            .chain(a.colors.iter().flatten())
            .chain(a.uvs.iter().flatten())
            .zip(
                b.positions
                    .iter()
                    .flatten()
                    .chain(b.colors.iter().flatten())
                    .chain(b.uvs.iter().flatten()),
            )
            .all(|(a, b)| a.to_bits() == b.to_bits())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pointed_resource_byte_offsets_and_growth_remain_locally_bounded() {
        assert!(validate_material_count(0).is_err());
        assert!(validate_material_count(MAX_MATERIAL_RECORDS).is_ok());
        assert!(validate_material_count(MAX_MATERIAL_RECORDS + 1).is_err());
        assert!(validate_material_count(u32::MAX).is_err());
        let last_record_byte = (u64::from(MAX_MATERIAL_RECORDS) - 1) * 128 + 127;
        assert_eq!(last_record_byte, u64::from(u32::MAX));
        assert_eq!(arena_capacity(17, 23).unwrap(), 23);
        assert!(arena_capacity(24, 23).is_err());
    }
    use prime_scene::scene::{DynamicScene, Prototype};

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

    fn triangle(x: f32) -> Triangle {
        Triangle {
            positions: [[x, 1.0, 0.0], [x + 1.0, 1.0, 0.0], [x, 2.0, 0.0]],
            colors: [[1.0; 4]; 3],
            uvs: [[0.0; 2]; 3],
            texture_id: 0,
            flags: 0,
        }
    }
    fn instance(x: f64) -> Instance {
        Instance {
            revision: 1,
            prototype_id: 7,
            origin: [x, 0.0, 0.0],
            transform: translation([0.0; 3]),
            texture_id: INHERIT,
            flags: INHERIT,
            tint: [255; 4],
            uv_transform: [1.0, 1.0, 0.0, 0.0],
        }
    }
    fn source() -> InstanceScene {
        let mut source = InstanceScene {
            epoch: 1,
            resource_revision: 1,
            instance_revision: 1,
            ..Default::default()
        };
        source.prototypes.insert(
            7,
            Prototype {
                revision: 1,
                triangles: vec![triangle(0.0)].into(),
                bounds: [[0.0, 1.0, 0.0], [1.0, 2.0, 0.0]],
            },
        );
        source
    }

    #[test]
    fn ten_thousand_placements_share_one_prototype_and_instance_edits_do_not_rebuild_it() {
        let mut source = source();
        for i in 0..10_000 {
            source.instances.insert(i, instance(i as f64));
        }
        let mut scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let mut planner = Planner::default();
        let first = planner.plan(&scene, &source).unwrap();
        assert_eq!(first.geometry.len(), 1);
        assert!(first.placements_changed);
        assert_eq!(planner.placements().len(), 10_000);
        let unchanged = planner.plan(&scene, &source).unwrap();
        assert!(unchanged.geometry.is_empty() && !unchanged.placements_changed);
        source.instances.get_mut(&99).unwrap().transform[3] = 2.5;
        source.instance_revision += 1;
        let moved = planner.plan(&scene, &source).unwrap();
        assert!(moved.geometry.is_empty());
        assert!(moved.placements_changed);
        assert_eq!(planner.placements()[99].transform[3], 101.5);
        source.instances.remove(&99);
        source.instance_revision += 1;
        let removed = planner.plan(&scene, &source).unwrap();
        assert!(removed.geometry.is_empty() && removed.removed.is_empty());
        assert_eq!(planner.placements().len(), 9_999);
        scene.anchor = [4096.0, 0.0, 0.0];
        let rebased = planner.plan(&scene, &source).unwrap();
        assert!(rebased.geometry.is_empty());
        assert!(rebased.placements_changed);
        assert_eq!(planner.placements()[0].transform[3], -4096.0);
        source.prototypes.get_mut(&7).unwrap().revision += 1;
        source.resource_revision += 1;
        assert_eq!(planner.plan(&scene, &source).unwrap().geometry.len(), 1);
    }

    #[test]
    fn raw_snapshot_changes_only_affected_spatial_bucket_without_static_scan() {
        let mut scene = Scene {
            epoch: 1,
            dynamic: DynamicScene {
                revision: 1,
                origin: [0.0; 3],
                triangles: vec![triangle(1.0), triangle(1001.0)].into(),
            },
            ..Default::default()
        };
        let mut planner = Planner::default();
        let objects = InstanceScene::default();
        let initial = planner.plan(&scene, &objects).unwrap();
        assert_eq!(initial.geometry.len(), 2);
        assert!(initial.placements_changed);
        assert_eq!(planner.placements().len(), 2);
        scene.dynamic.revision += 1;
        assert!(planner.plan(&scene, &objects).unwrap().geometry.is_empty());
        Arc::make_mut(&mut scene.dynamic.triangles)[0].positions[0][1] += 0.25;
        scene.dynamic.revision += 1;
        let changed = planner.plan(&scene, &objects).unwrap();
        assert_eq!(changed.geometry.len(), 1);
        assert_eq!(changed.geometry[0].key, ObjectKey::Raw([0, 0, 0], 0));
        scene.dynamic.triangles = vec![triangle(1001.0)].into();
        scene.dynamic.revision += 1;
        let removed = planner.plan(&scene, &objects).unwrap();
        assert!(removed.geometry.is_empty());
        assert_eq!(removed.removed, [ObjectKey::Raw([0, 0, 0], 0)]);
    }

    #[test]
    fn raw_workspaces_preserve_bucket_order_and_reuse_same_content_storage() {
        let a = triangle(1.0);
        let b = triangle(4.0);
        let distant = triangle(65.0);
        let key = ObjectKey::Raw([0, 0, 0], 0);
        let mut scene = Scene {
            epoch: 1,
            dynamic: DynamicScene {
                revision: 1,
                triangles: vec![a, distant, b].into(),
                ..Default::default()
            },
            ..Default::default()
        };
        let source = InstanceScene::default();
        let mut planner = Planner::default();
        let initial = planner.plan(&scene, &source).unwrap();
        let buffer = planner.raw[&key].triangles.as_ptr();
        let update = initial
            .geometry
            .iter()
            .find(|item| item.key == key)
            .unwrap();
        assert_eq!(planner.triangles(update).as_ptr(), buffer);
        assert!(same_triangle(&planner.triangles(update)[0], &a));
        assert!(same_triangle(&planner.triangles(update)[1], &b));
        planner.recycle(initial);

        // Global interleaving changes while each cell's primitive order stays identical.
        scene.dynamic.triangles = vec![distant, a, b].into();
        scene.dynamic.revision += 1;
        let unchanged = planner.plan(&scene, &source).unwrap();
        assert!(unchanged.geometry.is_empty() && !unchanged.placements_changed);
        assert_eq!(planner.raw[&key].triangles.as_ptr(), buffer);
        planner.recycle(unchanged);

        // Swapping primitives in one cell is significant, even with identical membership.
        scene.dynamic.triangles = vec![b, distant, a].into();
        scene.dynamic.revision += 1;
        let reordered = planner.plan(&scene, &source).unwrap();
        assert_eq!(reordered.geometry.len(), 1);
        assert_eq!(reordered.geometry[0].key, key);
        let triangles = planner.triangles(&reordered.geometry[0]);
        assert_eq!(triangles.as_ptr(), buffer);
        assert!(same_triangle(&triangles[0], &b));
        assert!(same_triangle(&triangles[1], &a));
    }

    #[test]
    fn raw_comparison_preserves_color_uv_texture_and_signed_zero_bits() {
        let mut input = triangle(1.0);
        input.colors[0][1] = 0.0;
        let mut scene = Scene {
            epoch: 1,
            dynamic: DynamicScene {
                revision: 1,
                triangles: vec![input].into(),
                ..Default::default()
            },
            ..Default::default()
        };
        let source = InstanceScene::default();
        let mut planner = Planner::default();
        let initial = planner.plan(&scene, &source).unwrap();
        planner.recycle(initial);
        for change in 0..4 {
            match change {
                0 => input.colors[0][1] = -0.0,
                1 => input.uvs[1][1] = -0.0,
                2 => input.texture_id = 29,
                3 => input.flags = 2,
                _ => unreachable!(),
            }
            scene.dynamic.triangles = vec![input].into();
            scene.dynamic.revision += 1;
            let plan = planner.plan(&scene, &source).unwrap();
            assert_eq!(plan.geometry.len(), 1);
            assert!(same_triangle(
                &planner.triangles(&plan.geometry[0])[0],
                &input
            ));
            assert_eq!(plan.removed.len(), usize::from(change == 3));
            planner.recycle(plan);
        }
        assert!(planner.raw.contains_key(&ObjectKey::Raw([0, 0, 0], 2)));
        assert_eq!(planner.raw.len(), 1);
    }

    #[test]
    fn raw_bucket_growth_shrink_and_transient_cells_have_no_history() {
        let mut scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let source = InstanceScene::default();
        let mut planner = Planner::default();
        for count in [1, 17, 3, 0] {
            scene.dynamic.triangles = (0..count)
                .map(|index| triangle(1.0 + index as f32 / 32.0))
                .collect::<Vec<_>>()
                .into();
            scene.dynamic.revision += 1;
            let plan = planner.plan(&scene, &source).unwrap();
            assert_eq!(plan.triangle_count, count as u64);
            assert_eq!(planner.raw.len(), usize::from(count != 0));
            if count == 0 {
                assert_eq!(plan.removed, [ObjectKey::Raw([0, 0, 0], 0)]);
                assert!(plan.geometry.is_empty());
            } else {
                assert_eq!(planner.triangles(&plan.geometry[0]).len(), count);
            }
            planner.recycle(plan);
        }
        for cell in 0..128 {
            scene.dynamic.triangles = vec![triangle(cell as f32 * 16.0 + 1.0)].into();
            scene.dynamic.revision += 1;
            let plan = planner.plan(&scene, &source).unwrap();
            assert_eq!(plan.geometry.len(), 1);
            assert_eq!(planner.raw.len(), 1);
            assert!(planner.raw.contains_key(&ObjectKey::Raw([cell, 0, 0], 0)));
            assert_eq!(plan.removed.len(), usize::from(cell != 0));
            planner.recycle(plan);
        }
    }

    #[test]
    fn raw_updates_keep_mixed_prototype_prefix_and_rebase_all_placements() {
        let mut source = source();
        source.instances.insert(1, instance(2.0));
        source.instances.insert(2, instance(8.0));
        let mut scene = Scene {
            epoch: 1,
            dynamic: DynamicScene {
                revision: 1,
                origin: [32.0, 0.0, 0.0],
                triangles: vec![triangle(1.0), triangle(17.0)].into(),
            },
            ..Default::default()
        };
        let mut planner = Planner::default();
        let initial = planner.plan(&scene, &source).unwrap();
        assert_eq!(initial.triangle_count, 4);
        let prefix = [
            planner.placements()[0].transform,
            planner.placements()[1].transform,
        ];
        let prototype_data = source.prototypes[&7].triangles.as_ptr();
        planner.recycle(initial);

        scene.dynamic.triangles = vec![triangle(1.25)].into();
        scene.dynamic.revision += 1;
        let raw_update = planner.plan(&scene, &source).unwrap();
        assert_eq!(raw_update.triangle_count, 3);
        assert_eq!(planner.prototype_placements, 2);
        assert_eq!(planner.placements().len(), 3);
        assert_eq!(planner.placements()[0].transform, prefix[0]);
        assert_eq!(planner.placements()[1].transform, prefix[1]);
        assert_eq!(planner.placements()[2].transform[3], 32.0);
        assert_eq!(source.prototypes[&7].triangles.as_ptr(), prototype_data);
        planner.recycle(raw_update);

        // Preserve the same world-space dynamic origin while changing the frame anchor.
        scene.anchor[0] = 256.0;
        scene.dynamic.origin[0] = -224.0;
        let rebase = planner.plan(&scene, &source).unwrap();
        assert!(rebase.geometry.is_empty() && rebase.placements_changed);
        assert_eq!(planner.placements()[0].transform[3], -254.0);
        assert_eq!(planner.placements()[1].transform[3], -248.0);
        assert_eq!(planner.placements()[2].transform[3], -224.0);
        planner.recycle(rebase);

        source.instances.remove(&1);
        source.instance_revision += 1;
        let remove = planner.plan(&scene, &source).unwrap();
        assert!(remove.geometry.is_empty() && remove.placements_changed);
        assert_eq!(remove.triangle_count, 2);
        assert_eq!(planner.prototype_placements, 1);
        assert_eq!(planner.placements().len(), 2);
        assert_eq!(planner.placements()[0].transform[3], -248.0);
        assert_eq!(planner.placements()[1].transform[3], -224.0);
        planner.recycle(remove);

        // A new world cannot reuse either raw buckets or the old prototype prefix.
        scene.epoch = 2;
        scene.dynamic.triangles = Arc::from([]);
        scene.dynamic.revision += 1;
        let reset = planner.plan(&scene, &InstanceScene::default()).unwrap();
        assert!(reset.placements_changed);
        assert_eq!(reset.triangle_count, 0);
        assert!(planner.raw.is_empty() && planner.placements().is_empty());
    }

    #[test]
    fn affine_rebase_preserves_reflection_shear_and_large_world_precision() {
        let mut input = instance(30_000_000.25);
        input.transform = [
            -2.0, 0.5, 0.0, 0.125, 0.0, 3.0, 0.0, 0.25, 0.0, 0.0, 0.5, -0.75,
        ];
        let output = relative_transform(&input, [30_000_000.0, 0.0, 0.0]).unwrap();
        assert_eq!(
            output,
            [
                -2.0, 0.5, 0.0, 0.375, 0.0, 3.0, 0.0, 0.25, 0.0, 0.0, 0.5, -0.75
            ]
        );
        assert!(validate_transformed_bounds(output, [[0.0; 3], [1.0; 3]]).is_ok());
        assert!(validate_transformed_bounds(output, [[0.0; 3], [1_000_000.0; 3]]).is_err());
    }

    #[test]
    fn instance_delta_reuses_plan_storage_after_recording_consumes_it() {
        let mut source = source();
        source.instances.insert(1, instance(0.0));
        let scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let mut planner = Planner::default();
        let initial = planner.plan(&scene, &source).unwrap();
        let allocation = planner.placements().as_ptr();
        planner.recycle(initial);
        source.instances.get_mut(&1).unwrap().tint = [1, 2, 3, 4];
        source.instance_revision += 1;
        let update = planner.plan(&scene, &source).unwrap();
        assert!(update.geometry.is_empty());
        assert!(update.placements_changed);
        let placements = planner.placements();
        assert_eq!(placements.as_ptr(), allocation);
        let mut bytes = Vec::new();
        pack_material(&mut bytes, 0x1234567890abcdef, INHERIT, &placements[0]);
        assert_eq!(bytes.len(), 48);
        assert_eq!(&bytes[16..20], &[1, 2, 3, 4]);
        assert_eq!(
            u64::from_le_bytes(bytes[0..8].try_into().unwrap()),
            0x1234567890abcdef
        );
        assert_eq!(f32::from_le_bytes(bytes[32..36].try_into().unwrap()), 1.0);
    }

    #[test]
    fn nonempty_instance_epoch_cannot_reuse_prototype_identity_from_another_world() {
        let mut source = source();
        source.instances.insert(1, instance(0.0));
        let mut planner = Planner::default();
        let scene = Scene {
            epoch: 2,
            ..Default::default()
        };
        assert!(planner.plan(&scene, &source).is_err());
        assert!(planner.plan(&scene, &InstanceScene::default()).is_ok());
    }
}
