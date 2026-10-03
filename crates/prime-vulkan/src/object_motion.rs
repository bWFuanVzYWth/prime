//! Previous displayed rigid poses. Source publications alone never advance this history.
use crate::plan::{Planner, ScenePlan};
use prime_scene::{Instance, Triangle, instances::InstanceInput, scene::Scene};
use std::{collections::BTreeMap, sync::Arc};

#[derive(Clone)]
struct Pose {
    origin: [f64; 3],
    transform: [f32; 12],
    geometry: Arc<[Triangle]>,
}

impl Pose {
    fn new(instance: &Instance, geometry: Arc<[Triangle]>) -> Self {
        Self {
            origin: instance.origin,
            transform: instance.transform,
            geometry,
        }
    }

    fn relative(&self, anchor: [f64; 3], local_translation: [f64; 3]) -> Option<[f32; 12]> {
        let mut transform = self.transform;
        for row in 0..3 {
            let mut value = self.origin[row] - anchor[row] + f64::from(transform[row * 4 + 3]);
            if !value.is_finite() || value.abs() > 1_048_576.0 {
                return None;
            }
            // The source pose keeps its range contract; the composed metadata offset can
            // be larger because it cancels the translated current local coordinates.
            if local_translation != [0.0; 3] {
                value += (0..3)
                    .map(|column| {
                        f64::from(transform[row * 4 + column]) * local_translation[column]
                    })
                    .sum::<f64>();
            }
            let relative = value as f32;
            if !relative.is_finite() {
                return None;
            }
            transform[row * 4 + 3] = relative;
        }
        Some(transform)
    }

    /// Offset from current local geometry to the corresponding accepted local geometry.
    fn local_translation(&self, current: &Self) -> Option<[f64; 3]> {
        if Arc::ptr_eq(&self.geometry, &current.geometry) {
            return Some([0.0; 3]);
        }
        if self.geometry.len() != current.geometry.len() {
            return None;
        }
        if self
            .geometry
            .iter()
            .zip(current.geometry.iter())
            .all(|(a, b)| {
                a.positions
                    .iter()
                    .flatten()
                    .zip(b.positions.iter().flatten())
                    .all(|(a, b)| a.to_bits() == b.to_bits())
            })
        {
            return Some([0.0; 3]);
        }
        let old_first = self.geometry.first()?.positions[0];
        let current_first = current.geometry.first()?.positions[0];
        let translation =
            std::array::from_fn(|axis| f64::from(old_first[axis]) - f64::from(current_first[axis]));
        if translation == [0.0; 3] || translation.iter().any(|value| !value.is_finite()) {
            return None;
        }
        for (old, new) in self.geometry.iter().zip(current.geometry.iter()) {
            for (old, new) in old.positions.iter().zip(new.positions.iter()) {
                for axis in 0..3 {
                    let a = old[axis];
                    let b = new[axis];
                    let old_local = a - old_first[axis];
                    let current_local = b - current_first[axis];
                    // Preserve the old exact-normalization proof without allocating a mesh.
                    if old_local.to_bits() != current_local.to_bits()
                        || (old_local + old_first[axis]).to_bits() != a.to_bits()
                        || (current_local + current_first[axis]).to_bits() != b.to_bits()
                    {
                        return None;
                    }
                }
            }
        }
        Some(translation)
    }
}

#[derive(Default)]
pub(super) struct History {
    epoch: Option<u64>,
    current: Vec<Option<(u64, Pose)>>,
    slots: BTreeMap<u64, usize>,
    displayed: BTreeMap<u64, Pose>,
    pending: Vec<u64>,
    dirty: Vec<usize>,
    touched: Vec<usize>,
}

