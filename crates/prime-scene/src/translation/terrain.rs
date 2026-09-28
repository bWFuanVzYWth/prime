//! Incremental static batch planning. Source identities are opaque; placement comes from origins.
use super::translation;
use crate::{
    geometry::MeshGeometry,
    incremental::{ContextId, SceneInput, TerrainGeneration, TerrainIndex},
    scene::{MeshKey, Scene, SceneMesh},
    spatial::Cell,
};
use std::{collections::BTreeMap, ops::Range};

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
}

#[derive(Clone, Copy)]
pub struct TerrainLimits {
    pub triangles_per_geometry: u32,
    pub geometry_records: u32,
}

impl TerrainPlanner {
    pub fn new(limits: TerrainLimits) -> Result<Self, String> {
        if limits.triangles_per_geometry == 0 || limits.geometry_records == 0 {
            return Err("Terrain limits must be nonzero".into());
        }
        Ok(Self {
            limits,
            epoch: None,
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
        })
    }

    /// Mutable diagnostics have no producer certificate: validate and index their full snapshot.
    pub fn plan(&mut self, scene: &Scene) -> Result<TerrainPlan, String> {
        if scene.anchor.iter().any(|value| !value.is_finite()) {
            return Err("Invalid scene anchor".into());
        }
        if self.epoch == Some(scene.epoch)
            && self.revision == Some(scene.revision)
            && self.anchor == scene.anchor
        {
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
        let plan = self.plan_incremental(scene.into(), &index)?;
        self.revision = Some(scene.revision);
        self.cursor = None;
        Ok(plan)
    }

    fn empty_plan(&mut self) -> TerrainPlan {
        TerrainPlan {
            removed: std::mem::take(&mut self.spare_removed),
            geometry: std::mem::take(&mut self.spare_geometry),
            placements_changed: false,
            triangle_count: self.triangle_count,
            meshes_visited: 0,
            cells_visited: 0,
        }
    }

    pub fn plan_input(&mut self, scene: SceneInput<'_>) -> Result<TerrainPlan, String> {
        match scene.terrain() {
            None => self.plan(&scene),
            Some(index) => self.plan_incremental(scene, index),
        }
    }

    fn plan_incremental(
        &mut self,
        scene: SceneInput<'_>,
        index: &TerrainIndex,
    ) -> Result<TerrainPlan, String> {
        let reset = self.epoch != Some(scene.epoch) || self.source != index.source;
        let changed = reset || self.cursor != Some(index.generation);
        let resync = reset
            || self.cursor.is_none()
            || (changed && (index.reset || self.cursor != Some(index.from)));
        let rebase = reset || self.anchor != scene.anchor;
        let mut plan = self.empty_plan();
        if !changed && !rebase {
            return Ok(plan);
        }

        // A cursor gap is an explicit resynchronization, never a guessed delta.
        // The normal path visits only cells affected since the previous publication.
        let full;
        let cells = if resync {
            full = index
                .cells
                .keys()
                .chain(self.signatures.keys())
                .copied()
                .collect();
            &full
        } else if changed {
            &index.changed
        } else {
            &std::collections::BTreeSet::new()
        };
        let mut replacements = Vec::new();
        let mut total = self.triangle_count;
        let mut records = self.geometry_records;
        for &key in cells {
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
            let previous = self.signatures.get(&key);
            let count: usize = materials.iter().map(Vec::len).sum();
            let unchanged = !reset
                && previous.is_some_and(|old| {
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
            if unchanged || (count == 0 && previous.is_none()) {
                continue;
            }
            if let Some(old) = previous {
                records -= old.len() as u64;
                for (_, members) in old {
                    total -= members
                        .iter()
                        .map(|(_, _, _, range)| range.len() as u64)
                        .sum::<u64>();
                }
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
        if reset {
            plan.removed.extend(self.signatures.keys().copied());
        }
        // All checks precede mutation of the consumer state/cursor.
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
        self.revision = None; // A later diagnostic snapshot must validate independently.
        self.cursor = Some(index.generation);
        self.source = index.source;
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
