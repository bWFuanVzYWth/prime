//! One world-aligned partition for translation and future spatial light data.
//! Cells assign ownership; their planes never clip or duplicate geometry.
use std::collections::{BTreeMap, BTreeSet};

/// Four 16-unit source sections along each axis. This policy belongs to translation.
pub const CELL_EDGE: f64 = 64.0;

fn grid_floor(position: f64, edge: f64) -> f64 {
    let coordinate = (position / edge).floor();
    // Division can underflow a finite negative subnormal to -0.0.
    if coordinate == 0.0 && position < 0.0 {
        -1.0
    } else {
        coordinate
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Cell([i32; 3]);

impl Cell {
    pub fn containing(world: [f64; 3]) -> Result<Self, String> {
        let mut coordinates = [0; 3];
        for (coordinate, position) in coordinates.iter_mut().zip(world) {
            // floor, including negative coordinates; never anchor-relative or truncation toward zero.
            let cell = grid_floor(position, CELL_EDGE);
            if !cell.is_finite() || cell < f64::from(i32::MIN) || cell > f64::from(i32::MAX) {
                return Err("World position exceeds the spatial grid coordinate range".into());
            }
            *coordinate = cell as i32;
        }
        Ok(Self(coordinates))
    }

    pub fn coordinates(self) -> [i32; 3] {
        self.0
    }

    pub fn origin(self) -> [f64; 3] {
        self.0.map(|coordinate| f64::from(coordinate) * CELL_EDGE)
    }

    pub(crate) fn relative_origin(self, anchor: [f64; 3]) -> [f32; 3] {
        std::array::from_fn(|i| (self.origin()[i] - anchor[i]) as f32)
    }
}

/// Dynamic material classes and bounded geometry parts share the same world cell.
/// Static terrain instead uses Cell directly: one acceleration structure per complete cell.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct BatchKey {
    pub cell: Cell,
    pub flags: u32,
    pub part: u32,
}

#[derive(Default)]
pub(crate) struct TerrainAvailability {
    sections: BTreeMap<u64, [f64; 3]>,
    // Reference counts prevent duplicate source identities at a position from
    // faking completeness or withdrawing a still-present position.
    cells: BTreeMap<Cell, (u64, [u32; 64])>,
    pub ready: BTreeSet<Cell>,
}

impl TerrainAvailability {
    pub fn origin(&self, key: u64) -> Option<&[f64; 3]> {
        self.sections.get(&key)
    }

    /// Called only after complete protocol validation, on the source owner thread.
    pub fn publish(&mut self, key: u64, origin: [f64; 3]) {
        let previous = self.sections.insert(key, origin);
        if previous == Some(origin) {
            return;
        }
        if let Some(previous) = previous {
            self.withdraw(previous);
        }
        let (cell, slot) = Self::membership(origin);
        let (mask, counts) = self.cells.entry(cell).or_insert((0, [0; 64]));
        counts[slot] += 1; // Resident source identities are bounded by the protocol.
        *mask |= 1 << slot;
        if *mask == u64::MAX {
            self.ready.insert(cell);
        }
    }

    pub fn remove(&mut self, key: u64) -> bool {
        if let Some(origin) = self.sections.remove(&key) {
            self.withdraw(origin);
            true
        } else {
            false
        }
    }

    fn withdraw(&mut self, origin: [f64; 3]) {
        let (cell, slot) = Self::membership(origin);
        let (mask, counts) = self.cells.get_mut(&cell).unwrap();
        counts[slot] -= 1;
        if counts[slot] == 0 {
            *mask &= !(1 << slot);
            self.ready.remove(&cell);
        }
        if *mask == 0 {
            self.cells.remove(&cell);
        }
    }

    fn membership(origin: [f64; 3]) -> (Cell, usize) {
        let cell = Cell::containing(origin).expect("validated source origin fits the world grid");
        // Determine integer section coordinates before subtraction so origins
        // just below a negative plane cannot round up to local position 64.
        let local = std::array::from_fn::<_, 3, _>(|i| {
            (grid_floor(origin[i], 16.0) as i64 - i64::from(cell.coordinates()[i]) * 4) as usize
        });
        (cell, local[0] * 16 + local[1] * 4 + local[2])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grid_uses_floor_and_all_three_axes_at_section_and_cell_boundaries() {
        for (value, expected) in [
            (-128.0, -2),
            (-64.001, -2),
            (-64.0, -1),
            (-0.001, -1),
            (-f64::from_bits(1), -1),
            (-f64::from_bits(10), -1),
            (0.0, 0),
            (15.999, 0),
            (16.0, 0),
            (63.999, 0),
            (64.0, 1),
            (128.0, 2),
        ] {
            let cell = Cell::containing([value; 3]).unwrap();
            assert_eq!(cell.coordinates(), [expected; 3]);
            assert_eq!(cell.origin(), [f64::from(expected) * 64.0; 3]);
        }
        for section_x in 0..4 {
            for section_y in 0..4 {
                for section_z in 0..4 {
                    assert_eq!(
                        Cell::containing(
                            [section_x, section_y, section_z].map(|v| f64::from(v * 16))
                        )
                        .unwrap()
                        .coordinates(),
                        [0; 3]
                    );
                }
            }
        }
    }

    #[test]
    fn world_grid_rejects_nonfinite_and_unrepresentable_positions() {
        for value in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, f64::MAX] {
            assert!(Cell::containing([0.0, value, 0.0]).is_err());
        }
        let cell = Cell::containing([30_000_000.25, -64.0001, 63.9999]).unwrap();
        assert_eq!(cell.coordinates(), [468750, -2, 0]);
        assert_eq!(
            cell.relative_origin([30_000_000.0, 0.0, 0.0]),
            [0.0, -128.0, 0.0]
        );
    }

    #[test]
    fn incremental_availability_survives_duplicate_owners_and_retires_empty_cells() {
        let mut availability = TerrainAvailability::default();
        let origin = |slot: u64| {
            [
                -64.0 + (slot / 16) as f64 * 16.0,
                (slot / 4 % 4) as f64 * 16.0,
                64.0 + (slot % 4) as f64 * 16.0,
            ]
        };
        let cell = Cell::containing(origin(0)).unwrap();
        for slot in 0..64 {
            availability.publish(slot, origin(slot));
        }
        assert_eq!(availability.ready, [cell].into());
        availability.publish(1000, origin(63));
        assert!(availability.remove(63));
        assert_eq!(availability.ready, [cell].into());
        availability.publish(1000, [256.0; 3]);
        assert!(availability.ready.is_empty());
        availability.publish(63, origin(63));
        assert_eq!(availability.ready, [cell].into());
        for slot in 0..64 {
            assert!(availability.remove(slot));
        }
        assert!(availability.remove(1000));
        assert!(availability.cells.is_empty() && availability.sections.is_empty());
        assert!(availability.ready.is_empty());
        // Every finite accepted origin, including subnormals, has a valid local slot.
        for bits in 1..128 {
            availability.publish(0, [-f64::from_bits(bits); 3]);
            assert_eq!(
                TerrainAvailability::membership([-f64::from_bits(bits); 3]).1,
                63
            );
        }
        assert!(availability.remove(0));
        assert!(availability.cells.is_empty());
    }
}
