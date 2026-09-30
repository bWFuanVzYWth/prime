use super::SurfaceFace;

const LEAF: u32 = 1 << 31;

/// One local tree's spatial summary and caller-assigned consumer slot. The builder stores slots
/// directly; it does not invent a second identity mapping when partitioning the world tree.
#[derive(Clone, Copy, Debug)]
pub struct LightRoot {
    pub bounds: [[f32; 3]; 2],
    pub power: f32,
    pub slot: u32,
}

/// Rebuild the small top level only on a scene publication. Static local trees stay immutable.
pub fn build_light_forest(roots: &[LightRoot]) -> Result<Vec<LightNode>, String> {
    if roots.len() > LEAF as usize / 2 {
        return Err("Too many nodes in one light forest".into());
    }
    if roots.is_empty() {
        return Ok(Vec::new());
    }
    let mut order: Vec<_> = (0..roots.len()).collect();
    for root in roots {
        if root.slot >= LEAF
            || !root.power.is_finite()
            || root.power <= 0.0
            || (0..3).any(|i| {
                !root.bounds[0][i].is_finite()
                    || !root.bounds[1][i].is_finite()
                    || root.bounds[0][i] > root.bounds[1][i]
            })
        {
            return Err("Invalid light root".into());
        }
    }
    let mut slots: Vec<_> = roots.iter().map(|r| r.slot).collect();
    slots.sort_unstable();
    if slots.windows(2).any(|x| x[0] == x[1]) {
        return Err("Duplicate light root slot".into());
    }
    let mut nodes = Vec::with_capacity(roots.len() * 2 - 1);
    nodes.push(LightNode::default());
    build_node(&mut nodes, roots, 0, &mut order)?;
    Ok(nodes)
}

fn build_node(
    nodes: &mut Vec<LightNode>,
    roots: &[LightRoot],
    at: usize,
    order: &mut [usize],
) -> Result<(), String> {
    let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
    for &i in order.iter() {
        for a in 0..3 {
            bounds[0][a] = bounds[0][a].min(roots[i].bounds[0][a]);
            bounds[1][a] = bounds[1][a].max(roots[i].bounds[1][a]);
        }
    }
    if order.len() == 1 {
        nodes[at] = LightNode {
            bounds,
            power: roots[order[0]].power,
            child: LEAF | roots[order[0]].slot,
        };
    } else {
        let axis = (0..3)
            .max_by(|&a, &b| {
                (bounds[1][a] - bounds[0][a]).total_cmp(&(bounds[1][b] - bounds[0][b]))
            })
            .unwrap();
        let center =
            |id: usize| f64::from(roots[id].bounds[0][axis]) + f64::from(roots[id].bounds[1][axis]);
        let middle = order.len() / 2;
        order.select_nth_unstable_by(middle, |&a, &b| {
            center(a)
                .total_cmp(&center(b))
                .then(roots[a].slot.cmp(&roots[b].slot))
        });
        let child = nodes.len();
        nodes.extend([LightNode::default(); 2]);
        let (left, right) = order.split_at_mut(middle);
        build_node(nodes, roots, child, left)?;
        build_node(nodes, roots, child + 1, right)?;
        let power = nodes[child].power + nodes[child + 1].power;
        if !power.is_finite() {
            return Err("Light tree aggregate power overflow".into());
        }
        nodes[at] = LightNode {
            bounds,
            power,
            child: child as u32,
        };
    }
    Ok(())
}

/// Fixed 32-byte node: bounds and power, adjacent children or one emitter index. No encoded
/// traversal trail or relocatable pointers. The baseline proposal is power weighted; spatial
/// bounds are available for a later measured importance heuristic, not silently used in PDFs.
#[derive(Clone, Copy, Debug, Default)]
pub struct LightNode {
    pub bounds: [[f32; 3]; 2],
    pub power: f32,
    pub child: u32,
}
impl LightNode {
    pub fn emitter(self) -> Option<u32> {
        (self.child & LEAF != 0).then_some(self.child & !LEAF)
    }
}

/// Direct sampling record. A selected light never fetches a surface key, template or corners.
#[derive(Clone, Debug)]
pub struct Emitter {
    pub quad: u32,
    pub positions: [[f32; 3]; 4],
    pub first_fraction: f32,
    pub radiance: [f32; 3],
    pub area: f32,
    pub power: f32,
    pub two_sided: bool,
}
impl Emitter {
    pub fn sample_position(&self, sample: [f32; 2]) -> [f32; 3] {
        let (half, u) = if self.first_fraction == 1.0 || sample[0] < self.first_fraction {
            (0, sample[0] / self.first_fraction)
        } else {
            (
                1,
                (sample[0] - self.first_fraction) / (1.0 - self.first_fraction),
            )
        };
        let corners = [[0, 1, 2], [2, 3, 0]][half];
        let s = u.sqrt();
        let b = [1.0 - s, s * (1.0 - sample[1]), s * sample[1]];
        std::array::from_fn(|i| (0..3).map(|c| b[c] * self.positions[corners[c]][i]).sum())
    }
}

