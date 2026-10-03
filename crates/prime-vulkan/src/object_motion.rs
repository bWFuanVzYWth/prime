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

    fn relative(&self, anchor: [f64; 3]) -> Option<[f32; 12]> {
        let mut transform = self.transform;
        for row in 0..3 {
            let value = self.origin[row] - anchor[row] + f64::from(transform[row * 4 + 3]);
            if !value.is_finite() || value.abs() > 1_048_576.0 {
                return None;
            }
            transform[row * 4 + 3] = value as f32;
        }
        Some(transform)
    }

    fn corresponds(&self, current: &Self) -> bool {
        Arc::ptr_eq(&self.geometry, &current.geometry)
            || self.geometry.len() == current.geometry.len()
                && self
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
        old.corresponds(current)
            .then(|| old.relative(anchor))
            .flatten()
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
        assert!(old.corresponds(&current));
        assert_eq!(old.relative([1_000_256.0, 0.0, 0.0]).unwrap()[3], 1.25);
        assert_eq!(current.relative([1_000_256.0, 0.0, 0.0]).unwrap()[3], 4.25);
        let mut triangles = current.geometry.to_vec();
        triangles[0].positions.swap(0, 1);
        current.geometry = triangles.into();
        assert!(
            !old.corresponds(&current),
            "triangle reordering must not guess barycentrics"
        );
        let mut triangles = old.geometry.to_vec();
        triangles[0].positions[0][0] = -0.0;
        current.geometry = triangles.into();
        assert!(
            !old.corresponds(&current),
            "the geometry proof is bit exact"
        );
        assert!(old.relative([-1_000_256.0, 0.0, 0.0]).is_none());
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
