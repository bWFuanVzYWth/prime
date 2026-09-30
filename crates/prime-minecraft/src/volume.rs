//! A closed, oriented source model proves optical occupancy; collision/sturdy flags do not.
use crate::model::Quad;
use std::collections::HashMap;

pub(crate) struct Volume {
    pub axes: [Vec<f32>; 3],
    cells: Vec<bool>,
}
impl Volume {
    pub fn boxes(&self) -> impl Iterator<Item = [[f32; 3]; 2]> + '_ {
        let nx = self.axes[0].len() - 1;
        let ny = self.axes[1].len() - 1;
        self.cells
            .iter()
            .enumerate()
            .filter(|(_, full)| **full)
            .map(move |(i, _)| {
                let index = [i % nx, (i / nx) % ny, i / (nx * ny)];
                [
                    std::array::from_fn(|a| self.axes[a][index[a]]),
                    std::array::from_fn(|a| self.axes[a][index[a] + 1]),
                ]
            })
    }
    pub fn prepare(quads: &[Quad]) -> Option<Self> {
        if quads.is_empty() || quads.len() > 512 {
            return None;
        }
        let mut edges = HashMap::<([u32; 3], [u32; 3]), i32>::new();
        let mut axes: [Vec<f32>; 3] = Default::default();
        for q in quads {
            if q.positions
                .iter()
                .any(|p| p.iter().any(|v| !v.is_finite() || *v < 0. || *v > 1.))
            {
                return None;
            }
            for (i, p) in q.positions.iter().enumerate() {
                for a in 0..3 {
                    axes[a].push(if p[a] == 0. { 0. } else { p[a] });
                }
                let a = p.map(|v| if v == 0. { 0 } else { v.to_bits() });
                let b = q.positions[(i + 1) % 4].map(|v| if v == 0. { 0 } else { v.to_bits() });
                if a != b {
                    let (key, sign) = if a < b { ((a, b), 1) } else { ((b, a), -1) };
                    *edges.entry(key).or_default() += sign;
                }
            }
        }
        if edges.values().any(|&n| n != 0) {
            return None;
        }
        for axis in &mut axes {
            axis.sort_unstable_by(f32::total_cmp);
            axis.dedup();
            if axis.len() < 2 {
                return None;
            }
        }
        let size = axes.each_ref().map(|v| v.len() - 1);
        let count = size.iter().product::<usize>();
        if count > 16384 {
            return None;
        }
        // Validate topology/rectangles once. Grid samples then use only X crossing spans.
        let mut spans = Vec::new();
        for q in quads {
            let v = q.positions;
            let axis = (0..3).find(|&a| v.iter().all(|p| p[a] == v[0][a]))?;
            let a = (axis + 1) % 3;
            let b = (axis + 2) % 3;
            let lo = [a, b].map(|a| v.iter().map(|p| p[a]).reduce(f32::min).unwrap());
            let hi = [a, b].map(|a| v.iter().map(|p| p[a]).reduce(f32::max).unwrap());
            if lo[0] >= hi[0]
                || lo[1] >= hi[1]
                || (0..4).any(|i| {
                    let p = v[i];
                    let n = v[(i + 1) % 4];
                    (p[a] != lo[0] && p[a] != hi[0])
                        || (p[b] != lo[1] && p[b] != hi[1])
                        || (p[a] != n[a]) == (p[b] != n[b])
                })
            {
                return None;
            }
            if axis == 0 {
                let sign = (v[1][1] - v[0][1]) * (v[2][2] - v[0][2])
                    - (v[1][2] - v[0][2]) * (v[2][1] - v[0][1]);
                spans.push((
                    f64::from(v[0][0]),
                    lo.map(f64::from),
                    hi.map(f64::from),
                    if sign > 0. { 1 } else { -1 },
                ));
            }
        }
        // Midpoints never land on a source edge. Signed crossings preserve overlapping unions.
        let mut cells = Vec::with_capacity(count);
        for z in 0..size[2] {
            for y in 0..size[1] {
                for x in 0..size[0] {
                    let p = std::array::from_fn::<_, 3, _>(|a| {
                        (axes[a][[x, y, z][a]] as f64 + axes[a][[x, y, z][a] + 1] as f64) * 0.5
                    });
                    let mut winding = 0;
                    for &(plane, lo, hi, sign) in &spans {
                        if plane > p[0]
                            && p[1] > lo[0]
                            && p[1] < hi[0]
                            && p[2] > lo[1]
                            && p[2] < hi[1]
                        {
                            winding += sign;
                        }
                    }
                    if winding < 0 {
                        return None;
                    }
                    cells.push(winding > 0);
                }
            }
        }
        cells.iter().any(|&v| v).then_some(Self { axes, cells })
    }
    #[cfg(test)]
    pub fn contains(&self, p: [f32; 3]) -> bool {
        let mut index = [0; 3];
        for a in 0..3 {
            let i = self.axes[a].partition_point(|&v| v <= p[a]);
            if i == 0 || i == self.axes[a].len() {
                return false;
            }
            index[a] = i - 1;
        }
        self.cells
            [index[0] + (self.axes[0].len() - 1) * (index[1] + (self.axes[1].len() - 1) * index[2])]
    }
    /// Disjoint rectangles through the volume at a known plane; boundary direction selects
    /// the solid side without an arbitrary geometric epsilon.
    #[cfg(test)]
    pub fn section(&self, axis: usize, plane: f32, positive: bool) -> Vec<[f32; 4]> {
        let a = (axis + 1) % 3;
        let b = (axis + 2) % 3;
        let mut out = Vec::new();
        let bound =
            self.axes[axis].partition_point(|&v| if positive { v <= plane } else { v < plane });
        if bound == 0 || bound == self.axes[axis].len() {
            return out;
        }
        for u in self.axes[a].windows(2) {
            for v in self.axes[b].windows(2) {
                let mut p = [0.; 3];
                p[axis] = (self.axes[axis][bound - 1] + self.axes[axis][bound]) * 0.5;
                p[a] = (u[0] + u[1]) * 0.5;
                p[b] = (v[0] + v[1]) * 0.5;
                if self.contains(p) {
                    out.push([u[0], v[0], u[1], v[1]]);
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn cube(lo: [f32; 3], hi: [f32; 3]) -> Vec<Quad> {
        let mut out = Vec::new();
        for axis in 0..3 {
            for side in 0..2 {
                let a = (axis + 1) % 3;
                let b = (axis + 2) % 3;
                let mut positions = [[0., 0.], [1., 0.], [1., 1.], [0., 1.]].map(|uv| {
                    let mut p = [0.; 3];
                    p[axis] = if side == 0 { lo[axis] } else { hi[axis] };
                    p[a] = lo[a] + uv[0] * (hi[a] - lo[a]);
                    p[b] = lo[b] + uv[1] * (hi[b] - lo[b]);
                    p
                });
                if side == 0 {
                    positions.reverse();
                }
                out.push(Quad {
                    positions,
                    uvs: [[0.; 2]; 4],
                    face: 6,
                    tint: -1,
                    layer: 2,
                    sprite: 0,
                    emission: 0,
                });
            }
        }
        out
    }
    #[test]
    fn closed_overlap_union_and_partial_pane_do_not_use_collision_or_bounding_boxes() {
        let mut q = cube([0.4375, 0., 0.], [0.5625, 1., 1.]);
        q.extend(cube([0., 0., 0.4375], [1., 1., 0.5625]));
        let v = Volume::prepare(&q).unwrap();
        for x in 0..16 {
            for z in 0..16 {
                assert_eq!(
                    v.contains([(x as f32 + 0.5) / 16., 0.25, (z as f32 + 0.5) / 16.]),
                    (7..9).contains(&x) || (7..9).contains(&z)
                );
            }
        }
        let area: f32 = v
            .section(1, 0.8, true)
            .iter()
            .map(|r| (r[2] - r[0]) * (r[3] - r[1]))
            .sum();
        assert_eq!(area, 0.125 * 2. - 0.125 * 0.125);
        q.pop();
        assert!(Volume::prepare(&q).is_none());
        let mut q = cube([0.; 3], [1.; 3]);
        q[0].positions[0][1] = 0.3;
        assert!(Volume::prepare(&q).is_none());
    }
}
