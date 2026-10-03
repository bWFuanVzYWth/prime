//! Persistent prototype reuse and dynamic geometry ownership, without GPU operations.
pub use super::placements::PlacementChange;
use super::{
    BatchLimits,
    placements::{Identity, Placements, coalesce},
    translation,
};
use crate::instances::{InstanceCursor, InstanceInput};
use crate::{
    scene::{Instance, Scene, Triangle},
    spatial::{BatchKey, Cell},
};
use std::{collections::BTreeMap, sync::Arc};

#[cfg(test)]
use crate::scene::InstanceScene;

const INHERIT: u32 = u32::MAX;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ObjectKey {
    Prototype(u64),
    Raw(BatchKey),
}

pub struct GeometryUpdate {
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

#[derive(Default)]
struct RawCell {
    cursor: usize,
    parts: Vec<RawBucket>,
}

#[derive(Clone, Copy, Debug)]
pub struct Placement {
    pub key: ObjectKey,
    pub transform: [f32; 12],
    pub texture_id: u32,
    pub flags: u32,
    pub tint: [u8; 4],
    pub uv: [f32; 4],
}

pub struct ScenePlan {
    pub removed: Vec<ObjectKey>,
    pub geometry: Vec<GeometryUpdate>,
    pub placements_changed: bool,
    pub tlas_changed: bool,
    pub changes: Vec<PlacementChange>,
    pub instances_visited: usize,
    pub poses_evaluated: usize,
    pub triangle_count: u64,
}

pub struct Planner {
    limits: BatchLimits,
    epoch: Option<u64>,
    resource_revision: Option<u64>,
    instance_revision: Option<u64>,
    dynamic_revision: Option<u64>,
    dynamic_origin: [f64; 3],
    anchor: [f64; 3],
    prototypes: BTreeMap<u64, u64>,
    raw: BTreeMap<(Cell, u32), RawCell>,
    placements: Placements,
    instance_cursor: Option<InstanceCursor>,
    spare_changes: Vec<PlacementChange>,
    touched: Vec<u64>,
    triangle_count: u64,
    spare_geometry: Vec<GeometryUpdate>,
    spare_removed: Vec<ObjectKey>,
}

impl Planner {
    pub fn new(limits: BatchLimits) -> Result<Self, String> {
        limits.validate()?;
        Ok(Self {
            limits,
            epoch: None,
            resource_revision: None,
            instance_revision: None,
            dynamic_revision: None,
            dynamic_origin: [0.0; 3],
            anchor: [0.0; 3],
            prototypes: BTreeMap::new(),
            raw: BTreeMap::new(),
            placements: Placements::default(),
            instance_cursor: None,
            spare_changes: Vec::new(),
            touched: Vec::new(),
            triangle_count: 0,
            spare_geometry: Vec::new(),
            spare_removed: Vec::new(),
        })
    }

