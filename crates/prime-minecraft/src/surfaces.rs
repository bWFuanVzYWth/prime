//! Resource-local proofs. No placement, tint callback or world-space epsilon participates.
use crate::model::{Quad, State};
use prime_scene::{
    compiled::CompiledQuad,
    surface::{Emission, LayerMode, SurfaceDetail, SurfaceFace, SurfaceLayer},
};
use std::sync::Arc;
#[cfg(test)]
#[path = "surfaces_tests.rs"]
mod tests;

#[derive(Clone, Copy)]
pub(crate) struct Pair {
    pub other: usize,
    pub corners: [usize; 4],
    pub reverse: bool,
    pub inner: bool,
}
pub(crate) struct Recipe {
    pub source: usize,
    pub pair: Option<Pair>,
    pub two_sided: bool,
}

fn exact_sum(a: f64, b: f64) -> (f64, f64) {
    let sum = a + b;
    let b_virtual = sum - a;
    (sum, (a - (sum - b_virtual)) + (b - b_virtual))
}
fn affine<const N: usize>(values: [[f32; N]; 4]) -> bool {
    (0..N).all(|i| {
        exact_sum(f64::from(values[0][i]), f64::from(values[2][i]))
            == exact_sum(f64::from(values[1][i]), f64::from(values[3][i]))
    })
}
fn nondegenerate(positions: [[f32; 3]; 4]) -> bool {
    let p = positions.map(|p| p.map(f64::from));
    (0..3).any(|axis| {
        let (x, y) = ((axis + 1) % 3, (axis + 2) % 3);
        // Each product of two source f32 values is exact in f64. An expansion keeps every
        // residual of the projected (p1-p0) x (p3-p0), including nearly collinear input.
        let terms = [
            p[1][x] * p[3][y],
            -p[1][x] * p[0][y],
            -p[0][x] * p[3][y],
            -p[1][y] * p[3][x],
            p[1][y] * p[0][x],
            p[0][y] * p[3][x],
        ];
        let mut expansion = [0.; 6];
        let mut count = 0;
        for mut sum in terms {
            let mut next = 0;
            for i in 0..count {
                let (high, low) = exact_sum(sum, expansion[i]);
                sum = high;
                if low != 0. {
                    expansion[next] = low;
                    next += 1;
                }
            }
            if sum != 0. {
                expansion[next] = sum;
                next += 1;
            }
            count = next;
        }
        count != 0
    })
}
fn mapping(a: &Quad, b: &Quad) -> Option<([usize; 4], bool)> {
    for reverse in [false, true] {
        // Even offsets preserve the source diagonal and both exact triangle interpolants.
        for shift in [0, 2, 1, 3] {
            let corners = std::array::from_fn(|i| {
                if reverse {
                    (shift + 4 - i) % 4
                } else {
                    (shift + i) % 4
                }
            });
            if (0..4).all(|i| a.positions[i] == b.positions[corners[i]])
                && (shift % 2 == 0
                    // A different diagonal is equivalent only on a nondegenerate exact
                    // parallelogram with an affine UV map on each source side.
                    || affine(a.positions)
                        && nondegenerate(a.positions)
                        && affine(a.uvs)
                        && affine(b.uvs))
            {
                return Some((corners, reverse));
            }
        }
    }
    None
}
fn same(a: &Quad, b: &Quad, corners: [usize; 4]) -> bool {
    a.tint == b.tint
        && a.layer == b.layer
        && a.sprite == b.sprite
        && a.emission == b.emission
        && (0..4).all(|i| a.uvs[i] == b.uvs[corners[i]])
}
fn inner(a: &Quad, b: &Quad) -> Option<[usize; 4]> {
    const E: f32 = 0.002 / 16.;
    let mut expanded = b.clone();
    for p in &mut expanded.positions {
        for x in p {
            *x = if *x == E {
                0.
            } else if *x == 1. - E {
                1.
            } else {
                return None;
            };
        }
    }
    let (corners, reverse) = mapping(a, &expanded)?;
    (reverse && same(a, b, corners)).then_some(corners)
}
pub(crate) fn prepare(quads: &[Quad]) -> Vec<Recipe> {
    let mut used = vec![false; quads.len()];
    let mut out = Vec::new();
    for i in 0..quads.len() {
        if used[i] {
            continue;
        }
        let a = &quads[i];
        let mut two_sided = false;
        // Arbitrarily many identical copies with the SAME activation condition can disappear
        // at definition time; differing cull conditions stay in a conditional recipe.
        for j in i + 1..quads.len() {
            if !used[j]
                && a.face == quads[j].face
                && let Some((m, reverse)) = mapping(a, &quads[j])
                && same(a, &quads[j], m)
            {
                used[j] = true;
                two_sided |= reverse;
            }
        }
        let mut recipe = Recipe {
            source: i,
            pair: None,
            two_sided,
        };
        for j in i + 1..quads.len() {
            if used[j] {
                continue;
            }
            let b = &quads[j];
            let pair = if let Some((corners, reverse)) = mapping(a, b) {
                Some(Pair {
                    other: j,
                    corners,
                    reverse,
                    inner: false,
                })
            } else if let Some(corners) = inner(a, b) {
                Some(Pair {
                    other: j,
                    corners,
                    reverse: true,
                    inner: true,
                })
            } else if let Some(corners) = inner(b, a) {
                recipe.source = j;
                Some(Pair {
                    other: i,
                    corners,
                    reverse: true,
                    inner: true,
                })
            } else {
                None
            };
            if let Some(pair) = pair {
                recipe.pair = Some(pair);
                used[j] = true;
                break;
            }
        }
        out.push(recipe);
    }
    out
}

