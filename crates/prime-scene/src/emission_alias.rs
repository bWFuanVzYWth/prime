//! Immutable all-frame emission support and exact clipped-texel area proposals.
//! Source envelopes are CPU-only. The encoded per-emitter table is appended to its local
//! light-tree buffer, with an integer offset in the emitter's unused position padding.
use crate::surface::SurfaceFace;
use std::sync::Arc;

const RANDOM_MASS: u32 = 1 << 24;
const MAX_GRID: usize = 256 * 256;
const HEADER_WORDS: usize = 8;
const ENTRY_WORDS: usize = 8;
const PATCH_WORDS: usize = 8;

#[derive(Clone, Debug, PartialEq)]
pub struct EmissionProposal {
    pub extent: [u32; 2],
    // Five bytes per texel: linear-709 white-tint envelope weight, then peak coverage alpha.
    data: Arc<[u8]>,
}

impl EmissionProposal {
    /// The windows contain Color xy followed by MERS xy. The complete declared animation
    /// sequence is required, independently of the current/next descriptor and blend.
    pub fn from_frames(
        extent: [u32; 2],
        color_size: [u32; 2],
        color: &[u8],
        mers_size: [u32; 2],
        mers: &[u8],
        windows: &[[u32; 4]],
    ) -> Result<Option<Arc<Self>>, String> {
        let valid_image = |s: [u32; 2], p: &[u8]| {
            s.into_iter().all(|v| v > 0 && v <= 16384)
                && u64::from(s[0]) * u64::from(s[1]) * 4 == p.len() as u64
        };
        if extent.contains(&0)
            || !valid_image(color_size, color)
            || !valid_image(mers_size, mers)
            || windows.is_empty()
            || windows.iter().any(|w| {
                [color_size, mers_size]
                    .into_iter()
                    .enumerate()
                    .any(|(i, s)| {
                        (0..2).any(|a| {
                            u64::from(w[2 * i + a]) + u64::from(extent[a]) > u64::from(s[a])
                        })
                    })
            })
        {
            return Err("invalid complete emission proposal frame windows".into());
        }
        let count = extent[0] as usize * extent[1] as usize;
        // High-resolution sources retain the established uniform proposal, without allocating
        // an envelope that this first pixel-art implementation cannot consume.
        if count > MAX_GRID {
            return Ok(None);
        }
        let mut data = Vec::with_capacity(count * 5);
        let mut positive = false;
        let mut range = [f32::INFINITY, 0f32];
        let mut alpha_range = [255u8, 0u8];
        for y in 0..extent[1] {
            for x in 0..extent[0] {
                let mut peak = [0u8; 4];
                let mut emission = 0u8;
                for w in windows {
                    let c = (((w[1] + y) * color_size[0] + w[0] + x) * 4) as usize;
                    let m = (((w[3] + y) * mers_size[0] + w[2] + x) * 4) as usize;
                    for a in 0..4 {
                        peak[a] = peak[a].max(color[c + a]);
                    }
                    emission = emission.max(mers[m + 1]);
                }
                // Independent maxima cover encoded-RGBA animation interpolation, including
                // opposite Color/G endpoint trends whose intermediate product is nonzero.
                let linear: [f64; 3] = std::array::from_fn(|i| {
                    let b = peak[i];
                    let s = f64::from(b) / 255.;
                    if s <= 0.04045 {
                        s / 12.92
                    } else {
                        ((s + 0.055) / 1.055).powf(2.4)
                    }
                });
                let weight = ((linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722)
                    * f64::from(emission)
                    / 255.) as f32;
                positive |= weight > 0.;
                range[0] = range[0].min(weight);
                range[1] = range[1].max(weight);
                alpha_range[0] = alpha_range[0].min(peak[3]);
                alpha_range[1] = alpha_range[1].max(peak[3]);
                data.extend_from_slice(&weight.to_le_bytes());
                data.push(peak[3]);
            }
        }
        // This source proof also holds for every affine crop: its area-weighted mean is
        // at least the minimum. Constant peak alpha cannot create a texel mask in build.
        if range[0] > 0. && range[1] < 2. * range[0] && alpha_range[0] == alpha_range[1] {
            return Ok(None);
        }
        Ok(positive.then(|| {
            Arc::new(Self {
                extent,
                data: data.into(),
            })
        }))
    }

