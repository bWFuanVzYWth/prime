//! Stable direct-quad slots for ReSTIR terrain. Hashes index exact record comparisons;
//! they are not identity proofs (RA-009 in docs/restir-adaptations.md).
//! Matching and hash storage exist only on dirty updates.
use crate::packing::{self, Input, Plan};
use prime_scene::{
    geometry::{CompiledQuad, MeshGeometry},
    surface::{SurfaceFace, SurfaceMesh},
    translation::{TerrainGeometry, TerrainMember, TerrainUpdate},
};
use std::{
    collections::{BTreeMap, HashMap},
    hash::{DefaultHasher, Hasher},
    sync::Arc,
};

#[derive(Clone, Copy, Default)]
pub(super) struct QuadState {
    pub live: u32,
    pub changed: bool,
}

pub(super) struct Page {
    pub flags: u32,
    pub format: usize,
    pub mesh: Arc<SurfaceMesh>,
    pub states: Vec<QuadState>,
}

pub(super) struct StaticPacking {
    pub pages: Vec<Page>,
}

fn tombstone(flags: u32) -> SurfaceFace {
    SurfaceFace::from_quad(CompiledQuad {
        positions: [[0.; 3]; 4],
        color: [0.; 4],
        uvs: [[0.; 2]; 4],
        texture_id: 0,
        flags,
    })
}

fn live(face: &SurfaceFace) -> u32 {
    (0..2).fold(0, |mask, half| {
        let triangle = face.geometry.triangle(half);
        let a: [f32; 3] =
            std::array::from_fn(|i| triangle.positions[1][i] - triangle.positions[0][i]);
        let b: [f32; 3] =
            std::array::from_fn(|i| triangle.positions[2][i] - triangle.positions[0][i]);
        let cross = [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ];
        mask | if cross.iter().any(|x| *x != 0.) {
            1 << half
        } else {
            0
        }
    })
}

fn key(
    face: &SurfaceFace,
    format: usize,
    textures: &BTreeMap<u32, u32>,
) -> Result<[u8; 432], String> {
    let mut bytes = packing::encode(face, format, None, None, textures)?;
    if format != 0 {
        // Canonical light IDs and represented area weight are reconstructed from
        // the stable row. They do not change its geometry or selected leaf.
        bytes[220..224].fill(0);
        bytes[232..236].fill(0);
    }
    Ok(bytes)
}

fn hash(bytes: &[u8]) -> u64 {
    let mut hash = DefaultHasher::new();
    hash.write(bytes);
    hash.finish()
}

fn dependencies(face: &SurfaceFace, textures: &mut BTreeMap<u32, u32>) {
    let mut insert = |id| {
        textures.insert(id, id);
    };
    insert(face.geometry.texture_id);
    if let Some(detail) = &face.detail {
        insert(detail.layer.texture_id);
    }
    if let Some(optics) = face.optics {
        for id in optics.ior_textures.into_iter().flatten() {
            insert(id);
        }
    }
}

