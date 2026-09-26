//! Persistent instance state: pure batch validation, then one explicit mutation phase.
use crate::{
    protocol::{InstanceBatch, MAX_TRIANGLES},
    scene::{InstanceScene, Texture},
};
use std::collections::{BTreeMap, BTreeSet};

const MAX_IDENTITIES: usize = 262_144;

/// Owns the version-neutral persistent scene and its last committed sequence.
/// There are no borrowed source pointers, GPU handles, or interior mutability here.
#[derive(Default)]
pub struct InstanceContext {
    scene: InstanceScene,
    sequence: u64,
    references: BTreeMap<u64, usize>,
    triangle_count: usize,
}

struct Plan {
    batch: InstanceBatch,
    reference_counts: BTreeMap<u64, usize>,
    triangle_count: usize,
    resource_revision: u64,
    instance_revision: u64,
}

impl InstanceContext {
    pub fn new(epoch: u64) -> Self {
        Self {
            scene: InstanceScene {
                epoch,
                ..Default::default()
            },
            ..Default::default()
        }
    }

    pub fn scene(&self) -> &InstanceScene {
        &self.scene
    }

    pub fn sequence(&self) -> u64 {
        self.sequence
    }

    pub fn triangle_count(&self) -> usize {
        self.triangle_count
    }

    /// The caller supplies the other source streams' unique triangle storage use.
    /// Preparation borrows the old state; publication cannot fail after validation.
    pub fn submit(
        &mut self,
        bytes: &[u8],
        textures: &BTreeMap<u32, Texture>,
        other_triangles: usize,
    ) -> Result<(), String> {
        let batch = InstanceBatch::parse(bytes)?;
        let plan = self.plan(batch, textures, other_triangles)?;
        self.apply(plan);
        Ok(())
    }

