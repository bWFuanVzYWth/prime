//! Immutable page/reference publication for receiver-dependent tree proposals.
//! Both TREE and TREE_SPHERE store replay paths rather than fixed light probabilities.
use crate::{
    light_distance_cpu::{Node, Tree as DistanceTree},
    light_grid_cpu::Light,
    plan::Slots,
};
use prime_scene::surface::{LightNode, LightRoot, build_light_forest};
use std::{collections::BTreeMap, ops::Range};

#[derive(Clone, Copy)]
pub(crate) struct Input<'a> {
    pub key: u64,
    pub origin: [f64; 3],
    pub root: LightNode,
    pub lights: &'a [Light],
    pub paths: &'a [u32],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Page {
    pub key: u64,
    pub origin: [f64; 3],
    pub first: u32,
    pub count: u32,
    pub path: u32,
    root: LightNode,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub(crate) struct Reference {
    pub page: u32,
    pub emitter: u32,
    /// Replay trail in the selected local topology; never interpreted as a float.
    pub path: u32,
    pub inv_area: f32,
}

#[derive(Default)]
pub(crate) struct Tree {
    pub pages: Vec<Option<Page>>,
    pub refs: Vec<Reference>,
    pub world: Vec<Node>,
    pub power: f32,
    by_key: BTreeMap<u64, u32>,
    free_pages: Vec<u32>,
    slots: Slots,
    anchor: Option<[f64; 3]>,
}

pub(crate) struct Changes {
    pub changed: bool,
    pub ranges: Vec<Range<u32>>,
}

impl Tree {
    pub(crate) fn page(&self, key: u64) -> Option<&Page> {
        self.by_key
            .get(&key)
            .and_then(|&slot| self.pages[slot as usize].as_ref())
    }

