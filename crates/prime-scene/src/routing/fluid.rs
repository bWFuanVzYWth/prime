//! Pure fluid surface construction from heights, flow, source materials and face coverage.
use super::{Quad, count};
use crate::protocol::Reader;

enum Coverage {
    Empty,
    Full,
    Rectangles(Vec<[f64; 4]>),
}
impl Coverage {
    fn read(input: &mut Reader<'_>) -> Result<Self, String> {
        match input.u32()? {
            0 => Ok(Self::Empty),
            1 => Ok(Self::Full),
            2 => {
                let n = count(input, 32)?;
                let mut rects = Vec::with_capacity(n);
                for _ in 0..n {
                    let r = [input.f64()?, input.f64()?, input.f64()?, input.f64()?];
                    if r[0] > r[2] || r[1] > r[3] {
                        return Err("invalid fluid face coverage".into());
                    }
                    rects.push(r);
                }
                Ok(Self::Rectangles(rects))
            }
            _ => Err("unknown fluid face coverage".into()),
        }
    }
    fn covers(&self, height: f32) -> bool {
        match self {
            Self::Empty => false,
            Self::Full => true,
            Self::Rectangles(rects) => {
                let height = f64::from(height);
                let mut xs = vec![0.0, 1.0];
                for r in rects {
                    xs.push(r[0].clamp(0.0, 1.0));
                    xs.push(r[2].clamp(0.0, 1.0));
                }
                xs.sort_unstable_by(f64::total_cmp);
                xs.dedup();
                let mut ys = Vec::with_capacity(rects.len());
                for span in xs.windows(2) {
                    if span[0] == span[1] {
                        continue;
                    }
                    let x = (span[0] + span[1]) * 0.5;
                    ys.clear();
                    ys.extend(
                        rects
                            .iter()
                            .filter(|r| r[0] <= x && r[2] >= x)
                            .map(|r| (r[1], r[3])),
                    );
                    ys.sort_unstable_by(|a, b| a.0.total_cmp(&b.0));
                    let mut covered = 0.0;
                    for &(start, end) in &ys {
                        if start > covered {
                            break;
                        }
                        covered = covered.max(end);
                    }
                    if covered < height {
                        return false;
                    }
                }
                true
            }
        }
    }
}

fn corner(center: f32, a: f32, b: f32, diagonal: f32) -> f32 {
    if center >= 1.0 || a >= 1.0 || b >= 1.0 {
        return 1.0;
    }
    let mut total = 0.0;
    let mut weight = 0.0;
    let mut add = |h: f32| {
        if h >= 0.8 {
            total += h * 10.0;
            weight += 10.0;
        } else if h >= 0.0 {
            total += h;
            weight += 1.0;
        }
    };
    if a > 0.0 || b > 0.0 {
        if diagonal >= 1.0 {
            return 1.0;
        }
        add(diagonal);
    }
    add(center);
    add(b);
    add(a);
    total / weight
}

