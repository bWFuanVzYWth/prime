use super::*;
use rectangle_decomposition::QuadLeaf64;
use std::{collections::BTreeMap, num::NonZeroU16};

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
struct Plane {
    domain: u64,
    axis: usize,
    at: u32,
    tile: [i32; 2],
    reverse: bool,
}

#[derive(Clone, Copy)]
struct Cell {
    source: usize,
    xy: [u8; 2],
    /// Source corners in positive canonical plane order: 00, 10, 11, 01.
    corners: [usize; 4],
    mapping: RepeatUv,
}

fn media(rule: SurfaceRule) -> [u32; 2] {
    match rule {
        SurfaceRule::Interface { negative, positive } => [negative, positive],
        _ => [0; 2],
    }
}

fn validate(q: &SurfaceQuad) -> Result<(), String> {
    let g = &q.geometry;
    if g.flags > 2
        || g.positions.as_flattened().iter().any(|v| !v.is_finite())
        || g.uvs.as_flattened().iter().any(|v| !v.is_finite())
        || g.color
            .iter()
            .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
        || q.emission
            .radiance
            .iter()
            .any(|v| !v.is_finite() || *v < 0.0)
    {
        return Err("Invalid closed surface geometry/material".into());
    }
    if matches!(q.rule, SurfaceRule::Interface { .. }) && q.emission != Emission::default() {
        return Err("An uncoated interface cannot carry an emission layer".into());
    }
    Ok(())
}

fn exact_sum(a: f32, b: f32) -> (f64, f64) {
    let (a, b) = (f64::from(a), f64::from(b));
    let sum = a + b;
    let b_virtual = sum - a;
    (sum, (a - (sum - b_virtual)) + (b - b_virtual))
}

/// Exact regular unit quad, with a UV map that is affine across both source triangles.
/// No snapping, rounding to a grid, normal guess, or epsilon is involved.
fn grid(g: &CompiledQuad, domain: u64, source: usize) -> Option<(Plane, Cell)> {
    let axis = (0..3).find(|&a| g.positions.iter().all(|p| p[a] == g.positions[0][a]))?;
    let u = (axis + 1) % 3;
    let v = (axis + 2) % 3;
    let lo = [u, v].map(|a| {
        g.positions
            .iter()
            .map(|p| p[a])
            .fold(f32::INFINITY, f32::min)
    });
    if lo
        .iter()
        .any(|&x| x != x.floor() || !(-16_777_216.0..16_777_216.0).contains(&x))
    {
        return None;
    }
    let mut corners = [usize::MAX; 4];
    let mut order = [0; 4];
    for (i, p) in g.positions.iter().enumerate() {
        let delta = [p[u] - lo[0], p[v] - lo[1]];
        let c = match delta {
            [0.0, 0.0] => 0,
            [1.0, 0.0] => 1,
            [1.0, 1.0] => 2,
            [0.0, 1.0] => 3,
            _ => return None,
        };
        if corners[c] != usize::MAX {
            return None;
        }
        corners[c] = i;
        order[i] = c;
    }
    let step = (order[1] + 4 - order[0]) % 4;
    if ![1, 3].contains(&step) || (0..4).any(|i| order[(i + 1) % 4] != (order[i] + step) % 4) {
        return None;
    }
    let uv = corners.map(|i| g.uvs[i]);
    let mapping = RepeatUv {
        origin: uv[0],
        du: std::array::from_fn(|i| uv[1][i] - uv[0][i]),
        dv: std::array::from_fn(|i| uv[3][i] - uv[0][i]),
    };
    for i in 0..2 {
        // Keep the residual: f32 UVs with widely separated exponents can lose information
        // even in an f64 sum. The new map must represent the exact source affine function.
        if exact_sum(uv[1][i], -uv[0][i]) != (f64::from(mapping.du[i]), 0.0)
            || exact_sum(uv[3][i], -uv[0][i]) != (f64::from(mapping.dv[i]), 0.0)
            || exact_sum(uv[0][i], uv[2][i]) != exact_sum(uv[1][i], uv[3][i])
        {
            return None;
        }
    }
    let at = g.positions[0][axis];
    Some((
        Plane {
            domain,
            axis,
            at: if at == 0.0 { 0 } else { at.to_bits() },
            tile: lo.map(|x| (x as i32).div_euclid(64)),
            reverse: step == 3,
        },
        Cell {
            source,
            xy: lo.map(|x| (x as i32).rem_euclid(64) as u8),
            corners,
            mapping,
        },
    ))
}

