use prime_scene::surface::{LightNode, LightRoot, build_light_forest};

pub const RECEIVERS: usize = 32;
pub const METHODS: [&str; 3] = ["power_tree", "power_alias", "spatial_tree"];
const PAGE_SIZE: usize = 256;

#[derive(Clone, Copy)]
pub struct Emitter {
    pub position: [f32; 3],
    pub half_size: f32,
    pub radiance: f32,
    pub power: f32,
}

pub struct Page {
    pub nodes: Vec<LightNode>,
    pub first: usize,
    pub count: usize,
    pub aliases: Vec<Alias>,
}

#[derive(Clone, Copy, Debug)]
pub struct Alias {
    pub cut: f32,
    pub other: u32,
}

// Store represented thresholds and evaluate their actual marginal, including f32 rounding.
pub fn aliases(weights: &[f32]) -> Vec<Alias> {
    let sum: f64 = weights.iter().map(|&x| f64::from(x)).sum();
    let mut mass: Vec<_> = weights
        .iter()
        .map(|&x| f64::from(x) * weights.len() as f64 / sum)
        .collect();
    let mut small = Vec::new();
    let mut large = Vec::new();
    for (i, &p) in mass.iter().enumerate() {
        if p < 1.0 {
            small.push(i);
        } else {
            large.push(i);
        }
    }
    let mut result: Vec<_> = (0..weights.len())
        .map(|i| Alias {
            cut: 1.0,
            other: i as u32,
        })
        .collect();
    while !small.is_empty() && !large.is_empty() {
        let a = small.pop().unwrap();
        let b = large.pop().unwrap();
        result[a] = Alias {
            cut: mass[a] as f32,
            other: b as u32,
        };
        mass[b] += mass[a] - 1.0;
        if mass[b] < 1.0 {
            small.push(b);
        } else {
            large.push(b);
        }
    }
    result
}

fn alias_pdf(table: &[Alias]) -> Vec<f64> {
    let mut pdf = vec![0.0; table.len()];
    for (i, a) in table.iter().enumerate() {
        pdf[i] += f64::from(a.cut) / table.len() as f64;
        pdf[a.other as usize] += (1.0 - f64::from(a.cut)) / table.len() as f64;
    }
    pdf
}

pub struct Fixture {
    pub name: String,
    pub emitters: Vec<Emitter>,
    pub pages: Vec<Page>,
    pub world: Vec<LightNode>,
    pub aliases: Vec<Alias>,
    pub wall: bool,
}

pub fn receiver(index: usize) -> [f32; 3] {
    [
        (index % 8) as f32 - 3.5,
        0.0,
        (index / 8) as f32 * 2.0 - 3.0,
    ]
}

// Deliberately a simple experimental receiver-aware proposal, not a port of the old tree
// or PBRT's orientation bounds. Positive distance/radius regularization preserves support.
pub fn importance(node: LightNode, point: [f32; 3]) -> f32 {
    let delta: [f32; 3] =
        std::array::from_fn(|a| (node.bounds[0][a] + node.bounds[1][a]) * 0.5 - point[a]);
    let radius: f32 = (0..3)
        .map(|a| ((node.bounds[1][a] - node.bounds[0][a]) * 0.5).powi(2))
        .sum();
    let distance: f32 = delta.iter().map(|v| v * v).sum();
    node.power / distance.max(radius).max(1.0e-12)
}

fn tree_pdf(nodes: &[LightNode], point: [f32; 3], spatial: bool, count: usize) -> Vec<f64> {
    let mut result = vec![0.0; count];
    let mut stack = vec![(0usize, 1.0)];
    while let Some((at, probability)) = stack.pop() {
        let node = nodes[at];
        if let Some(leaf) = node.emitter() {
            result[leaf as usize] = probability;
        } else {
            let i = node.child as usize;
            let p = if spatial {
                let a = importance(nodes[i], point);
                let b = importance(nodes[i + 1], point);
                f64::from(a / (a + b))
            } else {
                f64::from(nodes[i].power) / f64::from(node.power)
            };
            stack.push((i, probability * p));
            stack.push((i + 1, probability * (1.0 - p)));
        }
    }
    result
}

