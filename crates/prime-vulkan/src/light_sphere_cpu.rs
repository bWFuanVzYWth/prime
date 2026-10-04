//! Directional SAOH quad proposal. World nodes use AABB midpoint sphere bounds.
//! Child's high bit identifies a source leaf; interior leaves counts reserve finite RNG support.
//! Direction/SAOH construction is derived from Prime b35438684203200b6ab8c0b19977bd06625faaea.
use prime_scene::surface::Emitter;

#[cfg(all(test, feature = "shader-tests"))]
pub(crate) fn register_tree_build() -> &'static str {
    static STRATEGY: std::sync::OnceLock<&'static str> = std::sync::OnceLock::new();
    STRATEGY.get_or_init(|| match std::env::var("PRIME_REGISTER_TREE_BUILD") {
        Err(std::env::VarError::NotPresent) => "saoh",
        Ok(value) if value == "saoh" => "saoh",
        Ok(value) if value == "balanced" => "balanced",
        _ => panic!("PRIME_REGISTER_TREE_BUILD must be saoh or balanced"),
    })
}

#[cfg(test)]
pub(crate) fn leaf_depths(paths: &[u32]) -> [u64; 28] {
    let mut histogram = [0; 28];
    for &path in paths {
        if path != u32::MAX {
            histogram[(path >> 27) as usize] += 1;
        }
    }
    histogram
}

pub(crate) const SELECTORS: u32 = 1 << 24;

fn radius(bounds: [[f32; 3]; 2], center: [f32; 3]) -> f32 {
    let r = (0..3)
        .map(|a| {
            (f64::from(center[a]) - f64::from(bounds[0][a]))
                .abs()
                .max((f64::from(bounds[1][a]) - f64::from(center[a])).abs())
                .powi(2)
        })
        .sum::<f64>()
        .sqrt();
    if r == 0.0 { 0.0 } else { (r as f32).next_up() }
}

pub(crate) const LEAF: u32 = 1 << 31;
pub(crate) const MAX_DEPTH: u32 = 27;
const HALF_PI: f32 = std::f32::consts::FRAC_PI_2;
const CONE_LIMIT: f32 = std::f32::consts::PI * 0.125;

fn dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
fn normalized(v: [f32; 3]) -> [f32; 3] {
    let inverse = 1.0 / dot(v, v).sqrt();
    v.map(|x| x * inverse)
}
fn sin(x: f32) -> f32 {
    f64::from(x).sin() as f32
}
fn cos(x: f32) -> f32 {
    f64::from(x).cos() as f32
}
fn acos(x: f32) -> f32 {
    f64::from(x.clamp(-1.0, 1.0)).acos() as f32
}
fn sign(x: f32) -> f32 {
    if x >= 0.0 { 1.0 } else { -1.0 }
}
fn cone_cosine(c: f32, angle: f32) -> f32 {
    let c = c.clamp(-1.0, 1.0);
    if c >= cos(angle) {
        1.0
    } else {
        (c * cos(angle) + (1.0 - c * c).max(0.0).sqrt() * sin(angle)).clamp(0.0, 1.0)
    }
}
fn unpack_axis(packed: u32) -> [f32; 3] {
    let x = (packed & 1023) as f32 / 1023.0 * 2.0 - 1.0;
    let y = ((packed >> 10) & 1023) as f32 / 1023.0 * 2.0 - 1.0;
    let z = 1.0 - x.abs() - y.abs();
    normalized(if z < 0.0 {
        [(1.0 - y.abs()) * sign(x), (1.0 - x.abs()) * sign(y), z]
    } else {
        [x, y, z]
    })
}

