//! Stable page/reference publication for receiver-dependent tree proposals.
//! TREE stores replay paths rather than fixed light probabilities.
use crate::{
    light_distance_cpu::{Node, Tree as DistanceTree},
    plan::Slots,
};
use prime_scene::surface::{LightNode, LightRoot, build_light_forest};
use std::{collections::BTreeMap, ops::Range};

#[derive(Clone, Copy)]
pub(crate) struct Input<'a> {
    pub key: u64,
    pub origin: [f64; 3],
    pub root: LightNode,
    pub inverse_areas: &'a [f32],
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
    pub world_changed: bool,
    pub ranges: Vec<Range<u32>>,
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

fn same_root(a: LightNode, b: LightNode) -> bool {
    // Local child indices are consumed through the current node buffer and replay paths;
    // only this page's bounds/power participate in constructing the world tree.
    a.bounds == b.bounds && a.power == b.power
}

impl Tree {
    pub(crate) fn page(&self, key: u64) -> Option<&Page> {
        self.by_key
            .get(&key)
            .and_then(|&slot| self.pages[slot as usize].as_ref())
    }

    fn references_match(&self, page: &Page, input: &Input<'_>) -> bool {
        self.refs[page.first as usize..(page.first + page.count) as usize]
            .iter()
            .zip(input.paths.iter().zip(input.inverse_areas))
            .all(|(reference, (&path, &inv_area))| {
                reference.path == path && reference.inv_area == inv_area
            })
    }