impl Fixture {
    pub fn new(name: &str) -> Result<Self, String> {
        let count: usize = match name {
            "uniform" | "near_far" | "occluded" => 1024,
            "many" | "many_strips" => 200_704,
            _ => {
                return Err(format!(
                    "Unknown fixture {name}; use uniform, near_far, occluded, many or many_strips"
                ));
            }
        };
        let side = count.isqrt();
        let mut emitters = Vec::with_capacity(count);
        for i in 0..count {
            // Real local light pages cover compact world regions. Keep an explicit stripe
            // control: identical lights, different page membership / bounding volumes.
            let (x, z) = if name == "many" {
                let tile = i / PAGE_SIZE;
                (
                    (tile % (side / 16)) * 16 + i % 16,
                    (tile / (side / 16)) * 16 + (i / 16) % 16,
                )
            } else {
                (i % side, i / side)
            };
            let x = x as f32 - (side as f32 - 1.0) * 0.5;
            let z = z as f32 - (side as f32 - 1.0) * 0.5;
            let (position, radiance) = match name {
                "near_far" if i < 64 => ([(i % 8) as f32 - 3.5, 2.0, (i / 8) as f32 - 3.5], 1.0),
                "near_far" => ([x + 80.0, 16.0, z], 32.0),
                "occluded" => ([x, 3.0, z], if x > 0.0 { 16.0 } else { 1.0 }),
                "many" | "many_strips" => ([x, 3.0, z], (1 + i % 4) as f32),
                _ => ([x, 8.0, z], 1.0),
            };
            // Binary-exact area and powers also isolate lookup quality from weight-rounding.
            let half_size = 0.125;
            emitters.push(Emitter {
                position,
                half_size,
                radiance,
                power: radiance * 0.0625,
            });
        }
        let mut pages = Vec::new();
        for (page, emitters) in emitters.chunks(PAGE_SIZE).enumerate() {
            let roots: Vec<_> = emitters
                .iter()
                .enumerate()
                .map(|(i, e)| LightRoot {
                    bounds: [
                        [
                            e.position[0] - e.half_size,
                            e.position[1],
                            e.position[2] - e.half_size,
                        ],
                        [
                            e.position[0] + e.half_size,
                            e.position[1],
                            e.position[2] + e.half_size,
                        ],
                    ],
                    power: e.power,
                    slot: i as u32,
                })
                .collect();
            pages.push(Page {
                nodes: build_light_forest(&roots)?,
                first: page * PAGE_SIZE,
                count: emitters.len(),
                aliases: aliases(&roots.iter().map(|r| r.power).collect::<Vec<_>>()),
            });
        }
        let roots: Vec<_> = pages
            .iter()
            .enumerate()
            .map(|(i, p)| LightRoot {
                bounds: p.nodes[0].bounds,
                power: p.nodes[0].power,
                slot: i as u32,
            })
            .collect();
        Ok(Self {
            name: name.into(),
            wall: name == "occluded",
            emitters,
            pages,
            world: build_light_forest(&roots)?,
            aliases: aliases(&roots.iter().map(|r| r.power).collect::<Vec<_>>()),
        })
    }

    pub fn probabilities(&self, method: usize, point: [f32; 3]) -> Vec<f64> {
        let world = if method == 1 {
            alias_pdf(&self.aliases)
        } else {
            tree_pdf(&self.world, point, method == 2, self.pages.len())
        };
        let mut result = Vec::with_capacity(self.emitters.len());
        for (p, page) in world.into_iter().zip(&self.pages) {
            let local = if method == 1 {
                alias_pdf(&page.aliases)
            } else {
                tree_pdf(&page.nodes, point, method == 2, page.count)
            };
            result.extend(local.into_iter().map(|q| p * q));
        }
        result
    }

    pub fn theory(&self) -> Theory {
        let mut result = Theory {
            reference: [0.0; RECEIVERS],
            variance: [[0.0; RECEIVERS]; 3],
            optimal_variance: [0.0; RECEIVERS],
            quadrature_relative_gap: 0.0,
        };
        for r in 0..RECEIVERS {
            let point = receiver(r);
            let moments: Vec<_> = self
                .emitters
                .iter()
                .map(|e| {
                    if self.wall && (point[0] > 0.0) != (e.position[0] > 0.0) {
                        return (0.0, 0.0, 0.0);
                    }
                    let value = irradiance(*e, point);
                    let coarse = second_moment(*e, point, false);
                    let fine = second_moment(*e, point, true);
                    (value, fine, (fine - coarse).abs())
                })
                .collect();
            let reference: f64 = moments.iter().map(|m| m.0).sum();
            result.reference[r] = reference;
            let best: f64 = moments.iter().map(|m| m.1.sqrt()).sum();
            result.optimal_variance[r] = (best * best - reference * reference).max(0.0);
            for method in 0..3 {
                let pdf = self.probabilities(method, point);
                let second: f64 = moments.iter().zip(&pdf).map(|(m, p)| m.1 / p).sum();
                let gap: f64 = moments.iter().zip(&pdf).map(|(m, p)| m.2 / p).sum();
                result.variance[method][r] = (second - reference * reference).max(0.0);
                result.quadrature_relative_gap = result
                    .quadrature_relative_gap
                    .max(gap / second.max(f64::MIN_POSITIVE));
            }
        }
        result
    }
}

pub struct Theory {
    pub reference: [f64; RECEIVERS],
    pub variance: [[f64; RECEIVERS]; 3],
    pub optimal_variance: [f64; RECEIVERS],
    /// 2x2 vs 4x4 Gauss-Legendre second-moment difference, not a certified error bound.
    pub quadrature_relative_gap: f64,
}

