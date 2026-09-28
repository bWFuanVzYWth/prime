//! Lower compressed states once into a small slab and its face halo. The hot loop uses
//! resolved cells, not repeated palette unpacking, state hashing or model classification.
use super::{Catalog, Job, Section, SectionData};
use crate::model::State;
use std::collections::HashMap;

static MISSING: State = State {
    flags: 0,
    model: 0,
    name: String::new(),
    faces: [crate::shape::FaceId(0); 6],
    support: 0,
    fluid: crate::fluid::Fluid {
        kind: 0,
        amount: 0,
        falling: false,
        material: 0,
    },
};
#[derive(Clone, Copy)]
struct Cell<'a> {
    state: &'a State,
    faces: u32,
    matching: bool,
}
impl<'a> Cell<'a> {
    fn lower(id: u32, catalog: &'a Catalog) -> Self {
        let state = catalog.states.get(&id).unwrap_or(&MISSING);
        Self {
            state,
            faces: catalog.face_mask(state),
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
    fn has_fluid(&self) -> bool {
        match &self.palette {
            Palette::Single(c) => c.state.fluid.kind != 0,
            Palette::Local(cells) => cells.iter().any(|c| c.state.fluid.kind != 0),
            Palette::Global => true,
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
    let has_fluid = center.has_fluid();
    let mut views = job.key.halo().map(|key| {
        if key == job.key
            || !has_fluid
                && key.0.abs_diff(job.key.0) + key.1.abs_diff(job.key.1) + key.2.abs_diff(job.key.2)
                    > 1
        {
            return None;
        }

        if key.1 < job.key.1 && job.first_y != 0 || key.1 > job.key.1 && job.first_y != 12 {
            None
        } else {
            sections.get(&key).map(|d| View::new(d, catalog))
        }
    });
    views[13] = Some(center);
    let missing = Cell {
        state: &MISSING,
        faces: 63,
        matching: false,
    };
    let mut cells = [missing; 6 * PLANE];
    // Full one-cell neighborhood, including diagonals: fluid slopes/flow/backfaces consume it.
    // Only halo rows expand; central 16-cell spans still unpack once, word by word.
    for py in 0..6 {
        let y = job.first_y as i32 + py as i32 - 1;
        let dy = y.div_euclid(16);
        let sy = y.rem_euclid(16) as usize;
        for pz in 0..18 {
            let z = pz as i32 - 1;
            let dz = z.div_euclid(16);
            let sz = z.rem_euclid(16) as usize;
            let row = py * PLANE + pz * ROW;
            let vi = ((dy + 1) * 9 + (dz + 1) * 3 + 1) as usize;
            if let Some(view) = &views[vi] {
                view.row(sy * 256 + sz * 16, &mut cells[row + 1..row + 17]);
            }
            if let Some(view) = &views[vi - 1] {
                cells[row] = view.cell(sy * 256 + sz * 16 + 15);
            }
            if let Some(view) = &views[vi + 1] {
                cells[row + 17] = view.cell(sy * 256 + sz * 16);
            }
        }
    }
    for y in 0..4 {
        for z in 0..16 {
            for x in 0..16 {
                let index = (y + 1) * PLANE + (z + 1) * ROW + x + 1;
                let cell = cells[index];
                if cell.state.fluid.kind != 0 {
                    crate::fluid::emit(
                        catalog,
                        cell.state,
                        [x as f32, (job.first_y + y) as f32, z as f32],
                        |dx, dy, dz| {
                            cells
                                [(index as i32 + dy * PLANE as i32 + dz * ROW as i32 + dx) as usize]
                                .state
                        },
                        &mut job.layers,
                        &mut job.hacks,
                    );
                }
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
                    if !(catalog.covers(other.state.faces[face ^ 1], cell.state.faces[face])
                        || cell.matching && cell.state.name == other.state.name)
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