    /// Execution must consume this plan before planning another frame. Like GPU
    /// execution, a late error can leave private caches changed: the enclosing
    /// renderer remains poisoned and must be discarded, never retried in place.
    pub fn plan<'a>(
        &mut self,
        scene: &Scene,
        input: impl Into<InstanceInput<'a>>,
    ) -> Result<ScenePlan, String> {
        let input = input.into();
        let objects = &*input;
        if objects.epoch != scene.epoch
            && (!objects.prototypes.is_empty() || !objects.instances.is_empty())
        {
            return Err("Instance scene epoch does not match the terrain scene".into());
        }
        let reset = self.epoch != Some(scene.epoch);
        let delta = input.changes(self.instance_cursor);
        let gap =
            input.cursor().is_some() && input.cursor() != self.instance_cursor && delta.is_none();
        let resources = reset || gap || self.resource_revision != Some(objects.resource_revision);
        let rebase = reset || self.anchor != scene.anchor;
        let instances = reset
            || gap
            || resources
            || rebase
            || self.instance_revision != Some(objects.instance_revision);
        let origin = scene.dynamic.origin;
        let raw_changed = reset
            || self.dynamic_revision != Some(scene.dynamic.revision)
            || self.dynamic_origin != origin;
        let previous_count = self.placement_count();
        let mut result = ScenePlan {
            removed: std::mem::take(&mut self.spare_removed),
            geometry: std::mem::take(&mut self.spare_geometry),
            changes: std::mem::take(&mut self.spare_changes),
            placements_changed: false,
            tlas_changed: false,
            instances_visited: 0,
            poses_evaluated: 0,
            triangle_count: self.triangle_count,
        };
        self.touched.clear();
        if reset {
            result
                .removed
                .extend(self.prototypes.keys().map(|id| ObjectKey::Prototype(*id)));
            result.removed.extend(self.raw_keys());
            self.prototypes.clear();
            self.raw.clear();
            self.placements.clear();
        }
        if resources {
            let mut keys: Vec<u64> = if let Some((prototypes, _)) = delta.filter(|_| !reset) {
                prototypes.iter().copied().collect()
            } else {
                self.prototypes
                    .keys()
                    .chain(objects.prototypes.keys())
                    .copied()
                    .collect()
            };
            keys.sort_unstable();
            keys.dedup();
            for id in keys {
                if let Some(prototype) = objects.prototypes.get(&id) {
                    if self.prototypes.get(&id) == Some(&prototype.revision) && !gap {
                        continue;
                    }
                    if prototype.triangles.is_empty() {
                        return Err("Empty prototype".into());
                    }
                    if prototype.triangles.len() > self.limits.triangles as usize {
                        return Err("Prototype exceeds the executor geometry limit".into());
                    }
                    result.geometry.push(GeometryUpdate {
                        key: ObjectKey::Prototype(id),
                        source: GeometrySource::Prototype(prototype.triangles.clone()),
                    });
                    self.prototypes.insert(id, prototype.revision);
                    if let Some(ids) = self.placements.dependencies.get(&id) {
                        self.touched.extend(ids);
                    }
                } else if self.prototypes.remove(&id).is_some() {
                    result.removed.push(ObjectKey::Prototype(id));
                }
            }
        }
        let mut changed_keys: std::collections::BTreeSet<_> =
            result.geometry.iter().map(|g| g.key).collect();
        if instances {
            if let Some((_, ids)) = delta.filter(|_| !reset && !rebase) {
                self.touched.extend(ids);
            } else {
                self.touched.extend(objects.instances.keys());
                self.touched.extend(self.placements.instance_ids());
            }
            self.touched.sort_unstable();
            self.touched.dedup();
            for &id in &self.touched {
                result.instances_visited += 1;
                let Some(instance) = objects.instances.get(&id) else {
                    self.placements
                        .remove(Identity::Instance(id), &mut result.changes);
                    continue;
                };
                let prototype = objects
                    .prototypes
                    .get(&instance.prototype_id)
                    .ok_or("Instance references missing prototype")?;
                let geometry_changed =
                    changed_keys.contains(&ObjectKey::Prototype(instance.prototype_id));
                let spatial = if rebase
                    || geometry_changed
                    || self.placements.source(id).is_none_or(|old| {
                        old.prototype_id != instance.prototype_id
                            || old.origin != instance.origin
                            || old.transform != instance.transform
                    }) {
                    result.poses_evaluated += 1;
                    let transform = relative_transform(instance, scene.anchor)?;
                    validate_transformed_bounds(transform, prototype.bounds)?;
                    Some((instance_cell(instance, prototype.bounds)?, transform))
                } else {
                    None
                };
                self.placements.set_instance(
                    id,
                    instance,
                    spatial,
                    prototype.triangles.len() as u64,
                    &mut result.changes,
                    geometry_changed,
                )?;
            }
        }
        if raw_changed {
            update_raw_buckets(scene, &mut self.raw, self.limits.triangles, &mut result)?;
        }
        let raw_geometry_changed = result
            .geometry
            .iter()
            .any(|g| matches!(g.key, ObjectKey::Raw(..)))
            || result
                .removed
                .iter()
                .any(|key| matches!(key, ObjectKey::Raw(..)));
        changed_keys.extend(result.geometry.iter().map(|g| g.key));
        if raw_geometry_changed || rebase {
            let removed: Vec<_> = self
                .placements
                .raw_keys()
                .filter(|key| {
                    let ObjectKey::Raw(batch) = key else {
                        unreachable!()
                    };
                    self.raw
                        .get(&(batch.cell, batch.flags))
                        .is_none_or(|cell| cell.parts.len() <= batch.part as usize)
                })
                .collect();
            for key in removed {
                self.placements
                    .remove(Identity::Raw(key), &mut result.changes);
            }
            for (&(cell, flags), data) in &self.raw {
                for part in 0..data.parts.len() {
                    let key = ObjectKey::Raw(BatchKey {
                        cell,
                        flags,
                        part: part as u32,
                    });
                    let force = changed_keys.contains(&key);
                    self.placements.set(
                        Identity::Raw(key),
                        Placement {
                            key,
                            transform: translation(cell.relative_origin(scene.anchor)),
                            texture_id: INHERIT,
                            flags: INHERIT,
                            tint: [255; 4],
                            uv: [1.0, 1.0, 0.0, 0.0],
                        },
                        &mut result.changes,
                        force,
                    );
                }
            }
        }
        if self.placement_count() > self.limits.placements as usize {
            return Err("Too many object instances".into());
        }
        coalesce(&mut result.changes, self.placement_count());
        result.placements_changed =
            reset || previous_count != self.placement_count() || !result.changes.is_empty();
        result.tlas_changed = reset
            || previous_count != self.placement_count()
            || result.changes.iter().any(|c| c.transform);
        result.triangle_count = self
            .placements
            .triangles
            .checked_add(scene.dynamic.triangles.len() as u64)
            .ok_or("Instanced triangle count overflow")?;
        self.epoch = Some(scene.epoch);
        self.resource_revision = Some(objects.resource_revision);
        self.instance_revision = Some(objects.instance_revision);
        self.instance_cursor = input.cursor();
        self.dynamic_revision = Some(scene.dynamic.revision);
        self.dynamic_origin = origin;
        self.anchor = scene.anchor;
        self.triangle_count = result.triangle_count;
        Ok(result)
    }