impl History {
    pub fn prepare(
        &mut self,
        planner: &Planner,
        plan: &ScenePlan,
        source: InstanceInput<'_>,
        scene: &Scene,
    ) {
        let all = self.epoch != Some(scene.epoch);
        if all {
            *self = Self {
                epoch: Some(scene.epoch),
                ..Self::default()
            };
        }
        let count = planner.placement_count();
        while self.current.len() > count {
            if let Some((id, _)) = self.current.pop().unwrap() {
                self.slots.remove(&id);
                self.pending.push(id);
            }
        }
        self.current.resize_with(count, || None);
        self.touched.clear();
        if all {
            self.touched.extend(0..count);
        } else {
            self.touched
                .extend(plan.changes.iter().map(|change| change.slot));
        }
        // Remove every changed old association before assigning new slots: dense compaction
        // can move a surviving identity through a slot which held another removed identity.
        for &slot in &self.touched {
            if let Some((id, _)) = self.current[slot].take() {
                self.slots.remove(&id);
                self.pending.push(id);
            }
        }
        for index in 0..self.touched.len() {
            let slot = self.touched[index];
            if let Some(id) = planner.placement_instance_id(slot) {
                let instance = &source.instances[&id];
                let geometry = source.prototypes[&instance.prototype_id].triangles.clone();
                self.stage(slot, id, Pose::new(instance, geometry));
            }
            self.dirty.push(slot);
        }
        self.dirty.retain(|&slot| slot < count);
        self.dirty.sort_unstable();
        self.dirty.dedup();
    }

    fn stage(&mut self, slot: usize, id: u64, pose: Pose) {
        self.current[slot] = Some((id, pose));
        self.slots.insert(id, slot);
        self.pending.push(id);
    }

    pub fn rows(&self) -> &[usize] {
        &self.dirty
    }

    pub fn clear_rows(&mut self) {
        self.dirty.clear();
    }

    pub fn previous(&self, slot: usize, anchor: [f64; 3]) -> Option<[f32; 12]> {
        let (id, current) = self.current.get(slot)?.as_ref()?;
        let old = self.displayed.get(id)?;
        old.relative(anchor, old.local_translation(current)?)
    }

    pub fn commit(&mut self) {
        self.pending.sort_unstable();
        self.pending.dedup();
        for id in self.pending.drain(..) {
            if let Some(&slot) = self.slots.get(&id) {
                let (_, pose) = self.current[slot].as_ref().unwrap();
                self.displayed.insert(id, pose.clone());
                // A moved/new pose settles on the next frame even without another source edit.
                self.dirty.push(slot);
            } else {
                self.displayed.remove(&id);
            }
        }
    }