fn label(q: &SurfaceQuad, mapping: RepeatUv) -> [u32; 18] {
    let [r, g, b, a] = q.geometry.color.map(f32::to_bits);
    let [u, v] = mapping.origin.map(f32::to_bits);
    let [du, dv] = mapping.du.map(f32::to_bits);
    let [eu, ev] = mapping.dv.map(f32::to_bits);
    let [er, eg, eb] = q.emission.radiance.map(f32::to_bits);
    let [negative, positive] = media(q.rule);
    [
        q.geometry.texture_id,
        q.geometry.flags,
        r,
        g,
        b,
        a,
        u,
        v,
        du,
        dv,
        eu,
        ev,
        er,
        eg,
        eb,
        u32::from(q.emission.two_sided),
        negative,
        positive,
    ]
}

pub(super) fn append(out: &mut Vec<SurfaceFace>, q: &SurfaceQuad, repeat: Option<RepeatUv>) {
    out.push(SurfaceFace {
        geometry: q.geometry.into(),
        repeat,
        emission: q.emission,
        media: media(q.rule),
        emitter: None,
        emitter_area_weight: 0.0,
    });
}

// Include the actual two source triangles in the proof. Merely sorting four vertices would
// incorrectly remove a nonplanar quad with a different diagonal or a bow-tie ordering.
fn duplicate_key(q: &SurfaceQuad) -> ([[[u32; 5]; 3]; 2], [u32; 10]) {
    let mut triangles = [[0, 1, 2], [2, 3, 0]].map(|indices| {
        let mut vertices = indices.map(|i| {
            let [x, y, z] = q.geometry.positions[i].map(f32::to_bits);
            let [u, v] = q.geometry.uvs[i].map(f32::to_bits);
            [x, y, z, u, v]
        });
        vertices.sort_unstable();
        vertices
    });
    triangles.sort_unstable();
    let [r, g, b, a] = q.geometry.color.map(f32::to_bits);
    let [er, eg, eb] = q.emission.radiance.map(f32::to_bits);
    (
        triangles,
        [
            q.geometry.texture_id,
            q.geometry.flags,
            r,
            g,
            b,
            a,
            er,
            eg,
            eb,
            u32::from(q.emission.two_sided),
        ],
    )
}

impl SurfaceCompiler {
    pub(super) fn grid_candidate(quad: &CompiledQuad) -> bool {
        grid(quad, 0, 0).is_some()
    }