    pub fn triangles<'a>(&'a self, update: &'a GeometryUpdate) -> &'a [Triangle] {
        match &update.source {
            GeometrySource::Prototype(triangles) => triangles,
            GeometrySource::Raw => self.raw_triangles(update.key),
        }
    }

    fn raw_triangles(&self, key: ObjectKey) -> &[Triangle] {
        let ObjectKey::Raw(batch) = key else {
            unreachable!()
        };
        &self.raw[&(batch.cell, batch.flags)].parts[batch.part as usize].triangles
    }

    fn raw_keys(&self) -> impl Iterator<Item = ObjectKey> {
        self.raw.iter().flat_map(|(&(cell, flags), data)| {
            (0..data.parts.len()).map(move |part| {
                ObjectKey::Raw(BatchKey {
                    cell,
                    flags,
                    part: part as u32,
                })
            })
        })
    }

    pub fn placement_count(&self) -> usize {
        self.placements.values.len()
    }

    pub fn placements(&self) -> impl Iterator<Item = &Placement> {
        self.placements.values.iter()
    }

    pub fn placement(&self, slot: usize) -> &Placement {
        &self.placements.values[slot]
    }

    /// Stable host instance identity; raw batches have no temporal correspondence.
    pub fn placement_instance_id(&self, slot: usize) -> Option<u64> {
        self.placements.instance_id(slot)
    }

    /// Membership is retained independently of compact executor slots.
    pub fn instance_cells(&self) -> impl Iterator<Item = (Cell, &std::collections::BTreeSet<u64>)> {
        self.placements.cells.iter().map(|(&cell, ids)| (cell, ids))
    }

    /// Return owned CPU scratch after execution; the next delta reuses capacity.
    /// No GPU completion is needed: command recording has consumed the plan.
    pub fn recycle(&mut self, mut plan: ScenePlan) {
        plan.geometry.clear();
        plan.removed.clear();
        plan.changes.clear();
        self.spare_changes = plan.changes;
        self.spare_geometry = plan.geometry;
        self.spare_removed = plan.removed;
    }
}

