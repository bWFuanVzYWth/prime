//! Incremental static batch planning. Source identities are opaque; placement comes from origins.
use super::translation;
use crate::{
    geometry::MeshGeometry,
    incremental::{ContextId, SceneInput, ScenePublication, TerrainGeneration, TerrainIndex},
    scene::{MeshKey, Scene, SceneMesh},
    spatial::Cell,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    ops::{Bound, Range},
};

type Signature = Vec<(MeshKey, u64, [f32; 3], Range<usize>)>;
#[cfg(test)]
use crate::Triangle;

pub struct TerrainMember {
    pub triangles: MeshGeometry,
    pub range: Range<usize>,
    pub offset: [f32; 3],
}

pub struct TerrainUpdate {
    pub key: Cell,
    pub geometries: Vec<TerrainGeometry>,
}

/// Separate material/index ranges inside one static acceleration structure.
pub struct TerrainGeometry {
    pub flags: u32,
    pub members: Vec<TerrainMember>,
    pub triangle_count: u32,
}

pub struct TerrainPlacement {
    pub key: Cell,
    pub transform: [f32; 12],
}

pub struct TerrainPlan {
    pub removed: Vec<Cell>,
    pub geometry: Vec<TerrainUpdate>,
    pub placements_changed: bool,
    /// Published source geometry changed. An identical-signature renderer rebuild does not
    /// invalidate sampling history merely because its acceleration structure is replaced.
    pub content_changed: bool,
    pub triangle_count: u64,
    /// Actual grouping work, useful for deterministic complexity regression tests.
    pub meshes_visited: usize,
    pub cells_visited: usize,
}

#[derive(Default)]
struct Group<'a> {
    signature: Signature,
    members: Vec<(&'a SceneMesh, Range<usize>, [f32; 3])>,
    triangle_count: u32,
}

pub struct TerrainPlanner {
    limits: TerrainLimits,
    epoch: Option<u64>,
    terrain_resource_generation: Option<u64>,
    revision: Option<u64>,
    anchor: [f64; 3],
    signatures: BTreeMap<Cell, Vec<(u32, Signature)>>,
    placements: Vec<TerrainPlacement>,
    triangle_count: u64,
    geometry_records: u64,
    spare_geometry: Vec<TerrainUpdate>,
    spare_removed: Vec<Cell>,
    cursor: Option<TerrainGeneration>,
    source: Option<ContextId>,
    publication: Option<ScenePublication>,
    pending: BTreeSet<Cell>,
    build_cursor: Option<Cell>,
    invalidated: BTreeSet<Cell>,
    withdrawals: BTreeSet<Cell>,
}

#[derive(Clone, Copy)]
pub struct TerrainLimits {
    pub triangles_per_geometry: u32,
    pub geometry_records: u32,
}

fn subtract_counts(old: &[(u32, Signature)], total: &mut u64, records: &mut u64) {
    *records -= old.len() as u64;
    for (_, members) in old {
        *total -= members
            .iter()
            .map(|(_, _, _, range)| range.len() as u64)
            .sum::<u64>();
    }
}

// Merge bounded ranges without copying/scanning the backlog. Duplicate dirty identities
// consume one turn; a removed or incomplete cell consumes no construction allowance.
fn select_cells<'a>(
    selected: &mut Vec<Cell>,
    pending: impl Iterator<Item = &'a Cell>,
    scheduled: impl Iterator<Item = &'a Cell>,
    withdrawn: &BTreeSet<Cell>,
    budget: usize,
) {
    let mut pending = pending.peekable();
    let mut scheduled = scheduled.peekable();
    while selected.len() < budget {
        let key = match (pending.peek(), scheduled.peek()) {
            (Some(&left), Some(&right)) if left == right => {
                scheduled.next();
                *pending.next().unwrap()
            }
            (Some(&left), Some(&right)) if left < right => *pending.next().unwrap(),
            (Some(_), Some(_)) | (None, Some(_)) => *scheduled.next().unwrap(),
            (Some(_), None) => *pending.next().unwrap(),
            (None, None) => break,
        };
        if !withdrawn.contains(&key) {
            selected.push(key);
        }
    }
}

impl TerrainPlanner {
    pub fn new(limits: TerrainLimits) -> Result<Self, String> {
        if limits.triangles_per_geometry == 0 || limits.geometry_records == 0 {
            return Err("Terrain limits must be nonzero".into());
        }
        Ok(Self {
            limits,
            epoch: None,
            terrain_resource_generation: None,
            revision: None,
            anchor: [0.0; 3],
            signatures: BTreeMap::new(),
            placements: Vec::new(),
            triangle_count: 0,
            geometry_records: 0,
            spare_geometry: Vec::new(),
            spare_removed: Vec::new(),
            cursor: None,
            source: None,
            publication: None,
            pending: BTreeSet::new(),
            build_cursor: None,
            invalidated: BTreeSet::new(),
            withdrawals: BTreeSet::new(),
        })
    }

    /// Renderer-derived resources changed without a source geometry publication.
    /// Keep signatures so rebuilding a cell subtracts its previous counts exactly once.
    pub fn invalidate_cells(&mut self, cells: impl IntoIterator<Item = Cell>) {
        self.invalidated.extend(cells);
    }

    /// Published geometry refers to a revoked external resource identity. Withdraw it before
    /// the next render, but rebuild still-current source cells through the normal cell budget.
    pub fn withdraw_cells(&mut self, cells: impl IntoIterator<Item = Cell>) {
        self.withdrawals.extend(cells);
    }

    pub fn has_pending(&self) -> bool {
        !self.pending.is_empty() || !self.invalidated.is_empty() || !self.withdrawals.is_empty()
    }

    /// Mutable diagnostics have no producer certificate: validate and index their full snapshot.
    pub fn plan(&mut self, scene: &Scene) -> Result<TerrainPlan, String> {
        self.plan_limited(scene, usize::MAX)
    }