    /// Complete stable-page snapshot. Planning and validation precede live-table mutation.
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
            if input.inverse_areas.is_empty()
                || input.paths.len() != input.inverse_areas.len()
                || input.origin.iter().any(|x| !x.is_finite())
                || !input.root.power.is_finite()
                || input.root.power <= 0.0
                || (0..3).any(|a| {
                    !input.root.bounds[0][a].is_finite()
                        || !input.root.bounds[1][a].is_finite()
                        || input.root.bounds[0][a] > input.root.bounds[1][a]
                })
                || input
                    .inverse_areas
                    .iter()
                    .enumerate()
                    .any(|(index, inv_area)| {
                        !inv_area.is_finite() || *inv_area <= 0.0 || input.paths[index] >> 27 > 27
                    })
            {
                return Err("Invalid light tree page input".into());
            }
            if incoming.insert(input.key, input).is_some() {
                return Err("Duplicate light tree page key".into());
            }
        }
        if self.anchor == Some(anchor)
            && incoming.len() == self.by_key.len()
            && incoming.values().all(|input| {
                self.page(input.key).is_some_and(|page| {
                    page.origin == input.origin
                        && page.count as usize == input.inverse_areas.len()
                        && same_root(page.root, input.root)
                        && self.references_match(page, input)
                })
            })
        {
            trace.count("changed", 0);
            trace.succeed();
            return Ok(Changes {
                changed: false,
                world_changed: false,
                ranges: Vec::new(),
            });
        }
        let mut slots = self.slots.clone();
        let mut pages = self.pages.clone();
        let mut free_pages = self.free_pages.clone();
        let mut by_key = self.by_key.clone();
        let mut world_changed = self.anchor != Some(anchor);
        for (&key, &slot) in &self.by_key {
            if !incoming.contains_key(&key) {
                let page = pages[slot as usize].take().unwrap();
                slots.release(page.first, page.count);
                free_pages.push(slot);
                by_key.remove(&key);
                world_changed = true;
            }
        }
        let mut updated = Vec::new();
        for input in incoming.values() {
            if let Some(&slot) = by_key.get(&input.key) {
                let page = pages[slot as usize].as_mut().unwrap();
                if page.count as usize != input.inverse_areas.len() {
                    return Err("Immutable light tree page changed length".into());
                }
                if page.origin != input.origin {
                    page.origin = input.origin;
                    world_changed = true;
                }
                if !same_root(page.root, input.root) {
                    page.root = input.root;
                    world_changed = true;
                }
                if !self.references_match(page, input) {
                    updated.push((slot, page.first, *input));
                }
                continue;
            }
            let count =
                u32::try_from(input.inverse_areas.len()).map_err(|_| "Too many tree emitters")?;
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
            updated.push((slot, first, *input));
            world_changed = true;
        }
        if !world_changed && updated.is_empty() {
            trace.count("changed", 0);
            trace.succeed();
            return Ok(Changes {
                changed: false,
                world_changed: false,
                ranges: Vec::new(),
            });
        }
        let rebuilt_world = if world_changed {
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
            Some((world, source.first().map_or(0.0, |node| node.power)))
        } else {
            None
        };
        self.refs.resize(slots.end as usize, Reference::default());
        let mut ranges = Vec::with_capacity(updated.len());
        for (slot, first, input) in updated {
            for (index, inv_area) in input.inverse_areas.iter().enumerate() {
                self.refs[first as usize + index] = Reference {
                    page: slot,
                    emitter: index as u32,
                    path: input.paths[index],
                    inv_area: *inv_area,
                };
            }
            ranges.push(first..first + input.inverse_areas.len() as u32);
        }
        if let Some((world, power)) = rebuilt_world {
            self.world = world;
            self.power = power;
        }
        self.pages = pages;
        self.by_key = by_key;
        self.free_pages = free_pages;
        self.slots = slots;
        self.anchor = Some(anchor);
        trace.count("changed", 1);
        trace.count("ranges", ranges.len() as u64);
        trace.succeed();
        Ok(Changes {
            changed: true,
            world_changed,
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
        let inverse_areas = [0.5, 0.25];
        let mut a = Input {
            key: 1,
            origin: [0.0; 3],
            root: source[0],
            inverse_areas: &inverse_areas,
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
                    assert_eq!(reference.inv_area, inverse_areas[emitter]);
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
        let inverse_areas = [1.0];
        let input = Input {
            key: 1,
            origin: [0.0; 3],
            root: source[0],
            inverse_areas: &inverse_areas,
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

    #[test]
    fn same_key_replaces_local_paths_and_areas_without_rebuilding_world() {
        let source = nodes(&[1.0, 3.0]);
        let local = DistanceTree::world(&source, 2).unwrap();
        let input = Input {
            key: 9,
            origin: [0.0; 3],
            root: source[0],
            inverse_areas: &[0.5, 0.25],
            paths: &local.paths,
        };
        let mut tree = Tree::default();
        tree.update(&[input], [0.0; 3]).unwrap();
        let page = *tree.page(9).unwrap();
        let world = crate::light_distance_cpu::node_bytes(&tree.world);
        let paths = [local.paths[1], local.paths[0]];
        let replacement = Input {
            paths: &paths,
            inverse_areas: &[0.125, 0.75],
            ..input
        };
        let changes = tree.update(&[replacement], [0.0; 3]).unwrap();
        assert!(changes.changed);
        assert!(!changes.world_changed);
        assert_eq!(changes.ranges, [page.first..page.first + page.count]);
        assert_eq!(crate::light_distance_cpu::node_bytes(&tree.world), world);
        assert_eq!(tree.page(9).unwrap().first, page.first);
        assert_eq!(tree.page(9).unwrap().path, page.path);
        for (index, reference) in tree.refs.iter().enumerate() {
            assert_eq!(reference.page, 0);
            assert_eq!(reference.emitter, index as u32);
            assert_eq!(reference.path, paths[index]);
            assert_eq!(reference.inv_area, replacement.inverse_areas[index]);
        }
        let unchanged = tree.update(&[replacement], [0.0; 3]).unwrap();
        assert!(!unchanged.changed);
        assert!(!unchanged.world_changed);
        assert!(unchanged.ranges.is_empty());
    }

    #[test]
    fn same_key_replaces_world_root_without_reallocating_references() {
        let source = nodes(&[1.0]);
        let input = Input {
            key: 9,
            origin: [0.0; 3],
            root: source[0],
            inverse_areas: &[0.5],
            paths: &[0],
        };
        let mut tree = Tree::default();
        tree.update(&[input], [0.0; 3]).unwrap();
        let first = tree.page(9).unwrap().first;
        let refs = tree.refs.clone();
        let replacement = Input {
            root: LightNode {
                bounds: [[10.0, 20.0, 30.0], [12.0, 24.0, 36.0]],
                power: 7.0,
                ..input.root
            },
            ..input
        };
        let changes = tree.update(&[replacement], [0.0; 3]).unwrap();
        assert!(changes.changed);
        assert!(changes.world_changed);
        assert!(changes.ranges.is_empty());
        assert_eq!(tree.page(9).unwrap().first, first);
        assert_eq!(tree.refs, refs);
        assert_eq!(tree.power, 7.0);
        assert_eq!(tree.world[0].power, 7.0);
        assert_eq!(tree.world[0].center, [11.0, 22.0, 33.0]);
        assert_eq!(tree.world[0].child, 1 << 31);
    }

    #[test]
    fn invalid_same_key_replacement_does_not_mutate_live_publication() {
        let source = nodes(&[1.0]);
        let input = Input {
            key: 9,
            origin: [0.0; 3],
            root: source[0],
            inverse_areas: &[0.5],
            paths: &[0],
        };
        let mut tree = Tree::default();
        tree.update(&[input], [0.0; 3]).unwrap();
        let refs = tree.refs.clone();
        let world = crate::light_distance_cpu::node_bytes(&tree.world);
        for replacement in [
            Input {
                paths: &[u32::MAX],
                ..input
            },
            Input {
                inverse_areas: &[f32::NAN],
                ..input
            },
            Input {
                inverse_areas: &[1.0, 2.0],
                paths: &[0, 0],
                ..input
            },
            Input {
                root: LightNode {
                    power: f32::NAN,
                    ..input.root
                },
                ..input
            },
        ] {
            assert!(tree.update(&[replacement], [0.0; 3]).is_err());
            assert_eq!(tree.refs, refs);
            assert_eq!(crate::light_distance_cpu::node_bytes(&tree.world), world);
            assert_eq!(tree.page(9).unwrap().count, 1);
            assert_eq!(tree.page(9).unwrap().first, 0);
        }
        assert!(!tree.update(&[input], [0.0; 3]).unwrap().changed);
    }
}
