//! Exact clipping in a known planar patch. General attributes retain the original 012/230 planes.
use super::*;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rectangle {
    pub axis: usize,
    pub plane: f32,
    pub axes: [usize; 2],
    pub bounds: [f32; 4],
}
impl Rectangle {
    pub fn plane_bounds(face: &SurfaceFace) -> Option<Self> {
        let p = face.geometry.positions;
        let axis = (0..3).find(|&a| p.iter().all(|v| v[a] == p[0][a]))?;
        let axes = [(axis + 1) % 3, (axis + 2) % 3];
        let lo = axes.map(|a| p.iter().map(|v| v[a]).reduce(f32::min).unwrap());
        let hi = axes.map(|a| p.iter().map(|v| v[a]).reduce(f32::max).unwrap());
        (lo[0] < hi[0] && lo[1] < hi[1]).then_some(Self {
            axis,
            axes,
            plane: p[0][axis],
            bounds: [lo[0], lo[1], hi[0], hi[1]],
        })
    }
    pub fn from_face(face: &SurfaceFace) -> Option<Self> {
        let p = face.geometry.positions;
        let axis = (0..3).find(|&a| p.iter().all(|v| v[a] == p[0][a]))?;
        let axes = [(axis + 1) % 3, (axis + 2) % 3];
        let lo = axes.map(|a| p.iter().map(|v| v[a]).reduce(f32::min).unwrap());
        let hi = axes.map(|a| p.iter().map(|v| v[a]).reduce(f32::max).unwrap());
        if lo[0] >= hi[0] || lo[1] >= hi[1] {
            return None;
        }
        let mut corners = 0;
        for v in p {
            let side = |i: usize| {
                if v[axes[i]] == lo[i] {
                    Some(0)
                } else if v[axes[i]] == hi[i] {
                    Some(1)
                } else {
                    None
                }
            };
            let bit = 1 << (side(0)? + side(1)? * 2);
            if corners & bit != 0 {
                return None;
            }
            corners |= bit;
        }
        // Adjacent edges, not a self-crossing quad.
        if (0..2).any(|i| {
            (p[(i + 1) % 4][axes[0]] - p[i][axes[0]]) * (p[(i + 1) % 4][axes[1]] - p[i][axes[1]])
                != 0.
        }) {
            return None;
        }
        Some(Self {
            axis,
            plane: p[0][axis],
            axes,
            bounds: [lo[0], lo[1], hi[0], hi[1]],
        })
    }
    pub fn intersection(self, other: Self) -> Option<Self> {
        if self.axis != other.axis || self.plane != other.plane {
            return None;
        }
        let [a, b, c, d] = self.bounds;
        let [e, f, g, h] = other.bounds;
        let bounds = [a.max(e), b.max(f), c.min(g), d.min(h)];
        (bounds[0] < bounds[2] && bounds[1] < bounds[3]).then_some(Self { bounds, ..self })
    }
    pub fn subtract(self, cut: Self) -> Vec<Self> {
        let Some(cut) = self.intersection(cut) else {
            return vec![self];
        };
        let [a, b, c, d] = self.bounds;
        let [e, f, g, h] = cut.bounds;
        [[a, b, e, d], [g, b, c, d], [e, b, g, f], [e, h, g, d]]
            .into_iter()
            .filter(|r| r[0] < r[2] && r[1] < r[3])
            .map(|bounds| Self { bounds, ..self })
            .collect()
    }
}