    pub(super) fn compile_rectangles(
        &mut self,
        quads: &[SurfaceQuad],
    ) -> Result<(Vec<SurfaceFace>, Vec<usize>, CompileStats), String> {
        let mut stats = CompileStats {
            source_quads: quads.len(),
            ..Default::default()
        };
        let mut output = Vec::new();
        let mut retained = Vec::new();
        let mut planes: BTreeMap<Plane, Vec<Cell>> = BTreeMap::new();
        let mut duplicates = std::collections::BTreeSet::new();
        // Source order is meaningful for explicit duplicate owners: choose the lowest stable
        // provenance, independently of the producer's capture/worker ordering.
        let mut order: Vec<_> = (0..quads.len()).collect();
        order.sort_unstable_by_key(|&i| quads[i].provenance);
        for index in order {
            let q = &quads[index];
            validate(q)?;
            match q.rule {
                SurfaceRule::Interface { negative, positive } if negative == positive => {
                    stats.removed_interfaces += 1;
                    continue;
                }
                // One-sided emission cannot use an unoriented duplicate proof.
                SurfaceRule::Duplicate(group)
                    if (q.emission == Emission::default() || q.emission.two_sided)
                        && !duplicates.insert((q.provenance.domain, group, duplicate_key(q))) =>
                {
                    stats.removed_duplicates += 1;
                    continue;
                }
                _ => {}
            }
            if let Some((plane, cell)) = grid(&q.geometry, q.provenance.domain, index) {
                planes.entry(plane).or_default().push(cell);
            } else {
                retained.push(index);
                stats.passthrough_quads += 1;
            }
        }
        let mut leaves = Vec::new();
        let mut labels = BTreeMap::new();
        let mut representatives = Vec::new();
        let mut cell_sources = [0; 4096];
        let mut cell_labels = [0; 4096];
        for (plane, mut cells) in planes {
            leaves.clear();
            labels.clear();
            representatives.clear();
            cell_labels.fill(0);
            cells.sort_unstable_by_key(|c| (c.xy, quads[c.source].provenance));
            let mut first = 0;
            while first < cells.len() {
                let mut end = first + 1;
                while end < cells.len() && cells[end].xy == cells[first].xy {
                    end += 1;
                }
                // Coincident surfaces may be ordered coverage layers. Without a source proof,
                // exclude ALL occupants of this cell from merging, not just the later one.
                if end - first != 1 {
                    for cell in &cells[first..end] {
                        retained.push(cell.source);
                        stats.passthrough_quads += 1;
                    }
                } else {
                    let cell = cells[first];
                    let next = representatives.len() as u16 + 1; // at most 4096 distinct cells
                    let value = *labels
                        .entry(label(&quads[cell.source], cell.mapping))
                        .or_insert_with(|| {
                            representatives.push(cell);
                            next
                        });
                    leaves.push(QuadLeaf64 {
                        u: cell.xy[0],
                        v: cell.xy[1],
                        lod: 0,
                        value: NonZeroU16::new(value).unwrap(),
                    });
                    let at = usize::from(cell.xy[1]) * 64 + usize::from(cell.xy[0]);
                    cell_sources[at] = cell.source;
                    cell_labels[at] = value;
                    stats.grid_quads += 1;
                }
                first = end;
            }
            // Any rectangle larger than one cell contains an equal-label shared edge. This
            // exact rejection keeps checkerboards out of the decomposition/output expansion.
            if !leaves.iter().any(|leaf| {
                let at = usize::from(leaf.v) * 64 + usize::from(leaf.u);
                (leaf.u != 0 && cell_labels[at - 1] == leaf.value.get())
                    || (leaf.v != 0 && cell_labels[at - 64] == leaf.value.get())
            }) {
                retained.extend(
                    leaves
                        .iter()
                        .map(|leaf| cell_sources[usize::from(leaf.v) * 64 + usize::from(leaf.u)]),
                );
                stats.rectangles += leaves.len();
                continue;
            }
            let rectangles = self
                .rectangles
                .decompose_borrowed(&leaves)
                .map_err(|e| format!("Surface rectangle decomposition: {e:?}"))?;
            for rectangle in rectangles {
                let width = rectangle.x.end - rectangle.x.start;
                let height = rectangle.y.end - rectangle.y.start;
                stats.rectangles += 1;
                if width == 1 && height == 1 {
                    retained.push(
                        cell_sources
                            [usize::from(rectangle.y.start) * 64 + usize::from(rectangle.x.start)],
                    );
                    continue;
                }
                let cell = representatives[usize::from(rectangle.value) - 1];
                let source = &quads[cell.source];
                let mut q = source.clone();
                let u = (plane.axis + 1) % 3;
                let v = (plane.axis + 2) % 3;
                let xy = [[0, 0], [width, 0], [width, height], [0, height]];
                for (c, delta) in xy.into_iter().enumerate() {
                    let i = cell.corners[c];
                    q.geometry.positions[i][plane.axis] = f32::from_bits(plane.at);
                    q.geometry.positions[i][u] =
                        (plane.tile[0] * 64 + i32::from(rectangle.x.start) + i32::from(delta[0]))
                            as f32;
                    q.geometry.positions[i][v] =
                        (plane.tile[1] * 64 + i32::from(rectangle.y.start) + i32::from(delta[1]))
                            as f32;
                    q.geometry.uvs[i] = delta.map(f32::from);
                }
                append(&mut output, &q, Some(cell.mapping));
            }
        }
        Ok((output, retained, stats))
    }
}