    pub fn backing(&self) -> &Arc<[u8]> {
        &self.data
    }
    pub fn valid_extent(&self, extent: [u32; 2]) -> bool {
        self.extent == extent
            && !extent.contains(&0)
            && extent[0] as usize * extent[1] as usize * 5 == self.data.len()
    }
    fn texel(&self, i: usize) -> (f64, u8) {
        (
            f64::from(f32::from_le_bytes(
                self.data[i * 5..i * 5 + 4].try_into().unwrap(),
            )),
            self.data[i * 5 + 4],
        )
    }
}

#[derive(Clone, Debug)]
pub struct EmitterAlias {
    /// Little-endian uint words, offsets relative to the first header word.
    pub words: Vec<u32>,
}

#[derive(Clone, Copy)]
struct Vertex {
    uv: [f64; 2],
    bary: [f64; 2],
}
struct Patch {
    half: u32,
    area: f64,
    bary: [[f64; 2]; 3],
}
struct Cell {
    texel: usize,
    weight: f64,
    area: f64,
    patches: Vec<Patch>,
}

fn determinant(a: [f64; 2], b: [f64; 2]) -> f64 {
    a[0] * b[1] - a[1] * b[0]
}
fn sub(a: [f64; 2], b: [f64; 2]) -> [f64; 2] {
    [a[0] - b[0], a[1] - b[1]]
}
fn clip(input: Vec<Vertex>, axis: usize, edge: f64, above: bool) -> Vec<Vertex> {
    let mut output = Vec::with_capacity(input.len() + 1);
    if input.is_empty() {
        return output;
    }
    let inside = |v: Vertex| {
        if above {
            v.uv[axis] >= edge
        } else {
            v.uv[axis] <= edge
        }
    };
    let mut a = *input.last().unwrap();
    for b in input {
        if inside(a) != inside(b) {
            let t = (edge - a.uv[axis]) / (b.uv[axis] - a.uv[axis]);
            output.push(Vertex {
                uv: std::array::from_fn(|i| a.uv[i] + t * (b.uv[i] - a.uv[i])),
                bary: std::array::from_fn(|i| a.bary[i] + t * (b.bary[i] - a.bary[i])),
            });
        }
        if inside(b) {
            output.push(b);
        }
        a = b;
    }
    output
}

/// Only one injective affine mapping is supported; layered/repeating/folded sources stay
/// uniform. Small arithmetic tolerance accommodates the source's f32 corner representation.
fn affine(face: &SurfaceFace) -> bool {
    let q = &face.geometry;
    if face.repeat.is_some()
        || face.detail.is_some()
        || q.uvs
            .iter()
            .flatten()
            .any(|&v| !v.is_finite() || !(0.0..=1.0).contains(&v))
    {
        return false;
    }
    let uv = q.uvs.map(|p| p.map(f64::from));
    let a = sub(uv[1], uv[0]);
    let b = sub(uv[2], uv[0]);
    let d = determinant(a, b);
    if d == 0. {
        return false;
    }
    let c = sub(uv[3], uv[0]);
    let s = determinant(c, b) / d;
    let t = determinant(a, c) / d;
    let positions = q.positions.map(|p| p.map(f64::from));
    let scale = q.areas().into_iter().sum::<f64>().sqrt().max(1e-12);
    if (0..3).any(|i| {
        let predicted = positions[0][i]
            + s * (positions[1][i] - positions[0][i])
            + t * (positions[2][i] - positions[0][i]);
        (predicted - positions[3][i]).abs() > scale * 1e-6
    }) {
        return false;
    }
    let d1 = determinant(sub(uv[3], uv[2]), sub(uv[0], uv[2]));
    // A collapsed second half is the declared independent-triangle representation.
    d1 == 0. && q.positions[3] == q.positions[2] || d1 * d > 0.
}

fn masses(weights: &[f64]) -> Vec<u32> {
    let sum: f64 = weights.iter().sum();
    let available = RANDOM_MASS - weights.len() as u32;
    let mut result = Vec::with_capacity(weights.len());
    let mut remainder = Vec::with_capacity(weights.len());
    for (i, &w) in weights.iter().enumerate() {
        let exact = w / sum * f64::from(available);
        result.push(1 + exact.floor() as u32);
        remainder.push((i, exact.fract()));
    }
    remainder.sort_unstable_by(|a, b| b.1.total_cmp(&a.1).then(a.0.cmp(&b.0)));
    let missing = RANDOM_MASS - result.iter().sum::<u32>();
    for &(i, _) in remainder.iter().take(missing as usize) {
        result[i] += 1;
    }
    result
}