fn relative_transform(instance: &Instance, anchor: [f64; 3]) -> Result<[f32; 12], String> {
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

fn instance_cell(instance: &Instance, bounds: [[f32; 3]; 2]) -> Result<Cell, String> {
    let center: [f64; 3] =
        std::array::from_fn(|i| (f64::from(bounds[0][i]) + f64::from(bounds[1][i])) * 0.5);
    let world = std::array::from_fn(|row| {
        instance.origin[row]
            + f64::from(instance.transform[row * 4 + 3])
            + (0..3)
                .map(|i| f64::from(instance.transform[row * 4 + i]) * center[i])
                .sum::<f64>()
    });
    Cell::containing(world)
}

// Each real triangle belongs to exactly one centroid cell. We do not clip or
// duplicate source triangles; a single long triangle can extend outside its cell.
fn update_raw_buckets(
    scene: &Scene,
    cells: &mut BTreeMap<(Cell, u32), RawCell>,
    limit: u32,
    plan: &mut ScenePlan,
) -> Result<(), String> {
    let origin = scene.dynamic.origin;
    for cell in cells.values_mut() {
        cell.cursor = 0;
        for bucket in &mut cell.parts {
            bucket.cursor = 0;
            bucket.changed = false;
        }
    }
    for triangle in scene.dynamic.triangles.iter() {
        let center = std::array::from_fn(|i| {
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
        let cell = Cell::containing(center)?;
        let cell_origin = cell.origin();
        let mut local = *triangle;
        for position in &mut local.positions {
            for i in 0..3 {
                position[i] = (origin[i] - cell_origin[i] + f64::from(position[i])) as f32;
            }
        }
        // One lookup per triangle, including dense cells. Parts reuse the same cell cursor.
        let data = cells.entry((cell, triangle.flags)).or_default();
        let part = data.cursor / limit as usize;
        if part == data.parts.len() {
            u32::try_from(part).map_err(|_| "Too many geometry parts in one cell")?;
            data.parts.push(RawBucket::default());
        }
        data.cursor += 1;
        let bucket = &mut data.parts[part];
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
    cells.retain(|&(cell, flags), data| {
        for (part, bucket) in data.parts.iter_mut().enumerate() {
            let key = ObjectKey::Raw(BatchKey {
                cell,
                flags,
                part: part as u32,
            });
            if bucket.cursor == 0 {
                plan.removed.push(key);
                continue;
            }
            if bucket.cursor != bucket.triangles.len() {
                bucket.triangles.truncate(bucket.cursor);
                bucket.changed = true;
            }
            if bucket.changed {
                plan.geometry.push(GeometryUpdate {
                    key,
                    source: GeometrySource::Raw,
                });
            }
        }
        data.parts.truncate(data.cursor.div_ceil(limit as usize));
        data.cursor != 0
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
    use crate::scene::{DynamicScene, Prototype};

    fn planner() -> Planner {
        Planner::new(BatchLimits {
            triangles: 1 << 25,
            placements: (1 << 23) - 1,
        })
        .unwrap()
    }
    fn raw_key(coordinates: [i32; 3], flags: u32) -> ObjectKey {
        ObjectKey::Raw(BatchKey {
            cell: Cell::containing(coordinates.map(|v| f64::from(v) * crate::spatial::CELL_EDGE))
                .unwrap(),
            flags,
            part: 0,
        })
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
        let mut planner = planner();
        let first = planner.plan(&scene, &source).unwrap();
        assert_eq!(first.geometry.len(), 1);
        assert!(first.placements_changed);
        assert_eq!(planner.placement_count(), 10_000);
        let unchanged = planner.plan(&scene, &source).unwrap();
        assert!(unchanged.geometry.is_empty() && !unchanged.placements_changed);
        source.instances.get_mut(&99).unwrap().transform[3] = 2.5;
        source.instance_revision += 1;
        let moved = planner.plan(&scene, &source).unwrap();
        assert!(moved.geometry.is_empty());
        assert!(moved.placements_changed);
        assert_eq!(planner.placements().nth(99).unwrap().transform[3], 101.5);
        source.instances.remove(&99);
        source.instance_revision += 1;
        let removed = planner.plan(&scene, &source).unwrap();
        assert!(removed.geometry.is_empty() && removed.removed.is_empty());
        assert_eq!(planner.placement_count(), 9_999);
        scene.anchor = [4096.0, 0.0, 0.0];
        let rebased = planner.plan(&scene, &source).unwrap();
        assert!(rebased.geometry.is_empty());
        assert!(rebased.placements_changed);
        assert_eq!(planner.placements().next().unwrap().transform[3], -4096.0);
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
        let mut planner = planner();
        let objects = InstanceScene::default();
        let initial = planner.plan(&scene, &objects).unwrap();
        assert_eq!(initial.geometry.len(), 2);
        assert!(initial.placements_changed);
        assert_eq!(planner.placement_count(), 2);
        scene.dynamic.revision += 1;
        assert!(planner.plan(&scene, &objects).unwrap().geometry.is_empty());
        Arc::make_mut(&mut scene.dynamic.triangles)[0].positions[0][1] += 0.25;
        scene.dynamic.revision += 1;
        let changed = planner.plan(&scene, &objects).unwrap();
        assert_eq!(changed.geometry.len(), 1);
        assert_eq!(changed.geometry[0].key, raw_key([0, 0, 0], 0));
        scene.dynamic.triangles = vec![triangle(1001.0)].into();
        scene.dynamic.revision += 1;
        let removed = planner.plan(&scene, &objects).unwrap();
        assert!(removed.geometry.is_empty());
        assert_eq!(removed.removed, [raw_key([0, 0, 0], 0)]);
    }

    #[test]
    fn raw_workspaces_preserve_bucket_order_and_reuse_same_content_storage() {
        let a = triangle(1.0);
        let b = triangle(4.0);
        let distant = triangle(65.0);
        let key = raw_key([0, 0, 0], 0);
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
        let mut planner = planner();
        let initial = planner.plan(&scene, &source).unwrap();
        let buffer = planner.raw_triangles(key).as_ptr();
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
        assert_eq!(planner.raw_triangles(key).as_ptr(), buffer);
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
        let mut planner = planner();
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
        assert!(planner.raw_keys().any(|key| key == raw_key([0, 0, 0], 2)));
        assert_eq!(planner.raw.len(), 1);
    }

    #[test]
    fn raw_bucket_growth_shrink_and_transient_cells_have_no_history() {
        let mut scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let source = InstanceScene::default();
        let mut planner = planner();
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
                assert_eq!(plan.removed, [raw_key([0, 0, 0], 0)]);
                assert!(plan.geometry.is_empty());
            } else {
                assert_eq!(planner.triangles(&plan.geometry[0]).len(), count);
            }
            planner.recycle(plan);
        }
        for cell in 0..128 {
            scene.dynamic.triangles = vec![triangle(cell as f32 * 64.0 + 1.0)].into();
            scene.dynamic.revision += 1;
            let plan = planner.plan(&scene, &source).unwrap();
            assert_eq!(plan.geometry.len(), 1);
            assert_eq!(planner.raw.len(), 1);
            assert!(
                planner
                    .raw_keys()
                    .any(|key| key == raw_key([cell, 0, 0], 0))
            );
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
        let mut planner = planner();
        let initial = planner.plan(&scene, &source).unwrap();
        assert_eq!(initial.triangle_count, 4);
        let prefix = [
            planner.placements().next().unwrap().transform,
            planner.placements().nth(1).unwrap().transform,
        ];
        let prototype_data = source.prototypes[&7].triangles.as_ptr();
        planner.recycle(initial);

        scene.dynamic.triangles = vec![triangle(1.25)].into();
        scene.dynamic.revision += 1;
        let raw_update = planner.plan(&scene, &source).unwrap();
        assert_eq!(raw_update.triangle_count, 3);
        assert_eq!(planner.placements.instance_ids().count(), 2);
        assert_eq!(planner.placement_count(), 3);
        assert_eq!(planner.placements().next().unwrap().transform, prefix[0]);
        assert_eq!(planner.placements().nth(1).unwrap().transform, prefix[1]);
        assert_eq!(planner.placements().nth(2).unwrap().transform[3], 0.0);
        assert_eq!(source.prototypes[&7].triangles.as_ptr(), prototype_data);
        planner.recycle(raw_update);

        // Preserve the same world-space dynamic origin while changing the frame anchor.
        scene.anchor[0] = 256.0;
        let rebase = planner.plan(&scene, &source).unwrap();
        assert!(rebase.geometry.is_empty() && rebase.placements_changed);
        assert_eq!(planner.placements().next().unwrap().transform[3], -254.0);
        assert_eq!(planner.placements().nth(1).unwrap().transform[3], -248.0);
        assert_eq!(planner.placements().nth(2).unwrap().transform[3], -256.0);
        planner.recycle(rebase);

        source.instances.remove(&1);
        source.instance_revision += 1;
        let remove = planner.plan(&scene, &source).unwrap();
        assert!(remove.geometry.is_empty() && remove.placements_changed);
        assert_eq!(remove.triangle_count, 2);
        assert_eq!(planner.placements.instance_ids().count(), 1);
        assert_eq!(planner.placement_count(), 2);
        assert_eq!(
            planner
                .placements()
                .find(|p| p.key == ObjectKey::Prototype(7))
                .unwrap()
                .transform[3],
            -248.0
        );
        assert_eq!(
            planner
                .placements()
                .find(|p| matches!(p.key, ObjectKey::Raw(_)))
                .unwrap()
                .transform[3],
            -256.0
        );
        planner.recycle(remove);

        // A new world cannot reuse either raw buckets or the old prototype prefix.
        scene.epoch = 2;
        scene.dynamic.triangles = Arc::default();
        scene.dynamic.revision += 1;
        let reset = planner.plan(&scene, &InstanceScene::default()).unwrap();
        assert!(reset.placements_changed);
        assert_eq!(reset.triangle_count, 0);
        assert!(planner.raw.is_empty() && planner.placements().next().is_none());
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
        let mut planner = planner();
        let initial = planner.plan(&scene, &source).unwrap();
        let allocation = planner.placements.values.as_ptr();
        planner.recycle(initial);
        source.instances.get_mut(&1).unwrap().tint = [1, 2, 3, 4];
        source.instance_revision += 1;
        let update = planner.plan(&scene, &source).unwrap();
        assert!(update.geometry.is_empty());
        assert!(update.placements_changed);
        assert_eq!(planner.placements.values.as_ptr(), allocation);
        let placement = planner.placements().next().unwrap();
        assert_eq!(placement.tint, [1, 2, 3, 4]);
        assert_eq!(placement.uv, [1.0, 1.0, 0.0, 0.0]);
    }

    #[test]
    fn nonempty_instance_epoch_cannot_reuse_prototype_identity_from_another_world() {
        let mut source = source();
        source.instances.insert(1, instance(0.0));
        let mut planner = planner();
        let scene = Scene {
            epoch: 2,
            ..Default::default()
        };
        assert!(planner.plan(&scene, &source).is_err());
        assert!(planner.plan(&scene, &InstanceScene::default()).is_ok());
    }

    #[test]
    fn raw_geometry_uses_the_same_four_by_four_by_four_cell_as_terrain() {
        let mut triangles = Vec::new();
        for x in 0..4 {
            for y in 0..4 {
                for z in 0..4 {
                    let mut value = triangle(x as f32 * 16.0 + 1.0);
                    for position in &mut value.positions {
                        position[1] += y as f32 * 16.0;
                        position[2] += z as f32 * 16.0;
                    }
                    triangles.push(value);
                }
            }
        }
        let scene = Scene {
            dynamic: DynamicScene {
                revision: 1,
                origin: [-64.0; 3],
                triangles: triangles.into(),
            },
            ..Default::default()
        };
        let mut planner = planner();
        let plan = planner.plan(&scene, &InstanceScene::default()).unwrap();
        assert_eq!(plan.geometry.len(), 1);
        assert_eq!(plan.geometry[0].key, raw_key([-1; 3], 0));
        assert_eq!(planner.triangles(&plan.geometry[0]).len(), 64);
        assert_eq!(planner.placement_count(), 1);
        assert_eq!(
            planner.placements().next().unwrap().transform,
            translation([-64.0; 3])
        );
    }

    #[test]
    fn dense_raw_parts_preserve_order_and_long_triangles_without_clipping_or_duplication() {
        let mut long = triangle(1.0);
        long.positions = [[-90.0, 1.0, 0.0], [100.0, 1.0, 0.0], [1.0, 2.0, 0.0]];
        let input = [
            triangle(1.0),
            triangle(2.0),
            long,
            triangle(4.0),
            triangle(5.0),
        ];
        let mut scene = Scene {
            dynamic: DynamicScene {
                revision: 1,
                triangles: input.to_vec().into(),
                ..Default::default()
            },
            ..Default::default()
        };
        let objects = InstanceScene::default();
        let mut planner = Planner::new(BatchLimits {
            triangles: 2,
            placements: 1024,
        })
        .unwrap();
        let plan = planner.plan(&scene, &objects).unwrap();
        assert_eq!(plan.triangle_count, 5);
        assert_eq!(plan.geometry.len(), 3);
        let actual: Vec<_> = plan
            .geometry
            .iter()
            .flat_map(|update| planner.triangles(update))
            .collect();
        assert!(actual.iter().zip(&input).all(|(a, b)| same_triangle(a, b)));
        for (part, update) in plan.geometry.iter().enumerate() {
            assert_eq!(
                update.key,
                ObjectKey::Raw(BatchKey {
                    cell: Cell::containing([0.0; 3]).unwrap(),
                    flags: 0,
                    part: part as u32
                })
            );
            assert!(planner.triangles(update).len() <= 2);
        }
        planner.recycle(plan);
        scene.dynamic.triangles = vec![input[0], input[1]].into();
        scene.dynamic.revision += 1;
        let shrink = planner.plan(&scene, &objects).unwrap();
        assert!(shrink.geometry.is_empty());
        assert_eq!(shrink.removed.len(), 2);
        assert_eq!(planner.raw.len(), 1);
    }

    #[test]
    fn instance_cell_membership_tracks_world_transforms_and_never_rebuilds_shared_geometry() {
        let mut source = source();
        for (id, origin) in [
            [1.0, 1.0, 1.0],
            [48.0, 48.0, 48.0],
            [64.0; 3],
            [-1.0, -2.0, -1.0],
        ]
        .into_iter()
        .enumerate()
        {
            let mut value = instance(0.0);
            value.origin = origin;
            source.instances.insert(id as u64, value);
        }
        let mut scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let mut planner = planner();
        let first = planner.plan(&scene, &source).unwrap();
        assert_eq!(first.geometry.len(), 1);
        let cells = |planner: &Planner| {
            planner
                .instance_cells()
                .map(|(cell, placements)| (cell.coordinates(), placements.len()))
                .collect::<BTreeMap<_, _>>()
        };
        assert_eq!(
            cells(&planner),
            BTreeMap::from([([-1; 3], 1), ([0; 3], 2), ([1; 3], 1)])
        );
        planner.recycle(first);
        source.instances.get_mut(&1).unwrap().transform[3] = 16.0;
        source.instance_revision += 1;
        let moved = planner.plan(&scene, &source).unwrap();
        assert!(moved.geometry.is_empty() && moved.placements_changed);
        assert_eq!(cells(&planner)[&[1, 0, 0]], 1);
        planner.recycle(moved);
        let membership = cells(&planner);
        scene.anchor = [256.0; 3];
        let rebase = planner.plan(&scene, &source).unwrap();
        assert!(rebase.geometry.is_empty());
        assert_eq!(cells(&planner), membership);
        planner.recycle(rebase);
        source.instances.remove(&3);
        source.instance_revision += 1;
        let removed = planner.plan(&scene, &source).unwrap();
        assert!(removed.geometry.is_empty() && removed.removed.is_empty());
        assert!(!cells(&planner).contains_key(&[-1; 3]));
        assert_eq!(planner.placement_count(), 3);
    }

    #[test]
    fn raw_world_origin_stays_on_its_side_of_a_cell_plane_across_rebases() {
        let mut value = triangle(0.0);
        value.positions = [[0.0; 3]; 3];
        let mut scene = Scene {
            dynamic: DynamicScene {
                revision: 1,
                origin: [-f64::EPSILON, 64.0 - 1e-10, 0.0],
                triangles: vec![value].into(),
            },
            ..Default::default()
        };
        let objects = InstanceScene::default();
        let mut planner = planner();
        let first = planner.plan(&scene, &objects).unwrap();
        assert_eq!(first.geometry[0].key, raw_key([-1, 0, 0], 0));
        planner.recycle(first);
        let storage = planner.raw_triangles(raw_key([-1, 0, 0], 0)).as_ptr();
        for anchor in [[256.0; 3], [-65536.0; 3], [0.0; 3]] {
            scene.anchor = anchor;
            let moved = planner.plan(&scene, &objects).unwrap();
            assert!(moved.geometry.is_empty() && moved.removed.is_empty());
            assert_eq!(
                planner.raw_triangles(raw_key([-1, 0, 0], 0)).as_ptr(),
                storage
            );
            planner.recycle(moved);
        }
    }
}