    /// Bound cell grouping, not individual sections or material parts. Zero still withdraws
    /// unavailable cells and rebases published placements without constructing geometry.
    pub fn plan_limited(&mut self, scene: &Scene, budget: usize) -> Result<TerrainPlan, String> {
        if scene.anchor.iter().any(|value| !value.is_finite()) {
            return Err("Invalid scene anchor".into());
        }
        let unchanged_snapshot = self.epoch == Some(scene.epoch)
            && self.revision == Some(scene.revision)
            && self.terrain_resource_generation == Some(scene.terrain_resource_generation);
        if unchanged_snapshot && self.anchor == scene.anchor && !self.has_pending() {
            return Ok(self.empty_plan());
        }
        let mut index = TerrainIndex {
            from: self.cursor.unwrap_or_default(),
            generation: self.cursor.unwrap_or_default().next()?,
            reset: true,
            ..Default::default()
        };
        for (&id, mesh) in &scene.meshes {
            if mesh.triangles.is_empty() {
                continue;
            }
            if mesh.flags > 2 {
                return Err("Unsupported mesh material flags".into());
            }
            if mesh
                .origin
                .iter()
                .any(|value| !value.is_finite() || value.abs() > 33_000_000.0)
            {
                return Err("Invalid mesh world origin".into());
            }
            index
                .cells
                .entry(Cell::containing(mesh.origin)?)
                .or_default()
                .insert(id);
        }
        let plan = self.plan_index_limited(scene.into(), &index, budget, unchanged_snapshot)?;
        self.revision = Some(scene.revision);
        self.cursor = None;
        Ok(plan)
    }

    fn empty_plan(&mut self) -> TerrainPlan {
        TerrainPlan {
            removed: std::mem::take(&mut self.spare_removed),
            geometry: std::mem::take(&mut self.spare_geometry),
            placements_changed: false,
            content_changed: false,
            triangle_count: self.triangle_count,
            meshes_visited: 0,
            cells_visited: 0,
        }
    }

    pub fn plan_input(&mut self, scene: SceneInput<'_>) -> Result<TerrainPlan, String> {
        self.plan_input_limited(scene, usize::MAX)
    }

    pub fn plan_input_limited(
        &mut self,
        scene: SceneInput<'_>,
        budget: usize,
    ) -> Result<TerrainPlan, String> {
        match scene.terrain() {
            None => self.plan_limited(&scene, budget),
            Some(index) => self.plan_index_limited(scene, index, budget, false),
        }
    }

    #[cfg(test)]
    fn plan_incremental(
        &mut self,
        scene: SceneInput<'_>,
        index: &TerrainIndex,
    ) -> Result<TerrainPlan, String> {
        self.plan_index_limited(scene, index, usize::MAX, false)
    }

