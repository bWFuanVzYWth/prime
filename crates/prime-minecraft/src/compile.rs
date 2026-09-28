//! Lower compressed states into a slab-local SoA: emission masks, occlusion masks and state
//! references. Ordinary full/empty face culling only reads the compact mask arrays; partial
//! shapes, matching blocks and geometry expansion still use the exact resolved source states.
use super::{Catalog, Job, Section, SectionData};
use crate::model::State;
use std::collections::HashMap;

static MISSING: State = State {
    id: 0,
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
const ROW: usize = 18;
const PLANE: usize = ROW * ROW;
const CELLS: usize = 6 * PLANE;
const FLUID: u8 = 128;
const MATCHING: u16 = 1 << 12;

#[derive(Clone, Copy)]
struct Cell<'a> {
    state: &'a State,
    emission: u8,
    occlusion: u16,
}
impl<'a> Cell<'a> {
    fn lower(id: u32, catalog: &'a Catalog) -> Self {
        let state = catalog.states.get(&id).unwrap_or(&MISSING);
        // Face bits are reversed once so a neighbor can be tested with the source direction.
        // Bits 0..6 identify full faces, 6..12 identify faces requiring the exact shape test.
        let mut occlusion = if state.same_block_culls() {
            MATCHING
        } else {
            0
        };
        for (face, id) in state.faces.iter().enumerate() {
            occlusion |= match id.0 {
                0 => 0,
                1 => 1 << (face ^ 1),
                _ => 1 << ((face ^ 1) + 6),
            };
        }
        Self {
            state,
            emission: catalog.face_mask(state) as u8
                | if state.fluid.kind != 0 { FLUID } else { 0 },
            occlusion,
        }
    }
}
struct Slab<'a> {
    states: [&'a State; CELLS],
    emission: [u8; CELLS],
    occlusion: [u16; CELLS],
}
impl<'a> Slab<'a> {
    fn new() -> Self {
        Self {
            states: [&MISSING; CELLS],
            emission: [63; CELLS],
            occlusion: [0; CELLS],
        }
    }
    fn set(&mut self, index: usize, cell: Cell<'a>) {
        self.states[index] = cell.state;
        self.emission[index] = cell.emission;
        self.occlusion[index] = cell.occlusion;
    }
    fn visible(&self, index: usize, catalog: &Catalog) -> u32 {
        let neighbors = [
            index - PLANE,
            index + PLANE,
            index - ROW,
            index + ROW,
            index - 1,
            index + 1,
        ];
        // Merge the six opposing face pairs without chasing six State references.
        let occlusion = (self.occlusion[neighbors[0]] & 0x041)
            | (self.occlusion[neighbors[1]] & 0x082)
            | (self.occlusion[neighbors[2]] & 0x104)
            | (self.occlusion[neighbors[3]] & 0x208)
            | (self.occlusion[neighbors[4]] & 0x410)
            | (self.occlusion[neighbors[5]] & 0x820);
        // Bit 6 is the unculled model bucket, never an occlusion direction.
        let mut visible = self.emission[index] & 127 & !(occlusion as u8 & 63);
        let matching = self.occlusion[index] & MATCHING != 0;
        let mut exact = visible & if matching { 63 } else { (occlusion >> 6) as u8 };
        while exact != 0 {
            let face = exact.trailing_zeros() as usize;
            let bit = 1 << face;
            exact &= !bit;
            let state = self.states[index];
            let other = self.states[neighbors[face]];
            if catalog.covers(other.faces[face ^ 1], state.faces[face])
                || matching && state.name == other.name
            {
                visible &= !bit;
            }
        }
        u32::from(visible)
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
    fn row(&self, start: usize, output: &mut Slab<'a>, offset: usize) {
        if let Palette::Single(cell) = self.palette {
            output.states[offset..offset + 16].fill(cell.state);
            output.emission[offset..offset + 16].fill(cell.emission);
            output.occlusion[offset..offset + 16].fill(cell.occlusion);
            return;
        }
        let data = self.data;
        let mut word = start / data.per_word;
        let mut lane = start % data.per_word;
        let mask = (1u64 << data.bits) - 1;
        let mut at = 0;
        while at < 16 {
            let count = (data.per_word - lane).min(16 - at);
            let mut value = data.storage[word] >> (lane * data.bits as usize);
            for i in at..at + count {
                let index = (value & mask) as usize;
                let cell = match &self.palette {
                    Palette::Local(cells) => cells[index],
                    Palette::Global => Cell::lower(index as u32, self.catalog),
                    Palette::Single(_) => unreachable!(),
                };
                output.set(offset + i, cell);
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
    let mut cells = Slab::new();
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
                view.row(sy * 256 + sz * 16, &mut cells, row + 1);
            }
            if let Some(view) = &views[vi - 1] {
                cells.set(row, view.cell(sy * 256 + sz * 16 + 15));
            }
            if let Some(view) = &views[vi + 1] {
                cells.set(row + 17, view.cell(sy * 256 + sz * 16));
            }
        }
    }
    for y in 0..4 {
        for z in 0..16 {
            for x in 0..16 {
                let index = (y + 1) * PLANE + (z + 1) * ROW + x + 1;
                let emission = cells.emission[index];
                if emission == 0 {
                    continue;
                }
                let position = [
                    job.key.0 * 16 + x as i32,
                    job.key.1 * 16 + (job.first_y + y) as i32,
                    job.key.2 * 16 + z as i32,
                ];
                if emission & FLUID != 0 {
                    let state = cells.states[index];
                    job.tints.begin(state.id, position);
                    let starts = job.layers.each_ref().map(|l| l.len());
                    crate::fluid::emit(
                        catalog,
                        state,
                        [x as f32, (job.first_y + y) as f32, z as f32],
                        |dx, dy, dz| {
                            cells.states
                                [(index as i32 + dy * PLANE as i32 + dz * ROW as i32 + dx) as usize]
                        },
                        &mut job.layers,
                        &mut job.hacks,
                    );
                    if state.fluid.kind < 3
                        && catalog
                            .fluids
                            .get(&state.fluid.material)
                            .is_some_and(|m| m.flags & 1 != 0)
                    {
                        for (layer, start) in starts.into_iter().enumerate() {
                            job.tints.patch(-1, layer, start, job.layers[layer].len());
                        }
                    }
                }
                if emission & 127 == 0 {
                    continue;
                }
                let visible = cells.visible(index, catalog);
                if visible == 0 {
                    continue;
                }
                let state = cells.states[index];
                job.tints.begin(state.id, position);
                catalog.emit(
                    state,
                    position,
                    visible,
                    &mut job.layers,
                    &mut job.hacks,
                    &mut job.tints,
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        model::{Model, Quad},
        shape::{Face, FaceId},
        wire::{Reader, u32_to, u64_to},
    };

    #[test]
    fn soa_visibility_matches_scalar_shapes_directions_and_matching_blocks() {
        let mut catalog = Catalog::default();
        // Opposite half faces, plus a full shape with a non-special ID. Classification must
        // preserve the exact shape rule, including empty source faces and equal profile IDs.
        for (id, cells) in [(2, 1), (3, 2), (4, 3)] {
            let mut bytes = Vec::new();
            for value in [id, 3, 2, 1] {
                u32_to(&mut bytes, value);
            }
            for value in [0_f64, 0.5, 1., 0., 1.] {
                u64_to(&mut bytes, value.to_bits());
            }
            u64_to(&mut bytes, cells);
            let pages = [bytes.as_slice()];
            let (id, face) = Face::read(&mut Reader::new(&pages).unwrap()).unwrap();
            catalog.faces.insert(id, face);
        }
        for (model, faces) in [(1, vec![6]), (2, vec![3]), (3, (0..7).collect())] {
            catalog.models.insert(
                model,
                Model::Mesh(
                    faces
                        .into_iter()
                        .map(|face| Quad {
                            positions: [[0.; 3]; 4],
                            uvs: [[0.; 2]; 4],
                            face,
                            tint: -1,
                            layer: 0,
                        })
                        .collect(),
                ),
            );
        }
        for model in 0..4 {
            for name in [
                "minecraft:stone",
                "minecraft:glass",
                "minecraft:red_stained_glass",
            ] {
                for shape in 0..5 {
                    let id = catalog.states.len() as u32 + 1;
                    catalog.states.insert(
                        id,
                        State {
                            id,
                            model,
                            name: name.into(),
                            faces: std::array::from_fn(|face| FaceId((shape + face as u32) % 5)),
                            ..Default::default()
                        },
                    );
                }
            }
        }
        catalog.prepare();
        let index = 2 * PLANE + 2 * ROW + 2;
        let neighbors = [
            index - PLANE,
            index + PLANE,
            index - ROW,
            index + ROW,
            index - 1,
            index + 1,
        ];
        let mut slab = Slab::new();
        for &source in catalog.states.keys() {
            slab.set(index, Cell::lower(source, &catalog));
            for &neighbor in catalog.states.keys() {
                for &at in &neighbors {
                    slab.set(at, Cell::lower(neighbor, &catalog));
                    let state = &catalog.states[&source];
                    let expected = neighbors
                        .iter()
                        .enumerate()
                        .fold(64, |visible, (face, &at)| {
                            visible
                                | if catalog.hidden(state, slab.states[at], face) {
                                    0
                                } else {
                                    1 << face
                                }
                        })
                        & catalog.face_mask(state);
                    assert_eq!(
                        slab.visible(index, &catalog),
                        expected,
                        "source={source} neighbor={neighbor} at={at}"
                    );
                }
                // Next pair starts with an open/missing halo, then fills each direction in turn.
                for &at in &neighbors {
                    slab.set(at, Cell::lower(u32::MAX, &catalog));
                }
            }
        }
    }
}