#[derive(Clone, Copy)]
struct Vertex {
    p: [f32; 3],
    weights: [f32; 4],
}
fn interpolate(v: &Vertex, face: &SurfaceFace) -> SurfaceFace {
    let mut out = face.clone();
    fn values<const N: usize>(source: [[f32; N]; 4], weights: [f32; 4]) -> [f32; N] {
        // Preserve constant attributes exactly even when rounded weights do not sum to one.
        // Accumulate differences in f64 so clipped UV/color planes remain stable.
        std::array::from_fn(|a| {
            let base = f64::from(source[0][a]);
            let value = (base
                + (1..4)
                    .map(|i| (f64::from(source[i][a]) - base) * f64::from(weights[i]))
                    .sum::<f64>()) as f32;
            // Clipping stays inside the original triangles (or affine rectangle).
            // Rounded edge weights must not escape the source attribute range.
            let lo = source.iter().map(|v| v[a]).reduce(f32::min).unwrap();
            let hi = source.iter().map(|v| v[a]).reduce(f32::max).unwrap();
            value.clamp(lo, hi)
        })
    }
    out.geometry.positions = [v.p; 4];
    out.geometry.colors = [values(face.geometry.colors, v.weights); 4];
    out.geometry.uvs = [values(face.geometry.uvs, v.weights); 4];
    if let Some(d) = &mut out.detail {
        let original = &face.detail.as_ref().unwrap().layer;
        let d = Arc::make_mut(d);
        d.layer.colors = [values(original.colors, v.weights); 4];
        d.layer.uvs = [values(original.uvs, v.weights); 4];
    }
    out
}
fn face(vertices: &[Vertex], source: &SurfaceFace) -> SurfaceFace {
    let mut out = source.clone();
    for i in 0..4 {
        let v = interpolate(&vertices[i.min(vertices.len() - 1)], source);
        out.geometry.positions[i] = v.geometry.positions[0];
        out.geometry.colors[i] = v.geometry.colors[0];
        out.geometry.uvs[i] = v.geometry.uvs[0];
        if let Some(d) = &mut out.detail {
            let d = Arc::make_mut(d);
            let v = &v.detail.as_ref().unwrap().layer;
            d.layer.colors[i] = v.colors[0];
            d.layer.uvs[i] = v.uvs[0];
        }
    }
    out
}
fn affine<const N: usize>(values: [[f32; N]; 4]) -> bool {
    (0..N).all(|a| {
        f64::from(values[0][a]) + f64::from(values[2][a])
            == f64::from(values[1][a]) + f64::from(values[3][a])
    })
}
pub fn affine_rectangle(source: &SurfaceFace) -> bool {
    Rectangle::from_face(source).is_some()
        && affine(source.geometry.uvs)
        && affine(source.geometry.colors)
        && source
            .detail
            .as_ref()
            .is_none_or(|d| affine(d.layer.uvs) && affine(d.layer.colors))
}
/// Bounds use Rectangle::axes. Repeated maps are affine in their input coordinates too.
pub fn clip_rectangle(source: &SurfaceFace, rect: Rectangle) -> Vec<SurfaceFace> {
    let Some(original) = Rectangle::plane_bounds(source) else {
        return vec![source.clone()];
    };
    let Some(rect) = original.intersection(rect) else {
        return Vec::new();
    };
    if rect == original {
        return vec![source.clone()];
    }
    let simple = affine_rectangle(source);
    if simple {
        let p = source.geometry.positions;
        let x = (0..3).find(|&a| p[1][a] != p[0][a]).unwrap();
        let y = (0..3).find(|&a| p[3][a] != p[0][a]).unwrap();
        let vertices = p.map(|mut v| {
            for i in 0..2 {
                v[rect.axes[i]] = if v[rect.axes[i]] == original.bounds[i] {
                    rect.bounds[i]
                } else {
                    rect.bounds[i + 2]
                };
            }
            let u = (v[x] - p[0][x]) / (p[1][x] - p[0][x]);
            let w = (v[y] - p[0][y]) / (p[3][y] - p[0][y]);
            Vertex {
                p: v,
                weights: [1. - u - w, u, 0., w],
            }
        });
        return vec![face(&vertices, source)];
    }
    let mut result = Vec::new();
    for corners in [[0, 1, 2], [2, 3, 0]] {
        let mut polygon: Vec<_> = corners
            .into_iter()
            .map(|i| Vertex {
                p: source.geometry.positions[i],
                weights: std::array::from_fn(|j| u8::from(i == j) as f32),
            })
            .collect();
        for edge in 0..4 {
            let axis = rect.axes[edge % 2];
            let bound = rect.bounds[edge];
            let inside = |v: Vertex| {
                if edge < 2 {
                    v.p[axis] >= bound
                } else {
                    v.p[axis] <= bound
                }
            };
            let mut clipped = Vec::new();
            for i in 0..polygon.len() {
                let a = polygon[i];
                let b = polygon[(i + 1) % polygon.len()];
                if inside(a) {
                    clipped.push(a);
                }
                if inside(a) != inside(b) {
                    let t = (bound - a.p[axis]) / (b.p[axis] - a.p[axis]);
                    let mut v = Vertex {
                        p: std::array::from_fn(|i| a.p[i] + (b.p[i] - a.p[i]) * t),
                        weights: std::array::from_fn(|i| {
                            a.weights[i] + (b.weights[i] - a.weights[i]) * t
                        }),
                    };
                    v.p[axis] = bound;
                    clipped.push(v);
                }
            }
            polygon = clipped;
        }
        // Remove edge/corner duplicates and zero-area polygons before triangulating.
        polygon.dedup_by(|a, b| a.p == b.p);
        if polygon.len() > 1 && polygon.first().unwrap().p == polygon.last().unwrap().p {
            polygon.pop();
        }
        if polygon.len() < 3 {
            continue;
        }
        if polygon.len() <= 4 {
            result.push(face(&polygon, source));
        } else {
            for i in 1..polygon.len() - 1 {
                result.push(face(&[polygon[0], polygon[i], polygon[i + 1]], source));
            }
        }
    }
    result
}

