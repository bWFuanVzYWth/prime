//! Bounded source contacts. Only actual block-boundary patches participate; halo never owns output.
use crate::{
    Job, SectionData,
    model::{Catalog, State},
    schedule::Section,
    tint::{Deferred, Request},
};
use prime_scene::surface::{
    LayerMode, Rectangle, SurfaceDetail, SurfaceFace, intersect_surfaces, subtract_surface,
};
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
};

#[derive(Clone)]
pub(crate) struct Bound {
    face: SurfaceFace,
    tint: [Option<Request>; 2],
    fluid: bool,
}
pub(crate) type Cache = HashMap<[i32; 3], Vec<Bound>>;
pub(crate) struct Context<'a> {
    pub catalog: &'a Catalog,
    pub sections: &'a HashMap<Section, SectionData>,
    pub cells: &'a HashSet<[i32; 3]>,
    pub origin: [i32; 3],
}
impl Context<'_> {
    fn state(&self, p: [i32; 3]) -> Option<&State> {
        let [x, y, z] = p;
        let s = Section(x.div_euclid(16), y.div_euclid(16), z.div_euclid(16));
        let id = self
            .sections
            .get(&s)?
            .state((y.rem_euclid(16) * 256 + z.rem_euclid(16) * 16 + x.rem_euclid(16)) as usize);
        self.catalog.states.get(&id)
    }
    fn publishable(&self, p: [i32; 3]) -> bool {
        self.cells.contains(&p.map(|v| v.div_euclid(64)))
    }
    fn collect(&self, p: [i32; 3], visible: u32, hacks: &mut crate::model::Hacks) -> Vec<Bound> {
        let Some(state) = self.state(p) else {
            return Vec::new();
        };
        if state.air() {
            return Vec::new();
        }
        let mut layers = Default::default();
        let mut surfaces = Default::default();
        let mut tints = Deferred::default();
        tints.begin(state.id, p);
        self.catalog.emit(
            state,
            p,
            visible,
            &mut layers,
            hacks,
            &mut tints,
            &mut surfaces,
        );
        let mut out = Vec::new();
        for layer in 0..3 {
            out.extend(layers[layer].drain(..).enumerate().map(|(i, q)| Bound {
                face: SurfaceFace::from_quad(q),
                tint: [tints.binding(layer, i), None],
                fluid: false,
            }));
            out.extend(
                surfaces[layer]
                    .drain(..)
                    .enumerate()
                    .map(|(i, face)| Bound {
                        face,
                        tint: [tints.binding(3 + layer, i), tints.binding(6 + layer, i)],
                        fluid: false,
                    }),
            );
        }
        let mut heights = None;
        if state.fluid.kind != 0 {
            let missing = State::default();
            heights = crate::fluid::emit(
                self.catalog,
                state,
                p.map(|v| v.rem_euclid(16) as f32),
                |x, y, z| {
                    self.state([p[0] + x, p[1] + y, p[2] + z])
                        .unwrap_or(&missing)
                },
                &mut layers,
                hacks,
                true,
            );
            let tint = self
                .catalog
                .fluids
                .get(&state.fluid.material)
                .filter(|m| m.flags & 1 != 0)
                .map(|_| Request {
                    state: state.id,
                    position: p,
                    slot: -1,
                });
            for layer in &mut layers {
                out.extend(layer.drain(..).map(|q| Bound {
                    face: SurfaceFace::from_quad(q),
                    tint: [tint, None],
                    fluid: true,
                }));
            }
        }
        let offset =
            std::array::from_fn::<_, 3, _>(|a| (p[a].div_euclid(16) * 16 - self.origin[a]) as f32);
        for b in &mut out {
            for v in &mut b.face.geometry.positions {
                for a in 0..3 {
                    v[a] += offset[a];
                }
            }
            crate::optics::assign(self.catalog, state, &mut b.face, b.fluid);
            if b.fluid && state.fluid.kind == 2 {
                b.face.emission = prime_scene::surface::Emission {
                    radiance: [1.5; 3],
                    two_sided: false,
                    textured: true,
                };
            }
            if b.fluid
                && self
                    .state([p[0], p[1] - 1, p[2]])
                    .is_some_and(|s| s.flags & 1024 == 0)
            {
                let bottom = (p[1] - self.origin[1]) as f32;
                for v in &mut b.face.geometry.positions {
                    if ((v[1] - bottom) - 0.001).abs() <= 2. * f32::EPSILON * bottom.abs().max(1.) {
                        v[1] = bottom;
                    }
                }
            }
            if b.fluid
                && let Some(rect) = Rectangle::plane_bounds(&b.face)
            {
                let side = i32::from(normal(&b.face)[rect.axis] > 0.);
                let plane = (p[rect.axis] - self.origin[rect.axis] + side) as f32;
                let mut n = p;
                n[rect.axis] += if side == 1 { 1 } else { -1 };
                if (rect.axis != 1 || side == 0)
                    && self.state(n).is_some_and(|s| s.flags & 1024 == 0)
                    && ((rect.plane - plane).abs() - 0.001).abs()
                        <= 2. * f32::EPSILON * plane.abs().max(1.)
                {
                    for v in &mut b.face.geometry.positions {
                        v[rect.axis] = plane;
                    }
                }
            }
        }
        if state.flags & 768 != 0 && out.iter().any(|b| !b.fluid && b.face.optics.is_none()) {
            hacks.optics += 1;
        }
        if let Some(volume) = self.catalog.volumes.get(&state.model) {
            let local = std::array::from_fn::<_, 3, _>(|a| (p[a] - self.origin[a]) as f32);
            let boxes: Vec<_> = volume
                .boxes()
                .map(|b| b.map(|v| std::array::from_fn(|a| v[a] + local[a])))
                .collect();
            out = out
                .into_iter()
                .flat_map(|bound| {
                    let rect = Rectangle::plane_bounds(&bound.face);
                    let mut faces = vec![bound.face.clone()];
                    for &bounds in &boxes {
                        // A union's interior source faces are not optical boundaries. The strictly
                        // outward occupied side decides this without offsetting/thickening geometry.
                        let internal = rect.is_some_and(|r| {
                            if normal(&bound.face)[r.axis] > 0. {
                                bounds[0][r.axis] <= r.plane && bounds[1][r.axis] > r.plane
                            } else {
                                bounds[0][r.axis] < r.plane && bounds[1][r.axis] >= r.plane
                            }
                        });
                        if bound.fluid || internal {
                            faces = faces
                                .into_iter()
                                .flat_map(|f| prime_scene::surface::subtract_box(&f, bounds))
                                .collect();
                        }
                    }
                    if !bound.fluid
                        && state.fluid.kind == 1
                        && let Some(heights) = heights
                    {
                        faces = faces
                            .into_iter()
                            .flat_map(|face| water_contact(face, local, heights))
                            .collect();
                    }
                    faces
                        .into_iter()
                        .map(|face| Bound {
                            face,
                            ..bound.clone()
                        })
                        .collect::<Vec<_>>()
                })
                .collect();
        }
        out
    }
    fn neighbor<'a>(&self, cache: &'a mut Cache, p: [i32; 3]) -> &'a [Bound] {
        cache
            .entry(p)
            .or_insert_with(|| self.collect(p, 127, &mut Default::default()))
    }
    pub fn emit(&self, cache: &mut Cache, job: &mut Job, p: [i32; 3], visible: u32) {
        let own = self.collect(p, visible, &mut job.hacks);
        for mut source in own {
            let Some(mut rect) = Rectangle::plane_bounds(&source.face) else {
                emit(job, source);
                continue;
            };
            let local = (p[rect.axis] - self.origin[rect.axis]) as f32;
            let sign = normal(&source.face)[rect.axis];
            let side = i32::from(sign > 0.);
            let plane = local + side as f32;
            // Restore only the documented fluid side/bottom inset, and only against a known
            // non-leaf neighbor. Unknown geometry and the intentional water/leaf gap stay intact.
            let mut n = p;
            n[rect.axis] += if side == 1 { 1 } else { -1 };
            let neighbor = self.state(n);
            if source.fluid
                && (rect.axis != 1 || side == 0)
                && neighbor.is_some_and(|s| s.flags & 1024 == 0)
                && ((rect.plane - plane).abs() - 0.001).abs()
                    <= 2. * f32::EPSILON * local.abs().max(1.)
            {
                for v in &mut source.face.geometry.positions {
                    v[rect.axis] = plane;
                }
                rect.plane = plane;
            }
            if rect.plane != plane || sign == 0. || neighbor.is_none() {
                emit(job, source);
                continue;
            }
            // Out-of-block custom geometry has no one-cell dependency proof.
            if source.face.geometry.positions.iter().any(|v| {
                (0..3).any(|a| {
                    v[a] < (p[a] - self.origin[a]) as f32
                        || v[a] > (p[a] - self.origin[a] + 1) as f32
                })
            }) {
                emit(job, source);
                continue;
            }
            let candidates: Vec<_> = self
                .neighbor(cache, n)
                .iter()
                .filter_map(|other| {
                    if other.fluid && neighbor.unwrap().flags & 1024 != 0 {
                        return None;
                    }
                    let other_rect = Rectangle::plane_bounds(&other.face)?;
                    if sign * normal(&other.face)[rect.axis] >= 0. {
                        return None;
                    }
                    let overlap = rect.intersection(other_rect)?;
                    Some((other, overlap))
                })
                .collect();
            // Ambiguous stacks are preserved instead of allowing source enumeration to pick a layer.
            if candidates.iter().enumerate().any(|(i, (_, a))| {
                candidates[i + 1..]
                    .iter()
                    .any(|(_, b)| a.intersection(*b).is_some())
            }) {
                emit(job, source);
                continue;
            }
            let mut remaining = vec![source.face.clone()];
            for (other, _) in candidates {
                if subtract_surface(&source.face, &other.face).is_none() {
                    continue;
                }
                let a = source.face.flags();
                let b = other.face.flags();
                let owner = if self.publishable(p) != self.publishable(n) {
                    self.publishable(p)
                } else {
                    p < n
                };
                let fire = matches!(
                    self.state(p).unwrap().name.as_str(),
                    "minecraft:fire" | "minecraft:soul_fire"
                );
                let other_fire = matches!(
                    neighbor.unwrap().name.as_str(),
                    "minecraft:fire" | "minecraft:soul_fire"
                );
                let same_medium = source.face.optics.is_some_and(|o| o.transmit && !o.thin)
                    && other.face.optics.is_some_and(|o| o.transmit && !o.thin)
                    && source.face.media[0] == other.face.media[0]
                    && source.tint[0].is_none()
                    && other.tint[0].is_none();
                let same_medium = same_medium
                    && source.face.detail.is_none()
                    && other.face.detail.is_none()
                    && source.face.emission == Default::default()
                    && other.face.emission == Default::default();
                let action = if same_medium {
                    Resolution::Drop
                } else if a == 0 && b != 0 && !other_fire {
                    Resolution::Keep
                } else if b == 0 && a != 0 && !fire {
                    if self.publishable(p) && !self.publishable(n) {
                        Resolution::Neighbor
                    } else {
                        Resolution::Drop
                    }
                } else if source.face.detail.is_none() && other.face.detail.is_none() {
                    if owner {
                        Resolution::Combine
                    } else {
                        Resolution::Drop
                    }
                } else {
                    continue;
                };
                // An ordinary opaque side already wins unchanged. Splitting it around an
                // unrelated cutout changes neither material nor medium, only storage/work.
                if matches!(action, Resolution::Keep) && other.face.optics.is_none() {
                    continue;
                }
                remaining = remaining
                    .into_iter()
                    .flat_map(|face| {
                        subtract_surface(&face, &other.face).unwrap_or_else(|| vec![face])
                    })
                    .collect();
                if matches!(action, Resolution::Drop) {
                    continue;
                }
                let own_optical = source.face.optics.is_some_and(|o| o.transmit);
                let other_optical = other.face.optics.is_some_and(|o| o.transmit);
                let swap = matches!(action, Resolution::Neighbor)
                    || matches!(action, Resolution::Combine)
                        && (fire || (!own_optical && other_optical));
                let (base, top) = if swap {
                    (other, &source)
                } else {
                    (&source, other)
                };
                let Some(parts) = intersect_surfaces(&base.face, &top.face) else {
                    continue;
                };
                for (face, layer) in parts {
                    let mut piece = Bound {
                        face,
                        ..base.clone()
                    };
                    if let Some(other_optics) = top.face.optics {
                        let o = piece
                            .face
                            .optics
                            .get_or_insert(prime_scene::surface::Optics {
                                negative: Default::default(),
                                positive: Default::default(),
                                transmit: false,
                                thin: false,
                            });
                        o.positive = other_optics.negative;
                        piece.face.media[1] = top.face.media[0];
                    }
                    if matches!(action, Resolution::Combine) {
                        let mode = if fire || other_fire {
                            LayerMode::OverlayFront
                        } else if own_optical != other_optical {
                            LayerMode::OverlayBoth
                        } else {
                            LayerMode::Bilateral
                        };
                        piece.face.detail = Some(Arc::new(SurfaceDetail { mode, layer }));
                        piece.tint[1] = top.tint[0];
                    }
                    emit(job, piece);
                }
            }
            for face in remaining {
                emit(
                    job,
                    Bound {
                        face,
                        ..source.clone()
                    },
                );
            }
        }
    }
}
/// Partition against the two actual fluid triangles, not their bounding height. Their
/// vertical prisms are disjoint, so sloping water and a nonplanar top retain exact support.
fn water_contact(face: SurfaceFace, origin: [f32; 3], heights: [f32; 4]) -> Vec<SurfaceFace> {
    use prime_scene::surface::{Optics, partition_surface};
    if heights.iter().all(|&h| h == heights[0]) {
        let height = origin[1] + heights[0];
        let (inside, outside) = if face.geometry.positions.iter().all(|p| p[1] <= height) {
            (vec![face], Vec::new())
        } else if let Some(rect) = Rectangle::from_face(&face) {
            if let Some(axis) = rect.axes.iter().position(|&a| a == 1) {
                let mut wet = rect;
                wet.bounds[axis + 2] = wet.bounds[axis + 2].min(height);
                if wet.bounds[axis + 2] <= wet.bounds[axis] {
                    (Vec::new(), vec![face])
                } else {
                    (
                        prime_scene::surface::clip_rectangle(&face, wet),
                        rect.subtract(wet)
                            .into_iter()
                            .flat_map(|r| prime_scene::surface::clip_rectangle(&face, r))
                            .collect(),
                    )
                }
            } else {
                (Vec::new(), vec![face])
            }
        } else {
            partition_surface(&face, &[[0., -1., 0., f64::from(height)]])
        };
        return inside
            .into_iter()
            .map(|mut part| {
                let optics = part.optics.get_or_insert(Optics {
                    negative: Default::default(),
                    positive: Default::default(),
                    transmit: false,
                    thin: false,
                });
                optics.positive = crate::optics::water();
                part.media[1] = 1;
                part
            })
            .chain(outside)
            .collect();
    }
    let points = [
        [0., heights[0], 0.],
        [0., heights[1], 1.],
        [1., heights[2], 1.],
        [1., heights[3], 0.],
    ]
    .map(|p| std::array::from_fn::<_, 3, _>(|a| f64::from(p[a]) + f64::from(origin[a])));
    let mut remaining = vec![face];
    let mut result = Vec::new();
    for ids in [[0, 1, 2], [2, 3, 0]] {
        let p = ids.map(|i| points[i]);
        let e = std::array::from_fn::<_, 3, _>(|a| p[1][a] - p[0][a]);
        let f = std::array::from_fn::<_, 3, _>(|a| p[2][a] - p[0][a]);
        let n = [
            e[1] * f[2] - e[2] * f[1],
            e[2] * f[0] - e[0] * f[2],
            e[0] * f[1] - e[1] * f[0],
        ];
        let mut planes = Vec::with_capacity(4);
        planes.push([-n[0], -n[1], -n[2], (0..3).map(|a| n[a] * p[0][a]).sum()]);
        for i in 0..3 {
            let a = p[i];
            let b = p[(i + 1) % 3];
            let dx = b[0] - a[0];
            let dz = b[2] - a[2];
            planes.push([dz, 0., -dx, -dz * a[0] + dx * a[2]]);
        }
        let mut next = Vec::new();
        for f in remaining {
            let (inside, outside) = partition_surface(&f, &planes);
            for mut part in inside {
                let optics = part.optics.get_or_insert(Optics {
                    negative: Default::default(),
                    positive: Default::default(),
                    transmit: false,
                    thin: false,
                });
                optics.positive = crate::optics::water();
                part.media[1] = 1;
                result.push(part);
            }
            next.extend(outside);
        }
        remaining = next;
    }
    result.extend(remaining);
    result
}
fn normal(face: &SurfaceFace) -> [f32; 3] {
    let p = face.geometry.positions;
    let a = std::array::from_fn::<_, 3, _>(|i| p[1][i] - p[0][i]);
    let b = std::array::from_fn::<_, 3, _>(|i| p[2][i] - p[0][i]);
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
enum Resolution {
    Drop,
    Keep,
    Neighbor,
    Combine,
}
fn emit(job: &mut Job, bound: Bound) {
    let layer = bound.face.flags() as usize;
    let face = &bound.face;
    let g = &face.geometry;
    if face.detail.is_none()
        && face.optics.is_none()
        && face.repeat.is_none()
        && face.emission == Default::default()
        && face.media == [0; 2]
        && g.colors.iter().all(|c| *c == g.colors[0])
    {
        let i = job.layers[layer].len();
        job.layers[layer].push(prime_scene::compiled::CompiledQuad {
            positions: g.positions,
            uvs: g.uvs,
            color: g.colors[0],
            texture_id: g.texture_id,
            flags: g.flags,
        });
        if let Some(request) = bound.tint[0] {
            job.tints.bind(request, layer, i);
        }
        return;
    }
    let i = job.surfaces[layer].len();
    job.surfaces[layer].push(bound.face);
    for (side, request) in bound.tint.into_iter().enumerate() {
        if let Some(request) = request {
            job.tints.bind(request, 3 + side * 3 + layer, i);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Model, Quad};
    fn sheet(axis: usize, side: usize, width: f32, layer: usize, tint: i32) -> Quad {
        let axes = [(axis + 1) % 3, (axis + 2) % 3];
        let positions = [[0., 0.], [width, 0.], [width, 1.], [0., 1.]].map(|uv| {
            let mut p = [0.; 3];
            p[axis] = side as f32;
            p[axes[0]] = uv[0];
            p[axes[1]] = uv[1];
            p
        });
        let mut q = Quad {
            positions,
            uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
            face: 6,
            tint,
            layer,
            sprite: 0,
            emission: 0,
        };
        if side == 0 {
            q.positions = [0, 3, 2, 1].map(|i| q.positions[i]);
            q.uvs = [0, 3, 2, 1].map(|i| q.uvs[i]);
        }
        q
    }
    fn job(p: [i32; 3]) -> Job {
        Job {
            key: Section(
                p[0].div_euclid(16),
                p[1].div_euclid(16),
                p[2].div_euclid(16),
            ),
            first_y: 0,
            layers: Default::default(),
            surfaces: Default::default(),
            hacks: Default::default(),
            compiled: None,
            tints: Default::default(),
            color_start: 0,
        }
    }
    fn sources(
        axis: usize,
        at: i32,
        flags: [usize; 2],
    ) -> (Catalog, HashMap<Section, SectionData>, [[i32; 3]; 2]) {
        let mut a = [0; 3];
        a[axis] = at;
        let mut b = a;
        b[axis] += 1;
        let mut catalog = Catalog::default();
        catalog.states.insert(
            0,
            State {
                flags: 1,
                ..Default::default()
            },
        );
        for (id, side) in [(1, 1), (2, 0)] {
            catalog.models.insert(
                id,
                Model::Mesh(vec![sheet(
                    axis,
                    side,
                    if id == 1 { 1. } else { 0.5 },
                    flags[id as usize - 1],
                    id as i32,
                )]),
            );
            catalog.states.insert(
                id,
                State {
                    id,
                    model: id,
                    name: format!("test:{id}"),
                    ..Default::default()
                },
            );
        }
        catalog.prepare();
        let mut sections = HashMap::new();
        for (p, id) in [(a, 1_u64), (b, 2_u64)] {
            let s = Section(
                p[0].div_euclid(16),
                p[1].div_euclid(16),
                p[2].div_euclid(16),
            );
            let d = sections.entry(s).or_insert_with(|| SectionData {
                bits: 32,
                per_word: 2,
                palette: vec![],
                storage: vec![0; 2048],
            });
            let i = (p[1].rem_euclid(16) * 256 + p[2].rem_euclid(16) * 16 + p[0].rem_euclid(16))
                as usize;
            d.storage[i / 2] |= id << ((i % 2) * 32);
        }
        (catalog, sections, [a, b])
    }
    fn compile(
        catalog: &Catalog,
        sections: &HashMap<Section, SectionData>,
        cells: &HashSet<[i32; 3]>,
        p: [i32; 3],
    ) -> Job {
        let mut j = job(p);
        let context = Context {
            catalog,
            sections,
            cells,
            origin: [j.key.0 * 16, j.key.1 * 16, j.key.2 * 16],
        };
        context.emit(&mut Cache::new(), &mut j, p, 127);
        j
    }
    fn faces(job: &Job, layer: usize) -> Vec<SurfaceFace> {
        job.layers[layer]
            .iter()
            .copied()
            .map(SurfaceFace::from_quad)
            .chain(job.surfaces[layer].iter().cloned())
            .collect()
    }
    #[test]
    fn partial_contacts_have_one_owner_across_every_section_and_cluster_boundary() {
        for axis in 0..3 {
            for at in [-65, -17, -1, 15, 63] {
                let (catalog, sections, [a, b]) = sources(axis, at, [1, 1]);
                let cells =
                    HashSet::from([a.map(|v| v.div_euclid(64)), b.map(|v| v.div_euclid(64))]);
                let ja = compile(&catalog, &sections, &cells, a);
                let jb = compile(&catalog, &sections, &cells, b);
                assert_eq!(faces(&ja, 1).len(), 2, "axis={axis} at={at}");
                assert!((0..3).all(|layer| faces(&jb, layer).is_empty()));
                let compound = ja.surfaces[1].iter().find(|s| s.detail.is_some()).unwrap();
                assert_eq!(compound.detail.as_ref().unwrap().mode, LayerMode::Bilateral);
                assert_eq!(
                    ja.tints.requests.iter().filter(|r| r.position == b).count(),
                    1
                );
                // The winning cell can leave the render window while remaining in source halo.
                if a[axis].div_euclid(64) != b[axis].div_euclid(64) {
                    let cells = HashSet::from([b.map(|v| v.div_euclid(64))]);
                    let jb = compile(&catalog, &sections, &cells, b);
                    assert_eq!(jb.surfaces[1].len(), 1);
                    assert!(jb.surfaces[1][0].detail.is_some());
                }
            }
        }
    }
    #[test]
    fn opaque_partial_contact_clips_only_covered_area_and_unknown_halo_preserves_source() {
        let (catalog, mut sections, [a, b]) = sources(2, 15, [2, 0]);
        let cells = HashSet::from([[0, 0, 0]]);
        let ja = compile(&catalog, &sections, &cells, a);
        let jb = compile(&catalog, &sections, &cells, b);
        assert_eq!(faces(&ja, 2).len(), 1);
        assert_eq!(faces(&jb, 0).len(), 1);
        assert_eq!(
            Rectangle::from_face(&faces(&ja, 2)[0]).unwrap().bounds,
            [0.5, 0., 1., 1.]
        );
        sections.remove(&Section(0, 0, 1));
        let ja = compile(&catalog, &sections, &cells, a);
        assert_eq!(
            Rectangle::from_face(&faces(&ja, 2)[0]).unwrap().bounds,
            [0., 0., 1., 1.]
        );
    }
    #[test]
    fn opaque_against_partial_cutout_preserves_one_compact_face_and_tint_binding() {
        let (catalog, sections, [a, b]) = sources(2, 15, [0, 1]);
        let cells = HashSet::from([[0, 0, 0]]);
        let ja = compile(&catalog, &sections, &cells, a);
        assert!(ja.surfaces.iter().all(Vec::is_empty));
        assert_eq!(ja.layers[0].len(), 1);
        assert_eq!(
            Rectangle::from_face(&faces(&ja, 0)[0]).unwrap().bounds,
            [0., 0., 1., 1.]
        );
        assert_eq!(ja.tints.requests.len(), 1);
        assert_eq!(ja.tints.binding(0, 0).unwrap().position, a);
        let jb = compile(&catalog, &sections, &cells, b);
        assert!((0..3).all(|layer| faces(&jb, layer).is_empty()));
    }
    #[test]
    fn waterlogged_glass_partitions_the_actual_nonplanar_top_and_keeps_dry_regions() {
        let q = sheet(0, 0, 1., 2, -1);
        let mut face = SurfaceFace::from_quad(crate::surfaces::closed(
            &State::default(),
            &q,
            [0.5, 0., 0.],
        ));
        face.media = [7, 0];
        face.optics = Some(prime_scene::surface::Optics {
            negative: prime_scene::surface::Medium {
                ior: 1.5,
                extinction: [0.1; 3],
            },
            positive: Default::default(),
            transmit: true,
            thin: false,
        });
        for heights in [[0.5; 4], [0.3, 0.9, 0.6, 0.8]] {
            let parts = water_contact(face.clone(), [0.; 3], heights);
            assert!(
                (parts
                    .iter()
                    .map(|f| f.geometry.areas().iter().sum::<f64>())
                    .sum::<f64>()
                    - 1.)
                    .abs()
                    < 1e-6
            );
            for z in 0..31 {
                for y in 0..29 {
                    let z = (z as f32 + 0.23) / 31.;
                    let y = (y as f32 + 0.19) / 29.;
                    let height = if z >= 0.5 {
                        heights[0] * (1. - z) + heights[1] * (z - 0.5) + heights[2] * 0.5
                    } else {
                        heights[0] * 0.5 + heights[2] * z + heights[3] * (0.5 - z)
                    };
                    let candidates: Vec<_> = parts
                        .iter()
                        .filter(|f| {
                            (0..2).any(|half| {
                                let p = f.geometry.triangle(half).positions;
                                let a = [p[1][1] - p[0][1], p[1][2] - p[0][2]];
                                let b = [p[2][1] - p[0][1], p[2][2] - p[0][2]];
                                let d = [y - p[0][1], z - p[0][2]];
                                let det = a[0] * b[1] - a[1] * b[0];
                                if det == 0. {
                                    return false;
                                }
                                let u = (d[0] * b[1] - d[1] * b[0]) / det;
                                let v = (a[0] * d[1] - a[1] * d[0]) / det;
                                u >= 0. && v >= 0. && u + v <= 1.
                            })
                        })
                        .collect();
                    assert_eq!(candidates.len(), 1, "point y={y} z={z}");
                    assert_eq!(candidates[0].media[1] == 1, y < height);
                    assert_eq!(candidates[0].media[0], 7);
                }
            }
        }
    }
}