#[derive(Clone, Copy, Debug)]
struct Direction {
    axis: [f32; 3],
    angle: f32,
    mode: u32,
    lobes: [f32; 6],
}
impl Direction {
    fn normal(axis: [f32; 3], two: bool) -> Self {
        let axis = normalized(axis);
        Self {
            axis,
            angle: 0.0,
            mode: u32::from(two),
            lobes: std::array::from_fn(|i| {
                if two {
                    0.5 * axis[i / 2].abs()
                } else {
                    (axis[i / 2] * if i % 2 == 0 { 1.0 } else { -1.0 }).max(0.0)
                }
            }),
        }
    }
    fn combine(a: Self, ap: f32, b: Self, bp: f32) -> Self {
        if ap <= 0.0 {
            return b;
        }
        if bp <= 0.0 {
            return a;
        }
        let aw = ap / (ap + bp);
        let bw = bp / (ap + bp);
        let mut result = Self {
            axis: [0.0, 0.0, 1.0],
            angle: HALF_PI,
            mode: 2,
            lobes: std::array::from_fn(|i| aw * a.lobes[i] + bw * b.lobes[i]),
        };
        if a.mode == b.mode && a.mode <= 1 {
            let mut axis = b.axis;
            let mut cosine = dot(a.axis, axis).clamp(-1.0, 1.0);
            if a.mode == 1 && cosine < 0.0 {
                axis = axis.map(|x| -x);
                cosine = -cosine;
            }
            let separation = acos(cosine);
            let (axis, angle) = if a.angle >= separation + b.angle {
                (a.axis, a.angle)
            } else if b.angle >= separation + a.angle {
                (axis, b.angle)
            } else {
                let angle = 0.5 * (separation + a.angle + b.angle);
                if angle > CONE_LIMIT {
                    return result;
                }
                let t = if separation > 1e-6 {
                    (angle - a.angle) / separation
                } else {
                    0.5
                };
                let s = sin(separation);
                let (wa, wb) = if s.abs() > 1e-6 {
                    (sin((1.0 - t) * separation) / s, sin(t * separation) / s)
                } else {
                    (1.0 - t, t)
                };
                (
                    normalized(std::array::from_fn(|i| wa * a.axis[i] + wb * axis[i])),
                    angle,
                )
            };
            if angle <= CONE_LIMIT {
                result.axis = axis;
                result.angle = angle;
                result.mode = a.mode;
            }
        }
        result
    }
    fn spread(self) -> f32 {
        if self.mode == 3 {
            3.0
        } else if self.mode == 2 {
            (self.lobes.into_iter().sum::<f32>() - 1.0).max(0.0)
        } else {
            1.0 - cos(self.angle) + 0.5 * std::f32::consts::PI * sin(self.angle)
        }
    }
    fn pack(self) -> u32 {
        if self.mode == 3 {
            return 3 << 30;
        }
        if self.mode <= 1 {
            let inverse =
                1.0 / (self.axis[0].abs() + self.axis[1].abs() + self.axis[2].abs()).max(1e-20);
            let [x, y, z] = self.axis.map(|v| v * inverse);
            let [x, y] = if z < 0.0 {
                [(1.0 - y.abs()) * sign(x), (1.0 - x.abs()) * sign(y)]
            } else {
                [x, y]
            };
            let bits = ((x * 0.5 + 0.5).clamp(0.0, 1.0) * 1023.0).round() as u32
                | (((y * 0.5 + 0.5).clamp(0.0, 1.0) * 1023.0).round() as u32) << 10;
            let angle = self.angle + acos(dot(self.axis, unpack_axis(bits))) + HALF_PI / 1023.0;
            if angle <= HALF_PI {
                let sine = (f64::from(angle).sin() * 1023.0).ceil().min(1023.0) as u32;
                return bits | sine << 20 | self.mode << 30;
            }
        }
        self.lobes
            .into_iter()
            .enumerate()
            .fold(2 << 30, |p, (i, l)| {
                p | ((l.clamp(0.0, 1.0) * 31.0).ceil() as u32).min(31) << (i * 5)
            })
    }
    fn unpack(p: u32) -> Self {
        let mode = p >> 30;
        if mode >= 2 {
            return Self {
                axis: [0.0, 0.0, 1.0],
                angle: HALF_PI,
                mode,
                lobes: std::array::from_fn(|i| {
                    if mode == 3 {
                        1.0
                    } else {
                        ((p >> (i * 5)) & 31) as f32 / 31.0
                    }
                }),
            };
        }
        let axis = unpack_axis(p);
        let angle = f64::from(((p >> 20) & 1023) as f32 / 1023.0).asin() as f32;
        Self {
            axis,
            angle,
            mode,
            lobes: std::array::from_fn(|i| {
                let a = axis[i / 2];
                if mode == 1 {
                    0.5 * cone_cosine(a, angle).max(cone_cosine(-a, angle))
                } else {
                    cone_cosine(a * if i % 2 == 0 { 1.0 } else { -1.0 }, angle)
                }
            }),
        }
    }
}

