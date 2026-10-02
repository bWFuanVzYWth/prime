//! Dirty sections are admitted as world-aligned cells; source ownership stays in TerrainContext.
use crate::schedule::Section;
use std::{
    collections::BTreeMap,
    ops::Bound::{Excluded, Unbounded},
};

type Cell = [i32; 3];

fn address(section: Section) -> (Cell, u64) {
    let coordinates = [section.0, section.1, section.2];
    let cell = coordinates.map(|v| v.div_euclid(4));
    let [x, y, z] = coordinates.map(|v| v.rem_euclid(4) as u32);
    (cell, 1 << (x * 16 + y * 4 + z))
}

#[derive(Default)]
pub(crate) struct CompileQueue {
    cells: BTreeMap<Cell, u64>,
    cursor: Option<Cell>,
}

/// A batch reservation is acknowledged only after its source publication succeeds.
pub(crate) struct Selection {
    pub sections: Vec<Section>,
    cells: Vec<(Cell, u64)>,
}

impl Selection {
    pub fn cell_count(&self) -> usize {
        self.cells.len()
    }
}

impl CompileQueue {
    pub fn insert(&mut self, section: Section) {
        let (cell, bit) = address(section);
        *self.cells.entry(cell).or_default() |= bit;
    }

    pub fn remove(&mut self, section: Section) {
        let (cell, bit) = address(section);
        if let Some(mask) = self.cells.get_mut(&cell) {
            *mask &= !bit;
            if *mask == 0 {
                self.cells.remove(&cell);
            }
        }
    }

    pub fn len(&self) -> usize {
        self.cells.len()
    }

    pub fn select(&self, limit: usize) -> Selection {
        let mut cells = Vec::with_capacity(limit.min(self.cells.len()));
        if let Some(cursor) = self.cursor {
            cells.extend(
                self.cells
                    .range((Excluded(cursor), Unbounded))
                    .chain(self.cells.range(..=cursor))
                    .take(limit)
                    .map(|(&cell, &mask)| (cell, mask)),
            );
        } else {
            cells.extend(
                self.cells
                    .iter()
                    .take(limit)
                    .map(|(&cell, &mask)| (cell, mask)),
            );
        }
        let count = cells
            .iter()
            .map(|(_, mask)| mask.count_ones() as usize)
            .sum();
        let mut sections = Vec::with_capacity(count);
        for &(cell, mut mask) in &cells {
            while mask != 0 {
                let slot = mask.trailing_zeros() as i32;
                sections.push(Section(
                    cell[0] * 4 + slot / 16,
                    cell[1] * 4 + (slot / 4) % 4,
                    cell[2] * 4 + slot % 4,
                ));
                mask &= mask - 1;
            }
        }
        // Keep the existing section/slab order, sorting only this admitted batch.
        sections.sort_unstable();
        Selection { sections, cells }
    }

    pub fn complete(&mut self, selection: &Selection) {
        for &(cell, mask) in &selection.cells {
            let pending = self.cells.get_mut(&cell).expect("selected dirty cell");
            *pending &= !mask;
            if *pending == 0 {
                self.cells.remove(&cell);
            }
        }
        if let Some(&(cell, _)) = selection.cells.last() {
            self.cursor = Some(cell);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_robin_passes_repeated_edits_and_wraps_without_duplicate_cells() {
        let mut queue = CompileQueue::default();
        for x in [0, 4, 8] {
            queue.insert(Section(x, 0, 0));
        }
        for x in [0, 4, 8, 0] {
            queue.insert(Section(0, 0, 0));
            let selected = queue.select(1);
            assert_eq!(selected.sections, [Section(x, 0, 0)]);
            queue.complete(&selected);
        }
        assert_eq!(queue.len(), 0);
        for x in [0, 4, 8] {
            queue.insert(Section(x, 0, 0));
        }
        let selected = queue.select(128);
        assert_eq!(selected.cell_count(), 3);
        assert_eq!(
            selected.sections,
            [Section(0, 0, 0), Section(4, 0, 0), Section(8, 0, 0)]
        );
        queue.complete(&selected);
        assert_eq!(queue.len(), 0);
    }

    #[test]
    fn negative_cell_has_all_64_distinct_slots_and_reservation_is_not_completion() {
        let mut queue = CompileQueue::default();
        let mut expected = Vec::new();
        for x in -4..0 {
            for y in -8..-4 {
                for z in -12..-8 {
                    let section = Section(x, y, z);
                    expected.push(section);
                    queue.insert(section);
                    queue.insert(section);
                }
            }
        }
        let selected = queue.select(1);
        assert_eq!(selected.sections, expected);
        assert_eq!(selected.cells, [([-1, -2, -3], u64::MAX)]);
        assert_eq!(queue.len(), 1);
        assert_eq!(queue.select(1).sections, expected);
        queue.complete(&selected);
        assert_eq!(queue.len(), 0);
    }
}
