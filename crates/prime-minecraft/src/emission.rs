//! Fixed source-cycle power moments. Calibration changes amplitude, never the texture mask.
use std::sync::Arc;

#[derive(PartialEq)]
pub(crate) struct Moments {
    pub extent: [u32; 2],
    // Three coverage domains: opaque, alpha-test >= .1, stochastic alpha.
    // Each pair is reference Color luminance and Color luminance times authored G.
    prefix: Arc<[[f64; 6]]>,
}
impl Moments {
    pub fn new(extent: [u32; 2], cells: Vec<[f64; 6]>) -> Self {
        let [w, h] = extent.map(|n| n as usize);
        let mut prefix = vec![[0.; 6]; (w + 1) * (h + 1)];
        for y in 0..h {
            let mut row = [0.; 6];
            for x in 0..w {
                for c in 0..6 {
                    row[c] += cells[y * w + x][c];
                    prefix[(y + 1) * (w + 1) + x + 1][c] = prefix[y * (w + 1) + x + 1][c] + row[c];
                }
            }
        }
        Self {
            extent,
            prefix: prefix.into(),
        }
    }
    fn cumulative(&self, uv: [f64; 2], mode: usize) -> [f64; 2] {
        let [w, h] = self.extent.map(|n| n as usize);
        let p = [
            uv[0].clamp(0., 1.) * w as f64,
            uv[1].clamp(0., 1.) * h as f64,
        ];
        let [x, y] = p.map(|v| v.floor() as usize);
        let [fx, fy] = [p[0] - x as f64, p[1] - y as f64];
        let get = |dx: usize, dy: usize, c: usize| {
            self.prefix[(y + dy).min(h) * (w + 1) + (x + dx).min(w)][mode * 2 + c]
        };
        std::array::from_fn(|c| {
            let a = get(0, 0, c) * (1. - fx) + get(1, 0, c) * fx;
            let b = get(0, 1, c) * (1. - fx) + get(1, 1, c) * fx;
            a * (1. - fy) + b * fy
        })
    }
    fn rectangle(&self, bounds: [f64; 4], mode: usize) -> [f64; 2] {
        let a = self.cumulative([bounds[0], bounds[1]], mode);
        let b = self.cumulative([bounds[2], bounds[1]], mode);
        let c = self.cumulative([bounds[0], bounds[3]], mode);
        let d = self.cumulative([bounds[2], bounds[3]], mode);
        std::array::from_fn(|i| d[i] - b[i] - c[i] + a[i])
    }
    pub fn gain(
        &self,
        uvs: [[f32; 2]; 4],
        positions: [[f32; 3]; 4],
        flags: u32,
        native: f32,
    ) -> f32 {
        if native == 0. {
            return 0.;
        }
        let mode = (flags & 3).min(2) as usize;
        let uv = uvs.map(|v| v.map(f64::from));
        let mut integral = [0.; 2];
        let in_sprite = uv.iter().flatten().all(|v| (0. ..=1.).contains(v));
        let rectangle = in_sprite
            && (0..4).all(|i| {
                let next = uv[(i + 1) % 4];
                (uv[i][0] == next[0]) != (uv[i][1] == next[1])
            })
            && uv.iter().all(|p| {
                uv.iter().filter(|q| q[0] == p[0]).count() == 2
                    && uv.iter().filter(|q| q[1] == p[1]).count() == 2
            });
        // A rectangular affine source has constant area Jacobian, which cancels in the ratio.
        let areas = [[0, 1, 2], [2, 3, 0]].map(|ids| world_area(ids.map(|i| positions[i])));
        let uv_areas = [[0, 1, 2], [2, 3, 0]].map(|ids| polygon_area(&ids.map(|i| uv[i])));
        if rectangle
            && uv_areas[0] > 0.
            && uv_areas[1] > 0.
            && (areas[0] * uv_areas[1] - areas[1] * uv_areas[0]).abs()
                <= 1e-9 * (areas[0] * uv_areas[1]).max(areas[1] * uv_areas[0])
        {
            let bounds = [
                uv.iter().map(|p| p[0]).fold(1., f64::min),
                uv.iter().map(|p| p[1]).fold(1., f64::min),
                uv.iter().map(|p| p[0]).fold(0., f64::max),
                uv.iter().map(|p| p[1]).fold(0., f64::max),
            ];
            integral = self.rectangle(bounds, mode);
        } else if in_sprite {
            for (half, ids) in [[0, 1, 2], [2, 3, 0]].into_iter().enumerate() {
                if areas[half] == 0. {
                    continue;
                }
                let tri = ids.map(|i| uv[i]);
                if uv_areas[half] == 0. {
                    let p = if tri.iter().all(|p| *p == tri[0]) {
                        let cell = std::array::from_fn::<_, 2, _>(|c| {
                            (tri[0][c] * f64::from(self.extent[c]))
                                .floor()
                                .min(f64::from(self.extent[c] - 1))
                                as u32
                        });
                        self.cell(cell, mode)
                    } else {
                        // Rank-one UVs span texels but have no area Jacobian. Keep the simple
                        // whole-source artistic reference rather than treating them as a point.
                        self.rectangle([0., 0., 1., 1.], mode)
                            .map(|v| v / f64::from(self.extent[0] * self.extent[1]))
                    };
                    for c in 0..2 {
                        integral[c] += p[c] * areas[half];
                    }
                    continue;
                }
                let lo = std::array::from_fn::<_, 2, _>(|c| {
                    (tri.iter().map(|p| p[c]).fold(1., f64::min) * f64::from(self.extent[c]))
                        .floor() as u32
                });
                let hi = std::array::from_fn::<_, 2, _>(|c| {
                    (tri.iter().map(|p| p[c]).fold(0., f64::max) * f64::from(self.extent[c]))
                        .ceil()
                        .min(f64::from(self.extent[c])) as u32
                });
                for y in lo[1]..hi[1] {
                    for x in lo[0]..hi[0] {
                        let mut polygon = tri.to_vec();
                        for (axis, lower, upper) in [
                            (
                                0,
                                f64::from(x) / f64::from(self.extent[0]),
                                f64::from(x + 1) / f64::from(self.extent[0]),
                            ),
                            (
                                1,
                                f64::from(y) / f64::from(self.extent[1]),
                                f64::from(y + 1) / f64::from(self.extent[1]),
                            ),
                        ] {
                            polygon = clip(&polygon, axis, lower, true);
                            polygon = clip(&polygon, axis, upper, false);
                        }
                        let area = polygon_area(&polygon) * areas[half] / uv_areas[half];
                        if area == 0. {
                            continue;
                        }
                        let p = self.cell([x, y], mode);
                        for c in 0..2 {
                            integral[c] += p[c] * area;
                        }
                    }
                }
            }
        } else {
            // Cross-sprite/repeating source domains use a fixed whole-source artistic reference.
            integral = self.rectangle([0., 0., 1., 1.], mode);
        }
        if integral[1] > 0. {
            (f64::from(native) * integral[0] / integral[1]) as f32
        } else {
            0.
        }
    }
    fn cell(&self, [x, y]: [u32; 2], mode: usize) -> [f64; 2] {
        self.rectangle(
            [
                f64::from(x) / f64::from(self.extent[0]),
                f64::from(y) / f64::from(self.extent[1]),
                f64::from(x + 1) / f64::from(self.extent[0]),
                f64::from(y + 1) / f64::from(self.extent[1]),
            ],
            mode,
        )
    }
}
fn polygon_area(p: &[[f64; 2]]) -> f64 {
    if p.len() < 3 {
        return 0.;
    }
    (0..p.len())
        .map(|i| {
            let q = p[(i + 1) % p.len()];
            p[i][0] * q[1] - p[i][1] * q[0]
        })
        .sum::<f64>()
        .abs()
        * 0.5
}
fn clip(p: &[[f64; 2]], axis: usize, bound: f64, positive: bool) -> Vec<[f64; 2]> {
    let mut out = Vec::with_capacity(p.len() + 1);
    if p.is_empty() {
        return out;
    }
    let mut a = p[p.len() - 1];
    for &b in p {
        let inside = |v: [f64; 2]| {
            if positive {
                v[axis] >= bound
            } else {
                v[axis] <= bound
            }
        };
        if inside(a) != inside(b) {
            let t = (bound - a[axis]) / (b[axis] - a[axis]);
            out.push(std::array::from_fn(|c| a[c] + t * (b[c] - a[c])));
        }
        if inside(b) {
            out.push(b);
        }
        a = b;
    }
    out
}
fn world_area(p: [[f32; 3]; 3]) -> f64 {
    let a = std::array::from_fn::<_, 3, _>(|i| f64::from(p[1][i]) - f64::from(p[0][i]));
    let b = std::array::from_fn::<_, 3, _>(|i| f64::from(p[2][i]) - f64::from(p[0][i]));
    let c = [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ];
    c.iter().map(|v| v * v).sum::<f64>().sqrt() * 0.5
}

