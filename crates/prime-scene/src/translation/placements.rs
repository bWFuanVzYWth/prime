//! Stable dense slots. A removal moves at most one surviving placement.
use super::objects::{ObjectKey, Placement};
use crate::{scene::Instance, spatial::Cell};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(super) enum Identity {
    Instance(u64),
    Raw(ObjectKey),
}

#[derive(Clone, Copy, Debug)]
pub struct PlacementChange {
    pub slot: usize,
    pub transform: bool,
    pub material: bool,
}

struct Member {
    source: Instance,
    cell: Cell,
    triangles: u64,
}

#[derive(Default)]
pub(super) struct Placements {
    pub values: Vec<Placement>,
    identities: Vec<Identity>,
    slots: BTreeMap<Identity, usize>,
    members: BTreeMap<u64, Member>,
    pub cells: BTreeMap<Cell, BTreeSet<u64>>,
    pub dependencies: BTreeMap<u64, BTreeSet<u64>>,
    pub triangles: u64,
}

impl Placements {
    pub fn source(&self, id: u64) -> Option<&Instance> {
        self.members.get(&id).map(|m| &m.source)
    }
    pub fn instance_ids(&self) -> impl Iterator<Item = u64> + '_ {
        self.members.keys().copied()
    }
    pub fn raw_keys(&self) -> impl Iterator<Item = ObjectKey> + '_ {
        self.slots.keys().filter_map(|id| {
            if let Identity::Raw(key) = id {
                Some(*key)
            } else {
                None
            }
        })
    }
    pub fn set_instance(
        &mut self,
        id: u64,
        source: &Instance,
        spatial: Option<(Cell, [f32; 12])>,
        triangles: u64,
        changes: &mut Vec<PlacementChange>,
        force: bool,
    ) -> Result<(), String> {
        let previous = self.members.get(&id);
        let cell = spatial
            .map(|p| p.0)
            .or_else(|| previous.map(|p| p.cell))
            .expect("new members have a validated pose");
        let transform = spatial
            .map(|p| p.1)
            .unwrap_or_else(|| self.values[self.slots[&Identity::Instance(id)]].transform);
        let old_triangles = previous.map_or(0, |m| m.triangles);
        self.triangles = (self.triangles - old_triangles)
            .checked_add(triangles)
            .ok_or("Instanced triangle count overflow")?;
        if let Some(old) = previous {
            if old.cell != cell {
                remove_member(&mut self.cells, old.cell, id);
            }
            if old.source.prototype_id != source.prototype_id {
                remove_member(&mut self.dependencies, old.source.prototype_id, id);
            }
        }
        if previous.is_none_or(|old| old.cell != cell) {
            self.cells.entry(cell).or_default().insert(id);
        }
        if previous.is_none_or(|old| old.source.prototype_id != source.prototype_id) {
            self.dependencies
                .entry(source.prototype_id)
                .or_default()
                .insert(id);
        }
        self.members.insert(
            id,
            Member {
                source: source.clone(),
                cell,
                triangles,
            },
        );
        self.set(
            Identity::Instance(id),
            Placement {
                key: ObjectKey::Prototype(source.prototype_id),
                transform,
                texture_id: source.texture_id,
                flags: source.flags,
                tint: source.tint,
                uv: source.uv_transform,
            },
            changes,
            force,
        );
        Ok(())
    }
    pub fn set(
        &mut self,
        id: Identity,
        value: Placement,
        changes: &mut Vec<PlacementChange>,
        force: bool,
    ) {
        if let Some(&slot) = self.slots.get(&id) {
            let old = self.values[slot];
            let transform = force
                || old.key != value.key
                || old.transform != value.transform
                || old.flags != value.flags;
            let material = force
                || old.key != value.key
                || old.texture_id != value.texture_id
                || old.flags != value.flags
                || old.tint != value.tint
                || old.uv != value.uv;
            if transform || material {
                changes.push(PlacementChange {
                    slot,
                    transform,
                    material,
                });
            }
            self.values[slot] = value;
        } else {
            let slot = self.values.len();
            self.slots.insert(id, slot);
            self.identities.push(id);
            self.values.push(value);
            changes.push(PlacementChange {
                slot,
                transform: true,
                material: true,
            });
        }
    }
    pub fn remove(&mut self, id: Identity, changes: &mut Vec<PlacementChange>) {
        let Some(slot) = self.slots.remove(&id) else {
            return;
        };
        self.values.swap_remove(slot);
        self.identities.swap_remove(slot);
        if slot < self.values.len() {
            self.slots.insert(self.identities[slot], slot);
            changes.push(PlacementChange {
                slot,
                transform: true,
                material: true,
            });
        }
        if let Identity::Instance(id) = id {
            let old = self.members.remove(&id).unwrap();
            self.triangles -= old.triangles;
            remove_member(&mut self.cells, old.cell, id);
            remove_member(&mut self.dependencies, old.source.prototype_id, id);
        }
    }
    pub fn clear(&mut self) {
        self.values.clear();
        self.identities.clear();
        self.slots.clear();
        self.members.clear();
        self.cells.clear();
        self.dependencies.clear();
        self.triangles = 0;
    }
}

fn remove_member<K: Ord + Copy>(map: &mut BTreeMap<K, BTreeSet<u64>>, key: K, id: u64) {
    let members = map.get_mut(&key).expect("registered dependency");
    members.remove(&id);
    if members.is_empty() {
        map.remove(&key);
    }
}

pub(super) fn coalesce(changes: &mut Vec<PlacementChange>, count: usize) {
    changes.retain(|c| c.slot < count);
    changes.sort_unstable_by_key(|c| c.slot);
    changes.dedup_by(|later, first| {
        if later.slot != first.slot {
            return false;
        }
        first.transform |= later.transform;
        first.material |= later.material;
        true
    });
}
