//! CPU scene planning. No Vulkan handles, uploads, submissions or device access.
use crate::{float, float4, uint};
use prime_scene::scene::{Instance, InstanceScene, Scene, Triangle};
use std::{collections::BTreeMap, sync::Arc};

pub(crate) const OBJECT_BIT: u32 = 0x0080_0000;
pub(crate) const INHERIT: u32 = u32::MAX;
const RAW_CELL: f64 = 16.0;

#[derive(Default)]
pub(crate) struct Slots {
    pub(crate) end: u32,
    free: BTreeMap<u32, u32>,
}
impl Slots {
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
            .ok_or("Triangle address overflow")?;
        if end > OBJECT_BIT {
            return Err("Triangle arena exceeds the 23-bit static triangle offset".into());
        }
        self.end = end;
        Ok(first)
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
    pub triangles: Arc<[Triangle]>,
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
    pub placements: Option<Vec<Placement>>,
    pub triangle_count: u32,
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
    raw: BTreeMap<ObjectKey, Arc<[Triangle]>>,
    triangle_count: u32,
    spare_geometry: Vec<GeometryUpdate>,
    spare_removed: Vec<ObjectKey>,
    spare_placements: Vec<Placement>,
}

impl Planner {
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
            placements: None,
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
                        triangles: prototype.triangles.clone(),
                    });
                    self.prototypes.insert(id, prototype.revision);
                }
            }
        }
        if raw_changed {
            let mut buckets = raw_buckets(scene)?;
            self.raw.retain(|key, _| {
                if buckets.contains_key(key) {
                    true
                } else {
                    result.removed.push(*key);
                    false
                }
            });
            for (key, triangles) in &mut buckets {
                if self
                    .raw
                    .get(key)
                    .is_some_and(|old| same_triangles(old, triangles))
                {
                    continue;
                }
                let triangles: Arc<[Triangle]> = std::mem::take(triangles).into();
                self.raw.insert(*key, triangles.clone());
                result.geometry.push(GeometryUpdate {
                    key: *key,
                    triangles,
                });
            }
        }
        let raw_geometry_changed = result
            .geometry
            .iter()
            .any(|item| matches!(item.key, ObjectKey::Raw(..)))
            || result
                .removed
                .iter()
                .any(|key| matches!(key, ObjectKey::Raw(..)));
        if instances || raw_geometry_changed {
            let mut placements = std::mem::take(&mut self.spare_placements);
            placements.reserve(objects.instances.len() + self.raw.len());
            let mut count = scene.dynamic.triangles.len() as u64;
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
                placements.push(Placement {
                    key: ObjectKey::Prototype(instance.prototype_id),
                    transform,
                    texture_id: instance.texture_id,
                    flags: instance.flags,
                    tint: instance.tint,
                    uv: instance.uv_transform,
                });
            }
            for key in self.raw.keys() {
                let ObjectKey::Raw(cell, _) = key else {
                    unreachable!()
                };
                let origin = std::array::from_fn(|i| {
                    (f64::from(cell[i]) * RAW_CELL - scene.anchor[i]) as f32
                });
                placements.push(Placement {
                    key: *key,
                    transform: translation(origin),
                    texture_id: INHERIT,
                    flags: INHERIT,
                    tint: [255; 4],
                    uv: [1.0, 1.0, 0.0, 0.0],
                });
            }
            if placements.len() >= OBJECT_BIT as usize {
                return Err("Too many object instances".into());
            }
            result.triangle_count =
                u32::try_from(count).map_err(|_| "Instanced triangle count overflow")?;
            result.placements = Some(placements);
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

    /// Return owned CPU scratch after execution; the next delta reuses capacity.
    /// No GPU completion is needed: command recording has consumed the plan.
    pub fn recycle(&mut self, mut plan: ScenePlan) {
        plan.geometry.clear();
        plan.removed.clear();
        self.spare_geometry = plan.geometry;
        self.spare_removed = plan.removed;
        if let Some(mut placements) = plan.placements {
            placements.clear();
            self.spare_placements = placements;
        }
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

pub(crate) fn pack_material(bytes: &mut Vec<u8>, first: u32, texture: u32, placement: &Placement) {
    uint(bytes, first);
    uint(bytes, texture);
    uint(bytes, placement.flags);
    uint(bytes, u32::from_le_bytes(placement.tint));
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
fn raw_buckets(scene: &Scene) -> Result<BTreeMap<ObjectKey, Vec<Triangle>>, String> {
    let origin: [f64; 3] =
        std::array::from_fn(|i| scene.anchor[i] + f64::from(scene.dynamic.origin[i]));
    let mut buckets = BTreeMap::<ObjectKey, Vec<Triangle>>::new();
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
        buckets
            .entry(ObjectKey::Raw(cell, triangle.flags))
            .or_default()
            .push(local);
    }
    Ok(buckets)
}

fn same_triangles(a: &[Triangle], b: &[Triangle]) -> bool {
    a.len() == b.len()
        && a.iter().zip(b).all(|(a, b)| {
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
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::scene::{DynamicScene, Prototype};

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
        assert_eq!(first.placements.unwrap().len(), 10_000);
        let unchanged = planner.plan(&scene, &source).unwrap();
        assert!(unchanged.geometry.is_empty() && unchanged.placements.is_none());
        source.instances.get_mut(&99).unwrap().transform[3] = 2.5;
        source.instance_revision += 1;
        let moved = planner.plan(&scene, &source).unwrap();
        assert!(moved.geometry.is_empty());
        assert_eq!(moved.placements.unwrap()[99].transform[3], 101.5);
        source.instances.remove(&99);
        source.instance_revision += 1;
        let removed = planner.plan(&scene, &source).unwrap();
        assert!(removed.geometry.is_empty() && removed.removed.is_empty());
        assert_eq!(removed.placements.unwrap().len(), 9_999);
        scene.anchor = [4096.0, 0.0, 0.0];
        let rebased = planner.plan(&scene, &source).unwrap();
        assert!(rebased.geometry.is_empty());
        assert_eq!(rebased.placements.unwrap()[0].transform[3], -4096.0);
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
        assert_eq!(initial.placements.unwrap().len(), 2);
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
        let allocation = initial.placements.as_ref().unwrap().as_ptr();
        planner.recycle(initial);
        source.instances.get_mut(&1).unwrap().tint = [1, 2, 3, 4];
        source.instance_revision += 1;
        let update = planner.plan(&scene, &source).unwrap();
        assert!(update.geometry.is_empty());
        let placements = update.placements.as_ref().unwrap();
        assert_eq!(placements.as_ptr(), allocation);
        let mut bytes = Vec::new();
        pack_material(&mut bytes, 17, INHERIT, &placements[0]);
        assert_eq!(bytes.len(), 32);
        assert_eq!(&bytes[12..16], &[1, 2, 3, 4]);
        assert_eq!(u32::from_le_bytes(bytes[0..4].try_into().unwrap()), 17);
        assert_eq!(f32::from_le_bytes(bytes[16..20].try_into().unwrap()), 1.0);
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