// Closed-form boundary integral of h² / (x² + z² + h²)². This reference does not
// share the GPU estimator or sample sequence, and has no Monte Carlo noise floor.
fn irradiance(e: Emitter, receiver: [f32; 3]) -> f64 {
    let [x, h, z] = std::array::from_fn(|a| f64::from(e.position[a]) - f64::from(receiver[a]));
    let s = f64::from(e.half_size);
    let primitive = |x: f64, z: f64| {
        let a = (x * x + h * h).sqrt();
        let b = (z * z + h * h).sqrt();
        0.5 * (x / a * (z / a).atan() + z / b * (x / b).atan())
    };
    f64::from(e.radiance) / std::f64::consts::PI
        * (primitive(x + s, z + s) - primitive(x - s, z + s) - primitive(x + s, z - s)
            + primitive(x - s, z - s))
}

fn quadrature(e: Emitter, receiver: [f32; 3], fine: bool, square: bool) -> f64 {
    const G2: [(f64, f64); 2] = [(-0.5773502691896257, 1.0), (0.5773502691896257, 1.0)];
    const G4: [(f64, f64); 4] = [
        (-0.8611363115940526, 0.3478548451374538),
        (-0.3399810435848563, 0.6521451548625461),
        (0.3399810435848563, 0.6521451548625461),
        (0.8611363115940526, 0.3478548451374538),
    ];
    let nodes: &[(f64, f64)] = if fine { &G4 } else { &G2 };
    let [x, h, z] = std::array::from_fn(|a| f64::from(e.position[a]) - f64::from(receiver[a]));
    let s = f64::from(e.half_size);
    let scale = f64::from(e.radiance) * 4.0 * s * s * h * h / std::f64::consts::PI;
    let mut total = 0.0;
    for &(u, wu) in nodes {
        for &(v, wv) in nodes {
            let r2 = (x + u * s).powi(2) + h * h + (z + v * s).powi(2);
            let f = scale / (r2 * r2);
            total += if square { f * f } else { f } * wu * wv * 0.25;
        }
    }
    total
}

fn second_moment(e: Emitter, receiver: [f32; 3], fine: bool) -> f64 {
    quadrature(e, receiver, fine, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alias_mass_and_spatial_support_are_normalized() {
        for name in ["uniform", "near_far", "occluded"] {
            let fixture = Fixture::new(name).unwrap();
            for r in [0, 7, 19, 31] {
                let power = fixture.probabilities(0, receiver(r));
                for method in 0..3 {
                    let pdf = fixture.probabilities(method, receiver(r));
                    assert!((pdf.iter().sum::<f64>() - 1.0).abs() < 1e-12);
                    assert!(pdf.iter().all(|p| *p > 0.0 && p.is_finite()));
                    if method == 1 {
                        assert!(
                            pdf.iter()
                                .zip(&power)
                                .all(|(a, b)| (a / b - 1.0).abs() < 1e-6)
                        );
                    }
                }
            }
        }
        for weights in [&[1.0][..], &[1e-6, 1.0, 1e6], &[1.0, 2.0, 3.0, 7.0, 9.0]] {
            let pdf = alias_pdf(&aliases(weights));
            let sum: f64 = weights.iter().map(|&v| f64::from(v)).sum();
            for (p, &w) in pdf.iter().zip(weights) {
                assert!((p - f64::from(w) / sum).abs() < 1e-7);
            }
        }
    }

    #[test]
    fn analytic_reference_matches_independent_area_quadrature() {
        for position in [[0.0, 2.0, 0.0], [5.0, 3.0, 2.0], [-100.0, 8.0, 150.0]] {
            let e = Emitter {
                position,
                half_size: 0.125,
                radiance: 3.0,
                power: 0.1875,
            };
            let reference = irradiance(e, [0.0; 3]);
            let numerical = quadrature(e, [0.0; 3], true, false);
            assert!(reference > 0.0);
            assert!(
                (reference / numerical - 1.0).abs() < 2e-7,
                "{reference} {numerical}"
            );
        }
    }

    #[test]
    fn spatial_proposal_reduces_near_far_variance_without_changing_energy() {
        let theory = Fixture::new("near_far").unwrap().theory();
        assert!(theory.quadrature_relative_gap < 1e-4);
        for r in 0..RECEIVERS {
            assert!(theory.reference[r] > 0.0);
            assert!((theory.variance[0][r] / theory.variance[1][r] - 1.0).abs() < 1e-6);
            assert!(theory.variance[2][r] < theory.variance[0][r] * 0.1);
            assert!(theory.optimal_variance[r] <= theory.variance[2][r]);
        }
    }

    #[test]
    fn page_layout_control_preserves_the_light_field() {
        let tiled = Fixture::new("many").unwrap();
        let strips = Fixture::new("many_strips").unwrap();
        let keys = |f: &Fixture| {
            let mut values: Vec<_> = f
                .emitters
                .iter()
                .map(|e| (e.position.map(f32::to_bits), e.radiance.to_bits()))
                .collect();
            values.sort_unstable();
            values
        };
        assert_eq!(keys(&tiled), keys(&strips));
        assert!(tiled.pages.iter().all(|p| (0..3).all(|a| p.nodes[0].bounds[1][a] - p.nodes[0].bounds[0][a] <= 15.25)));
    }
}