#[cfg(test)]
mod tests {
    use super::*;
    const POS: [[f32; 3]; 4] = [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]];
    const UV: [[f32; 2]; 4] = [[0., 0.], [1., 0.], [1., 1.], [0., 1.]];
    #[test]
    fn cropped_mirrored_and_triangle_domains_preserve_power_ratio() {
        let cells = [
            [1., 0.25, 1., 0.25, 1., 0.25],
            [1., 0.5, 1., 0.5, 1., 0.5],
            [1., 0.75, 1., 0.75, 1., 0.75],
            [1., 1., 1., 1., 1., 1.],
        ];
        let m = Moments::new([2, 2], cells.to_vec());
        assert_eq!(m.gain(UV, POS, 0, 1.), 1.6);
        let crop = UV.map(|p| [p[0] * 0.5, p[1] * 0.5]);
        assert_eq!(m.gain(crop, POS, 0, 1.), 4.);
        assert_eq!(m.gain(crop.map(|p| [0.5 - p[0], p[1]]), POS, 0, 1.), 4.);
        let folded = [[0., 0.], [1., 1.], [1., 0.], [0., 1.]];
        assert_eq!(m.gain(folded, POS, 0, 1.), 2.);
        assert_eq!(
            m.gain([[0.1, 0.1], [0.9, 0.1], [0.9, 0.1], [0.1, 0.1]], POS, 0, 1.),
            1.6
        );
        assert_eq!(m.gain([[0.1, 0.1]; 4], POS, 0, 1.), 4.);
        let tri = [[0., 0.], [1., 0.], [0., 1.], [0., 1.]];
        let mut triangle_positions = POS;
        triangle_positions[3] = triangle_positions[2];
        // Quarter cells 0/1/2 intersect with areas .25/.125/.125 of this UV triangle.
        let expected = 0.5 / (0.25 * 0.25 + 0.125 * 0.5 + 0.125 * 0.75);
        assert!((f64::from(m.gain(tri, triangle_positions, 0, 1.)) - expected).abs() < 1e-6);
        assert_eq!(m.gain(UV, POS, 0, 0.), 0.);
        assert_eq!(
            Moments::new([1, 1], vec![[1., 0., 1., 0., 1., 0.]]).gain(UV, POS, 0, 1.),
            0.
        );
    }
}