    // Private plans cannot be applied to a different context or after intervening mutation.
    fn plan(
        &self,
        batch: InstanceBatch,
        textures: &BTreeMap<u32, Texture>,
        other_triangles: usize,
    ) -> Result<Plan, String> {
        if batch.epoch == 0 || batch.epoch != self.scene.epoch {
            return Err("stale or uninitialized instance resource epoch".into());
        }
        if batch.sequence <= self.sequence {
            return Err("instance batch sequence must increase within its resource epoch".into());
        }
        validate_revisions(
            batch
                .prototypes
                .iter()
                .map(|(id, p)| (*id, p.revision))
                .chain(batch.prototype_removals.iter().copied()),
            batch.sequence,
        )?;
        validate_revisions(
            batch
                .instances
                .iter()
                .map(|(id, i)| (*id, i.revision))
                .chain(batch.instance_removals.iter().copied()),
            batch.sequence,
        )?;

        let prototype_count = self.scene.prototypes.len()
            - batch
                .prototype_removals
                .iter()
                .filter(|(id, _)| self.scene.prototypes.contains_key(id))
                .count()
            + batch
                .prototypes
                .keys()
                .filter(|id| !self.scene.prototypes.contains_key(id))
                .count();
        let instance_count = self.scene.instances.len()
            - batch
                .instance_removals
                .iter()
                .filter(|(id, _)| self.scene.instances.contains_key(id))
                .count()
            + batch
                .instances
                .keys()
                .filter(|id| !self.scene.instances.contains_key(id))
                .count();
        if prototype_count > MAX_IDENTITIES || instance_count > MAX_IDENTITIES {
            return Err("resident prototype or instance capacity exceeded".into());
        }

        let removed: BTreeSet<_> = batch.prototype_removals.iter().map(|(id, _)| *id).collect();
        let mut triangles = self.triangle_count;
        for (id, _) in &batch.prototype_removals {
            if let Some(old) = self.scene.prototypes.get(id) {
                triangles -= old.triangles.len();
            }
        }
        for (id, prototype) in &batch.prototypes {
            triangles -= self
                .scene
                .prototypes
                .get(id)
                .map_or(0, |p| p.triangles.len());
            triangles += prototype.triangles.len();
            for triangle in &*prototype.triangles {
                if triangle.texture_id != 0 && !textures.contains_key(&triangle.texture_id) {
                    return Err("prototype references a texture that has not been captured".into());
                }
            }
        }
        if other_triangles
            .checked_add(triangles)
            .is_none_or(|total| total > MAX_TRIANGLES)
        {
            return Err("scene exceeds 8 million unique triangle capacity".into());
        }

        // Only touched identities are visited. Reference counts avoid scanning all instances
        // when removing one prototype or moving one instance to another prototype.
        let mut reference_delta = BTreeMap::<u64, isize>::new();
        let mut instance_changed = false;
        for (id, instance) in &batch.instances {
            if removed.contains(&instance.prototype_id)
                || (!batch.prototypes.contains_key(&instance.prototype_id)
                    && !self.scene.prototypes.contains_key(&instance.prototype_id))
            {
                return Err(
                    "instance references a prototype absent from the final batch state".into(),
                );
            }
            if instance.texture_id != 0
                && instance.texture_id != u32::MAX
                && !textures.contains_key(&instance.texture_id)
            {
                return Err(
                    "instance override references a texture that has not been captured".into(),
                );
            }
            if let Some(old) = self.scene.instances.get(id) {
                *reference_delta.entry(old.prototype_id).or_default() -= 1;
                // Revision is source ordering, not a render value.
                instance_changed |= old.prototype_id != instance.prototype_id
                    || old.origin != instance.origin
                    || old.transform != instance.transform
                    || old.texture_id != instance.texture_id
                    || old.flags != instance.flags
                    || old.tint != instance.tint
                    || old.uv_transform != instance.uv_transform;
            } else {
                instance_changed = true;
            }
            *reference_delta.entry(instance.prototype_id).or_default() += 1;
        }
        for (id, _) in &batch.instance_removals {
            if let Some(old) = self.scene.instances.get(id) {
                *reference_delta.entry(old.prototype_id).or_default() -= 1;
                instance_changed = true;
            }
        }
        let mut reference_counts = BTreeMap::new();
        for (id, delta) in reference_delta {
            let count = self.references.get(&id).copied().unwrap_or(0);
            let final_count = count
                .checked_add_signed(delta)
                .ok_or("instance reference count overflow")?;
            reference_counts.insert(id, final_count);
        }
        for id in &removed {
            if reference_counts
                .get(id)
                .or_else(|| self.references.get(id))
                .copied()
                .unwrap_or(0)
                != 0
            {
                return Err("cannot remove a prototype referenced by surviving instances".into());
            }
        }
        let resource_changed = !batch.prototypes.is_empty()
            || removed
                .iter()
                .any(|id| self.scene.prototypes.contains_key(id));
        let resource_revision = next_revision(self.scene.resource_revision, resource_changed)?;
        let instance_revision = next_revision(self.scene.instance_revision, instance_changed)?;
        Ok(Plan {
            batch,
            reference_counts,
            triangle_count: triangles,
            resource_revision,
            instance_revision,
        })
    }

    fn apply(&mut self, plan: Plan) {
        let Plan {
            batch,
            reference_counts,
            triangle_count,
            resource_revision,
            instance_revision,
        } = plan;
        for (id, prototype) in batch.prototypes {
            self.scene.prototypes.insert(id, prototype);
        }
        for (id, _) in batch.prototype_removals {
            self.scene.prototypes.remove(&id);
        }
        for (id, instance) in batch.instances {
            self.scene.instances.insert(id, instance);
        }
        for (id, _) in batch.instance_removals {
            self.scene.instances.remove(&id);
        }
        for (id, count) in reference_counts {
            if count == 0 {
                self.references.remove(&id);
            } else {
                self.references.insert(id, count);
            }
        }
        self.triangle_count = triangle_count;
        self.sequence = batch.sequence;
        self.scene.resource_revision = resource_revision;
        self.scene.instance_revision = instance_revision;
    }
}

fn next_revision(current: u64, changed: bool) -> Result<u64, String> {
    current
        .checked_add(u64::from(changed))
        .ok_or_else(|| "instance scene revision exhausted".into())
}

fn validate_revisions(
    changes: impl Iterator<Item = (u64, u64)>,
    sequence: u64,
) -> Result<(), String> {
    let mut seen = BTreeSet::new();
    for (id, revision) in changes {
        if id == 0 || !seen.insert(id) {
            return Err(
                "instance batch identities must be nonzero and unique within each category".into(),
            );
        }
        if revision != sequence {
            return Err("prototype and instance revisions must equal their batch sequence".into());
        }
    }
    Ok(())
}
