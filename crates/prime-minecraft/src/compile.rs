//! Lower compressed states once into a small slab and its face halo. The hot loop uses
//! resolved cells, not repeated palette unpacking, state hashing or model classification.
use super::{Catalog, Job, Section, SectionData};
use crate::model::State;
use std::collections::HashMap;

static MISSING: State = State {
    flags: 0,
    model: 0,
    name: String::new(),
};
#[derive(Clone, Copy)]
struct Cell<'a> {
    state: &'a State,
    faces: u32,
    full: bool,
    matching: bool,
}
impl<'a> Cell<'a> {
    fn lower(id: u32, catalog: &'a Catalog) -> Self {
        let state = catalog.states.get(&id).unwrap_or(&MISSING);
        Self {
            state,
            faces: catalog.face_mask(state),
            full: state.full(),
            matching: state.same_block_culls(),
        }
    }
}
enum Palette<'a> {
    Single(Cell<'a>),
    Local(Vec<Cell<'a>>),
    Global,
}
struct View<'a> {
    data: &'a SectionData,
    palette: Palette<'a>,
    catalog: &'a Catalog,
}
impl<'a> View<'a> {
    fn new(data: &'a SectionData, catalog: &'a Catalog) -> Self {
        let palette = match data.palette.as_slice() {
            [] => Palette::Global,
            [id] => Palette::Single(Cell::lower(*id, catalog)),
            ids => Palette::Local(ids.iter().map(|&id| Cell::lower(id, catalog)).collect()),
        };
        Self {
            data,
            palette,
            catalog,
        }
    }
    fn cell(&self, index: usize) -> Cell<'a> {
        match &self.palette {
            Palette::Single(cell) => *cell,
            Palette::Local(cells) => cells[self.data.index(index) as usize],
            Palette::Global => Cell::lower(self.data.index(index), self.catalog),
        }
    }
    fn row(&self, start: usize, output: &mut [Cell<'a>]) {
        if let Palette::Single(cell) = self.palette {
            output.fill(cell);
            return;
        }
        let data = self.data;
        let mut word = start / data.per_word;
        let mut lane = start % data.per_word;
        let mask = (1u64 << data.bits) - 1;
        let mut at = 0;
        while at < output.len() {
            let count = (data.per_word - lane).min(output.len() - at);
            let mut value = data.storage[word] >> (lane * data.bits as usize);
            for cell in &mut output[at..at + count] {
                let index = (value & mask) as usize;
                *cell = match &self.palette {
                    Palette::Local(cells) => cells[index],
                    Palette::Global => Cell::lower(index as u32, self.catalog),
                    Palette::Single(_) => unreachable!(),
                };
                value >>= data.bits;
            }
            at += count;
            word += 1;
            lane = 0;
        }
    }
}

pub(super) fn compile_slab(
    job: &mut Job,
    catalog: &Catalog,
    sections: &HashMap<Section, SectionData>,
) {
    const ROW: usize = 18;
    const PLANE: usize = ROW * ROW;
    let center = View::new(&sections[&job.key], catalog);
    let neighbors = job
        .key
        .neighbors()
        .map(|key| sections.get(&key).map(|d| View::new(d, catalog)));
    let missing = Cell {
        state: &MISSING,
        faces: 63,
        full: false,
        matching: false,
    };
    let mut cells = [missing; 6 * PLANE];
    // Inner rows and the two Y halo planes are contiguous source spans.
    for py in 0..6 {
        let y = job.first_y as isize + py as isize - 1;
        let (view, sy) = if y < 0 {
            (neighbors[0].as_ref(), 15)
        } else if y >= 16 {
            (neighbors[1].as_ref(), 0)
        } else {
            (Some(&center), y as usize)
        };
        if let Some(view) = view {
            for z in 0..16 {
                let at = py * PLANE + (z + 1) * ROW + 1;
                view.row(sy * 256 + z * 16, &mut cells[at..at + 16]);
            }
        }
    }
    // X/Z halos need only the four slab rows; unused edges/corners are never queried.
    for y in 0..4 {
        let sy = (job.first_y + y) * 256;
        let plane = (y + 1) * PLANE;
        for n in 0..16 {
            if let Some(view) = &neighbors[2] {
                cells[plane + n + 1] = view.cell(sy + 240 + n);
            }
            if let Some(view) = &neighbors[3] {
                cells[plane + 17 * ROW + n + 1] = view.cell(sy + n);
            }
            if let Some(view) = &neighbors[4] {
                cells[plane + (n + 1) * ROW] = view.cell(sy + n * 16 + 15);
            }
            if let Some(view) = &neighbors[5] {
                cells[plane + (n + 1) * ROW + 17] = view.cell(sy + n * 16);
            }
        }
    }
    for y in 0..4 {
        for z in 0..16 {
            for x in 0..16 {
                let index = (y + 1) * PLANE + (z + 1) * ROW + x + 1;
                let cell = cells[index];
                if cell.faces == 0 {
                    continue;
                }
                let mut visible = 64;
                for (face, neighbor) in [
                    index - PLANE,
                    index + PLANE,
                    index - ROW,
                    index + ROW,
                    index - 1,
                    index + 1,
                ]
                .into_iter()
                .enumerate()
                {
                    let other = cells[neighbor];
                    if !(other.full
                        || (cell.matching
                            && (std::ptr::eq(cell.state, other.state)
                                || cell.state.name == other.state.name)))
                    {
                        visible |= 1 << face;
                    }
                }
                if cell.faces & visible == 0 {
                    continue;
                }
                catalog.emit(
                    cell.state,
                    [
                        job.key.0 * 16 + x as i32,
                        job.key.1 * 16 + (job.first_y + y) as i32,
                        job.key.2 * 16 + z as i32,
                    ],
                    visible,
                    &mut job.layers,
                    &mut job.hacks,
                );
            }
        }
    }
}