pub(super) fn decode(input: &mut Reader<'_>, quads: &mut Vec<Quad>) -> Result<(), String> {
    let layer = input.u32()?;
    let color: [u8; 4] = input.take(4)?.try_into().unwrap();
    let offset = input.vector()?;
    let visible = input.u32()?;
    let overlay = input.u32()?;
    let backward = input.u32()?;
    if layer > 2
        || visible & !63 != 0
        || overlay & !63 != 0
        || backward > 1
        || offset.iter().any(|x| x.abs() > 4096.0)
    {
        return Err("invalid routed fluid attributes".into());
    }
    let mut samples = [0.0; 9];
    for value in &mut samples {
        *value = input.f32()?;
        if !(-1.0..=1.0).contains(value) {
            return Err("invalid routed fluid height".into());
        }
    }
    if samples[4] <= 0.0 {
        return Err("fluid source has no center height".into());
    }
    let flow = [input.f64()?, input.f64()?];
    let mut sprites = [[0.0; 4]; 3];
    for sprite in &mut sprites {
        for value in sprite {
            *value = input.f32()?;
        }
    }
    let mut shapes = [const { Coverage::Empty }; 12];
    for shape in &mut shapes {
        *shape = Coverage::read(input)?;
    }
    let mut enabled = [false; 6];
    for face in 0..6 {
        enabled[face] = visible & (1 << face) != 0 && (face == 1 || !shapes[face].covers(1.0));
    }
    enabled[0] &= !shapes[6].covers(1.0);
    let mut heights = [
        corner(samples[4], samples[1], samples[3], samples[0]),
        corner(samples[4], samples[7], samples[3], samples[6]),
        corner(samples[4], samples[7], samples[5], samples[8]),
        corner(samples[4], samples[1], samples[5], samples[2]),
    ];
    let minimum = heights.iter().copied().fold(1.0, f32::min);
    enabled[1] &= minimum < 1.0 || !shapes[7].covers(1.0);
    let bottom = if enabled[0] { 0.001 } else { 0.0 };
    let uv = |sprite: usize, u: f32, v: f32| {
        let s = sprites[sprite];
        [s[0] + (s[2] - s[0]) * u, s[1] + (s[3] - s[1]) * v]
    };
    let mut face = |positions: [[f32; 3]; 4], uvs: [[f32; 2]; 4], back: bool| {
        let positions = positions.map(|p| std::array::from_fn(|i| offset[i] + p[i]));
        quads.push(Quad {
            positions,
            colors: [color; 4],
            uvs,
            layer,
            face: 6,
            tint: u32::MAX,
        });
        if back {
            quads.push(Quad {
                positions: [positions[0], positions[3], positions[2], positions[1]],
                colors: [color; 4],
                uvs: [uvs[0], uvs[3], uvs[2], uvs[1]],
                layer,
                face: 6,
                tint: u32::MAX,
            });
        }
    };
    if enabled[1] {
        for height in &mut heights {
            *height -= 0.001;
        }
        let uvs = if flow == [0.0; 2] {
            [
                uv(0, 0.0, 0.0),
                uv(0, 0.0, 1.0),
                uv(0, 1.0, 1.0),
                uv(0, 1.0, 0.0),
            ]
        } else {
            let angle = flow[1].atan2(flow[0]) as f32 - std::f32::consts::FRAC_PI_2;
            let (s, c) = angle.sin_cos();
            let (s, c) = (s * 0.25, c * 0.25);
            [
                uv(1, 0.5 - c - s, 0.5 - c + s),
                uv(1, 0.5 - c + s, 0.5 + c + s),
                uv(1, 0.5 + c + s, 0.5 + c - s),
                uv(1, 0.5 + c - s, 0.5 - c - s),
            ]
        };
        face(
            [
                [0.0, heights[0], 0.0],
                [0.0, heights[1], 1.0],
                [1.0, heights[2], 1.0],
                [1.0, heights[3], 0.0],
            ],
            uvs,
            backward == 1,
        );
    }
    if enabled[0] {
        face(
            [
                [0.0, bottom, 0.0],
                [1.0, bottom, 0.0],
                [1.0, bottom, 1.0],
                [0.0, bottom, 1.0],
            ],
            [
                uv(0, 0.0, 0.0),
                uv(0, 1.0, 0.0),
                uv(0, 1.0, 1.0),
                uv(0, 0.0, 1.0),
            ],
            false,
        );
    }
    for (direction, a, b, x0, z0, x1, z1) in [
        (2, 0, 3, 0.0, 0.001, 1.0, 0.001),
        (3, 2, 1, 1.0, 0.999, 0.0, 0.999),
        (4, 1, 0, 0.001, 1.0, 0.001, 0.0),
        (5, 3, 2, 0.999, 0.0, 0.999, 1.0),
    ] {
        let (ha, hb) = (heights[a], heights[b]);
        if !enabled[direction] || shapes[6 + direction].covers(ha.max(hb)) {
            continue;
        }
        let is_overlay = overlay & (1 << direction) != 0;
        let sprite = if is_overlay { 2 } else { 1 };
        face(
            [
                [x0, ha, z0],
                [x1, hb, z1],
                [x1, bottom, z1],
                [x0, bottom, z0],
            ],
            [
                uv(sprite, 0.0, (1.0 - ha) * 0.5),
                uv(sprite, 0.5, (1.0 - hb) * 0.5),
                uv(sprite, 0.5, 0.5),
                uv(sprite, 0.0, 0.5),
            ],
            !is_overlay,
        );
    }
    Ok(())
}
