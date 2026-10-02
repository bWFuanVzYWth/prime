//! Persistent instance state: pure batch validation, then one explicit mutation phase.
use crate::incremental::ContextId;
use crate::{
    protocol::{InstanceBatch, validate_triangle_capacity},
    scene::{Instance, InstanceScene, Texture},
};
use std::collections::{BTreeMap, BTreeSet};
use std::ops::Deref;

const MAX_IDENTITIES: usize = 262_144;

/// Owns persistent source state and reusable, exclusively borrowed decode/plan storage.
/// No source pointers, GPU handles or interior mutability are retained.
#[derive(Default)]
pub struct InstanceContext {
    state: InstanceState,
    batch: InstanceBatch,
    scratch: PlanScratch,
    publication: Publication,
    pub(crate) texture_lifetime: crate::texture_lifetime::TextureLifetime,
}

/// A publication cursor cannot be manufactured from a source sequence or another owner.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct InstanceCursor {
    owner: ContextId,
    sequence: u64,
}

#[derive(Default)]
struct Changes {
    prototypes: BTreeSet<u64>,
    instances: BTreeSet<u64>,
}

#[derive(Default)]
struct Publication {
    owner: ContextId,
    from: u64,
    sequence: u64,
    changes: Changes,
    pending: Changes,
}

/// Read-only, owner-certified changes; standalone fixtures explicitly use full comparison.
#[derive(Clone, Copy)]
pub struct InstanceInput<'a> {
    scene: &'a InstanceScene,
    publication: Option<&'a Publication>,
}
impl Deref for InstanceInput<'_> {
    type Target = InstanceScene;
    fn deref(&self) -> &Self::Target {
        self.scene
    }
}
impl<'a> From<&'a InstanceScene> for InstanceInput<'a> {
    fn from(scene: &'a InstanceScene) -> Self {
        Self {
            scene,
            publication: None,
        }
    }
}
impl<'a> InstanceInput<'a> {
    pub(crate) fn cursor(self) -> Option<InstanceCursor> {
        self.publication.map(|p| InstanceCursor {
            owner: p.owner,
            sequence: p.sequence,
        })
    }
    pub(crate) fn changes(
        self,
        cursor: Option<InstanceCursor>,
    ) -> Option<(&'a BTreeSet<u64>, &'a BTreeSet<u64>)> {
        let p = self.publication?;
        let cursor = cursor?;
        (cursor.owner == p.owner && cursor.sequence == p.from)
            .then_some((&p.changes.prototypes, &p.changes.instances))
    }
}

fn same_content(a: &Instance, b: &Instance) -> bool {
    a.prototype_id == b.prototype_id
        && a.origin == b.origin
        && a.transform == b.transform
        && a.texture_id == b.texture_id
        && a.flags == b.flags
        && a.tint == b.tint
        && a.uv_transform == b.uv_transform
}

/// Published state is separate from reusable preparation storage, so planning can
/// borrow it read-only while writing scratch without moving or cloning live maps.
#[derive(Default)]
struct InstanceState {
    scene: InstanceScene,
    sequence: u64,
    references: BTreeMap<u64, usize>,
    triangle_count: usize,
}

#[derive(Default)]
struct PlanScratch {
    deltas: Vec<(u64, isize)>,
    counts: Vec<(u64, usize)>,
}

impl PlanScratch {
    fn clear(&mut self) {
        self.deltas.clear();
        self.counts.clear();
    }

    fn reference(&mut self, id: u64, delta: isize) -> Result<(), String> {
        self.deltas
            .try_reserve(1)
            .map_err(|_| "instance reference plan allocation failed")?;
        self.deltas.push((id, delta));
        Ok(())
    }
}

struct Plan {
    triangle_count: usize,
    resource_revision: u64,
    instance_revision: u64,
}

impl InstanceContext {
    pub fn new(epoch: u64) -> Self {
        Self {
            state: InstanceState {
                scene: InstanceScene {
                    epoch,
                    ..Default::default()
                },
                ..Default::default()
            },
            ..Default::default()
        }
    }

    pub fn scene(&self) -> &InstanceScene {
        &self.state.scene
    }

    /// Seal all accepted changes. No CPU consumer is allowed to mutate this publication.
    /// A skipped consumer detects the cursor gap and resynchronizes from the resident state.
    pub fn publish(&mut self) {
        let p = &mut self.publication;
        if p.pending.prototypes.is_empty() && p.pending.instances.is_empty() {
            return;
        }
        std::mem::swap(&mut p.changes, &mut p.pending);
        p.pending.prototypes.clear();
        p.pending.instances.clear();
        p.from = p.sequence;
        p.sequence = self.state.sequence;
    }

