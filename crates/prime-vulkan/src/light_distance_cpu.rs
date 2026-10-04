//! Receiver-distance metadata for the existing median light topology. No angular state.
use prime_scene::surface::{Emitter, LightNode};

pub(crate) const LEAF: u32 = 1 << 31;
pub(crate) const SELECTORS: u32 = 1 << 24;
pub(crate) const MAX_DEPTH: u32 = 27;

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Node {
    pub center: [f32; 3],
    pub power: f32,
    pub child: u32,
    pub leaves: u32,
}

#[derive(Default)]
pub(crate) struct Tree {
    pub nodes: Vec<Node>,
    pub paths: Vec<u32>,
}

impl Tree {
    pub(crate) fn local(source: &[LightNode], emitters: &[Emitter]) -> Result<Self, String> {
        Self::build(source, emitters.len(), Some(emitters))
    }

    pub(crate) fn world(source: &[LightNode], capacity: usize) -> Result<Self, String> {
        Self::build(source, capacity, None)
    }

    fn build(
        source: &[LightNode],
        capacity: usize,
        emitters: Option<&[Emitter]>,
    ) -> Result<Self, String> {
        if source.is_empty() {
            return Ok(Self::default());
        }
        let mut nodes = vec![Node::default(); source.len()];
        // Local moments stay in f64 until the final 10-bit in-bounds representation.
        // This single reverse pass avoids rescanning every subtree's emitters.
        let mut moments = emitters.map(|_| vec![([0f64; 3], 0f64); source.len()]);
        for (at, node) in source.iter().enumerate().rev() {
            if !node.power.is_finite()
                || node.power <= 0.
                || (0..3).any(|a| {
                    !node.bounds[0][a].is_finite()
                        || !node.bounds[1][a].is_finite()
                        || node.bounds[0][a] > node.bounds[1][a]
                })
            {
                return Err("Invalid power-distance tree node".into());
            }
            let leaves;
            if let Some(index) = node.emitter() {
                if index as usize >= capacity {
                    return Err("Power-distance leaf exceeds its directory".into());
                }
                leaves = 1;
                if let (Some(emitters), Some(moments)) = (emitters, &mut moments) {
                    let emitter = &emitters[index as usize];
                    if !emitter.power.is_finite()
                        || emitter.power <= 0.
                        || !emitter.first_fraction.is_finite()
                        || !(0.0..=1.0).contains(&emitter.first_fraction)
                        || emitter.positions.iter().flatten().any(|v| !v.is_finite())
                    {
                        return Err("Invalid power-distance emitter".into());
                    }
                    let fraction = f64::from(emitter.first_fraction);
                    let center = std::array::from_fn(|a| {
                        let p = emitter.positions.map(|p| f64::from(p[a]));
                        (fraction * (p[0] + p[1] + p[2]) + (1.0 - fraction) * (p[2] + p[3] + p[0]))
                            / 3.0
                    });
                    moments[at] = (center, f64::from(emitter.power));
                }
            } else {
                let child = node.child as usize;
                if child <= at || child + 1 >= source.len() {
                    return Err("Invalid power-distance topology".into());
                }
                leaves = nodes[child]
                    .leaves
                    .checked_add(nodes[child + 1].leaves)
                    .ok_or("Power-distance leaf count overflow")?;
                if let Some(moments) = &mut moments {
                    let (a, ap) = moments[child];
                    let (b, bp) = moments[child + 1];
                    let power = ap + bp;
                    moments[at] = (
                        std::array::from_fn(|axis| a[axis] + bp / power * (b[axis] - a[axis])),
                        power,
                    );
                }
            }
            if leaves > SELECTORS {
                return Err("Power-distance tree exceeds the 24-bit selector capacity".into());
            }
            nodes[at] = Node {
                center: std::array::from_fn(|a| {
                    let lo = f64::from(node.bounds[0][a]);
                    let hi = f64::from(node.bounds[1][a]);
                    moments
                        .as_ref()
                        .map_or(((lo + hi) * 0.5) as f32, |moments| {
                            if hi == lo {
                                lo as f32
                            } else {
                                let fraction = ((moments[at].0[a] - lo) / (hi - lo)).clamp(0., 1.);
                                (lo + (hi - lo) * (fraction * 1023.).round() / 1023.) as f32
                            }
                        })
                }),
                power: node.power,
                child: node.child,
                leaves,
            };
        }
        let mut paths = vec![u32::MAX; capacity];
        let mut trails = vec![u32::MAX; source.len()];
        trails[0] = 0;
        for (at, node) in nodes.iter().enumerate() {
            let trail = trails[at];
            let depth = trail >> 27;
            if trail == u32::MAX || depth > MAX_DEPTH {
                return Err("Disconnected or over-depth power-distance tree".into());
            }
            if node.child & LEAF != 0 {
                let path = &mut paths[(node.child & !LEAF) as usize];
                if *path != u32::MAX {
                    return Err("Duplicate power-distance leaf".into());
                }
                *path = trail;
            } else {
                let child = node.child as usize;
                if depth == MAX_DEPTH || trails[child..child + 2] != [u32::MAX; 2] {
                    return Err("Power-distance tree depth or child ownership invalid".into());
                }
                let next = (depth + 1) << 27 | trail & ((1 << 27) - 1);
                trails[child] = next;
                trails[child + 1] = next | 1 << depth;
            }
        }
        Ok(Self { nodes, paths })
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
                n.child,
                n.leaves,
            ]
        })
        .flat_map(u32::to_le_bytes)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::surface::{LightRoot, build_light_forest};

    #[test]
    fn local_centroids_respect_triangle_area_then_emitter_power() {
        let emitter = |quad: u32, x: f32, power: f32, fraction: f32| Emitter {
            quad,
            positions: [
                [x, 0., 0.],
                [x + 3., 0., 0.],
                [x, 3., 0.],
                if fraction == 1. {
                    [x, 3., 0.]
                } else {
                    [x - 9., 0., 0.]
                },
            ],
            first_fraction: fraction,
            radiance: [1.; 3],
            area: if fraction == 1. { 4.5 } else { 18. },
            power,
            two_sided: false,
        };
        let emitters = [emitter(0, 0., 1., 0.25), emitter(1, 20., 3., 1.)];
        let roots: Vec<_> = emitters
            .iter()
            .enumerate()
            .map(|(slot, e)| LightRoot {
                bounds: [
                    std::array::from_fn(|a| {
                        e.positions.iter().map(|p| p[a]).reduce(f32::min).unwrap()
                    }),
                    std::array::from_fn(|a| {
                        e.positions.iter().map(|p| p[a]).reduce(f32::max).unwrap()
                    }),
                ],
                power: e.power,
                slot: slot as u32,
            })
            .collect();
        let source = build_light_forest(&roots).unwrap();
        let tree = Tree::local(&source, &emitters).unwrap();
        // First half centroid=(1,1,0), second=(-3,1,0). The second emitter
        // contains only its first half, so its centroid=(21,1,0).
        let first_x = 0.25 * 1. + 0.75 * -3.;
        let mean_x = (first_x + 3. * 21.) / 4.;
        let quantize = |x: f64, lo: f32, hi: f32| {
            let lo = f64::from(lo);
            let extent = f64::from(hi) - lo;
            (lo + (((x - lo) / extent) * 1023.).round() / 1023. * extent) as f32
        };
        assert_eq!(tree.nodes[0].center[0], quantize(mean_x, -9., 23.));
        assert_eq!(tree.nodes[0].center[1], 1.);
        assert_eq!(tree.nodes[0].center[2], 0.);
        for (index, expected) in [(0, first_x), (1, 21.)] {
            let at = source
                .iter()
                .position(|n| n.emitter() == Some(index))
                .unwrap();
            assert_eq!(
                tree.nodes[at].center[0],
                quantize(
                    expected,
                    roots[index as usize].bounds[0][0],
                    roots[index as usize].bounds[1][0]
                )
            );
            assert_eq!(tree.nodes[at].power, emitters[index as usize].power);
            assert_eq!(tree.nodes[at].leaves, 1);
        }
        let mut malformed = emitters.clone();
        malformed[0].first_fraction = f32::NAN;
        assert!(Tree::local(&source, &malformed).is_err());
    }

    #[test]
    fn replay_depth_27_is_representable_and_28_is_rejected() {
        let chain = |depth: u32| {
            let mut nodes = Vec::with_capacity((2 * depth + 1) as usize);
            for level in 0..depth {
                nodes.push(LightNode {
                    bounds: [[0.; 3], [1.; 3]],
                    power: (depth - level + 1) as f32,
                    child: 2 * level + 1,
                });
                nodes.push(LightNode {
                    bounds: [[0.; 3], [1.; 3]],
                    power: 1.,
                    child: LEAF | level,
                });
            }
            nodes.push(LightNode {
                bounds: [[0.; 3], [1.; 3]],
                power: 1.,
                child: LEAF | depth,
            });
            nodes
        };
        let accepted = Tree::world(&chain(27), 28).unwrap();
        assert_eq!(accepted.paths[27], 27 << 27 | ((1 << 27) - 1));
        assert_eq!(accepted.nodes[0].leaves, 28);
        assert!(Tree::world(&chain(28), 29).is_err());
    }

    #[test]
    fn world_midpoints_paths_and_24_byte_records_preserve_sparse_ids() {
        let source = build_light_forest(&[
            LightRoot {
                bounds: [[0., 0., 0.], [2., 2., 2.]],
                power: 1.,
                slot: 3,
            },
            LightRoot {
                bounds: [[8., 0., 0.], [10., 2., 2.]],
                power: 3.,
                slot: 7,
            },
        ])
        .unwrap();
        let tree = Tree::world(&source, 8).unwrap();
        assert_eq!(tree.nodes[0].center, [5., 1., 1.]);
        assert_eq!(tree.nodes[0].leaves, 2);
        assert_eq!(tree.paths[3], 1 << 27);
        assert_eq!(tree.paths[7], 1 << 27 | 1);
        assert!(
            tree.paths
                .iter()
                .enumerate()
                .all(|(id, &path)| id == 3 || id == 7 || path == u32::MAX)
        );
        let bytes = node_bytes(&tree.nodes);
        assert_eq!(bytes.len(), source.len() * 24);
        assert_eq!(f32::from_le_bytes(bytes[12..16].try_into().unwrap()), 4.);
        assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(bytes[20..24].try_into().unwrap()), 2);
    }

    #[test]
    fn invalid_topology_directory_and_duplicate_leaves_fail_before_publication() {
        let leaf = LightNode {
            bounds: [[0.; 3], [1.; 3]],
            power: 1.,
            child: LEAF,
        };
        assert!(Tree::world(&[leaf], 0).is_err());
        assert!(
            Tree::world(
                &[LightNode {
                    power: f32::INFINITY,
                    ..leaf
                }],
                1
            )
            .is_err()
        );
        assert!(Tree::world(&[LightNode { child: 0, ..leaf }], 1).is_err());
        assert!(Tree::world(&[LightNode { child: 1, ..leaf }, leaf, leaf], 1).is_err());
        assert!(Tree::world(&[leaf, leaf], 1).is_err());
        let single = Tree::world(&[leaf], 1).unwrap();
        assert_eq!(single.paths, [0]);
        assert_eq!(single.nodes[0].leaves, 1);
    }
}