    fn plan_index_limited(
        &mut self,
        scene: SceneInput<'_>,
        index: &TerrainIndex,
        budget: usize,
        unchanged_snapshot: bool,
    ) -> Result<TerrainPlan, String> {
        if scene.anchor.iter().any(|value| !value.is_finite()) {
            return Err("Invalid scene anchor".into());
        }
        let publication = scene.publication();
        let reset = self.epoch != Some(scene.epoch)
            || self.terrain_resource_generation != Some(scene.terrain_resource_generation)
            || self.source != index.source
            || self
                .publication
                .is_none_or(|old| !old.same_owner(publication));
        let changed = !unchanged_snapshot && (reset || self.cursor != Some(index.generation));
        let resync = !unchanged_snapshot
            && (reset
                || self.cursor.is_none()
                || (changed && (index.reset || self.cursor != Some(index.from))));
        let rebase = reset || self.anchor != scene.anchor;
        let mut plan = self.empty_plan();
        if !changed && !rebase && !self.has_pending() {
            return Ok(plan);
        }

        // A cursor gap is an explicit resynchronization, never a guessed delta.
        // The normal path visits only cells affected since the previous publication.
        let mut full;
        let cells = if resync {
            full = index
                .cells
                .keys()
                .chain(self.signatures.keys())
                .chain(self.pending.iter())
                .chain(self.invalidated.iter())
                .chain(self.withdrawals.iter())
                .copied()
                .collect();
            &full
        } else if !self.invalidated.is_empty() || !self.withdrawals.is_empty() {
            full = self.invalidated.clone();
            full.extend(&self.withdrawals);
            if changed {
                full.extend(&index.changed);
            }
            &full
        } else if changed {
            &index.changed
        } else {
            &BTreeSet::new()
        };
        let mut scheduled = BTreeSet::new();
        let mut withdrawn = BTreeSet::new();
        for &key in cells {
            if scene.ready_terrain.contains(&key)
                && index
                    .cells
                    .get(&key)
                    .is_some_and(|members| !members.is_empty())
            {
                scheduled.insert(key);
            } else {
                withdrawn.insert(key);
                plan.cells_visited += 1;
            }
        }
        let empty = BTreeSet::new();
        let pending = if reset { &empty } else { &self.pending };
        let mut selected = Vec::new();
        if let Some(cursor) = self.build_cursor.filter(|_| !reset) {
            let after = (Bound::Excluded(cursor), Bound::Unbounded);
            select_cells(
                &mut selected,
                pending.range(after),
                scheduled.range(after),
                &withdrawn,
                budget,
            );
            select_cells(
                &mut selected,
                pending.range(..=cursor),
                scheduled.range(..=cursor),
                &withdrawn,
                budget,
            );
        } else {
            select_cells(
                &mut selected,
                pending.iter(),
                scheduled.iter(),
                &withdrawn,
                budget,
            );
        }
        let mut replacements = Vec::new();
        let mut total = if reset { 0 } else { self.triangle_count };
        let mut records = if reset { 0 } else { self.geometry_records };
        if reset {
            plan.removed.extend(self.signatures.keys().copied());
        } else {
            for key in withdrawn.union(&self.withdrawals) {
                if let Some(old) = self.signatures.get(key) {
                    subtract_counts(old, &mut total, &mut records);
                    plan.removed.push(*key);
                }
            }
        }
        plan.content_changed = reset || !plan.removed.is_empty();
        // Selection precedes all member grouping and owned geometry construction. Queued
        // cells retain only identity and always consume the current immutable publication.
        for &key in &selected {
            plan.cells_visited += 1;
            let mut materials: [Vec<Group<'_>>; 3] = Default::default();
            if scene.ready_terrain.contains(&key)
                && let Some(members) = index.cells.get(&key)
            {
                for id in members {
                    let mesh = &scene.meshes[id];
                    plan.meshes_visited += 1;
                    let offset = std::array::from_fn(|i| (mesh.origin[i] - key.origin()[i]) as f32);
                    let parts = &mut materials[mesh.flags as usize];
                    let mut first = 0;
                    while first < mesh.triangles.len() {
                        if parts.last().is_none_or(|part| {
                            part.triangle_count == self.limits.triangles_per_geometry
                        }) {
                            parts.push(Group::default());
                        }
                        let group = parts.last_mut().unwrap();
                        let count = (mesh.triangles.len() - first).min(
                            (self.limits.triangles_per_geometry - group.triangle_count) as usize,
                        );
                        let range = first..first + count;
                        group
                            .signature
                            .push((*id, mesh.revision, offset, range.clone()));
                        group.members.push((mesh, range, offset));
                        group.triangle_count += count as u32;
                        first += count;
                    }
                }
            }
            let previous = if reset || self.withdrawals.contains(&key) {
                None
            } else {
                self.signatures.get(&key)
            };
            let count: usize = materials.iter().map(Vec::len).sum();
            let same_signature = previous.is_some_and(|old| {
                old.len() == count
                    && old
                        .iter()
                        .zip(materials.iter().enumerate().flat_map(|(flags, parts)| {
                            parts.iter().map(move |part| (flags as u32, part))
                        }))
                        .all(|((flags, signature), (new_flags, group))| {
                            *flags == new_flags && *signature == group.signature
                        })
            });
            let unchanged = !self.invalidated.contains(&key) && same_signature;
            if unchanged || (count == 0 && previous.is_none()) {
                continue;
            }
            plan.content_changed |= !same_signature;
            if let Some(old) = previous {
                subtract_counts(old, &mut total, &mut records);
            }
            records += count as u64;
            let mut signatures = Vec::with_capacity(count);
            let mut geometries = Vec::with_capacity(count);
            for (flags, parts) in materials.into_iter().enumerate() {
                for group in parts {
                    total = total
                        .checked_add(u64::from(group.triangle_count))
                        .ok_or("Static triangle count overflow")?;
                    signatures.push((flags as u32, group.signature));
                    geometries.push(TerrainGeometry {
                        flags: flags as u32,
                        members: group
                            .members
                            .into_iter()
                            .map(|(mesh, range, offset)| TerrainMember {
                                triangles: mesh.triangles.clone(),
                                range,
                                offset,
                            })
                            .collect(),
                        triangle_count: group.triangle_count,
                    });
                }
            }
            replacements.push((key, signatures, geometries));
        }
        if records > u64::from(self.limits.geometry_records) {
            return Err("Too many static geometry metadata records".into());
        }
        let mut membership_changed = reset;
        // All checks precede mutation of the consumer state/cursor.
        if reset {
            self.signatures.clear();
            self.pending.clear();
            self.invalidated.clear();
            self.build_cursor = None;
        }
        for key in &self.withdrawals {
            membership_changed |= self.signatures.remove(key).is_some();
        }
        self.withdrawals.clear();
        self.pending.extend(scheduled);
        for key in withdrawn {
            membership_changed |= self.signatures.remove(&key).is_some();
            self.pending.remove(&key);
            self.invalidated.remove(&key);
        }
        for key in &selected {
            self.pending.remove(key);
            self.invalidated.remove(key);
        }
        if let Some(&last) = selected.last() {
            self.build_cursor = Some(last);
        }
        for (key, signature, geometries) in replacements {
            if signature.is_empty() {
                self.signatures.remove(&key);
                if !reset {
                    plan.removed.push(key);
                }
                membership_changed = true;
            } else {
                membership_changed |= self.signatures.insert(key, signature).is_none();
                plan.geometry.push(TerrainUpdate { key, geometries });
            }
        }
        plan.placements_changed = rebase || !plan.geometry.is_empty() || !plan.removed.is_empty();
        if membership_changed || rebase {
            self.placements.clear();
            self.placements
                .extend(self.signatures.keys().map(|&key| TerrainPlacement {
                    key,
                    transform: translation(key.relative_origin(scene.anchor)),
                }));
        }
        self.epoch = Some(scene.epoch);
        self.terrain_resource_generation = Some(scene.terrain_resource_generation);
        self.revision = None; // A later diagnostic snapshot must validate independently.
        self.cursor = Some(index.generation);
        self.source = index.source;
        self.publication = Some(publication);
        self.anchor = scene.anchor;
        self.triangle_count = total;
        self.geometry_records = records;
        plan.triangle_count = total;
        Ok(plan)
    }

    pub fn placements(&self) -> &[TerrainPlacement] {
        &self.placements
    }

    /// The executor has copied source data; its GPU completion does not borrow this plan.
    pub fn recycle(&mut self, mut plan: TerrainPlan) {
        plan.geometry.clear();
        plan.removed.clear();
        self.spare_geometry = plan.geometry;
        self.spare_removed = plan.removed;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mesh(origin: [f64; 3], count: usize, flags: u32) -> SceneMesh {
        SceneMesh {
            revision: 1,
            flags,
            origin,
            triangles: vec![
                Triangle {
                    positions: [[0.0; 3], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
                    colors: [[1.0; 4]; 3],
                    uvs: [[0.0; 2]; 3],
                    texture_id: 0,
                    flags,
                };
                count
            ]
            .into(),
        }
    }
    fn planner(limit: u32) -> TerrainPlanner {
        TerrainPlanner::new(TerrainLimits {
            triangles_per_geometry: limit,
            geometry_records: 1024,
        })
        .unwrap()
    }

    fn indexed_cells(count: usize) -> (Scene, TerrainIndex, Vec<Cell>) {
        let mut scene = Scene::default();
        let mut index = TerrainIndex {
            source: Some(ContextId::default()),
            generation: TerrainGeneration::default().next().unwrap(),
            ..Default::default()
        };
        let keys: Vec<_> = (0..count)
            .map(|i| {
                let origin = [i as f64 * 64.0, 0.0, 0.0];
                let key = Cell::containing(origin).unwrap();
                scene.meshes.insert((i as u64, 0), mesh(origin, 1, 0));
                scene.ready_terrain.insert(key);
                index.cells.insert(key, [(i as u64, 0)].into());
                key
            })
            .collect();
        (scene, index, keys)
    }

    fn publish(index: &mut TerrainIndex, cells: impl IntoIterator<Item = Cell>) {
        index.from = index.generation;
        index.generation = index.generation.next().unwrap();
        index.changed = cells.into_iter().collect();
        index.reset = false;
    }

    fn bounded(
        planner: &mut TerrainPlanner,
        scene: &Scene,
        index: &TerrainIndex,
        budget: usize,
    ) -> TerrainPlan {
        planner
            .plan_index_limited(scene.into(), index, budget, false)
            .unwrap()
    }

    fn assert_same_published(left: &TerrainPlanner, right: &TerrainPlanner) {
        assert_eq!(left.signatures, right.signatures);
        assert_eq!(left.triangle_count, right.triangle_count);
        assert_eq!(left.geometry_records, right.geometry_records);
        let placements = |planner: &TerrainPlanner| {
            planner
                .placements()
                .iter()
                .map(|p| (p.key, p.transform))
                .collect::<Vec<_>>()
        };
        assert_eq!(placements(left), placements(right));
    }

    #[test]
    fn bounded_same_publication_drains_before_grouping_and_matches_unbounded() {
        for budget in [1, 8, 128] {
            for snapshot in [false, true] {
                let (scene, index, keys) = indexed_cells(137);
                let mut limited = planner(32);
                let mut reference = planner(32);
                let initial = reference.plan(&scene).unwrap();
                reference.recycle(initial);
                let mut seen = BTreeSet::new();
                loop {
                    let plan = if snapshot {
                        limited.plan_input_limited((&scene).into(), budget).unwrap()
                    } else {
                        bounded(&mut limited, &scene, &index, budget)
                    };
                    assert_eq!(plan.geometry.len(), budget.min(keys.len() - seen.len()));
                    assert_eq!(plan.cells_visited, plan.geometry.len());
                    assert_eq!(plan.meshes_visited, plan.geometry.len());
                    assert!(plan.content_changed);
                    for batch in &plan.geometry {
                        assert!(
                            seen.insert(batch.key),
                            "same publication cannot requeue a built cell"
                        );
                    }
                    limited.recycle(plan);
                    if !limited.has_pending() {
                        break;
                    }
                }
                assert_eq!(seen, keys.into_iter().collect());
                assert_same_published(&limited, &reference);
                let steady = if snapshot {
                    limited.plan_limited(&scene, budget).unwrap()
                } else {
                    bounded(&mut limited, &scene, &index, budget)
                };
                assert!(steady.geometry.is_empty() && !steady.content_changed);
                assert_eq!((steady.cells_visited, steady.meshes_visited), (0, 0));
            }
        }
    }

    #[test]
    fn changed_budget_applies_to_the_next_drain_call() {
        let (scene, index, _) = indexed_cells(137);
        let mut planner = planner(32);
        for (budget, expected) in [(8, 8), (1, 1), (128, 128)] {
            let plan = bounded(&mut planner, &scene, &index, budget);
            assert_eq!(plan.geometry.len(), expected);
            planner.recycle(plan);
        }
        assert!(!planner.has_pending());
    }

    #[test]
    fn repeated_edits_coalesce_to_latest_source_and_keep_old_content_until_selected() {
        let (mut scene, mut index, keys) = indexed_cells(3);
        let mut planner = planner(32);
        let initial = planner.plan_incremental((&scene).into(), &index).unwrap();
        planner.recycle(initial);
        let old = planner.signatures[&keys[2]].clone();
        for member in scene.meshes.values_mut() {
            member.revision += 1;
        }
        publish(&mut index, keys.iter().copied());
        let first = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(first.geometry[0].key, keys[0]);
        assert_eq!(planner.signatures[&keys[2]], old);
        assert_eq!(planner.placements().len(), 3);
        planner.recycle(first);
        for revision in 3..8 {
            let mut latest = mesh(keys[2].origin(), revision as usize, 0);
            latest.revision = revision;
            scene.meshes.insert((2, 0), latest);
            publish(&mut index, [keys[2]]);
            let plan = bounded(&mut planner, &scene, &index, 0);
            assert!(plan.geometry.is_empty() && !plan.content_changed);
            assert_eq!(planner.signatures[&keys[2]], old);
            assert_eq!(planner.pending.len(), 2);
            planner.recycle(plan);
        }
        let middle = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(middle.geometry[0].key, keys[1]);
        planner.recycle(middle);
        let latest = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(latest.geometry[0].key, keys[2]);
        assert_eq!(latest.geometry[0].geometries[0].triangle_count, 7);
        assert!(
            latest.geometry[0].geometries[0].members[0]
                .triangles
                .ptr_eq(&scene.meshes[&(2, 0)].triangles)
        );
        assert_eq!(latest.triangle_count, 9);
        assert!(!planner.has_pending());
    }

    #[test]
    fn repeated_low_coordinate_edits_do_not_starve_existing_backlog() {
        let (mut scene, mut index, keys) = indexed_cells(10);
        let mut planner = planner(32);
        let initial = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(initial.geometry[0].key, keys[0]);
        planner.recycle(initial);
        for key in &keys[1..] {
            scene.meshes.get_mut(&(0, 0)).unwrap().revision += 1;
            publish(&mut index, [keys[0]]);
            let next = bounded(&mut planner, &scene, &index, 1);
            assert_eq!(next.geometry[0].key, *key);
            planner.recycle(next);
        }
        let wrapped = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(wrapped.geometry[0].key, keys[0]);
        assert!(!planner.has_pending());
    }

    #[test]
    fn withdrawals_and_revoked_readiness_bypass_a_zero_build_budget() {
        let (mut scene, mut index, keys) = indexed_cells(4);
        let mut planner = planner(32);
        let initial = bounded(&mut planner, &scene, &index, 2);
        planner.recycle(initial);
        index.cells.remove(&keys[0]);
        scene.meshes.remove(&(0, 0));
        scene.ready_terrain.remove(&keys[1]);
        scene.ready_terrain.remove(&keys[2]); // Pending, never published.
        publish(&mut index, keys[..3].iter().copied());
        let withdrawn = bounded(&mut planner, &scene, &index, 0);
        assert_eq!(withdrawn.removed, keys[..2]);
        assert!(withdrawn.geometry.is_empty() && withdrawn.content_changed);
        assert_eq!(withdrawn.triangle_count, 0);
        assert_eq!(withdrawn.meshes_visited, 0);
        assert!(planner.placements().is_empty());
        assert_eq!(planner.pending, [keys[3]].into());
        planner.recycle(withdrawn);
        let last = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(last.geometry[0].key, keys[3]);
        planner.recycle(last);
        scene.ready_terrain.insert(keys[1]);
        publish(&mut index, [keys[1]]);
        let restored = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(restored.geometry[0].key, keys[1]);
    }

    #[test]
    fn epoch_and_source_owner_changes_withdraw_old_domain_before_limited_rebuild() {
        for epoch_reset in [false, true] {
            let (mut scene, mut index, keys) = indexed_cells(4);
            let mut planner = planner(32);
            let initial = bounded(&mut planner, &scene, &index, 2);
            planner.recycle(initial);
            planner.invalidate_cells([keys[0], keys[3]]);
            if epoch_reset {
                scene.epoch += 1;
            } else {
                index.source = Some(ContextId::default());
            }
            let reset = bounded(&mut planner, &scene, &index, 1);
            assert_eq!(reset.removed, keys[..2]);
            assert_eq!(reset.geometry.len(), 1);
            assert_eq!(reset.triangle_count, 1);
            assert!(reset.content_changed);
            assert_eq!(planner.placements().len(), 1);
            assert_eq!(planner.pending.len(), 3);
            planner.recycle(reset);
            while planner.has_pending() {
                let plan = bounded(&mut planner, &scene, &index, 1);
                assert!(plan.removed.is_empty());
                planner.recycle(plan);
            }
            let mut reference = self::planner(32);
            let all = reference.plan(&scene).unwrap();
            reference.recycle(all);
            assert_same_published(&planner, &reference);
        }
    }

    #[test]
    fn missed_or_reset_publication_recovers_removals_and_keeps_backlog() {
        for reset in [false, true] {
            let (mut scene, mut index, keys) = indexed_cells(4);
            let mut planner = planner(32);
            let initial = bounded(&mut planner, &scene, &index, 1);
            planner.recycle(initial);
            scene.meshes.remove(&(0, 0));
            index.cells.remove(&keys[0]);
            publish(&mut index, [keys[0]]); // Consumer misses this publication.
            scene.meshes.get_mut(&(3, 0)).unwrap().revision += 1;
            publish(&mut index, [keys[3]]);
            index.reset = reset;
            let recovered = bounded(&mut planner, &scene, &index, 1);
            assert_eq!(recovered.removed, [keys[0]]);
            assert_eq!(recovered.geometry[0].key, keys[1]);
            planner.recycle(recovered);
            while planner.has_pending() {
                let next = bounded(&mut planner, &scene, &index, 1);
                planner.recycle(next);
            }
            assert_eq!(planner.signatures.len(), 3);
            assert_eq!(planner.signatures[&keys[3]][0].1[0].1, 2);
        }
    }

    #[test]
    fn rebase_updates_all_published_placements_and_future_pending_cells() {
        let (mut scene, index, keys) = indexed_cells(4);
        let mut planner = planner(32);
        let initial = bounded(&mut planner, &scene, &index, 2);
        planner.recycle(initial);
        scene.anchor = [256., -128., 512.];
        let rebase = bounded(&mut planner, &scene, &index, 0);
        assert!(rebase.placements_changed && !rebase.content_changed);
        assert_eq!((rebase.cells_visited, rebase.meshes_visited), (0, 0));
        for placement in planner.placements() {
            assert_eq!(
                placement.transform,
                translation(placement.key.relative_origin(scene.anchor))
            );
        }
        planner.recycle(rebase);
        let next = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(next.geometry[0].key, keys[2]);
        assert_eq!(next.geometry[0].geometries[0].members[0].offset, [0.; 3]);
        assert!(
            planner
                .placements()
                .iter()
                .all(|p| p.transform == translation(p.key.relative_origin(scene.anchor)))
        );
    }

    #[test]
    fn delayed_renderer_invalidation_is_forced_but_does_not_change_source_content() {
        let (scene, index, keys) = indexed_cells(3);
        let mut planner = planner(32);
        let initial = planner.plan_incremental((&scene).into(), &index).unwrap();
        planner.recycle(initial);
        planner.invalidate_cells([keys[0], keys[2], keys[2]]);
        let first = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(first.geometry[0].key, keys[0]);
        assert!(!first.content_changed);
        assert_eq!(planner.invalidated, [keys[2]].into());
        planner.recycle(first);
        let pause = bounded(&mut planner, &scene, &index, 0);
        assert!(pause.geometry.is_empty());
        assert_eq!(planner.invalidated, [keys[2]].into());
        planner.recycle(pause);
        let second = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(second.geometry[0].key, keys[2]);
        assert!(!second.content_changed);
        assert_eq!(second.triangle_count, 3);
        assert!(!planner.has_pending());
    }

    #[test]
    fn resource_generation_reset_revokes_all_old_cells_even_at_equal_scene_revision() {
        for snapshot in [false, true] {
            let (mut scene, mut index, keys) = indexed_cells(4);
            let mut planner = planner(32);
            let initial = if snapshot {
                planner.plan_limited(&scene, 2).unwrap()
            } else {
                bounded(&mut planner, &scene, &index, 2)
            };
            planner.recycle(initial);
            planner.invalidate_cells([keys[0]]);
            scene.terrain_resource_generation += 1;
            // A previously pending member vanished in the new resource domain.
            scene.meshes.remove(&(3, 0));
            index.cells.remove(&keys[3]);
            let reset = if snapshot {
                planner.plan_limited(&scene, 1).unwrap()
            } else {
                bounded(&mut planner, &scene, &index, 1)
            };
            assert_eq!(reset.removed, keys[..2]);
            assert_eq!(reset.geometry.len(), 1);
            assert!(reset.content_changed);
            assert_eq!(reset.triangle_count, 1);
            assert_eq!(planner.placements().len(), 1);
            assert_eq!(planner.pending, keys[1..3].iter().copied().collect());
            planner.recycle(reset);
            while planner.has_pending() {
                let next = if snapshot {
                    planner.plan_limited(&scene, 1).unwrap()
                } else {
                    bounded(&mut planner, &scene, &index, 1)
                };
                assert!(next.removed.is_empty());
                assert_eq!(next.geometry.len(), 1);
                planner.recycle(next);
            }
            let mut reference = self::planner(32);
            let all = reference.plan(&scene).unwrap();
            reference.recycle(all);
            assert_same_published(&planner, &reference);
        }
    }

    #[test]
    fn revoked_resources_withdraw_old_cells_immediately_and_rebuild_with_normal_quota() {
        let (mut scene, mut index, keys) = indexed_cells(4);
        let mut planner = planner(32);
        let initial = planner.plan_incremental((&scene).into(), &index).unwrap();
        planner.recycle(initial);
        scene.meshes.get_mut(&(0, 0)).unwrap().revision += 1;
        publish(&mut index, [keys[0]]);
        planner.invalidate_cells([keys[2]]);
        planner.withdraw_cells([keys[2], keys[3], keys[3]]);
        let first = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(first.removed, keys[2..]);
        assert_eq!(first.geometry[0].key, keys[0]);
        assert_eq!(first.triangle_count, 2);
        assert!(first.content_changed);
        assert_eq!(planner.pending, keys[2..].iter().copied().collect());
        assert_eq!(
            planner
                .placements()
                .iter()
                .map(|p| p.key)
                .collect::<Vec<_>>(),
            keys[..2]
        );
        planner.recycle(first);
        for key in &keys[2..] {
            let next = bounded(&mut planner, &scene, &index, 1);
            assert_eq!(next.geometry[0].key, *key);
            assert!(next.removed.is_empty() && next.content_changed);
            planner.recycle(next);
        }
        assert!(!planner.has_pending());
        planner.withdraw_cells([keys[0]]);
        let same_call = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(same_call.removed, [keys[0]]);
        assert_eq!(same_call.geometry[0].key, keys[0]);
        assert_eq!(same_call.triangle_count, 4);
        assert!(same_call.content_changed);
        assert!(!planner.has_pending());
    }

    #[test]
    fn revoked_incomplete_cell_is_removed_without_requeue_or_build_allowance() {
        let (mut scene, mut index, keys) = indexed_cells(2);
        let mut planner = planner(32);
        let initial = planner.plan_incremental((&scene).into(), &index).unwrap();
        planner.recycle(initial);
        scene.ready_terrain.remove(&keys[0]);
        publish(&mut index, [keys[0]]);
        planner.withdraw_cells([keys[0]]);
        let removal = bounded(&mut planner, &scene, &index, 0);
        assert_eq!(removal.removed, [keys[0]]);
        assert_eq!(removal.triangle_count, 1);
        assert!(removal.geometry.is_empty());
        assert_eq!(removal.meshes_visited, 0);
        assert!(!planner.has_pending());
    }

    #[test]
    fn failed_resource_reset_or_withdrawal_preserves_requests_and_published_state() {
        for generation_reset in [false, true] {
            let (mut scene, index, keys) = indexed_cells(3);
            let mut planner = planner(1);
            let initial = planner.plan_incremental((&scene).into(), &index).unwrap();
            planner.recycle(initial);
            planner.invalidate_cells([keys[2]]);
            planner.withdraw_cells([keys[0], keys[2]]);
            scene.meshes.insert((0, 0), mesh(keys[0].origin(), 3, 0));
            if generation_reset {
                scene.terrain_resource_generation += 1;
            }
            let old = (
                planner.terrain_resource_generation,
                planner.cursor,
                planner.build_cursor,
                planner.signatures.clone(),
            );
            planner.limits.geometry_records = 2;
            assert!(
                planner
                    .plan_index_limited((&scene).into(), &index, 1, false)
                    .is_err()
            );
            assert_eq!(
                (
                    planner.terrain_resource_generation,
                    planner.cursor,
                    planner.build_cursor,
                    planner.signatures.clone()
                ),
                old
            );
            assert_eq!(planner.withdrawals, [keys[0], keys[2]].into());
            assert_eq!(planner.invalidated, [keys[2]].into());
            planner.limits.geometry_records = 8;
            let retry = bounded(&mut planner, &scene, &index, 1);
            assert_eq!(
                retry.removed,
                if generation_reset {
                    keys.clone()
                } else {
                    vec![keys[0], keys[2]]
                }
            );
            assert_eq!(retry.geometry[0].key, keys[0]);
            assert_eq!(retry.triangle_count, if generation_reset { 3 } else { 4 });
            assert!(retry.content_changed);
            assert!(planner.withdrawals.is_empty());
        }
    }

    #[test]
    fn failed_bounded_plan_preserves_publication_queue_force_flags_and_build_cursor() {
        let (mut scene, mut index, keys) = indexed_cells(3);
        let mut planner = planner(1);
        let first = bounded(&mut planner, &scene, &index, 1);
        planner.recycle(first);
        planner.invalidate_cells([keys[0], keys[2]]);
        scene.meshes.insert((1, 0), mesh(keys[1].origin(), 3, 0));
        publish(&mut index, [keys[1]]);
        let before = (
            planner.cursor,
            planner.build_cursor,
            planner.pending.clone(),
            planner.invalidated.clone(),
            planner.signatures.clone(),
        );
        planner.limits.geometry_records = 2;
        assert_eq!(
            planner
                .plan_index_limited((&scene).into(), &index, 1, false)
                .err()
                .unwrap(),
            "Too many static geometry metadata records"
        );
        assert_eq!(
            (
                planner.cursor,
                planner.build_cursor,
                planner.pending.clone(),
                planner.invalidated.clone(),
                planner.signatures.clone()
            ),
            before
        );
        planner.limits.geometry_records = 8;
        let retry = bounded(&mut planner, &scene, &index, 1);
        assert_eq!(retry.geometry[0].key, keys[1]);
        assert_eq!(retry.triangle_count, 4);
        assert_eq!(planner.invalidated, [keys[0], keys[2]].into());
        planner.recycle(retry);
        scene.anchor[0] = f64::NAN;
        let cursor = planner.cursor;
        assert!(
            planner
                .plan_index_limited((&scene).into(), &index, 1, false)
                .is_err()
        );
        assert_eq!(planner.cursor, cursor);
        assert_eq!(planner.pending, [keys[0], keys[2]].into());
    }

    fn assert_local_invalidation(incremental: bool) {
        let first_cell = Cell::containing([0.; 3]).unwrap();
        let second_cell = Cell::containing([64., 0., 0.]).unwrap();
        let mut scene = Scene::default();
        scene.meshes.insert((1, 0), mesh([0.; 3], 4, 0));
        scene.meshes.insert((2, 1), mesh([64., 0., 0.], 3, 1));
        scene.ready_terrain.extend([first_cell, second_cell]);
        let mut index = TerrainIndex {
            generation: TerrainGeneration::default().next().unwrap(),
            ..Default::default()
        };
        index.cells.insert(first_cell, [(1, 0)].into());
        index.cells.insert(second_cell, [(2, 1)].into());
        let plan = |planner: &mut TerrainPlanner| {
            if incremental {
                planner.plan_incremental((&scene).into(), &index)
            } else {
                planner.plan(&scene)
            }
        };
        let mut planner = planner(2);
        let initial = plan(&mut planner).unwrap();
        assert_eq!(initial.geometry.len(), 2);
        assert_eq!(initial.triangle_count, 7);
        assert_eq!(planner.geometry_records, 4);
        planner.recycle(initial);
        let steady = plan(&mut planner).unwrap();
        assert!(steady.geometry.is_empty() && steady.removed.is_empty());
        assert_eq!((steady.meshes_visited, steady.cells_visited), (0, 0));
        planner.recycle(steady);

        planner.invalidate_cells([second_cell, second_cell]);
        let rebuilt = plan(&mut planner).unwrap();
        assert_eq!(rebuilt.geometry.len(), 1);
        assert_eq!(rebuilt.geometry[0].key, second_cell);
        assert!(rebuilt.removed.is_empty());
        assert_eq!(rebuilt.triangle_count, 7);
        assert_eq!(planner.geometry_records, 4);
        assert_eq!(rebuilt.meshes_visited, 1);
        assert_eq!(rebuilt.cells_visited, 1);
        assert_eq!(planner.placements().len(), 2);
        planner.recycle(rebuilt);

        let steady = plan(&mut planner).unwrap();
        assert!(steady.geometry.is_empty() && steady.removed.is_empty());
        assert!(!steady.placements_changed);
        assert_eq!(steady.triangle_count, 7);
        assert_eq!((steady.meshes_visited, steady.cells_visited), (0, 0));
        planner.recycle(steady);

        planner.invalidate_cells([first_cell]);
        planner.limits.geometry_records = 3;
        assert_eq!(
            plan(&mut planner).err().unwrap(),
            "Too many static geometry metadata records"
        );
        planner.limits.geometry_records = 4;
        let retry = plan(&mut planner).unwrap();
        assert_eq!(retry.geometry.len(), 1);
        assert_eq!(retry.geometry[0].key, first_cell);
        assert_eq!(retry.triangle_count, 7);
        assert_eq!(planner.geometry_records, 4);
    }

    #[test]
    fn snapshot_invalidation_rebuilds_only_requested_cells_and_preserves_counts() {
        assert_local_invalidation(false);
    }

    #[test]
    fn incremental_invalidation_rebuilds_only_requested_cells_and_preserves_counts() {
        assert_local_invalidation(true);
    }

    #[test]
    fn sixty_four_sections_and_all_materials_share_one_cell_acceleration() {
        let mut scene = Scene::default();
        for x in 0..4 {
            for y in 0..4 {
                for z in 0..4 {
                    let id = (x * 16 + y * 4 + z) as u64;
                    scene
                        .meshes
                        .insert((id, 0), mesh([x, y, z].map(|v| f64::from(v * 16)), 1, 0));
                }
            }
        }
        scene.meshes.insert((100, 0), mesh([64.0, 0.0, 0.0], 1, 0));
        scene.meshes.insert((200, 2), mesh([0.0; 3], 1, 2));
        scene.meshes.insert((201, 1), mesh([0.0; 3], 1, 1));
        scene.ready_terrain.extend([
            Cell::containing([0.0; 3]).unwrap(),
            Cell::containing([64.0, 0.0, 0.0]).unwrap(),
        ]);
        let mut planner = planner(1024);
        let plan = planner.plan(&scene).unwrap();
        assert_eq!(plan.geometry.len(), 2);
        assert_eq!(plan.triangle_count, 67);
        let batch = plan
            .geometry
            .iter()
            .find(|batch| batch.key.coordinates() == [0; 3])
            .unwrap();
        assert_eq!(batch.geometries.len(), 3);
        assert_eq!(batch.geometries[0].members.len(), 64);
        assert_eq!(
            batch
                .geometries
                .iter()
                .map(|g| (g.flags, g.triangle_count))
                .collect::<Vec<_>>(),
            [(0, 64), (1, 1), (2, 1)]
        );
        planner.recycle(plan);
        scene.meshes.get_mut(&(22, 0)).unwrap().revision += 1;
        scene.revision += 1;
        let change = planner.plan(&scene).unwrap();
        assert_eq!(change.geometry.len(), 1);
        assert_eq!(change.geometry[0].geometries.len(), 3);
        planner.recycle(change);
        scene.meshes.remove(&(100, 0));
        scene.revision += 1;
        let removed = planner.plan(&scene).unwrap();
        assert!(removed.geometry.is_empty());
        assert_eq!(removed.removed.len(), 1);
        assert_eq!(removed.removed[0].coordinates(), [1, 0, 0]);
    }

    #[test]
    fn dense_cell_parts_preserve_every_primitive_and_share_one_aligned_origin() {
        let mut scene = Scene::default();
        scene
            .meshes
            .insert((10, 0), mesh([-0.125, -64.0, 63.0], 7, 0));
        scene
            .ready_terrain
            .insert(Cell::containing([-0.125, -64.0, 63.0]).unwrap());
        let mut planner = planner(3);
        let plan = planner.plan(&scene).unwrap();
        assert_eq!(
            plan.geometry[0]
                .geometries
                .iter()
                .map(|geometry| geometry.triangle_count)
                .collect::<Vec<_>>(),
            [3, 3, 1]
        );
        let ranges: Vec<_> = plan
            .geometry
            .iter()
            .flat_map(|batch| &batch.geometries)
            .flat_map(|geometry| &geometry.members)
            .flat_map(|member| member.range.clone())
            .collect();
        assert_eq!(ranges, (0..7).collect::<Vec<_>>());
        assert_eq!(plan.geometry.len(), 1);
        assert_eq!(planner.placements().len(), 1);
        assert_eq!(plan.geometry[0].key.coordinates(), [-1, -1, 0]);
        for geometry in &plan.geometry[0].geometries {
            assert_eq!(geometry.members[0].offset, [63.875, 0.0, 63.0]);
        }
        assert!(
            planner
                .placements()
                .iter()
                .all(|p| p.transform == translation([-64.0, -64.0, 0.0]))
        );
    }

    #[test]
    fn mixed_triangle_and_quad_members_keep_odd_capacity_splits() {
        use crate::geometry::{CompiledQuad, MeshGeometry};
        let mut scene = Scene::default();
        scene.meshes.insert((1, 0), mesh([0.; 3], 1, 0));
        let quad = CompiledQuad {
            positions: [[0.; 3], [1., 0., 0.], [1., 1., 0.], [0., 1., 2.]],
            uvs: [[0.; 2]; 4],
            color: [1.; 4],
            texture_id: 0,
            flags: 0,
        };
        let compact = MeshGeometry::Quads(vec![quad; 2].into());
        let mut member = mesh([16., 0., 0.], 1, 0);
        member.triangles = compact.clone();
        scene.meshes.insert((2, 0), member);
        scene
            .ready_terrain
            .insert(Cell::containing([0.; 3]).unwrap());
        for limit in [1, 3] {
            let plan = planner(limit).plan(&scene).unwrap();
            assert_eq!(plan.triangle_count, 5);
            let members: Vec<_> = plan
                .geometry
                .iter()
                .flat_map(|b| &b.geometries)
                .flat_map(|g| &g.members)
                .filter(|m| m.offset[0] == 16.)
                .collect();
            assert!(members.iter().all(|m| m.triangles.ptr_eq(&compact)));
            let ranges: Vec<_> = members.iter().flat_map(|m| m.range.clone()).collect();
            assert_eq!(ranges, vec![0, 1, 2, 3]);
            let corners: Vec<_> = members
                .iter()
                .flat_map(|m| {
                    let view = m.triangles.view(m.range.clone());
                    (0..view.len()).map(move |i| view.triangle(i).positions)
                })
                .collect();
            assert_eq!(
                corners,
                compact.iter().map(|t| t.positions).collect::<Vec<_>>()
            );
        }
    }

    #[test]
    fn rebasing_and_texture_revisions_preserve_geometry_at_cell_planes() {
        let mut scene = Scene::default();
        scene
            .meshes
            .insert((1, 0), mesh([63.99999, -0.00001, 30_000_000.25], 1, 0));
        scene
            .ready_terrain
            .insert(Cell::containing([63.99999, -0.00001, 30_000_000.25]).unwrap());
        let mut planner = planner(10);
        let first = planner.plan(&scene).unwrap();
        let key = first.geometry[0].key;
        planner.recycle(first);
        let stationary = planner.plan(&scene).unwrap();
        assert!(!stationary.placements_changed && stationary.geometry.is_empty());
        planner.recycle(stationary);
        let anchor = [256.0, -256.0, 30_000_000.0];
        scene.anchor = anchor;
        scene.revision += 1;
        let rebase = planner.plan(&scene).unwrap();
        assert!(
            rebase.geometry.is_empty() && rebase.removed.is_empty() && rebase.placements_changed
        );
        assert_eq!(planner.placements()[0].key, key);
        planner.recycle(rebase);
        scene.revision += 1;
        let texture_only = planner.plan(&scene).unwrap();
        assert!(texture_only.geometry.is_empty() && !texture_only.placements_changed);
        planner.recycle(texture_only);
        scene.epoch += 1;
        let reset = planner.plan(&scene).unwrap();
        assert_eq!(reset.removed, [key]);
        assert_eq!(reset.geometry.len(), 1);
    }
}