    pub fn input(&self) -> InstanceInput<'_> {
        let p = &self.publication;
        InstanceInput {
            scene: self.scene(),
            publication: (p.pending.prototypes.is_empty() && p.pending.instances.is_empty())
                .then_some(p),
        }
    }

    pub fn sequence(&self) -> u64 {
        self.state.sequence
    }

    pub fn triangle_count(&self) -> usize {
        self.state.triangle_count
    }

    /// Only scratch changes before complete validation. Success and failure both release
    /// decoded values while retaining capacities; stable pose batches need no allocations.
    #[cfg(any(test, feature = "legacy-fixtures"))]
    pub fn submit(
        &mut self,
        bytes: &[u8],
        textures: &BTreeMap<u32, Texture>,
        other_triangles: usize,
    ) -> Result<(), String> {
        let result = self.batch.decode(bytes).and_then(|()| {
            plan(
                &self.state,
                &self.batch,
                textures,
                other_triangles,
                &mut self.scratch,
            )
        });
        let result = result.map(|plan| self.apply(plan));
        self.batch.clear();
        self.scratch.clear();
        result
    }

    pub fn submit_typed(
        &mut self,
        view: prime_abi::scene::InstancesView<'_>,
        textures: &BTreeMap<u32, Texture>,
        other_triangles: usize,
    ) -> Result<(), String> {
        let result = self.batch.decode_typed(view).and_then(|()| {
            plan(
                &self.state,
                &self.batch,
                textures,
                other_triangles,
                &mut self.scratch,
            )
        });
        let result = result.map(|plan| self.apply(plan));
        self.batch.clear();
        self.scratch.clear();
        result
    }

    // The private plan is applied synchronously to exactly the state it validated.
    fn apply(&mut self, plan: Plan) {
        let state = &mut self.state;
        for (id, prototype) in self.batch.prototypes.drain(..) {
            for triangle in &*prototype.triangles {
                self.texture_lifetime.acquire(triangle.texture_id);
            }
            if let Some(old) = state.scene.prototypes.get(&id) {
                for triangle in &*old.triangles {
                    self.texture_lifetime.release(triangle.texture_id);
                }
            }
            self.publication.pending.prototypes.insert(id);
            state.scene.prototypes.insert(id, prototype);
        }
        for (id, _) in &self.batch.prototype_removals {
            if let Some(old) = state.scene.prototypes.remove(id) {
                for triangle in &*old.triangles {
                    self.texture_lifetime.release(triangle.texture_id);
                }
                self.publication.pending.prototypes.insert(*id);
            }
        }
        for (id, instance) in self.batch.instances.drain(..) {
            if state
                .scene
                .instances
                .get(&id)
                .is_none_or(|old| !same_content(old, &instance))
            {
                self.publication.pending.instances.insert(id);
            }
            if state
                .scene
                .instances
                .get(&id)
                .is_none_or(|old| old.texture_id != instance.texture_id)
            {
                self.texture_lifetime.acquire(instance.texture_id);
                if let Some(old) = state.scene.instances.get(&id) {
                    self.texture_lifetime.release(old.texture_id);
                }
            }
            state.scene.instances.insert(id, instance);
        }
        for (id, _) in &self.batch.instance_removals {
            if let Some(old) = state.scene.instances.remove(id) {
                self.texture_lifetime.release(old.texture_id);
                self.publication.pending.instances.insert(*id);
            }
        }
        for &(id, count) in &self.scratch.counts {
            if count == 0 {
                state.references.remove(&id);
            } else {
                state.references.insert(id, count);
            }
        }
        state.triangle_count = plan.triangle_count;
        state.sequence = self.batch.sequence;
        state.scene.resource_revision = plan.resource_revision;
        state.scene.instance_revision = plan.instance_revision;
    }
}