#[derive(Clone, Copy)]
struct Source {
    bounds: [[f32; 3]; 2],
    center: [f32; 3],
    power: f32,
    index: u32,
    direction: Direction,
    represented_direction: Option<u32>,
}
#[derive(Clone, Copy, Default)]
pub(crate) struct Node {
    pub center: [f32; 3],
    pub power: f32,
    pub direction: u32,
    pub child: u32,
    pub leaves: u32,
    pub radius: f32,
}
pub(crate) struct Tree {
    pub nodes: Vec<Node>,
    pub paths: Vec<u32>,
    pub bounds: [[f32; 3]; 2],
}

#[derive(Clone, Copy)]
struct Aggregate {
    bounds: [[f32; 3]; 2],
    power: f32,
    direction: Option<Direction>,
    count: usize,
}
impl Default for Aggregate {
    fn default() -> Self {
        Self {
            bounds: [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]],
            power: 0.0,
            direction: None,
            count: 0,
        }
    }
}
impl Aggregate {
    fn include(&mut self, b: Self) {
        if b.count == 0 {
            return;
        }
        for i in 0..3 {
            self.bounds[0][i] = self.bounds[0][i].min(b.bounds[0][i]);
            self.bounds[1][i] = self.bounds[1][i].max(b.bounds[1][i]);
        }
        self.direction = Some(match self.direction {
            Some(a) => Direction::combine(a, self.power, b.direction.unwrap(), b.power),
            None => b.direction.unwrap(),
        });
        self.power += b.power;
        self.count += b.count;
    }
    fn source(s: Source) -> Self {
        Self {
            bounds: s.bounds,
            power: s.power,
            direction: Some(s.direction),
            count: 1,
        }
    }
    fn cost(self) -> f32 {
        if self.count == 0 {
            return 0.0;
        }
        let [x, y, z] = std::array::from_fn(|a| (self.bounds[1][a] - self.bounds[0][a]).max(0.0));
        let area = 2.0 * (x * y + y * z + z * x);
        self.power
            * (if area > 0.0 {
                area
            } else {
                (x * x + y * y + z * z).sqrt()
            })
            * (1.0 + self.direction.unwrap().spread())
    }
}
fn bin(center: f32, min: f32, extent: f32) -> usize {
    (((center - min) / extent).clamp(0.0, f32::from_bits(0x3f7fffff)) * 12.0) as usize
}
fn split(s: &mut [Source], depth: u32) -> usize {
    let mut lower = [f32::INFINITY; 3];
    let mut upper = [f32::NEG_INFINITY; 3];
    let mut all = Aggregate::default();
    for x in s.iter() {
        for a in 0..3 {
            lower[a] = lower[a].min(x.center[a]);
            upper[a] = upper[a].max(x.center[a]);
        }
        all.include(Aggregate::source(*x));
    }
    let extent: [f32; 3] = std::array::from_fn(|a| upper[a] - lower[a]);
    let longest = if extent[0] >= extent[1] && extent[0] >= extent[2] {
        0
    } else if extent[1] >= extent[2] {
        1
    } else {
        2
    };
    let mut candidates = Vec::with_capacity(33);
    for axis in 0..3 {
        if extent[axis] <= 0.0 {
            continue;
        }
        let mut bins = [Aggregate::default(); 12];
        for x in s.iter() {
            bins[bin(x.center[axis], lower[axis], extent[axis])].include(Aggregate::source(*x));
        }
        let mut prefix = bins;
        let mut suffix = bins;
        for b in 1..12 {
            let mut sum = prefix[b - 1];
            sum.include(bins[b]);
            prefix[b] = sum;
        }
        for b in (0..11).rev() {
            let mut sum = suffix[b + 1];
            sum.include(bins[b]);
            suffix[b] = sum;
        }
        for b in 0..11 {
            let (a, c) = (prefix[b], suffix[b + 1]);
            if a.count == 0 || c.count == 0 {
                continue;
            }
            let cost = (a.cost() + c.cost()) * (extent[longest] / extent[axis]);
            let continuation = f64::from(a.power) * ((a.count as f64).ln() / 2f64.ln())
                + f64::from(c.power) * ((c.count as f64).ln() / 2f64.ln());
            if cost.is_finite() {
                candidates.push((axis, b, cost, continuation));
            }
        }
    }
    let mut middle = 0;
    if !candidates.is_empty() {
        let cheapest = candidates
            .iter()
            .min_by(|a, b| a.2.total_cmp(&b.2))
            .unwrap();
        let mut best = cheapest;
        for c in &candidates {
            if f64::from(c.2) > f64::from(cheapest.2) * 1.02
                || (cheapest.2 < all.cost() && c.2 >= all.cost())
            {
                continue;
            }
            if c.3 < best.3 || (c.3 == best.3 && c.2 < best.2) {
                best = c;
            }
        }
        let (axis, b, _, _) = *best;
        let mut right = s.len();
        while middle < right {
            if bin(s[middle].center[axis], lower[axis], extent[axis]) <= b {
                middle += 1;
            } else {
                right -= 1;
                s.swap(middle, right);
            }
        }
    }
    let capacity = 1usize << (MAX_DEPTH - depth - 1);
    if middle == 0 || middle == s.len() || middle > capacity || s.len() - middle > capacity {
        s.sort_unstable_by(|a, b| {
            a.center[longest]
                .total_cmp(&b.center[longest])
                .then(a.index.cmp(&b.index))
        });
        middle = s.len() / 2;
    }
    middle
}