    pub fn reset(&mut self) {
        self.displayed.clear();
        self.pending.clear();
        for (&id, &slot) in &self.slots {
            self.pending.push(id);
            self.dirty.push(slot);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pose(x: f64) -> Pose {
        Pose {
            origin: [x, 0.0, 0.0],
            transform: [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0],
            geometry: vec![Triangle {
                positions: [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
                colors: [[1.0; 4]; 3],
                uvs: [[0.0; 2]; 3],
                texture_id: 0,
                flags: 0,
            }]
            .into(),
        }
    }

    #[test]
    fn publication_requires_commit_and_stopped_pose_settles_once() {
        let mut history = History::default();
        history.current.resize_with(1, || None);
        history.stage(0, 7, pose(1.0));
        assert!(history.previous(0, [0.0; 3]).is_none());
        history.commit();
        assert_eq!(history.rows(), &[0]);
        history.clear_rows();
        history.stage(0, 7, pose(2.0));
        assert_eq!(history.previous(0, [0.0; 3]).unwrap()[3], 1.0);
        history.commit();
        assert_eq!(history.previous(0, [0.0; 3]).unwrap()[3], 2.0);
        history.clear_rows();
        history.commit();
        assert!(
            history.rows().is_empty(),
            "unchanged frames have no history uploads"
        );
        history.reset();
        assert!(history.previous(0, [0.0; 3]).is_none());
        history.commit();
        assert!(history.previous(0, [0.0; 3]).is_some());
    }

    #[test]
    fn exact_ordered_positions_prove_correspondence_and_rebase_is_not_motion() {
        let old = pose(1_000_257.25);
        let mut current = old.clone();
        current.transform = [
            0.0, -2.0, 0.25, 3.0, 1.0, 0.0, 0.0, 4.0, 0.0, 0.0, -1.0, 5.0,
        ];
        assert_eq!(old.local_translation(&current), Some([0.0; 3]));
        assert_eq!(
            old.relative([1_000_256.0, 0.0, 0.0], [0.0; 3]).unwrap()[3],
            1.25
        );
        assert_eq!(
            current.relative([1_000_256.0, 0.0, 0.0], [0.0; 3]).unwrap()[3],
            4.25
        );
        let mut triangles = current.geometry.to_vec();
        triangles[0].positions.swap(0, 1);
        current.geometry = triangles.into();
        assert!(
            old.local_translation(&current).is_none(),
            "triangle reordering must not guess barycentrics"
        );
        let mut triangles = old.geometry.to_vec();
        triangles[0].positions[0][0] = -0.0;
        current.geometry = triangles.into();
        assert!(
            old.local_translation(&current).is_none(),
            "the geometry proof is bit exact"
        );
        assert!(old.relative([-1_000_256.0, 0.0, 0.0], [0.0; 3]).is_none());
    }

    fn translated(mut value: Pose, shift: [f32; 3]) -> Pose {
        for triangle in Arc::make_mut(&mut value.geometry) {
            for position in &mut triangle.positions {
                for axis in 0..3 {
                    position[axis] += shift[axis];
                }
            }
        }
        value
    }

    fn local_point(triangle: &Triangle, bary: [f64; 2]) -> [f64; 3] {
        std::array::from_fn(|axis| {
            // Independent convex combination of actual source vertices, not the normalized proof.
            f64::from(triangle.positions[0][axis]) * (1.0 - bary[0] - bary[1])
                + f64::from(triangle.positions[1][axis]) * bary[0]
                + f64::from(triangle.positions[2][axis]) * bary[1]
        })
    }

    #[test]
    fn local_translation_merges_the_accepted_affine_pose_and_rebases_without_geometry_copy() {
        let anchor = [1_000_256.0, -256.0, 512.0];
        let mut old = pose(anchor[0] + 1.25);
        old.origin = [anchor[0] + 1.25, anchor[1] - 0.5, anchor[2] + 0.75];
        old.transform = [
            0.0, -2.0, 0.25, 3.0, 1.0, 0.5, 0.0, 4.0, 0.125, 0.0, -1.0, 5.0,
        ];
        for first in [[0.0; 3], [4.0, 6.0, 8.0]] {
            let old = translated(old.clone(), first);
            for shift in [[8.0, -4.0, 2.0], [0.1, 0.1, 0.1]] {
                let mut current = translated(old.clone(), shift);
                current.transform = [1.0, 0.5, 0.0, 1.0, 0.0, 1.0, 0.0, 2.0, 0.0, 0.0, 2.0, 3.0];
                let geometry = current.geometry.clone();
                let mut history = History::default();
                history.current.resize_with(1, || None);
                history.stage(0, 7, old.clone());
                assert!(
                    history.previous(0, anchor).is_none(),
                    "publication is not acceptance"
                );
                history.commit();
                history.clear_rows();
                history.stage(0, 7, current.clone());
                let previous = history
                    .previous(0, anchor)
                    .expect("legacy exact local translation");
                for bary in [
                    [0.0, 0.0],
                    [1.0, 0.0],
                    [0.0, 1.0],
                    [0.125, 0.5],
                    [0.333, 0.271],
                ] {
                    let old_point = local_point(&old.geometry[0], bary);
                    let new_point = local_point(&current.geometry[0], bary);
                    for row in 0..3 {
                        let expected = old.origin[row] - anchor[row]
                            + f64::from(old.transform[row * 4 + 3])
                            + (0..3)
                                .map(|axis| {
                                    f64::from(old.transform[row * 4 + axis]) * old_point[axis]
                                })
                                .sum::<f64>();
                        let actual = f64::from(previous[row * 4 + 3])
                            + (0..3)
                                .map(|axis| f64::from(previous[row * 4 + axis]) * new_point[axis])
                                .sum::<f64>();
                        assert!(
                            (actual - expected).abs() < 1e-5,
                            "{first:?} + {shift:?}, {bary:?}, row {row}: {actual} != {expected}"
                        );
                    }
                }
                assert!(Arc::ptr_eq(
                    &geometry,
                    &history.current[0].as_ref().unwrap().1.geometry
                ));
                history.commit();
                assert_eq!(
                    history.previous(0, anchor),
                    current.relative(anchor, [0.0; 3])
                );
                assert_eq!(history.rows(), &[0]);
                history.clear_rows();
                history.commit();
                assert!(
                    history.rows().is_empty(),
                    "steady poses have no extra metadata work"
                );
            }
        }
    }

    #[test]
    fn local_translation_refuses_deformation_failed_roundtrip_and_nonrepresentable_prior_pose() {
        let old = pose(0.0);
        let current = translated(old.clone(), [0.1; 3]);
        assert!(
            old.local_translation(&current).is_some(),
            "non-binary baked translation"
        );
        let mut deformed = current.clone();
        Arc::make_mut(&mut deformed.geometry)[0].positions[1][0] += 0.001;
        assert!(old.local_translation(&deformed).is_none());
        let mut scaled = current.clone();
        Arc::make_mut(&mut scaled.geometry)[0].positions[1][0] *= 2.0;
        assert!(old.local_translation(&scaled).is_none());
        let mut reordered = current.clone();
        Arc::make_mut(&mut reordered.geometry)[0]
            .positions
            .swap(0, 1);
        assert!(old.local_translation(&reordered).is_none());

        let mut lossy = old.clone();
        Arc::make_mut(&mut lossy.geometry)[0].positions =
            [[-1000.0, 0.0, 0.0], [0.1, 1.0, 0.0], [0.2, 0.0, 1.0]];
        assert!(
            lossy
                .local_translation(&translated(lossy.clone(), [1.0, 0.0, 0.0]))
                .is_none()
        );
        let mut distant = old.clone();
        distant.transform[0] = 1_000_000.0;
        let mut current = translated(distant.clone(), [2.0, 0.0, 0.0]);
        current.transform = old.transform;
        let shift = distant.local_translation(&current).unwrap();
        let previous = distant.relative([0.0; 3], shift).unwrap();
        assert_eq!(previous[3], -2_000_000.0);
        for bary in [
            [0.0, 0.0],
            [1.0, 0.0],
            [0.0, 1.0],
            [0.125, 0.5],
            [0.5, 0.25],
        ] {
            let old_point = local_point(&distant.geometry[0], bary);
            let new_point = local_point(&current.geometry[0], bary);
            for row in 0..3 {
                let expected = f64::from(distant.transform[row * 4 + 3])
                    + (0..3)
                        .map(|axis| f64::from(distant.transform[row * 4 + axis]) * old_point[axis])
                        .sum::<f64>();
                let actual = (0..3).fold(previous[row * 4 + 3], |value, axis| {
                    previous[row * 4 + axis].mul_add(new_point[axis] as f32, value)
                });
                assert_eq!(
                    f64::from(actual),
                    expected,
                    "actual old/current points, {bary:?}, row {row}"
                );
            }
        }
        let mut outside = distant.clone();
        outside.origin[0] = 2_000_000.0;
        assert!(
            outside.relative([0.0; 3], shift).is_none(),
            "the accepted pose keeps its original range check before offset cancellation"
        );
        distant.transform[0] = f32::MAX;
        assert!(
            distant.relative([0.0; 3], shift).is_none(),
            "a finite f64 merged translation must also fit the FP32 metadata"
        );
        distant.transform[0] = f32::INFINITY;
        assert!(
            distant.relative([0.0; 3], shift).is_none(),
            "a non-finite merged translation is never published"
        );
    }

    #[test]
    fn different_identity_never_inherits_the_replaced_slots_pose() {
        let mut history = History::default();
        history.current.resize_with(1, || None);
        history.stage(0, 7, pose(1.0));
        history.commit();
        history.slots.remove(&7);
        history.pending.push(7);
        history.stage(0, 8, pose(2.0));
        assert!(history.previous(0, [0.0; 3]).is_none());
        history.commit();
        assert!(!history.displayed.contains_key(&7));
        assert_eq!(history.previous(0, [0.0; 3]).unwrap()[3], 2.0);
    }

    #[test]
    fn planner_compaction_preserves_identity_and_raw_capture_stays_unknown() {
        use prime_scene::{
            scene::{DynamicScene, InstanceScene, Prototype},
            translation::BatchLimits,
        };
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
                triangles: pose(0.0).geometry,
                bounds: [[0.0; 3], [1.0, 1.0, 0.0]],
            },
        );
        for (id, x) in [(10, 1.0), (20, 2.0), (30, 3.0)] {
            let value = pose(x);
            source.instances.insert(
                id,
                Instance {
                    revision: 1,
                    prototype_id: 7,
                    origin: value.origin,
                    transform: value.transform,
                    texture_id: u32::MAX,
                    flags: u32::MAX,
                    tint: [255; 4],
                    uv_transform: [1.0, 1.0, 0.0, 0.0],
                },
            );
        }
        let mut scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let mut planner = Planner::new(BatchLimits {
            triangles: 1024,
            placements: 1024,
        })
        .unwrap();
        let mut history = History::default();
        let plan = planner.plan(&scene, &source).unwrap();
        history.prepare(&planner, &plan, (&source).into(), &scene);
        planner.recycle(plan);
        history.commit();
        history.clear_rows();
        source.instances.remove(&10);
        source.instance_revision += 1;
        source.instances.get_mut(&30).unwrap().origin[0] = 4.0;
        let plan = planner.plan(&scene, &source).unwrap();
        history.prepare(&planner, &plan, (&source).into(), &scene);
        assert_eq!(planner.placement_count(), 2);
        for slot in 0..planner.placement_count() {
            let id = planner.placement_instance_id(slot).unwrap();
            assert_eq!(
                history.previous(slot, [0.0; 3]).unwrap()[3],
                if id == 20 { 2.0 } else { 3.0 }
            );
        }
        planner.recycle(plan);
        history.commit();
        assert!(!history.displayed.contains_key(&10));
        scene.dynamic = DynamicScene {
            revision: 1,
            triangles: pose(0.0).geometry.to_vec().into(),
            ..Default::default()
        };
        let plan = planner.plan(&scene, &source).unwrap();
        history.prepare(&planner, &plan, (&source).into(), &scene);
        let raw = (0..planner.placement_count())
            .find(|&slot| planner.placement_instance_id(slot).is_none())
            .unwrap();
        assert!(history.previous(raw, [0.0; 3]).is_none());
        history.commit();
        assert!(history.previous(raw, [0.0; 3]).is_none());
        planner.recycle(plan);
        scene.epoch += 1;
        source.epoch = scene.epoch;
        let plan = planner.plan(&scene, &source).unwrap();
        history.prepare(&planner, &plan, (&source).into(), &scene);
        assert!(
            history.displayed.is_empty(),
            "world epoch revokes every displayed pose"
        );
        assert!(
            (0..planner.placement_count()).all(|slot| history.previous(slot, [0.0; 3]).is_none())
        );
    }
}