/// Reads scene state and writes only caller-owned temporary storage. Work is bounded by
/// touched records, without scanning resident maps or copying their geometry Arcs.
fn plan(
    state: &InstanceState,
    batch: &InstanceBatch,
    textures: &BTreeMap<u32, Texture>,
    other_triangles: usize,
    scratch: &mut PlanScratch,
) -> Result<Plan, String> {
    let InstanceState {
        scene,
        sequence,
        references,
        triangle_count,
    } = state;
    if batch.epoch == 0 || batch.epoch != scene.epoch {
        return Err("stale or uninitialized instance resource epoch".into());
    }
    if batch.sequence <= *sequence {
        return Err("instance batch sequence must increase within its resource epoch".into());
    }
    let mut prototype_count = scene.prototypes.len();
    let mut triangles = *triangle_count;
    let mut resource_changed = !batch.prototypes.is_empty();
    for (id, _) in &batch.prototype_removals {
        if let Some(old) = scene.prototypes.get(id) {
            triangles -= old.triangles.len();
            prototype_count -= 1;
            resource_changed = true;
        }
    }
    for (id, prototype) in &batch.prototypes {
        if let Some(old) = scene.prototypes.get(id) {
            triangles -= old.triangles.len();
        } else {
            prototype_count += 1;
        }
        triangles += prototype.triangles.len();
        for triangle in &*prototype.triangles {
            if triangle.texture_id != 0 && !textures.contains_key(&triangle.texture_id) {
                return Err("prototype references a texture that has not been captured".into());
            }
        }
    }
    validate_triangle_capacity(
        other_triangles
            .checked_add(triangles)
            .ok_or("scene triangle count overflow")?,
    )?;

    let mut instance_count = scene.instances.len();
    let mut instance_changed = false;
    for (id, instance) in &batch.instances {
        let old = scene.instances.get(id);
        // A resident reference already proves this prototype exists. If the batch removes
        // it, the final reference-count check below rejects its surviving references.
        if old.is_none_or(|old| old.prototype_id != instance.prototype_id) {
            if contains(&batch.prototype_removals, instance.prototype_id)
                || (!contains(&batch.prototypes, instance.prototype_id)
                    && !scene.prototypes.contains_key(&instance.prototype_id))
            {
                return Err(
                    "instance references a prototype absent from the final batch state".into(),
                );
            }
            if let Some(old) = old {
                scratch.reference(old.prototype_id, -1)?;
            }
            scratch.reference(instance.prototype_id, 1)?;
        }
        if instance.texture_id != 0
            && instance.texture_id != u32::MAX
            && !textures.contains_key(&instance.texture_id)
        {
            return Err("instance override references a texture that has not been captured".into());
        }
        if let Some(old) = old {
            // Revision proves source ordering; only actual render fields change render identity.
            if !instance_changed {
                instance_changed = old.prototype_id != instance.prototype_id
                    || old.origin != instance.origin
                    || old.transform != instance.transform
                    || old.texture_id != instance.texture_id
                    || old.flags != instance.flags
                    || old.tint != instance.tint
                    || old.uv_transform != instance.uv_transform;
            }
        } else {
            instance_count += 1;
            instance_changed = true;
        }
    }
    for (id, _) in &batch.instance_removals {
        if let Some(old) = scene.instances.get(id) {
            scratch.reference(old.prototype_id, -1)?;
            instance_count -= 1;
            instance_changed = true;
        }
    }
    if prototype_count > MAX_IDENTITIES || instance_count > MAX_IDENTITIES {
        return Err("resident prototype or instance capacity exceeded".into());
    }

    scratch.deltas.sort_unstable_by_key(|(id, _)| *id);
    let mut cursor = 0;
    while let Some(&(id, first)) = scratch.deltas.get(cursor) {
        let mut delta = first;
        cursor += 1;
        while let Some(&(next_id, next_delta)) = scratch.deltas.get(cursor) {
            if next_id != id {
                break;
            }
            delta += next_delta;
            cursor += 1;
        }
        if delta == 0 {
            continue;
        }
        let final_count = references
            .get(&id)
            .copied()
            .unwrap_or(0)
            .checked_add_signed(delta)
            .ok_or("instance reference count overflow")?;
        scratch
            .counts
            .try_reserve(1)
            .map_err(|_| "instance reference plan allocation failed")?;
        scratch.counts.push((id, final_count));
    }
    for (id, _) in &batch.prototype_removals {
        let count = scratch
            .counts
            .binary_search_by_key(id, |(id, _)| *id)
            .ok()
            .map(|index| scratch.counts[index].1)
            .unwrap_or_else(|| references.get(id).copied().unwrap_or(0));
        if count != 0 {
            return Err("cannot remove a prototype referenced by surviving instances".into());
        }
    }
    Ok(Plan {
        triangle_count: triangles,
        resource_revision: next_revision(scene.resource_revision, resource_changed)?,
        instance_revision: next_revision(scene.instance_revision, instance_changed)?,
    })
}

fn contains<T>(records: &[(u64, T)], id: u64) -> bool {
    records.binary_search_by_key(&id, |(id, _)| *id).is_ok()
}

fn next_revision(current: u64, changed: bool) -> Result<u64, String> {
    current
        .checked_add(u64::from(changed))
        .ok_or_else(|| "instance scene revision exhausted".into())
}