#[cfg(test)]
fn split_balanced(s: &mut [Source]) -> usize {
    let mut lower = [f32::INFINITY; 3];
    let mut upper = [f32::NEG_INFINITY; 3];
    for source in s.iter() {
        for axis in 0..3 {
            lower[axis] = lower[axis].min(source.center[axis]);
            upper[axis] = upper[axis].max(source.center[axis]);
        }
    }
    let extent: [f32; 3] = std::array::from_fn(|axis| upper[axis] - lower[axis]);
    let longest = if extent[0] >= extent[1] && extent[0] >= extent[2] {
        0
    } else if extent[1] >= extent[2] {
        1
    } else {
        2
    };
    // IDs break equal-centroid ties: the resulting ordering is deterministic.
    s.sort_unstable_by(|a, b| {
        a.center[longest]
            .total_cmp(&b.center[longest])
            .then(a.index.cmp(&b.index))
    });
    s.len() / 2
}

impl Tree {
    fn build(source: Vec<Source>, capacity: usize, world: bool) -> Self {
        #[cfg(all(test, feature = "shader-tests"))]
        if register_tree_build() == "balanced" {
            return Self::build_with::<true>(source, capacity, world);
        }
        Self::build_with::<false>(source, capacity, world)
    }

    fn build_with<const BALANCED: bool>(
        mut source: Vec<Source>,
        capacity: usize,
        world: bool,
    ) -> Self {
        let mut nodes = Vec::with_capacity(source.len() * 2 - 1);
        nodes.push(Node::default());
        let mut tree = Self {
            nodes,
            paths: vec![u32::MAX; capacity],
            bounds: [[0.0; 3]; 2],
        };
        tree.bounds = tree.populate::<BALANCED>(&mut source, 0, 0, 0, world).0;
        #[cfg(all(test, feature = "shader-tests"))]
        if world {
            eprintln!(
                "register tree: scope=world strategy={} leaves={} leaf_depths={:?}",
                if BALANCED { "balanced" } else { "saoh" },
                source.len(),
                leaf_depths(&tree.paths),
            );
        }
        tree
    }
    fn populate<const BALANCED: bool>(
        &mut self,
        s: &mut [Source],
        at: usize,
        trail: u32,
        depth: u32,
        world: bool,
    ) -> ([[f32; 3]; 2], Direction) {
        assert!(depth <= MAX_DEPTH && s.len() <= 1usize << (MAX_DEPTH - depth));
        let mut aggregate = Aggregate::default();
        let mut mass = 0f64;
        let mut mean = [0f64; 3];
        for x in s.iter() {
            aggregate.include(Aggregate::source(*x));
            let next = mass + f64::from(x.power);
            for (a, m) in mean.iter_mut().enumerate() {
                *m += f64::from(x.power) / next * (f64::from(x.center[a]) - *m);
            }
            mass = next;
        }
        let mut node = Node {
            power: aggregate.power,
            center: std::array::from_fn(|a| {
                let lo = aggregate.bounds[0][a];
                if world {
                    return ((f64::from(lo) + f64::from(aggregate.bounds[1][a])) * 0.5) as f32;
                }
                let hi = aggregate.bounds[1][a];
                if hi <= lo {
                    lo
                } else {
                    lo + (hi - lo)
                        * ((((mean[a] as f32 - lo) / (hi - lo)).clamp(0.0, 1.0) * 1023.0).round()
                            / 1023.0)
                }
            }),
            ..Node::default()
        };
        let direction;
        if s.len() == 1 {
            direction = s[0].direction;
            node.child = LEAF | s[0].index;
            self.paths[s[0].index as usize] = depth << 27 | trail;
        } else {
            #[cfg(test)]
            let middle = if BALANCED {
                split_balanced(s)
            } else {
                split(s, depth)
            };
            #[cfg(not(test))]
            let middle = split(s, depth);
            node.child = self.nodes.len() as u32;
            self.nodes.extend([Node::default(); 2]);
            let (a, b) = s.split_at_mut(middle);
            let (_, ad) =
                self.populate::<BALANCED>(a, node.child as usize, trail, depth + 1, world);
            let (_, bd) = self.populate::<BALANCED>(
                b,
                node.child as usize + 1,
                trail | 1 << depth,
                depth + 1,
                world,
            );
            direction = Direction::combine(
                ad,
                self.nodes[node.child as usize].power,
                bd,
                self.nodes[node.child as usize + 1].power,
            );
        }
        node.leaves = s.len() as u32;
        node.radius = radius(aggregate.bounds, node.center);
        node.direction = if s.len() == 1 {
            s[0].represented_direction
                .unwrap_or_else(|| direction.pack())
        } else {
            direction.pack()
        };
        self.nodes[at] = node;
        (aggregate.bounds, direction)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Root {
    pub bounds: [[f32; 3]; 2],
    pub power: f32,
    pub direction: u32,
}

impl Tree {
    pub(crate) fn local(emitters: &[Emitter]) -> Result<Self, String> {
        let sources = emitters
            .iter()
            .enumerate()
            .map(|(index, emitter)| {
                let bounds = [
                    std::array::from_fn(|a| {
                        emitter
                            .positions
                            .iter()
                            .map(|p| p[a])
                            .fold(f32::INFINITY, f32::min)
                    }),
                    std::array::from_fn(|a| {
                        emitter
                            .positions
                            .iter()
                            .map(|p| p[a])
                            .fold(f32::NEG_INFINITY, f32::max)
                    }),
                ];
                let triangles = [[0, 1, 2], [2, 3, 0]];
                let weights = [emitter.first_fraction, 1.0 - emitter.first_fraction];
                let mut directions = [None; 2];
                let means: [[f32; 3]; 2] = triangles.map(|t| {
                    std::array::from_fn(|a| {
                        (t.iter()
                            .map(|&i| f64::from(emitter.positions[i][a]))
                            .sum::<f64>()
                            / 3.0) as f32
                    })
                });
                for (half, t) in triangles.into_iter().enumerate() {
                    if weights[half] > 0.0 {
                        let u: [f32; 3] = std::array::from_fn(|a| {
                            emitter.positions[t[1]][a] - emitter.positions[t[0]][a]
                        });
                        let v: [f32; 3] = std::array::from_fn(|a| {
                            emitter.positions[t[2]][a] - emitter.positions[t[0]][a]
                        });
                        directions[half] = Some(Direction::normal(
                            [
                                u[1] * v[2] - u[2] * v[1],
                                u[2] * v[0] - u[0] * v[2],
                                u[0] * v[1] - u[1] * v[0],
                            ],
                            emitter.two_sided,
                        ));
                    }
                }
                let direction = match directions {
                    [Some(a), Some(b)] => Direction::combine(a, weights[0], b, weights[1]),
                    [Some(a), None] => a,
                    [None, Some(b)] => b,
                    _ => return Err("Light sphere emitter has no positive-area half".to_owned()),
                };
                Ok(Source {
                    bounds,
                    center: std::array::from_fn(|a| {
                        weights[0] * means[0][a] + weights[1] * means[1][a]
                    }),
                    power: emitter.power,
                    index: index as u32,
                    direction,
                    represented_direction: None,
                })
            })
            .collect::<Result<Vec<_>, String>>()?;
        Self::checked(sources, emitters.len(), false)
    }

    pub(crate) fn world(sources: &[(u32, Root)], capacity: usize) -> Result<Self, String> {
        Self::checked(
            sources
                .iter()
                .map(|&(index, root)| Source {
                    bounds: root.bounds,
                    center: std::array::from_fn(|a| {
                        ((f64::from(root.bounds[0][a]) + f64::from(root.bounds[1][a])) * 0.5) as f32
                    }),
                    power: root.power,
                    index,
                    direction: Direction::unpack(root.direction),
                    represented_direction: Some(root.direction),
                })
                .collect(),
            capacity,
            true,
        )
    }

    fn checked(source: Vec<Source>, capacity: usize, world: bool) -> Result<Self, String> {
        if source.is_empty() || source.len() > SELECTORS as usize {
            return Err("Light sphere exceeds 24-bit selector capacity or is empty".into());
        }
        if source.iter().any(|s| {
            s.index as usize >= capacity
                || !s.power.is_finite()
                || s.power <= 0.0
                || (0..3).any(|a| {
                    !s.bounds[0][a].is_finite()
                        || !s.bounds[1][a].is_finite()
                        || s.bounds[0][a] > s.bounds[1][a]
                        || !s.center[a].is_finite()
                        || !s.direction.axis[a].is_finite()
                })
        }) {
            return Err("Invalid light sphere source".into());
        }
        let tree = Self::build(source, capacity, world);
        if tree
            .nodes
            .iter()
            .any(|n| !n.power.is_finite() || !n.radius.is_finite())
        {
            return Err("Light sphere aggregate overflow".into());
        }
        Ok(tree)
    }

    pub(crate) fn root(&self) -> Root {
        Root {
            bounds: self.bounds,
            power: self.nodes[0].power,
            direction: self.nodes[0].direction,
        }
    }
}

pub(crate) fn node_bytes(nodes: &[Node]) -> Vec<u8> {
    nodes
        .iter()
        .flat_map(|n| {
            [
                n.center[0].to_bits(),
                n.center[1].to_bits(),
                n.center[2].to_bits(),
                n.power.to_bits(),
                n.direction,
                n.child,
                n.leaves,
                n.radius.to_bits(),
            ]
        })
        .flat_map(u32::to_le_bytes)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn emitter(z: f32) -> Emitter {
        Emitter {
            quad: 0,
            positions: [[-2., -1., z], [2., -1., z], [2., 1., z], [-2., 1., z]],
            first_fraction: 0.5,
            radiance: [1.; 3],
            area: 8.,
            power: 8.,
            two_sided: false,
        }
    }
    fn assert_topology(tree: &Tree) {
        for (id, &path) in tree.paths.iter().enumerate() {
            if path == u32::MAX {
                continue;
            }
            let mut at = 0;
            for depth in 0..path >> 27 {
                let node = tree.nodes[at];
                assert_eq!(node.child & LEAF, 0);
                at = node.child as usize + ((path >> depth) & 1) as usize;
            }
            assert_eq!(tree.nodes[at].child, LEAF | id as u32);
            assert_eq!(tree.nodes[at].leaves, 1);
        }
        for node in &tree.nodes {
            if node.child & LEAF == 0 {
                let child = node.child as usize;
                assert_eq!(
                    node.leaves,
                    tree.nodes[child].leaves + tree.nodes[child + 1].leaves
                );
            }
        }
    }

    fn balanced_sources(count: usize, coincident: bool) -> Vec<Source> {
        (0..count)
            .map(|i| {
                let center = if coincident {
                    [0.; 3]
                } else {
                    [(i * 13 % 17) as f32, (i * 7 % 11) as f32, i as f32]
                };
                Source {
                    bounds: [center.map(|v| v - 0.5), center.map(|v| v + 0.5)],
                    center,
                    power: (i + 1) as f32,
                    index: (i * 3 + 2) as u32,
                    direction: Direction::normal([0., 0., 1.], false),
                    represented_direction: None,
                }
            })
            .collect()
    }

    #[test]
    fn sphere_balanced_tree_has_unique_sparse_leaves_and_minimal_depth_span() {
        for count in [1, 2, 3, 5, 7, 8, 9, 17, 31, 32, 33, 65] {
            for coincident in [false, true] {
                for world in [false, true] {
                    let tree = Tree::build_with::<true>(
                        balanced_sources(count, coincident),
                        count * 3 + 2,
                        world,
                    );
                    assert_topology(&tree);
                    assert_eq!(tree.nodes.len(), count * 2 - 1);
                    assert_eq!(tree.nodes[0].leaves as usize, count);
                    let ids: std::collections::BTreeSet<_> = tree
                        .nodes
                        .iter()
                        .filter(|n| n.child & LEAF != 0)
                        .map(|n| n.child & !LEAF)
                        .collect();
                    assert_eq!(ids.len(), count);
                    assert_eq!(ids, (0..count).map(|i| (i * 3 + 2) as u32).collect());
                    let histogram = leaf_depths(&tree.paths);
                    assert_eq!(histogram.iter().sum::<u64>(), count as u64);
                    let low = (usize::BITS - 1 - count.leading_zeros()) as usize;
                    let high = low + usize::from(!count.is_power_of_two());
                    for (depth, leaves) in histogram.into_iter().enumerate() {
                        assert!(leaves == 0 || (low..=high).contains(&depth));
                    }
                    for node in &tree.nodes {
                        if node.child & LEAF == 0 {
                            let child = node.child as usize;
                            assert_eq!(tree.nodes[child].leaves, node.leaves / 2);
                            assert_eq!(tree.nodes[child + 1].leaves, node.leaves - node.leaves / 2);
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn sphere_balanced_tree_integer_paths_preserve_support_and_total_mass() {
        for count in [3, 5, 17, 33, 65] {
            let tree =
                Tree::build_with::<true>(balanced_sources(count, false), count * 3 + 2, false);
            for p in [0.0, 1e-20, 0.17, 0.5, 1.0 - 1e-6, 1.0] {
                let mut total = 0;
                for (id, &path) in tree.paths.iter().enumerate() {
                    if path == u32::MAX {
                        continue;
                    }
                    let mut at = 0;
                    let mut mass = SELECTORS;
                    for depth in 0..path >> 27 {
                        let child = tree.nodes[at].child as usize;
                        let left = ((mass as f32 * p).round() as u32).clamp(
                            tree.nodes[child].leaves,
                            mass - tree.nodes[child + 1].leaves,
                        );
                        let second = (path >> depth) & 1 != 0;
                        mass = if second { mass - left } else { left };
                        at = child + usize::from(second);
                    }
                    assert_eq!(tree.nodes[at].child, LEAF | id as u32);
                    assert!(mass > 0);
                    total += mass;
                }
                assert_eq!(total, SELECTORS);
            }
        }
    }
    #[test]
    fn sphere_tree_world_midpoint_covers_represented_corners_and_sparse_ids() {
        let local = Tree::local(&[emitter(0.), emitter(4.)]).unwrap();
        let root = local.root();
        let mut shifted = root;
        shifted.bounds = root.bounds.map(|p| p.map(|x| x + 30.125));
        let world = Tree::world(&[(2, root), (7, shifted)], 9).unwrap();
        assert_topology(&local);
        assert_topology(&world);
        for leaf in world.nodes.iter().filter(|n| n.child & LEAF != 0) {
            assert_eq!(leaf.direction, root.direction);
            assert_eq!(leaf.power.to_bits(), root.power.to_bits());
        }
        let n = world.nodes[0];
        for a in 0..3 {
            assert_eq!(
                n.center[a],
                ((f64::from(world.bounds[0][a]) + f64::from(world.bounds[1][a])) * 0.5) as f32
            );
        }
        for corner in 0..8 {
            let d2 = (0..3)
                .map(|a| {
                    (f64::from(world.bounds[(corner >> a) & 1][a]) - f64::from(n.center[a])).powi(2)
                })
                .sum::<f64>();
            assert!(d2 <= f64::from(n.radius).powi(2));
        }
        assert_eq!(node_bytes(&world.nodes).len(), world.nodes.len() * 32);
    }
    #[test]
    fn sphere_tree_singleton_nonplanar_and_degenerate_halves_remain_finite() {
        let mut e = emitter(0.);
        e.two_sided = true;
        e.positions[3][2] = 1.0;
        let local = Tree::local(&[e.clone()]).unwrap();
        assert_topology(&local);
        assert_eq!(local.paths, [0]);
        e.positions[3] = e.positions[2];
        e.first_fraction = 1.0;
        let local = Tree::local(&[e]).unwrap();
        assert!(local.nodes[0].radius.is_finite());
        assert!(local.nodes[0].power > 0.0);
        assert!(Tree::world(&[], 0).is_err());
    }
    #[test]
    fn sphere_tree_integer_replay_reserves_each_rare_leaf() {
        // Deliberately adversarial scores, including all the mass on one side.
        let tree = Tree::local(&(0..17).map(|i| emitter(i as f32)).collect::<Vec<_>>()).unwrap();
        fn split(count: u32, a: u32, b: u32, p: f32) -> u32 {
            ((count as f32 * p).round() as u32).clamp(a, count - b)
        }
        for p in [0.0, 1e-20, 0.5, 1.0 - 1e-6, 1.0] {
            let mut total = 0;
            for &path in &tree.paths {
                let mut at = 0;
                let mut count = SELECTORS;
                for depth in 0..path >> 27 {
                    let c = tree.nodes[at].child as usize;
                    let left = split(count, tree.nodes[c].leaves, tree.nodes[c + 1].leaves, p);
                    let second = (path >> depth) & 1 != 0;
                    count = if second { count - left } else { left };
                    at = c + usize::from(second);
                }
                assert!(count > 0);
                total += count;
            }
            assert_eq!(total, SELECTORS);
        }
    }
}
