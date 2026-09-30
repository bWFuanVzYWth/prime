//! MC coordinates and active source demand are owned here, never in the Java router.
use crate::wire::Reader;
use std::collections::{BTreeSet, HashSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(crate) struct Section(pub i32, pub i32, pub i32);
impl Section {
    pub fn key(self) -> u64 {
        ((self.0 as u64 & 0x3fffff) << 42)
            | ((self.2 as u64 & 0x3fffff) << 20)
            | (self.1 as u64 & 0xfffff)
    }
    pub fn origin(self) -> [f64; 3] {
        [
            self.0 as f64 * 16.0,
            self.1 as f64 * 16.0,
            self.2 as f64 * 16.0,
        ]
    }
    pub fn halo(self) -> [Self; 27] {
        std::array::from_fn(|i| {
            Self(
                self.0 + i as i32 % 3 - 1,
                self.1 + i as i32 / 9 - 1,
                self.2 + (i as i32 / 3) % 3 - 1,
            )
        })
    }
    #[cfg(test)]
    pub fn neighbors(self) -> [Self; 6] {
        [
            Self(self.0, self.1 - 1, self.2),
            Self(self.0, self.1 + 1, self.2),
            Self(self.0, self.1, self.2 - 1),
            Self(self.0, self.1, self.2 + 1),
            Self(self.0 - 1, self.1, self.2),
            Self(self.0 + 1, self.1, self.2),
        ]
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Window {
    pub x0: i32,
    pub x1: i32,
    pub z0: i32,
    pub z1: i32,
}
impl Window {
    fn contains(self, x: i32, z: i32) -> bool {
        x >= self.x0 && x <= self.x1 && z >= self.z0 && z <= self.z1
    }
}
pub(crate) struct FrameInput {
    pub version: u32,
    pub epoch: u64,
    pub batch: u64,
    pub center: [i32; 2],
    pub radius: i32,
    pub min_y: i32,
    pub max_y: i32,
    pub source: Window,
    pub tick: u64,
    pub events: Vec<(u32, Section)>,
}
impl FrameInput {
    pub fn read(pages: &[&[u8]]) -> Result<Self, String> {
        let mut r = Reader::new(pages)?;
        let (version, epoch, batch) = r.header(1)?;
        let position = [r.f64()?, r.f64()?];
        if position.iter().any(|p| p.abs() > 32_000_000.0) {
            return Err("source camera outside world".into());
        }
        let center = position.map(|p| (p / 16.0).floor() as i32);
        let radius = r.i32()?;
        let min_y = r.i32()?;
        let max_y = r.i32()?;
        let source = Window {
            x0: r.i32()?,
            x1: r.i32()?,
            z0: r.i32()?,
            z1: r.i32()?,
        };
        let tick = r.u64()?;
        if !(0..=2_000_000).contains(&radius) || min_y < -524288 || max_y > 524287 || max_y < min_y
        {
            return Err("invalid source window".into());
        }
        let mut events = Vec::new();
        loop {
            let kind = r.u32()?;
            if kind == 0 {
                break;
            }
            if !matches!(kind, 1..=7) {
                return Err("unknown host event".into());
            }
            let section = Section(r.i32()?, r.i32()?, r.i32()?);
            if section.0.abs_diff(0) > 2_000_000 || section.2.abs_diff(0) > 2_000_000 {
                return Err("source column outside world".into());
            }
            events.push((kind, section));
        }
        r.finish()?;
        Ok(Self {
            version,
            epoch,
            batch,
            center,
            radius,
            min_y,
            max_y,
            source,
            tick,
            events,
        })
    }
}
#[derive(Default)]
pub(crate) struct Scheduler {
    loaded: BTreeSet<(i32, i32)>,
    active_columns: BTreeSet<(i32, i32)>,
    cache_columns: BTreeSet<(i32, i32)>,
    pub active: HashSet<Section>,
    pub cache: HashSet<Section>,
    view: Option<(Window, Window, i32, i32)>,
}
pub(crate) struct Demand {
    pub requests: Vec<Section>,
    pub removed: Vec<Section>,
    pub forget: Vec<Section>,
    pub compile: HashSet<Section>,
    pub columns: Vec<(i32, i32, bool)>,
    pub reset_catalog: bool,
}
impl Scheduler {
    pub fn plan(&mut self, frame: &FrameInput) -> Demand {
        let render = Window {
            x0: frame.center[0] - frame.radius,
            x1: frame.center[0] + frame.radius,
            z0: frame.center[1] - frame.radius,
            z1: frame.center[1] + frame.radius,
        };
        let view = (render, frame.source, frame.min_y, frame.max_y);
        let mut membership = self.view != Some(view);
        let mut dirty = HashSet::new();
        let mut departed = HashSet::new();
        let mut replaced = HashSet::new();
        let mut invalidate = false;
        let mut inventory = false;
        for &(kind, s) in &frame.events {
            match kind {
                1 => {
                    let added = self.loaded.insert((s.0, s.2));
                    membership |= added;
                    if (!inventory && !added) || departed.contains(&(s.0, s.2)) {
                        replaced.insert((s.0, s.2));
                    }
                }
                2 => {
                    membership |= self.loaded.remove(&(s.0, s.2));
                    departed.insert((s.0, s.2));
                }
                3 => {
                    if self.cache.contains(&s) {
                        dirty.insert(s);
                    }
                }
                4 => {
                    invalidate = true;
                }
                5 => {
                    membership = true;
                    inventory = true;
                    self.loaded.clear();
                }
                6 | 7 => {} // Tint dependencies are resolved against consumed source sets.
                _ => unreachable!(),
            }
        }
        let mut columns = Vec::new();
        let mut removed = Vec::new();
        let mut forget = Vec::new();
        let mut compile = HashSet::new();
        let mut requests = BTreeSet::new();
        if membership {
            let columns_next: BTreeSet<_> = self
                .loaded
                .iter()
                .copied()
                .filter(|&(x, z)| render.contains(x, z) && frame.source.contains(x, z))
                .collect();
            columns.extend(
                self.active_columns
                    .difference(&columns_next)
                    .map(|&(x, z)| (x, z, false)),
            );
            columns.extend(
                columns_next
                    .difference(&self.active_columns)
                    .map(|&(x, z)| (x, z, true)),
            );
            let previous_y = self.view.map_or((frame.min_y, frame.max_y), |v| (v.2, v.3));
            let next_y = (frame.min_y, frame.max_y);
            let (gone, entered) =
                section_delta(&self.active_columns, &columns_next, previous_y, next_y);
            for key in gone {
                self.active.remove(&key);
                removed.push(key);
            }
            for key in entered {
                self.active.insert(key);
                compile.insert(key);
            }
            // All active columns cover the same vertical range. Derive the dependency halo
            // at column granularity, then touch section identities only in changed columns.
            let mut cache_columns = columns_next.clone();
            for &(x, z) in &columns_next {
                for n in [
                    (x - 1, z - 1),
                    (x, z - 1),
                    (x + 1, z - 1),
                    (x - 1, z),
                    (x + 1, z),
                    (x - 1, z + 1),
                    (x, z + 1),
                    (x + 1, z + 1),
                ] {
                    if self.loaded.contains(&n) && frame.source.contains(n.0, n.1) {
                        cache_columns.insert(n);
                    }
                }
            }
            let (gone, entered) =
                section_delta(&self.cache_columns, &cache_columns, previous_y, next_y);
            for key in gone {
                self.cache.remove(&key);
                forget.push(key);
            }
            for key in entered {
                self.cache.insert(key);
                requests.insert(key);
            }
            self.cache_columns = cache_columns;
            self.active_columns = columns_next;
            self.view = Some(view);
        }
        if invalidate {
            dirty.extend(self.cache.iter().copied());
        }
        for &(x, z) in &replaced {
            // A same-frame replacement keeps membership but needs a new host entity-source mirror.
            if self.active_columns.contains(&(x, z))
                && !columns
                    .iter()
                    .any(|&(cx, cz, active)| cx == x && cz == z && active)
            {
                columns.push((x, z, true));
            }
            for y in frame.min_y..=frame.max_y {
                let s = Section(x, y, z);
                if self.cache.contains(&s) {
                    dirty.insert(s);
                }
            }
        }
        requests.extend(dirty.into_iter().filter(|s| self.cache.contains(s)));
        Demand {
            requests: requests.into_iter().collect(),
            removed,
            forget,
            compile,
            columns,
            reset_catalog: invalidate,
        }
    }
}

fn section_delta(
    before: &BTreeSet<(i32, i32)>,
    after: &BTreeSet<(i32, i32)>,
    old_y: (i32, i32),
    new_y: (i32, i32),
) -> (Vec<Section>, Vec<Section>) {
    let mut removed = Vec::new();
    let mut added = Vec::new();
    for &(x, z) in before.difference(after) {
        removed.extend((old_y.0..=old_y.1).map(|y| Section(x, y, z)));
    }
    for &(x, z) in after.difference(before) {
        added.extend((new_y.0..=new_y.1).map(|y| Section(x, y, z)));
    }
    if old_y != new_y {
        for &(x, z) in before.intersection(after) {
            removed.extend(
                (old_y.0..=old_y.1)
                    .filter(|&y| y < new_y.0 || y > new_y.1)
                    .map(|y| Section(x, y, z)),
            );
            added.extend(
                (new_y.0..=new_y.1)
                    .filter(|&y| y < old_y.0 || y > old_y.1)
                    .map(|y| Section(x, y, z)),
            );
        }
    }
    (removed, added)
}