impl EmitterAlias {
    pub fn build(face: &SurfaceFace, proposal: &EmissionProposal) -> Option<Self> {
        if !affine(face) {
            return None;
        }
        let q = &face.geometry;
        let areas = q.areas();
        let total_area: f64 = areas.iter().sum();
        if !total_area.is_finite() || total_area <= 0. {
            return None;
        }
        let [w, h] = proposal.extent;
        let lo: [u32; 2] = std::array::from_fn(|i| {
            (q.uvs.iter().map(|p| p[i]).fold(f32::INFINITY, f32::min) * proposal.extent[i] as f32)
                .floor() as u32
        });
        let hi: [u32; 2] = std::array::from_fn(|i| {
            (q.uvs.iter().map(|p| p[i]).fold(f32::NEG_INFINITY, f32::max)
                * proposal.extent[i] as f32)
                .ceil() as u32
        });
        let mut cells = Vec::new();
        let alpha = q.colors.iter().map(|c| f64::from(c[3])).fold(0., f64::max);
        let flags = if face.optics.is_some_and(|o| o.transmit) {
            0
        } else {
            q.flags & 3
        };
        for y in lo[1]..hi[1].min(h) {
            for x in lo[0]..hi[0].min(w) {
                let texel = (y * w + x) as usize;
                let (weight, peak_alpha) = proposal.texel(texel);
                let coverage = alpha * f64::from(peak_alpha) / 255.;
                if weight == 0. || flags == 1 && coverage < 0.1 || flags == 2 && coverage <= 0. {
                    continue;
                }
                let mut patches = Vec::new();
                for (half, &area) in areas.iter().enumerate() {
                    if area == 0. {
                        continue;
                    }
                    let t = q.triangle(half);
                    let mut polygon = t
                        .uvs
                        .into_iter()
                        .enumerate()
                        .map(|(i, uv)| Vertex {
                            uv: uv.map(f64::from),
                            bary: [[0., 0.], [1., 0.], [0., 1.]][i],
                        })
                        .collect();
                    for (axis, cell) in [x, y].into_iter().enumerate() {
                        let size = f64::from(proposal.extent[axis]);
                        polygon = clip(polygon, axis, f64::from(cell) / size, true);
                        polygon = clip(polygon, axis, f64::from(cell + 1) / size, false);
                    }
                    for i in 1..polygon.len().saturating_sub(1) {
                        let bary = [polygon[0].bary, polygon[i].bary, polygon[i + 1].bary];
                        let clipped_area =
                            area * determinant(sub(bary[1], bary[0]), sub(bary[2], bary[0])).abs();
                        if clipped_area > 0. {
                            patches.push(Patch {
                                half: half as u32,
                                area: clipped_area,
                                bary,
                            });
                        }
                    }
                }
                let area = patches.iter().map(|p| p.area).sum::<f64>();
                if area > 0. {
                    cells.push(Cell {
                        texel,
                        weight,
                        area,
                        patches,
                    });
                }
            }
        }
        if cells.is_empty() {
            return None;
        }
        let support_area: f64 = cells.iter().map(|c| c.area).sum();
        let weights: Vec<_> = cells.iter().map(|c| c.weight * c.area).collect();
        let mean = weights.iter().sum::<f64>() / total_area;
        let peak = cells.iter().map(|c| c.weight).fold(0., f64::max);
        // Dense low-contrast sources cannot justify these extra NEE/MIS reads.
        if support_area >= total_area * 0.75 && peak < mean * 2. {
            return None;
        }
        let mass = masses(&weights);
        let columns = cells.len().next_power_of_two();
        let capacity = RANDOM_MASS / columns as u32;
        let mut remaining = mass.clone();
        remaining.resize(columns, 0);
        let mut small = Vec::new();
        let mut large = Vec::new();
        for (i, &n) in remaining.iter().enumerate() {
            if n < capacity {
                small.push(i);
            } else {
                large.push(i);
            }
        }
        let mut alias = vec![(capacity, 0u32); columns];
        while let (Some(s), Some(l)) = (small.pop(), large.pop()) {
            alias[s] = (remaining[s], l as u32);
            remaining[l] -= capacity - remaining[s];
            if remaining[l] < capacity {
                small.push(l);
            } else {
                large.push(l);
            }
        }
        for (i, &n) in remaining.iter().enumerate() {
            if n == capacity {
                alias[i] = (capacity, i as u32);
            }
        }
        let entry_offset = HEADER_WORDS + w as usize * h as usize;
        let patch_offset = entry_offset + columns * ENTRY_WORDS;
        let mut words = vec![0; patch_offset];
        words[..HEADER_WORDS].copy_from_slice(&[
            w,
            h,
            columns as u32 - 1,
            24 - columns.trailing_zeros(),
            HEADER_WORDS as u32,
            entry_offset as u32,
            patch_offset as u32,
            cells.len() as u32,
        ]);
        words[HEADER_WORDS..entry_offset].fill(u32::MAX);
        for (i, &(threshold, other)) in alias.iter().enumerate() {
            let at = entry_offset + i * ENTRY_WORDS;
            words[at] = threshold;
            words[at + 1] = other;
        }
        for (i, cell) in cells.into_iter().enumerate() {
            words[HEADER_WORDS + cell.texel] = i as u32;
            let at = entry_offset + i * ENTRY_WORDS;
            words[at + 2] = mass[i];
            words[at + 3] = ((words.len() - patch_offset) / PATCH_WORDS) as u32;
            words[at + 4] = cell.patches.len() as u32;
            // Convert once at the CPU/GPU boundary; all GPU interval maths uses these f32 areas.
            words[at + 5] =
                ((f64::from(mass[i]) / f64::from(RANDOM_MASS) / cell.area) as f32).to_bits();
            words[at + 6] = (cell.area as f32).to_bits();
            for patch in cell.patches {
                words.extend([patch.half, (patch.area as f32).to_bits()]);
                words.extend(
                    patch
                        .bary
                        .into_iter()
                        .flatten()
                        .map(|v| (v as f32).to_bits()),
                );
            }
        }
        Some(Self { words })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::CompiledQuad;

    fn proposal(extent: [u32; 2], emission: &[u8]) -> Arc<EmissionProposal> {
        let color = vec![255; emission.len() * 4];
        let mers: Vec<_> = emission.iter().flat_map(|&e| [0, e, 0, 0]).collect();
        EmissionProposal::from_frames(extent, extent, &color, extent, &mers, &[[0; 4]])
            .unwrap()
            .unwrap()
    }
    fn face() -> SurfaceFace {
        SurfaceFace::from_quad(CompiledQuad {
            positions: [[0., 0., 0.], [2., 0., 0.], [2., 1., 0.], [0., 1., 0.]],
            uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
            color: [1.; 4],
            texture_id: 1,
            flags: 0,
        })
    }
    #[test]
    fn animation_envelope_covers_opposite_color_emission_and_peak_alpha() {
        let p = EmissionProposal::from_frames(
            [2, 1],
            [4, 1],
            &[0, 0, 0, 0, 0, 0, 0, 255, 255, 255, 255, 255, 0, 0, 0, 255],
            [4, 1],
            &[0, 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            &[[0, 0, 0, 0], [2, 0, 2, 0]],
        )
        .unwrap()
        .unwrap();
        assert_eq!(p.texel(0), (1., 255));
        assert_eq!(p.backing().len(), 10);
        assert!(
            EmissionProposal::from_frames([2, 1], [2, 1], &[255; 8], [2, 1], &[0; 8], &[[0; 4]])
                .unwrap()
                .is_none()
        );
        assert!(
            EmissionProposal::from_frames(
                [2, 1],
                [2, 1],
                &[255; 8],
                [2, 1],
                &[255; 8],
                &[[1, 0, 0, 0]]
            )
            .is_err()
        );
    }
    #[test]
    fn uniform_low_contrast_and_unknown_mapping_keep_uniform_area() {
        let mers: Vec<_> = [255, 200, 240, 220]
            .into_iter()
            .flat_map(|e| [0, e, 0, 0])
            .collect();
        assert!(
            EmissionProposal::from_frames([2, 2], [2, 2], &[255; 16], [2, 2], &mers, &[[0; 4]])
                .unwrap()
                .is_none()
        );
        let source = proposal([2, 2], &[255, 0, 0, 0]);
        let mut f = face();
        f.geometry.positions[3][2] = 0.2;
        assert!(EmitterAlias::build(&f, &source).is_none());
        f = face();
        f.detail = Some(Arc::new(crate::surface::SurfaceDetail {
            mode: crate::surface::LayerMode::OverlayFront,
            layer: crate::surface::SurfaceLayer {
                colors: f.geometry.colors,
                uvs: f.geometry.uvs,
                texture_id: 2,
                flags: 0,
                material_thin: false,
                repeat: None,
                emission: Default::default(),
            },
        }));
        assert!(EmitterAlias::build(&f, &source).is_none());
    }
    #[test]
    fn clipped_sparse_grid_quantizes_exact_actual_mass_and_normalized_density() {
        let source = proposal([4, 2], &[1, 0, 255, 0, 0, 37, 0, 11]);
        let mut f = face();
        // A genuinely cropped, oblique source triangle, crossing active texel cells.
        f.geometry = crate::geometry::Quad::from_triangle(crate::Triangle {
            positions: [[0., 0., 0.], [2., 0., 0.], [1., 1., 0.]],
            colors: [[1.; 4]; 3],
            uvs: [[0.1, 0.1], [0.95, 0.15], [0.4, 0.95]],
            texture_id: 1,
            flags: 0,
        });
        let table = EmitterAlias::build(&f, &source).unwrap();
        let w = &table.words;
        let columns = w[2] + 1;
        let capacity = 1 << w[3];
        let entries = w[5] as usize;
        let mut actual = vec![0u32; columns as usize];
        for i in 0..columns as usize {
            let row = &w[entries + i * ENTRY_WORDS..][..ENTRY_WORDS];
            actual[i] += row[0];
            actual[row[1] as usize] += capacity - row[0];
        }
        assert_eq!(actual.iter().sum::<u32>(), RANDOM_MASS);
        let mut integral = 0.;
        for (i, &n) in actual.iter().enumerate() {
            let row = &w[entries + i * ENTRY_WORDS..][..ENTRY_WORDS];
            assert_eq!(n, row[2], "actual column intervals define the PMF");
            if n == 0 {
                continue;
            }
            assert!(row[4] > 0);
            let density = f64::from(f32::from_bits(row[5]));
            let area = f64::from(f32::from_bits(row[6]));
            integral += density * area;
            assert!(
                (density - f64::from(n) / f64::from(RANDOM_MASS) / area).abs() < density * 2e-7
            );
            let patch_first = w[6] as usize + row[3] as usize * PATCH_WORDS;
            let patch_area: f64 = (0..row[4] as usize)
                .map(|p| {
                    let patch = &w[patch_first + p * PATCH_WORDS..][..PATCH_WORDS];
                    assert_eq!(patch[0], 0, "independent triangle preserves real half");
                    let b: [[f64; 2]; 3] = std::array::from_fn(|v| {
                        std::array::from_fn(|a| f64::from(f32::from_bits(patch[2 + v * 2 + a])))
                    });
                    for b in b {
                        assert!(b[0] >= -1e-7 && b[1] >= -1e-7 && b[0] + b[1] <= 1. + 1e-7);
                    }
                    f64::from(f32::from_bits(patch[1]))
                })
                .sum();
            assert!((patch_area - area).abs() < 1e-7);
        }
        assert!((integral - 1.).abs() < 2e-7);
    }
    #[test]
    fn coverage_is_source_support_only_and_opaque_ignores_alpha() {
        let mut color = vec![255; 16];
        color[3] = 0;
        let source = EmissionProposal::from_frames(
            [2, 2],
            [2, 2],
            &color,
            [2, 2],
            &[0, 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            &[[0; 4]],
        )
        .unwrap()
        .unwrap();
        let mut f = face();
        assert!(EmitterAlias::build(&f, &source).is_some());
        f.geometry.flags = 1;
        assert!(EmitterAlias::build(&f, &source).is_none());
        f.geometry.flags = 2;
        assert!(EmitterAlias::build(&f, &source).is_none());
    }
}