    /// Complete immutable-page snapshot. Planning and validation precede live-table mutation.
    pub(crate) fn update(
        &mut self,
        inputs: &[Input<'_>],
        anchor: [f64; 3],
    ) -> Result<Changes, String> {
        let mut trace = prime_diagnostics::scope("lt.cpu");
        trace.fail();
        trace.count("pg", inputs.len() as u64);
        if anchor.iter().any(|x| !x.is_finite()) {
            return Err("Invalid light tree anchor".into());
        }
        let mut incoming = BTreeMap::new();
        for input in inputs {
            if incoming.insert(input.key, input).is_some() {
                return Err("Duplicate light tree page key".into());
            }
        }
        if self.anchor == Some(anchor)
            && incoming.len() == self.by_key.len()
            && incoming.values().all(|input| {
                self.page(input.key).is_some_and(|page| {
                    page.origin == input.origin && page.count as usize == input.lights.len()
                })
            })
        {
            trace.count("changed", 0);
            trace.succeed();
            return Ok(Changes {
                changed: false,
                ranges: Vec::new(),
            });
        }
        let mut slots = self.slots.clone();
        let mut pages = self.pages.clone();
        let mut free_pages = self.free_pages.clone();
        let mut by_key = self.by_key.clone();
        let mut changed = self.anchor != Some(anchor);
        for (&key, &slot) in &self.by_key {
            if !incoming.contains_key(&key) {
                let page = pages[slot as usize].take().unwrap();
                slots.release(page.first, page.count);
                free_pages.push(slot);
                by_key.remove(&key);
                changed = true;
            }
        }
        let mut added = Vec::new();
        for input in incoming.values() {
            if let Some(&slot) = by_key.get(&input.key) {
                let page = pages[slot as usize].as_mut().unwrap();
                if page.count as usize != input.lights.len() {
                    return Err("Immutable light tree page changed length".into());
                }
                if page.origin != input.origin {
                    if input.origin.iter().any(|x| !x.is_finite()) {
                        return Err("Invalid light tree origin".into());
                    }
                    page.origin = input.origin;
                    changed = true;
                }
                continue;
            }
            if input.lights.is_empty()
                || input.paths.len() != input.lights.len()
                || input.origin.iter().any(|x| !x.is_finite())
                || input.lights.iter().enumerate().any(|(index, light)| {
                    !light.inv_area.is_finite()
                        || light.inv_area <= 0.0
                        || input.paths[index] >> 27 > 27
                })
            {
                return Err("Invalid light tree page input".into());
            }
            let count = u32::try_from(input.lights.len()).map_err(|_| "Too many tree emitters")?;
            let first = slots.allocate(count)?;
            let slot = if let Some(slot) = free_pages.pop() {
                slot
            } else {
                let slot = u32::try_from(pages.len()).map_err(|_| "Too many light tree pages")?;
                pages.push(None);
                slot
            };
            pages[slot as usize] = Some(Page {
                key: input.key,
                origin: input.origin,
                first,
                count,
                path: 0,
                root: input.root,
            });
            by_key.insert(input.key, slot);
            added.push((slot, first, *input));
            changed = true;
        }
        if !changed {
            trace.count("changed", 0);
            trace.succeed();
            return Ok(Changes {
                changed: false,
                ranges: Vec::new(),
            });
        }
        let mut world_scope = prime_diagnostics::scope("lt.world");
        world_scope.fail();
        let roots: Vec<_> = pages
            .iter()
            .enumerate()
            .filter_map(|(slot, page)| {
                page.as_ref().map(|page| LightRoot {
                    bounds: page.root.bounds.map(|p| {
                        std::array::from_fn(|a| {
                            (page.origin[a] - anchor[a] + f64::from(p[a])) as f32
                        })
                    }),
                    power: page.root.power,
                    slot: slot as u32,
                })
            })
            .collect();
        let source = build_light_forest(&roots)?;
        let tree = DistanceTree::world(&source, pages.len())?;
        let world = tree.nodes;
        for (slot, page) in pages.iter_mut().enumerate() {
            if let Some(page) = page {
                page.path = tree.paths[slot];
            }
        }
        world_scope.count("nodes", world.len() as u64);
        world_scope.succeed();
        drop(world_scope);
        self.refs.resize(slots.end as usize, Reference::default());
        let mut ranges = Vec::with_capacity(added.len());
        for (slot, first, input) in added {
            for (index, light) in input.lights.iter().enumerate() {
                self.refs[first as usize + index] = Reference {
                    page: slot,
                    emitter: index as u32,
                    path: input.paths[index],
                    inv_area: light.inv_area,
                };
            }
            ranges.push(first..first + input.lights.len() as u32);
        }
        self.power = source.first().map_or(0.0, |node| node.power);
        self.pages = pages;
        self.world = world;
        self.by_key = by_key;
        self.free_pages = free_pages;
        self.slots = slots;
        self.anchor = Some(anchor);
        trace.count("changed", 1);
        trace.count("ranges", ranges.len() as u64);
        trace.succeed();
        Ok(Changes {
            changed: true,
            ranges,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn nodes(weights: &[f32]) -> Vec<LightNode> {
        build_light_forest(
            &weights
                .iter()
                .enumerate()
                .map(|(slot, &power)| LightRoot {
                    bounds: [[slot as f32, 0.0, 0.0], [slot as f32 + 0.5, 0.5, 0.5]],
                    power,
                    slot: slot as u32,
                })
                .collect::<Vec<_>>(),
        )
        .unwrap()
    }

    #[test]
    fn snapshots_preserve_ids_update_anchor_and_reuse_removed_ranges() {
        let source = nodes(&[1.0, 3.0]);
        let local = DistanceTree::world(&source, 2).unwrap();
        let lights = [
            Light {
                power: 1.0,
                inv_area: 0.5,
                ..Light::default()
            },
            Light {
                power: 3.0,
                inv_area: 0.25,
                ..Light::default()
            },
        ];
        let mut a = Input {
            key: 1,
            origin: [0.0; 3],
            root: source[0],
            lights: &lights,
            paths: &local.paths,
        };
        let b = Input {
            key: 2,
            origin: [4.0; 3],
            ..a
        };
        let mut tree = Tree::default();
        assert!(tree.update(&[a, b], [0.0; 3]).unwrap().changed);
        let first = tree.page(1).unwrap().first;
        let refs = tree.refs.clone();
        assert!(!tree.update(&[a, b], [0.0; 3]).unwrap().changed);
        assert!(tree.update(&[a, b], [10.0; 3]).unwrap().ranges.is_empty());
        assert_eq!(tree.refs, refs);
        a.origin = [8.0; 3];
        assert!(tree.update(&[a, b], [10.0; 3]).unwrap().ranges.is_empty());
        assert_eq!(tree.page(1).unwrap().first, first);
        tree.update(&[b], [10.0; 3]).unwrap();
        let c = Input { key: 3, ..a };
        tree.update(&[b, c], [10.0; 3]).unwrap();
        assert_eq!(tree.page(3).unwrap().first, first);
        for (slot, page) in tree.pages.iter().enumerate() {
            if let Some(page) = page {
                for (emitter, reference) in tree.refs
                    [page.first as usize..(page.first + page.count) as usize]
                    .iter()
                    .enumerate()
                {
                    assert_eq!(reference.page as usize, slot);
                    assert_eq!(reference.emitter as usize, emitter);
                    assert_eq!(reference.path, local.paths[emitter]);
                    let mut node = tree.world[0];
                    for depth in 0..page.path >> 27 {
                        node =
                            tree.world[node.child as usize + ((page.path >> depth) & 1) as usize];
                    }
                    assert_eq!(node.child, 1 << 31 | slot as u32);
                    assert_eq!(reference.inv_area, lights[emitter].inv_area);
                }
            }
        }
        tree.update(&[], [10.0; 3]).unwrap();
        assert!(tree.world.is_empty());
        assert!(tree.refs.is_empty());
        assert!(tree.page(2).is_none());
        assert!(tree.page(3).is_none());
    }

    #[test]
    fn invalid_snapshot_does_not_change_live_tables() {
        let source = nodes(&[1.0]);
        let lights = [Light {
            power: 1.0,
            inv_area: 1.0,
            ..Light::default()
        }];
        let input = Input {
            key: 1,
            origin: [0.0; 3],
            root: source[0],
            lights: &lights,
            paths: &[0],
        };
        let mut tree = Tree::default();
        tree.update(&[input], [0.0; 3]).unwrap();
        let bad = Input {
            key: 2,
            paths: &[u32::MAX],
            ..input
        };
        let before = tree.refs.clone();
        assert!(tree.update(&[bad], [0.0; 3]).is_err());
        assert!(tree.page(1).is_some());
        assert_eq!(tree.refs, before);
        let bad_root = Input {
            key: 2,
            root: LightNode {
                power: f32::NAN,
                ..input.root
            },
            ..input
        };
        assert!(tree.update(&[bad_root], [0.0; 3]).is_err());
        assert!(tree.page(1).is_some());
        assert_eq!(tree.refs, before);
        assert!(tree.update(&[input, input], [0.0; 3]).is_err());
    }

    /// Same deterministic synthetic source/updates for both production CPU builders. These
    /// timings exclude geometry compilation and Vulkan directory encoding, submission and GPU
    /// consumption. Run explicitly in release; retain every observation instead of only means.
    #[test]
    #[ignore = "explicit release CPU comparison; writes raw observations to artifacts"]
    fn cpu_sampler_comparison() {
        use crate::light_grid_cpu::{LightGrid, PageInput};
        use std::{fs, io::Write, time::Instant};

        struct Fixture {
            key: u64,
            origin: [f64; 3],
            lights: Vec<Light>,
            nodes: Vec<LightNode>,
            root: LightNode,
            paths: Vec<u32>,
            emitters: Vec<prime_scene::surface::Emitter>,
            source_ns: u128,
        }
        impl Fixture {
            fn new(key: u64, count: usize) -> Self {
                // Fixed integer seed/mix; shared geometry/weights are prepared outside timings.
                let mut seed = 0x6a09e667f3bcc909u64 ^ key;
                let mut lights = Vec::with_capacity(count);
                let mut roots = Vec::with_capacity(count);
                for slot in 0..count {
                    seed ^= seed << 13;
                    seed ^= seed >> 7;
                    seed ^= seed << 17;
                    let center = [
                        1.0 + (seed & 255) as f32 / 18.0,
                        1.0 + ((seed >> 8) & 255) as f32 / 18.0,
                        1.0 + ((seed >> 16) & 255) as f32 / 18.0,
                    ];
                    let power = 0.25 + ((seed >> 24) & 255) as f32 / 16.0;
                    lights.push(Light {
                        center,
                        power,
                        inv_area: 1.0,
                        extent: 1.0 / 6.0,
                    });
                    roots.push(LightRoot {
                        bounds: [center.map(|x| x - 0.5), center.map(|x| x + 0.5)],
                        power,
                        slot: slot as u32,
                    });
                }
                let emitters = lights
                    .iter()
                    .enumerate()
                    .map(|(quad, light)| {
                        let c = light.center;
                        prime_scene::surface::Emitter {
                            quad: quad as u32,
                            positions: [
                                [c[0] - 0.5, c[1] - 0.5, c[2]],
                                [c[0] + 0.5, c[1] - 0.5, c[2]],
                                [c[0] + 0.5, c[1] + 0.5, c[2]],
                                [c[0] - 0.5, c[1] + 0.5, c[2]],
                            ],
                            first_fraction: 0.5,
                            radiance: [light.power; 3],
                            area: 1.,
                            power: light.power,
                            two_sided: false,
                        }
                    })
                    .collect();
                let started = Instant::now();
                let nodes = build_light_forest(&roots).unwrap();
                let source_ns = started.elapsed().as_nanos();
                Self {
                    key,
                    origin: [(key % 16) as f64 * 16.0, 0.0, (key / 16) as f64 * 16.0],
                    lights,
                    root: nodes[0],
                    nodes,
                    paths: Vec::new(),
                    emitters,
                    source_ns,
                }
            }
            fn local_tree(&mut self) {
                self.paths = DistanceTree::local(&self.nodes, &self.emitters)
                    .unwrap()
                    .paths;
            }
        }
        let directory = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../artifacts/light-sampler-restoration");
        fs::create_dir_all(&directory).unwrap();
        let path = directory.join("cpu-comparison.csv");
        let mut file = fs::File::create(&path).unwrap();
        writeln!(file, "pages,density,round,method,action,ns,live_pages,live_emitters,cells,cell_entries,world_nodes").unwrap();
        for page_count in [16, 64, 256] {
            for density in [1, 16, 64] {
                let replaced = (page_count / 8).max(1);
                for round in 0..6 {
                    // Alternate method order to reduce a fixed cache/thermal ordering bias.
                    for method in if round % 2 == 0 {
                        ["grid", "tree"]
                    } else {
                        ["tree", "grid"]
                    } {
                        let mut fixtures: Vec<_> = (1..=page_count + replaced)
                            .map(|key| Fixture::new(key as u64, density))
                            .collect();
                        let mut live: Vec<_> = (0..page_count).collect();
                        let mut grid = LightGrid::default();
                        let mut tree = Tree::default();
                        for (action, range) in [
                            ("source_init", 0..page_count),
                            ("source_add", page_count..page_count + replaced),
                        ] {
                            let ns: u128 = fixtures[range.clone()]
                                .iter()
                                .map(|fixture| fixture.source_ns)
                                .sum();
                            writeln!(file, "{page_count},{density},{round},shared_{method},{action},{ns},{},{},0,0,0", range.len(), range.len() * density).unwrap();
                        }
                        for action in [
                            "init",
                            "unchanged",
                            "remove",
                            "add",
                            "move",
                            "unchanged_after_move",
                        ] {
                            match action {
                                "remove" => live.truncate(page_count - replaced),
                                "add" => live.extend(page_count..page_count + replaced),
                                "move" => fixtures[live[0]].origin[0] += 1.0,
                                _ => {}
                            }
                            let started = Instant::now();
                            let (ns, cells, entries, world_nodes) = if method == "tree" {
                                if action == "init" {
                                    for fixture in &mut fixtures[..page_count] {
                                        fixture.local_tree();
                                    }
                                } else if action == "add" {
                                    for fixture in &mut fixtures[page_count..] {
                                        fixture.local_tree();
                                    }
                                }
                                let inputs: Vec<_> = live
                                    .iter()
                                    .map(|&index| {
                                        let fixture = &fixtures[index];
                                        Input {
                                            key: fixture.key,
                                            origin: fixture.origin,
                                            root: fixture.root,
                                            lights: &fixture.lights,
                                            paths: &fixture.paths,
                                        }
                                    })
                                    .collect();
                                tree.update(&inputs, [0.0; 3]).unwrap();
                                (started.elapsed().as_nanos(), 0, 0, tree.world.len())
                            } else {
                                let inputs: Vec<_> = live
                                    .iter()
                                    .map(|&index| {
                                        let fixture = &fixtures[index];
                                        PageInput {
                                            key: fixture.key,
                                            origin: fixture.origin,
                                            lights: &fixture.lights,
                                        }
                                    })
                                    .collect();
                                grid.update(&inputs).unwrap();
                                let ns = started.elapsed().as_nanos();
                                (
                                    ns,
                                    grid.cells.len(),
                                    grid.cells.values().map(Vec::len).sum(),
                                    0,
                                )
                            };
                            std::hint::black_box((&grid, &tree));
                            writeln!(file, "{page_count},{density},{round},{method},{action},{ns},{},{},{cells},{entries},{world_nodes}",
                                live.len(), live.len() * density).unwrap();
                        }
                    }
                }
            }
        }
        println!("Raw CPU comparison: {}", path.display());
        println!(
            "Fixed seed 0x6a09e667f3bcc909, serial builders/one calling thread; includes local distance metadata, world tree and refs; shared historical local median construction is in separate source rows; excludes geometry compilation, Vulkan directory encoding/hash staging, uploads and GPU quality/timing."
        );
    }
}