pub(crate) fn flags(state: &State, q: &Quad) -> usize {
    if matches!(
        state.name.as_str(),
        "minecraft:redstone_wire" | "minecraft:redstone_torch" | "minecraft:redstone_wall_torch"
    ) {
        1
    } else {
        q.layer
    }
}
fn overlay(state: &State, q: &Quad) -> bool {
    state.name == "minecraft:redstone_wire" && q.tint < 0
        || state.name == "minecraft:grass_block"
            && q.tint >= 0
            && (q.positions[1][1] != q.positions[0][1] || q.positions[2][1] != q.positions[0][1])
}
pub(crate) fn closed(state: &State, q: &Quad, offset: [f32; 3]) -> CompiledQuad {
    CompiledQuad {
        positions: q
            .positions
            .map(|p| std::array::from_fn(|a| p[a] + offset[a])),
        uvs: q.uvs,
        color: [1.; 4],
        texture_id: crate::sprite::texture(q.sprite),
        flags: flags(state, q) as u32,
    }
}
pub(crate) fn emission(state: &State, q: &Quad, two_sided: bool) -> Emission {
    let level = state.emission.max(q.emission);
    if level == 0 {
        return Emission::default();
    }
    // Prime's calibrated fallback, matching the previous translator; MC light level is ordinal.
    Emission {
        radiance: [1.5 * (level as f32 / 15.).powi(2); 3],
        two_sided: two_sided || flags(state, q) == 1,
        textured: true,
    }
}

/// Returns the two tint slots in actual retained geometry order, with their independent bindings.
pub(crate) fn resolve(
    state: &State,
    a: &Quad,
    b: &Quad,
    pair: Pair,
    offset: [f32; 3],
) -> Option<(SurfaceFace, [i32; 2])> {
    if pair.inner || same(a, b, pair.corners) {
        let mut face = SurfaceFace::from_quad(closed(state, a, offset));
        face.emission = emission(state, a, pair.reverse);
        return Some((face, [a.tint, -1]));
    }
    let (base, top, corners, mode) = if pair.reverse {
        (a, b, pair.corners, LayerMode::Bilateral)
    } else if overlay(state, b) && !overlay(state, a) {
        (a, b, pair.corners, LayerMode::OverlayBoth)
    } else if overlay(state, a) && !overlay(state, b) {
        let inverse = std::array::from_fn(|i| pair.corners.iter().position(|&c| c == i).unwrap());
        (b, a, inverse, LayerMode::OverlayBoth)
    } else {
        return None;
    };
    let mut face = SurfaceFace::from_quad(closed(state, base, offset));
    face.emission = emission(state, base, false);
    face.detail = Some(Arc::new(SurfaceDetail {
        mode,
        layer: SurfaceLayer {
            colors: [[1.; 4]; 4],
            uvs: corners.map(|i| top.uvs[i]),
            texture_id: crate::sprite::texture(top.sprite),
            flags: flags(state, top) as u32,
            repeat: None,
            emission: emission(state, top, false),
        },
    }));
    Some((face, [base.tint, top.tint]))
}