/// Subtract a proven occupied axis-aligned volume. Each original triangle retains its own
/// interpolation plane, including a sloping/nonplanar fluid top and non-affine source UVs.
pub fn subtract_box(source: &SurfaceFace, bounds: [[f32; 3]; 2]) -> Vec<SurfaceFace> {
    if (0..3).any(|a| {
        source
            .geometry
            .positions
            .iter()
            .all(|p| p[a] < bounds[0][a])
            || source
                .geometry
                .positions
                .iter()
                .all(|p| p[a] > bounds[1][a])
    }) {
        return vec![source.clone()];
    }
    if let Some(rect) = Rectangle::from_face(source) {
        let mut other = rect;
        other.bounds = [
            bounds[0][rect.axes[0]],
            bounds[0][rect.axes[1]],
            bounds[1][rect.axes[0]],
            bounds[1][rect.axes[1]],
        ];
        return rect
            .subtract(other)
            .into_iter()
            .flat_map(|r| clip_rectangle(source, r))
            .collect();
    }
    let planes = std::array::from_fn::<_, 6, _>(|i| {
        let a = i % 3;
        let sign = if i < 3 { 1. } else { -1. };
        let mut p = [0.; 4];
        p[a] = sign;
        p[3] = -sign * f64::from(bounds[i / 3][a]);
        p
    });
    partition_surface(source, &planes).1
}

