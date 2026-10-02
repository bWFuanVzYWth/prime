//! Pure MC fluid compilation from the closed 3×3×3 neighborhood and resource dictionary.
use crate::model::{Catalog, Hacks, Quad, State, cube, emit_quad};
#[cfg(test)]
use crate::wire::Reader;
use prime_scene::compiled::CompiledQuad;
#[derive(Clone, Copy, Default, PartialEq)]
pub(crate) struct Fluid {
    pub kind: u32,
    pub amount: u32,
    pub falling: bool,
    pub material: u32,
}
impl Fluid {
    pub fn from_fields(
        name: &str,
        level: u32,
        falling: u32,
        material: u32,
    ) -> Result<Self, String> {
        if level > 8 || falling > 1 {
            return Err("invalid source fluid properties".into());
        }
        let kind = match name {
            "minecraft:empty" => 0,
            "minecraft:water" | "minecraft:flowing_water" => 1,
            "minecraft:lava" | "minecraft:flowing_lava" => 2,
            _ => 3,
        };
        Ok(Self {
            kind,
            amount: if kind == 0 {
                0
            } else if level == 0 {
                8
            } else {
                level
            },
            falling: falling != 0,
            material,
        })
    }
    #[cfg(test)]
    pub fn read(r: &mut Reader<'_>) -> Result<Self, String> {
        let name = r.string()?;
        let level = r.u32()?;
        let falling = r.u32()?;
        let material = r.u32()?;
        if level > 8 || falling > 1 {
            return Err("invalid source fluid properties".into());
        }
        let kind = match name.as_str() {
            "minecraft:empty" => 0,
            "minecraft:water" | "minecraft:flowing_water" => 1,
            "minecraft:lava" | "minecraft:flowing_lava" => 2,
            _ => 3,
        };
        let amount = if kind == 0 {
            0
        } else if level == 0 {
            8
        } else {
            level
        };
        Ok(Self {
            kind,
            amount,
            falling: falling != 0,
            material,
        })
    }
    fn height(self) -> f32 {
        self.amount as f32 / 9.
    }
}
#[derive(PartialEq)]
pub(crate) struct FluidMaterial {
    layer: usize,
    pub flags: u32,
    sprites: [[f32; 4]; 3],
    pub identities: [u32; 3],
}
impl FluidMaterial {
    pub fn from_typed(value: &prime_abi::PrimeMcFluid) -> Result<(u32, Self), String> {
        if value.id == 0 || value.layer > 2 || value.flags & !3 != 0 {
            return Err("invalid fluid material".into());
        }
        prime_abi::minecraft::finite(&value.bounds)?;
        Ok((
            value.id,
            Self {
                layer: value.layer as usize,
                flags: value.flags,
                identities: value.identities,
                sprites: std::array::from_fn(|i| std::array::from_fn(|j| value.bounds[i * 4 + j])),
            },
        ))
    }
    #[cfg(test)]
    pub fn read(r: &mut Reader<'_>) -> Result<(u32, Self), String> {
        let id = r.u32()?;
        let layer = r.u32()?;
        let flags = r.u32()?;
        if id == 0 || layer > 2 || flags & !3 != 0 {
            return Err("invalid fluid material".into());
        }
        let mut sprites = [[0.; 4]; 3];
        let mut identities = [0; 3];
        for (index, sprite) in sprites.iter_mut().enumerate() {
            identities[index] = r.u32()?;
            for v in sprite {
                *v = r.f32()?;
            }
        }
        Ok((
            id,
            Self {
                layer: layer as usize,
                flags,
                sprites,
                identities,
            },
        ))
    }
}
/// These version-matched host tables are immutable and shared by resource catalogs.
#[derive(Clone)]
pub(crate) struct FluidMath {
    tables: std::sync::Arc<FluidTables>,
}
struct FluidTables {
    sin: Box<[f32]>,
    asin: [f64; 257],
    cos: [f64; 257],
}
impl Default for FluidMath {
    fn default() -> Self {
        static TABLES: std::sync::OnceLock<std::sync::Arc<FluidTables>> =
            std::sync::OnceLock::new();
        Self {
            tables: TABLES
                .get_or_init(|| {
                    let asin = std::array::from_fn(|i| (i as f64 / 256.).asin());
                    std::sync::Arc::new(FluidTables {
                        sin: (0..65536)
                            .map(|i| (i as f64 * std::f64::consts::PI * 2. / 65536.).sin() as f32)
                            .collect(),
                        cos: asin.map(f64::cos),
                        asin,
                    })
                })
                .clone(),
        }
    }
}
impl FluidMath {
    fn sin_cos(&self, angle: f32) -> (f32, f32) {
        let index = f64::from(angle) * 10430.378350470453;
        (
            self.tables.sin[(index as i64 & 65535) as usize],
            self.tables.sin[((index + 16384.) as i64 & 65535) as usize],
        )
    }
    fn atan2(&self, mut y: f64, mut x: f64) -> f64 {
        let d2 = x * x + y * y;
        let neg_y = y < 0.;
        let neg_x = x < 0.;
        x = x.abs();
        y = y.abs();
        let steep = y > x;
        if steep {
            std::mem::swap(&mut x, &mut y);
        }
        let inv = f64::from_bits(6910469410427058090 - (d2.to_bits() >> 1));
        let inv = inv * (1.5 - 0.5 * d2 * inv * inv);
        x *= inv;
        y *= inv;
        let bias = f64::from_bits(4805340802404319232);
        let yp = bias + y;
        let index = (yp.to_bits() as u32) as usize;
        let sd = y * self.tables.cos[index] - x * (yp - bias);
        let mut theta = self.tables.asin[index] + (6. + sd * sd) * sd * (1. / 6.);
        if steep {
            theta = std::f64::consts::FRAC_PI_2 - theta;
        }
        if neg_x {
            theta = std::f64::consts::PI - theta;
        }
        if neg_y {
            theta = -theta;
        }
        theta
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

pub(crate) fn emit<'a>(
    catalog: &Catalog,
    state: &State,
    offset: [f32; 3],
    get: impl Fn(i32, i32, i32) -> &'a State,
    layers: &mut [Vec<CompiledQuad>; 3],
    hacks: &mut Hacks,
    contacts: bool,
) -> Option<[f32; 4]> {
    let kind = state.fluid.kind;
    if kind == 0 {
        return None;
    }
    let Some(material) = catalog
        .fluids
        .get(&state.fluid.material)
        .filter(|_| kind < 3)
    else {
        hacks.fluid += 1;
        cube(offset, 63, [1., 0., 1., 1.], 2, layers);
        return None;
    };
    let positions = [
        (0, -1, 0),
        (0, 1, 0),
        (0, 0, -1),
        (0, 0, 1),
        (-1, 0, 0),
        (1, 0, 0),
    ];
    let neighbors = positions.map(|(x, y, z)| get(x, y, z));
    let mut enabled = std::array::from_fn::<_, 6, _>(|i| {
        neighbors[i].fluid.kind != kind
            && (contacts || i == 1 || !catalog.fluid_occluded(state, i ^ 1, 1.))
    });
    if !contacts {
        enabled[0] &= !catalog.fluid_occluded(neighbors[0], 0, 0.8888889);
    }
    if !enabled.iter().any(|b| *b) {
        return Some([1.; 4]);
    }
    let samples = std::array::from_fn::<_, 9, _>(|i| {
        let x = i as i32 % 3 - 1;
        let z = i as i32 / 3 - 1;
        let s = get(x, 0, z);
        if s.fluid.kind == kind {
            if get(x, 1, z).fluid.kind == kind {
                1.
            } else {
                s.fluid.height()
            }
        } else if s.flags & 32 != 0 {
            -1.
        } else {
            0.
        }
    });
    let overlay = neighbors.iter().enumerate().fold(0, |mask, (i, s)| {
        mask | if material.flags & 2 != 0 && s.flags & 64 != 0 {
            1 << i
        } else {
            0
        }
    });
    let color = [1.; 4];
    let sprites = material.sprites;
    let mut flow = [0f64; 2];
    // Direction.Plane.HORIZONTAL: NORTH, EAST, SOUTH, WEST. Arithmetic order is source semantics.
    for (x, z) in [(0, -1), (1, 0), (0, 1), (-1, 0)] {
        let neighbor = get(x, 0, z);
        let fluid = neighbor.fluid;
        if fluid.kind != 0 && fluid.kind != kind {
            continue;
        }
        let mut height = fluid.height();
        let mut distance = 0.;
        if height == 0. {
            let blocks_motion = neighbor.flags & 32 != 0
                && neighbor.name != "minecraft:cobweb"
                && neighbor.name != "minecraft:bamboo_sapling";
            if !blocks_motion {
                let below = get(x, -1, z).fluid;
                if below.kind == 0 || below.kind == kind {
                    height = below.height();
                    if height > 0. {
                        distance = state.fluid.height() - (height - 0.8888889);
                    }
                }
            }
        } else {
            distance = state.fluid.height() - height;
        }
        flow[0] += f64::from(x as f32 * distance);
        flow[1] += f64::from(z as f32 * distance);
    }
    let length = (flow[0] * flow[0] + flow[1] * flow[1]).sqrt();
    if length < 1e-5 {
        flow = [0.; 2];
    } else {
        flow = flow.map(|v| v / length);
    }
    if state.fluid.falling {
        for (face, x, z) in [(2, 0, -1), (5, 1, 0), (3, 0, 1), (4, -1, 0)] {
            let solid = [get(x, 0, z), get(x, 1, z)].into_iter().any(|s| {
                if s.fluid.kind == kind || s.flags & 128 != 0 {
                    return false;
                }
                if s.support & (1 << 31) != 0 {
                    hacks.fluid += 1;
                    return false;
                }
                s.support & (1 << (face * 3)) != 0
            });
            if solid {
                let length = (flow[0] * flow[0] + 36. + flow[1] * flow[1]).sqrt();
                flow = flow.map(|v| v / length);
                break;
            }
        }
    }
    let mut heights = [
        corner(samples[4], samples[1], samples[3], samples[0]),
        corner(samples[4], samples[7], samples[3], samples[6]),
        corner(samples[4], samples[7], samples[5], samples[8]),
        corner(samples[4], samples[1], samples[5], samples[2]),
    ];
    let minimum = heights.iter().copied().fold(1.0, f32::min);
    if !contacts {
        enabled[1] &= !catalog.fluid_occluded(neighbors[1], 1, minimum);
    }
    let bottom = if enabled[0] { 0.001 } else { 0.0 };
    let uv = |sprite: usize, u: f32, v: f32| {
        if material.identities[sprite] != 0 {
            return [u, v];
        }
        let s = sprites[sprite];
        [s[0] + (s[2] - s[0]) * u, s[1] + (s[3] - s[1]) * v]
    };
    let mut face = |positions: [[f32; 3]; 4], uvs: [[f32; 2]; 4], sprite: usize| {
        let q = Quad {
            sprite: 0,
            emission: 0,
            positions,
            uvs,
            face: 6,
            tint: -1,
            layer: material.layer,
        };
        emit_quad(
            &q,
            offset,
            color,
            crate::sprite::texture(material.identities[sprite]),
            layers,
        );
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
            let angle =
                catalog.fluid_math.atan2(flow[1], flow[0]) as f32 - std::f32::consts::FRAC_PI_2;
            let (s, c) = catalog.fluid_math.sin_cos(angle);
            let (s, c) = (s * 0.25, c * 0.25);
            [
                uv(1, 0.5 + (-c - s), 0.5 + (-c + s)),
                uv(1, 0.5 + (-c + s), 0.5 + (c + s)),
                uv(1, 0.5 + (c + s), 0.5 + (c - s)),
                uv(1, 0.5 + (c - s), 0.5 + (-c - s)),
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
            usize::from(flow != [0.; 2]),
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
            0,
        );
    }
    for (direction, a, b, x0, z0, x1, z1) in [
        (2, 0, 3, 0.0, 0.001, 1.0, 0.001),
        (3, 2, 1, 1.0, 0.999, 0.0, 0.999),
        (4, 1, 0, 0.001, 1.0, 0.001, 0.0),
        (5, 3, 2, 0.999, 0.0, 0.999, 1.0),
    ] {
        let (ha, hb) = (heights[a], heights[b]);
        if !enabled[direction]
            || !contacts && catalog.fluid_occluded(neighbors[direction], direction, ha.max(hb))
        {
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
            sprite,
        );
    }
    Some(heights)
}