impl StaticPacking {
    pub fn prepare(
        update: &mut TerrainUpdate,
        old: Option<&Self>,
        revision: u64,
    ) -> Result<Self, String> {
        Self::prepare_hashed(update, old, revision, hash)
    }
    fn prepare_hashed(
        update: &mut TerrainUpdate,
        old: Option<&Self>,
        revision: u64,
        fingerprint: impl Fn(&[u8]) -> u64,
    ) -> Result<Self, String> {
        let mut incoming = Vec::new();
        let mut textures = BTreeMap::from([(0, 0)]);
        for geometry in &update.geometries {
            let plan = Plan::new(
                geometry.members.iter().map(|member| Input {
                    triangles: member.triangles.view(member.range.clone()),
                    offset: Some(member.offset),
                    flags: Some(geometry.flags),
                }),
                true,
            )?;
            for group in &plan.groups {
                for face in plan.resolved_faces(group) {
                    dependencies(&face, &mut textures);
                    incoming.push((geometry.flags, group.format, face));
                }
            }
        }
        let mut rows: Vec<(u32, usize, Vec<Option<SurfaceFace>>, Vec<QuadState>)> = Vec::new();
        let mut candidates: HashMap<(u32, usize, u64), Vec<(usize, usize)>> = HashMap::new();
        if let Some(old) = old {
            for page in &old.pages {
                for face in &page.mesh.quads {
                    dependencies(face, &mut textures);
                }
            }
            for (row, page) in old.pages.iter().enumerate() {
                rows.push((
                    page.flags,
                    page.format,
                    vec![None; page.mesh.quads.len()],
                    vec![
                        QuadState {
                            live: 0,
                            changed: true
                        };
                        page.mesh.quads.len()
                    ],
                ));
                for (slot, face) in page.mesh.quads.iter().enumerate() {
                    if page.states[slot].live == 0 {
                        continue;
                    }
                    let bytes = key(face, page.format, &textures)?;
                    candidates
                        .entry((
                            page.flags,
                            page.format,
                            fingerprint(&bytes[..packing::stride(page.format)]),
                        ))
                        .or_default()
                        .push((row, slot));
                }
            }
        }
        let mut unmatched = Vec::new();
        for (flags, format, face) in incoming {
            let bytes = key(&face, format, &textures)?;
            let candidate = candidates
                .get_mut(&(
                    flags,
                    format,
                    fingerprint(&bytes[..packing::stride(format)]),
                ))
                .and_then(|bucket| {
                    let selected = bucket.iter().rposition(|&(row, slot)| {
                        old.is_some_and(|old| {
                            key(&old.pages[row].mesh.quads[slot], format, &textures).is_ok_and(
                                |old| {
                                    old[..packing::stride(format)]
                                        == bytes[..packing::stride(format)]
                                },
                            )
                        })
                    });
                    selected.map(|index| bucket.swap_remove(index))
                });
            if let Some((row, slot)) = candidate {
                rows[row].3[slot] = QuadState {
                    live: live(&face),
                    changed: false,
                };
                rows[row].2[slot] = Some(face);
            } else {
                unmatched.push((flags, format, face));
            }
        }
        let mut free: Vec<Vec<usize>> = rows
            .iter()
            .map(|(_, _, faces, _)| {
                faces
                    .iter()
                    .enumerate()
                    .filter_map(|(slot, face)| face.is_none().then_some(slot))
                    .rev()
                    .collect()
            })
            .collect();
        let mut available: BTreeMap<(u32, usize), Vec<usize>> = BTreeMap::new();
        for (row, (flags, format, faces, _)) in rows.iter().enumerate().rev() {
            if !free[row].is_empty() || faces.len() < packing::MAX_RECORDS as usize {
                available.entry((*flags, *format)).or_default().push(row);
            }
        }
        for (flags, format, face) in unmatched {
            let candidates = available.entry((flags, format)).or_default();
            let row = if let Some(&row) = candidates.last() {
                row
            } else {
                let row = rows.len();
                rows.push((flags, format, Vec::new(), Vec::new()));
                free.push(Vec::new());
                candidates.push(row);
                row
            };
            let state = QuadState {
                live: live(&face),
                changed: true,
            };
            if let Some(slot) = free[row].pop() {
                rows[row].2[slot] = Some(face);
                rows[row].3[slot] = state;
            } else {
                rows[row].2.push(Some(face));
                rows[row].3.push(state);
            }
            if free[row].is_empty() && rows[row].2.len() == packing::MAX_RECORDS as usize {
                candidates.pop();
            }
        }
        let mut pages = Vec::with_capacity(rows.len());
        for (flags, format, faces, states) in rows {
            let faces = faces
                .into_iter()
                .map(|face| face.unwrap_or_else(|| tombstone(flags)))
                .collect();
            pages.push(Page {
                flags,
                format,
                states,
                mesh: Arc::new(SurfaceMesh::from_resolved(revision, faces)?),
            });
        }
        update.geometries = pages
            .iter()
            .map(|page| TerrainGeometry {
                flags: page.flags,
                triangle_count: page
                    .states
                    .iter()
                    .map(|state| state.live.count_ones())
                    .sum(),
                members: vec![TerrainMember {
                    triangles: MeshGeometry::Surfaces(page.mesh.clone()),
                    range: 0..page.mesh.quads.len() * 2,
                    offset: [0.; 3],
                }],
            })
            .collect();
        Ok(Self { pages })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn face(x: f32) -> SurfaceFace {
        SurfaceFace::from_quad(CompiledQuad {
            positions: [[x, 0., 0.], [x + 1., 0., 0.], [x + 1., 1., 0.], [x, 1., 0.]],
            color: [1.; 4],
            uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
            texture_id: 0,
            flags: 0,
        })
    }
    fn update(faces: Vec<SurfaceFace>) -> TerrainUpdate {
        let count = faces.len() * 2;
        TerrainUpdate {
            key: prime_scene::spatial::Cell::containing([0.; 3]).unwrap(),
            content_changed: true,
            geometries: vec![TerrainGeometry {
                flags: 0,
                triangle_count: count as u32,
                members: vec![TerrainMember {
                    triangles: MeshGeometry::Surfaces(Arc::new(
                        SurfaceMesh::from_resolved(1, faces).unwrap(),
                    )),
                    range: 0..count,
                    offset: [0.; 3],
                }],
            }],
        }
    }
    #[test]
    fn excavation_reorder_addition_and_dead_slot_reuse_preserve_exact_quad_indices() {
        let mut source = update(vec![face(0.), face(10.), face(20.)]);
        let old = StaticPacking::prepare(&mut source, None, 1).unwrap();
        let mut source = update(vec![face(20.), face(0.), face(30.)]);
        let current = StaticPacking::prepare(&mut source, Some(&old), 2).unwrap();
        assert_eq!(current.pages.len(), 1);
        let page = &current.pages[0];
        assert_eq!(
            page.mesh
                .quads
                .iter()
                .map(|face| face.geometry.positions[0][0])
                .collect::<Vec<_>>(),
            [0., 30., 20.]
        );
        assert_eq!(
            page.states
                .iter()
                .map(|state| state.changed)
                .collect::<Vec<_>>(),
            [false, true, false]
        );
        let mut source = update(vec![face(20.), face(0.)]);
        let removed = StaticPacking::prepare(&mut source, Some(&current), 3).unwrap();
        assert_eq!(source.geometries[0].triangle_count, 4);
        assert_eq!(removed.pages[0].mesh.quads.len(), 3);
        assert_eq!(
            removed.pages[0]
                .states
                .iter()
                .map(|state| state.live)
                .collect::<Vec<_>>(),
            [3, 0, 3]
        );
        let plan = Plan::with_format(
            source.geometries[0].members.iter().map(|member| Input {
                triangles: member.triangles.view(member.range.clone()),
                offset: None,
                flags: Some(0),
            }),
            0,
        )
        .unwrap();
        assert_eq!(plan.groups[0].count, 3);
        let faces = plan.resolved_faces(&plan.groups[0]).collect::<Vec<_>>();
        assert_eq!(live(&faces[1]), 0);
        assert_eq!(faces[0].geometry.positions, face(0.).geometry.positions);
        assert_eq!(faces[2].geometry.positions, face(20.).geometry.positions);
        eprintln!(
            "static history CPU: SurfaceFace={} bytes, QuadState={} bytes; GPU identity=8 bytes/quad",
            std::mem::size_of::<SurfaceFace>(),
            std::mem::size_of::<QuadState>()
        );
    }
    #[test]
    fn mixed_format_tombstones_keep_one_ordered_physical_page_and_canonical_emitters() {
        use prime_scene::surface::{LayerMode, Medium, Optics, SurfaceDetail, SurfaceLayer};
        for format in 1..=3 {
            let decorate = |mut face: SurfaceFace| {
                face.emission.radiance = [2.; 3];
                if format >= 2 {
                    face.optics = Some(Optics {
                        negative: Medium::default(),
                        positive: Medium::default(),
                        ior_textures: [None; 2],
                        transmit: false,
                        thin: false,
                    });
                }
                if format == 3 {
                    face.detail = Some(Arc::new(SurfaceDetail {
                        mode: LayerMode::Bilateral,
                        layer: SurfaceLayer {
                            colors: [[1.; 4]; 4],
                            uvs: [[0.; 2]; 4],
                            texture_id: 0,
                            flags: 0,
                            repeat: None,
                            emission: face.emission,
                        },
                    }));
                }
                face
            };
            let faces = [0., 10., 20.].map(|x| decorate(face(x)));
            let mut source = update(faces.to_vec());
            let old = StaticPacking::prepare(&mut source, None, 1).unwrap();
            let mut source = update(vec![faces[2].clone(), faces[0].clone()]);
            let current = StaticPacking::prepare(&mut source, Some(&old), 2).unwrap();
            assert_eq!(current.pages.len(), 1);
            assert_eq!(current.pages[0].format, format);
            assert_eq!(current.pages[0].states[1].live, 0);
            assert_eq!(current.pages[0].mesh.lights.emitters.len(), 2);
            assert_eq!(current.pages[0].mesh.lights.emitters[0].quad, 0);
            assert_eq!(current.pages[0].mesh.lights.emitters[1].quad, 2);
            let plan = Plan::with_format(
                source.geometries[0].members.iter().map(|member| Input {
                    triangles: member.triangles.view(member.range.clone()),
                    offset: None,
                    flags: Some(0),
                }),
                format,
            )
            .unwrap();
            assert_eq!(plan.groups.len(), 1, "one BLAS geometry for the stable row");
            assert_eq!(plan.groups[0].count, 3);
            let mut packed = vec![0; plan.bytes()];
            plan.pack_bytes(
                &prime_scene::workers::CpuWorkers::new(1).unwrap(),
                &mut packed,
                &BTreeMap::from([(0, 0)]),
            )
            .unwrap();
            for (slot, expected_x) in [0., 0., 20.].into_iter().enumerate() {
                let at = slot * packing::stride(format);
                assert_eq!(
                    f32::from_le_bytes(packed[at..at + 4].try_into().unwrap()),
                    expected_x,
                    "format {format} reordered a physical slot"
                );
            }
            let faces = plan.resolved_faces(&plan.groups[0]).collect::<Vec<_>>();
            assert_eq!(live(&faces[1]), 0);
            assert_eq!(faces[2].emitter, Some(1));
            assert!(!current.pages[0].states[0].changed && !current.pages[0].states[2].changed);
        }
    }

    #[test]
    fn disconnected_merge_edits_preserve_unchanged_physical_rectangle() {
        use prime_scene::surface::{Provenance, SurfaceCompiler, SurfaceQuad};
        fn unit(x: f32, y: f32, rotation: usize) -> SurfaceQuad {
            let mut geometry = CompiledQuad {
                positions: [
                    [x, y, 0.],
                    [x + 1., y, 0.],
                    [x + 1., y + 1., 0.],
                    [x, y + 1., 0.],
                ],
                color: [1.; 4],
                uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
                texture_id: 0,
                flags: 0,
            };
            geometry.positions.rotate_left(rotation);
            geometry.uvs.rotate_left(rotation);
            SurfaceQuad::from_closed(
                geometry,
                Provenance {
                    domain: 1,
                    source: (x as u64) + 64 * (y as u64),
                },
            )
        }
        fn bounds(face: &SurfaceFace) -> [f32; 4] {
            [
                face.geometry
                    .positions
                    .iter()
                    .map(|p| p[0])
                    .fold(f32::INFINITY, f32::min),
                face.geometry
                    .positions
                    .iter()
                    .map(|p| p[1])
                    .fold(f32::INFINITY, f32::min),
                face.geometry
                    .positions
                    .iter()
                    .map(|p| p[0])
                    .fold(f32::NEG_INFINITY, f32::max),
                face.geometry
                    .positions
                    .iter()
                    .map(|p| p[1])
                    .fold(f32::NEG_INFINITY, f32::max),
            ]
        }
        fn uv(face: &SurfaceFace, point: [f32; 2]) -> [f32; 2] {
            (0..2)
                .find_map(|half| {
                    let triangle = face.geometry.triangle(half);
                    let p = triangle.positions;
                    let a = [p[1][0] - p[0][0], p[1][1] - p[0][1]];
                    let b = [p[2][0] - p[0][0], p[2][1] - p[0][1]];
                    let q = [point[0] - p[0][0], point[1] - p[0][1]];
                    let determinant = a[0] * b[1] - a[1] * b[0];
                    let u = (q[0] * b[1] - q[1] * b[0]) / determinant;
                    let v = (a[0] * q[1] - a[1] * q[0]) / determinant;
                    if u < 0. || v < 0. || u + v > 1. {
                        return None;
                    }
                    let value = std::array::from_fn(|i| {
                        triangle.uvs[0][i] * (1. - u - v)
                            + triangle.uvs[1][i] * u
                            + triangle.uvs[2][i] * v
                    });
                    Some(face.repeat.unwrap().evaluate(value))
                })
                .unwrap()
        }
        let original = [unit(10., 10., 0), unit(11., 10., 0)];
        let mut compiler = SurfaceCompiler::new();
        let before = compiler.compile(1, &original).unwrap();
        assert_eq!(
            before.quads.len(),
            1,
            "fixture must really merge the old pair"
        );
        let old_face = before.quads[0].clone();
        assert_eq!(bounds(&old_face), [10., 10., 12., 11.]);
        let mut source = update(before.quads);
        let old = StaticPacking::prepare(&mut source, None, 1).unwrap();
        let incoming = [
            original[0].clone(),
            original[1].clone(),
            unit(0., 0., 1),
            unit(1., 0., 1),
        ];
        let after = compiler.compile(2, &incoming).unwrap();
        assert_eq!(
            after.quads.len(),
            2,
            "disconnected pairs stay separate rectangles"
        );
        let unchanged = after
            .quads
            .iter()
            .find(|face| bounds(face) == bounds(&old_face))
            .unwrap();
        for point in [[10.25, 10.375], [11.375, 10.625]] {
            assert_eq!(
                uv(&old_face, point),
                uv(unchanged, point),
                "affine texture map changed"
            );
        }
        for face in [&old_face, unchanged] {
            for half in 0..2 {
                let p = face.geometry.triangle(half).positions;
                assert!(
                    (p[1][0] - p[0][0]) * (p[2][1] - p[0][1])
                        - (p[1][1] - p[0][1]) * (p[2][0] - p[0][0])
                        > 0.,
                    "source and compiled winding must stay positive"
                );
            }
        }
        assert_eq!(old_face.repeat, unchanged.repeat);
        assert_eq!(old_face.geometry.colors, unchanged.geometry.colors);
        let textures = BTreeMap::from([(0, 0)]);
        assert_eq!(
            key(&old_face, 1, &textures).unwrap(),
            key(unchanged, 1, &textures).unwrap(),
            "a disconnected source must not change the old direct-record bytes"
        );
        assert_eq!(old_face.geometry.positions, unchanged.geometry.positions);
        let mut source = update(after.quads);
        let current = StaticPacking::prepare(&mut source, Some(&old), 2).unwrap();
        let (slot, _) = current.pages[0]
            .mesh
            .quads
            .iter()
            .enumerate()
            .find(|(_, face)| bounds(face) == bounds(&old_face))
            .unwrap();
        assert_eq!(
            slot, 0,
            "the unchanged direct quad must retain its physical slot"
        );
        assert!(!current.pages[0].states[slot].changed);
        eprintln!(
            "disconnected merged representative: old slot=0 current slot={slot}, unchanged bounds={:?}, positions {:?} -> {:?}",
            bounds(&old_face),
            old_face.geometry.positions,
            current.pages[0].mesh.quads[slot].geometry.positions
        );
    }

    #[test]
    fn deliberate_hash_collisions_require_exact_records_and_invalid_halves_stay_dead() {
        let mut a = face(0.);
        a.geometry.positions[3] = a.geometry.positions[2];
        let mut source = update(vec![a.clone(), face(10.)]);
        let old = StaticPacking::prepare_hashed(&mut source, None, 1, |_| 0).unwrap();
        let mut source = update(vec![face(20.), a]);
        let current = StaticPacking::prepare_hashed(&mut source, Some(&old), 2, |_| 0).unwrap();
        assert_eq!(current.pages[0].states[0].live, 1);
        assert!(!current.pages[0].states[0].changed);
        assert!(current.pages[0].states[1].changed);
        assert_eq!(
            current.pages[0].mesh.quads[1].geometry.positions,
            face(20.).geometry.positions
        );
    }
    #[test]
    fn canonical_emitter_offsets_are_real_encoding_fields_and_not_face_identity() {
        let mut a = face(0.);
        a.emission.radiance = [2.; 3];
        a.emitter = Some(7);
        a.emitter_area_weight = 13.;
        let textures = BTreeMap::from([(0, 0)]);
        let bytes = packing::encode(&a, 1, None, None, &textures).unwrap();
        assert_eq!(f32::from_le_bytes(bytes[220..224].try_into().unwrap()), 13.);
        assert_eq!(u32::from_le_bytes(bytes[232..236].try_into().unwrap()), 7);
        let original = key(&a, 1, &textures).unwrap();
        a.emitter = Some(99);
        a.emitter_area_weight = 21.;
        assert_eq!(key(&a, 1, &textures).unwrap(), original);
        a.emission.radiance[0] = 3.;
        assert_ne!(key(&a, 1, &textures).unwrap(), original);
    }
}