pub fn partition_surface(
    source: &SurfaceFace,
    planes: &[[f64; 4]],
) -> (Vec<SurfaceFace>, Vec<SurfaceFace>) {
    fn append(out: &mut Vec<SurfaceFace>, mut polygon: Vec<Vertex>, source: &SurfaceFace) {
        polygon.dedup_by(|a, b| a.p == b.p);
        if polygon.len() > 1 && polygon[0].p == polygon.last().unwrap().p {
            polygon.pop();
        }
        if polygon.len() < 3 {
            return;
        }
        if polygon.len() <= 4 {
            out.push(face(&polygon, source));
        } else {
            for i in 1..polygon.len() - 1 {
                out.push(face(&[polygon[0], polygon[i], polygon[i + 1]], source));
            }
        }
    }
    let distance = |p: [f32; 3], plane: &[f64; 4]| {
        (0..3).map(|a| f64::from(p[a]) * plane[a]).sum::<f64>() + plane[3]
    };
    if planes.iter().all(|plane| {
        source
            .geometry
            .positions
            .iter()
            .all(|&p| distance(p, plane) >= 0.)
    }) {
        return (vec![source.clone()], Vec::new());
    }
    if planes.iter().any(|plane| {
        source
            .geometry
            .positions
            .iter()
            .all(|&p| distance(p, plane) < 0.)
    }) {
        return (Vec::new(), vec![source.clone()]);
    }
    let mut inside = Vec::new();
    let mut outside = Vec::new();
    for corners in [[0, 1, 2], [2, 3, 0]] {
        let mut polygon: Vec<_> = corners
            .into_iter()
            .map(|i| Vertex {
                p: source.geometry.positions[i],
                weights: std::array::from_fn(|j| u8::from(i == j) as f32),
            })
            .collect();
        for plane in planes {
            let distance =
                |v: Vertex| (0..3).map(|a| f64::from(v.p[a]) * plane[a]).sum::<f64>() + plane[3];
            let mut yes = Vec::new();
            let mut no = Vec::new();
            for i in 0..polygon.len() {
                let a = polygon[i];
                let b = polygon[(i + 1) % polygon.len()];
                let da = distance(a);
                let db = distance(b);
                if da >= 0. {
                    yes.push(a);
                } else {
                    no.push(a);
                }
                if (da >= 0.) != (db >= 0.) {
                    let t = da / (da - db);
                    let v = Vertex {
                        p: std::array::from_fn(|i| {
                            (f64::from(a.p[i]) + (f64::from(b.p[i]) - f64::from(a.p[i])) * t) as f32
                        }),
                        weights: std::array::from_fn(|i| {
                            (f64::from(a.weights[i])
                                + (f64::from(b.weights[i]) - f64::from(a.weights[i])) * t)
                                as f32
                        }),
                    };
                    yes.push(v);
                    no.push(v);
                }
            }
            append(&mut outside, no, source);
            polygon = yes;
            if polygon.is_empty() {
                break;
            }
        }
        append(&mut inside, polygon, source);
    }
    inside.retain(|f| f.geometry.areas().iter().any(|&a| a > 0.));
    outside.retain(|f| f.geometry.areas().iter().any(|&a| a > 0.));
    (inside, outside)
}
fn edges(positions: &[[f32; 3]], axis: usize) -> Option<Vec<[f64; 4]>> {
    let x = (axis + 1) % 3;
    let y = (axis + 2) % 3;
    let area: f64 = (0..positions.len())
        .map(|i| {
            let a = positions[i];
            let b = positions[(i + 1) % positions.len()];
            f64::from(a[x]) * f64::from(b[y]) - f64::from(a[y]) * f64::from(b[x])
        })
        .sum();
    if area == 0. {
        return None;
    }
    let sign = area.signum();
    let mut planes = Vec::new();
    for i in 0..positions.len() {
        let a = positions[i];
        let b = positions[(i + 1) % positions.len()];
        let mut plane = [0.; 4];
        plane[x] = (f64::from(a[y]) - f64::from(b[y])) * sign;
        plane[y] = (f64::from(b[x]) - f64::from(a[x])) * sign;
        plane[3] = -plane[x] * f64::from(a[x]) - plane[y] * f64::from(a[y]);
        if positions.iter().any(|p| {
            ((f64::from(b[x]) - f64::from(a[x])) * (f64::from(p[y]) - f64::from(a[y]))
                - (f64::from(b[y]) - f64::from(a[y])) * (f64::from(p[x]) - f64::from(a[x])))
                * sign
                < 0.
        }) {
            return None;
        }
        planes.push(plane);
    }
    Some(planes)
}
pub fn subtract_surface(source: &SurfaceFace, other: &SurfaceFace) -> Option<Vec<SurfaceFace>> {
    let a = Rectangle::plane_bounds(source)?;
    let b = Rectangle::plane_bounds(other)?;
    if a.intersection(b).is_none() {
        return Some(vec![source.clone()]);
    }
    if let Some(b) = Rectangle::from_face(other) {
        return Some(
            a.subtract(b)
                .into_iter()
                .flat_map(|r| clip_rectangle(source, r))
                .collect(),
        );
    }
    Some(partition_surface(source, &edges(&other.geometry.positions, a.axis)?).1)
}
/// Joint source-triangle partitions preserve both UV maps even when their diagonals differ.
pub fn intersect_surfaces(
    source: &SurfaceFace,
    other: &SurfaceFace,
) -> Option<Vec<(SurfaceFace, SurfaceLayer)>> {
    let a = Rectangle::plane_bounds(source)?;
    let b = Rectangle::plane_bounds(other)?;
    let Some(bounds) = a.intersection(b) else {
        return Some(Vec::new());
    };
    edges(&other.geometry.positions, a.axis)?;
    let mut out = Vec::new();
    let simple = affine_rectangle(other);
    for corners in if simple {
        vec![[0, 1, 3]]
    } else {
        vec![[0, 1, 2], [2, 3, 0]]
    } {
        let p = corners.map(|i| other.geometry.positions[i]);
        let pieces = if simple {
            clip_rectangle(source, bounds)
        } else {
            let Some(planes) = edges(&p, a.axis) else {
                continue;
            };
            partition_surface(source, &planes).0
        };
        let [x, y] = a.axes;
        let e = [
            f64::from(p[1][x]) - f64::from(p[0][x]),
            f64::from(p[1][y]) - f64::from(p[0][y]),
        ];
        let f = [
            f64::from(p[2][x]) - f64::from(p[0][x]),
            f64::from(p[2][y]) - f64::from(p[0][y]),
        ];
        let determinant = e[0] * f[1] - e[1] * f[0];
        if determinant == 0. {
            continue;
        }
        for piece in pieces {
            let values = piece.geometry.positions.map(|v| {
                let d = [
                    f64::from(v[x]) - f64::from(p[0][x]),
                    f64::from(v[y]) - f64::from(p[0][y]),
                ];
                let u = ((d[0] * f[1] - d[1] * f[0]) / determinant) as f32;
                let w = ((e[0] * d[1] - e[1] * d[0]) / determinant) as f32;
                let mut weights = [0.; 4];
                weights[corners[0]] = 1. - u - w;
                weights[corners[1]] = u;
                weights[corners[2]] = w;
                interpolate(&Vertex { p: v, weights }, other)
            });
            let layer = SurfaceLayer {
                colors: std::array::from_fn(|i| values[i].geometry.colors[0]),
                uvs: std::array::from_fn(|i| values[i].geometry.uvs[0]),
                texture_id: other.geometry.texture_id,
                flags: other.geometry.flags,
                repeat: other.repeat,
                emission: other.emission,
            };
            out.push((piece, layer));
        }
    }
    Some(out)
}