#[derive(Debug, Default)]
pub struct LightTree {
    pub nodes: Vec<LightNode>,
    pub emitters: Vec<Emitter>,
}
impl LightTree {
    pub(super) fn build(quads: &mut [SurfaceFace]) -> Result<Self, String> {
        let mut tree = Self::default();
        for (quad, t) in quads.iter_mut().enumerate() {
            t.emitter = None;
            t.emitter_area_weight = 0.0;
            if t.emission
                .radiance
                .iter()
                .any(|x| !x.is_finite() || *x < 0.0)
            {
                return Err("Invalid linear emission radiance".into());
            }
            let secondary = t
                .detail
                .as_ref()
                .map(|d| d.layer.emission)
                .unwrap_or_default();
            if secondary.radiance.iter().any(|x| !x.is_finite() || *x < 0.) {
                return Err("Invalid layer emission".into());
            }
            if t.emission.radiance == [0.0; 3] && secondary.radiance == [0.0; 3] {
                continue;
            }
            let areas = t.geometry.areas();
            let total = areas[0] + areas[1];
            if total == 0.0 {
                continue; // zero area, including the repeated corner, has no sampling mass
            }
            let area = total as f32;
            let first_fraction = (areas[0] / total) as f32;
            if areas[0] > 0.0 && areas[1] > 0.0 && !(first_fraction > 0.0 && first_fraction < 1.0) {
                return Err("Emitter half-area probability is outside the f32 light ABI".into());
            }
            let luminance = |emission: super::Emission| {
                emission
                    .radiance
                    .iter()
                    .zip([0.2126, 0.7152, 0.0722])
                    .map(|(&x, w)| f64::from(x) * w)
                    .sum::<f64>()
                    * if emission.two_sided { 2. } else { 1. }
            };
            // Proposal weight is an upper bound for layered coverage. The actual sampled
            // radiance always comes from the same selected layer as a traced hit.
            let power = (f64::from(area) * (luminance(t.emission) + luminance(secondary))) as f32;
            if !area.is_finite() || area <= 0.0 || !power.is_finite() || power <= 0.0 {
                return Err("Emitting triangle area/power is outside the f32 light ABI".into());
            }
            let index = u32::try_from(tree.emitters.len()).map_err(|_| "Too many emitters")?;
            if index >= LEAF / 2 {
                return Err("Too many light nodes".into());
            }
            t.emitter = Some(index);
            t.emitter_area_weight = power / area;
            if !t.emitter_area_weight.is_finite() || t.emitter_area_weight <= 0.0 {
                return Err("Emitter area density is outside the f32 light ABI".into());
            }
            tree.emitters.push(Emitter {
                quad: u32::try_from(quad).map_err(|_| "Too many surface quads")?,
                positions: t.geometry.positions,
                first_fraction,
                radiance: t.emission.radiance,
                area,
                power,
                two_sided: t.emission.two_sided,
            });
        }
        if !tree.emitters.is_empty() {
            let roots: Vec<_> = tree
                .emitters
                .iter()
                .enumerate()
                .map(|(i, e)| {
                    let bounds = [
                        std::array::from_fn(|a| {
                            e.positions
                                .iter()
                                .map(|p| p[a])
                                .fold(f32::INFINITY, f32::min)
                        }),
                        std::array::from_fn(|a| {
                            e.positions
                                .iter()
                                .map(|p| p[a])
                                .fold(f32::NEG_INFINITY, f32::max)
                        }),
                    ];
                    LightRoot {
                        bounds,
                        power: e.power,
                        slot: i as u32,
                    }
                })
                .collect();
            tree.nodes = build_light_forest(&roots)?;
        }
        Ok(tree)
    }

    /// Selection and reverse evaluation use the same represented emitter/root weights.
    pub fn select(&self, sample: f32) -> Option<(u32, f32)> {
        if !(0.0..1.0).contains(&sample) {
            return None;
        }
        let root = *self.nodes.first()?;
        let mut weight = sample * root.power;
        let mut node = root;
        loop {
            if let Some(emitter) = node.emitter() {
                return Some((emitter, self.selection_pdf(emitter)));
            }
            let left = self.nodes[node.child as usize];
            if weight < left.power {
                node = left;
            } else {
                weight -= left.power;
                node = self.nodes[node.child as usize + 1];
            }
        }
    }

    pub fn selection_pdf(&self, emitter: u32) -> f32 {
        self.emitters
            .get(emitter as usize)
            .map_or(0.0, |e| e.power / self.nodes[0].power)
    }

    pub fn area_pdf(&self, emitter: u32) -> f32 {
        self.emitters
            .get(emitter as usize)
            .map_or(0.0, |e| (e.power / e.area) / self.nodes[0].power)
    }
}
