//! Lower compressed states into a slab-local SoA: emission masks, occlusion masks and state
//! references. Ordinary full/empty face culling only reads the compact mask arrays; partial
//! shapes, matching blocks and geometry expansion still use the exact resolved source states.
use super::{Catalog, Job, Section, SectionData};
use crate::model::State;
use std::{
    collections::HashMap,
    simd::{Select, Simd, cmp::SimdPartialEq, num::SimdUint},
};

static MISSING: State = State {
    emission: 0,
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
    placement: crate::placement::Placement::NONE,
    masks: crate::model::StateMasks {
        emission: 63,
        occlusion: 0,
        contact: 0,
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
    contact: u8,
}
impl<'a> Cell<'a> {
    fn lower(id: u32, catalog: &'a Catalog) -> Self {
        let state = catalog.states.get(&id).unwrap_or(&MISSING);
        Self {
            state,
            emission: state.masks.emission,
            occlusion: state.masks.occlusion,
            contact: state.masks.contact,
        }
    }
}
struct Slab<'a> {
    states: [&'a State; CELLS],
    emission: [u8; CELLS],
    occlusion: [u16; CELLS],
    contact: [u8; CELLS],
}
impl<'a> Slab<'a> {
    fn new() -> Self {
        Self {
            states: [&MISSING; CELLS],
            emission: [63; CELLS],
            occlusion: [0; CELLS],
            contact: [0; CELLS],
        }
    }
    fn set(&mut self, index: usize, cell: Cell<'a>) {
        self.states[index] = cell.state;
        self.emission[index] = cell.emission;
        self.occlusion[index] = cell.occlusion;
        self.contact[index] = cell.contact;
    }
    fn needs_contact(&self, index: usize) -> bool {
        let own = self.contact[index];
        if own & 4 != 0 {
            return true;
        }
        [
            index - PLANE,
            index + PLANE,
            index - ROW,
            index + ROW,
            index - 1,
            index + 1,
        ]
        .into_iter()
        .any(|i| {
            // Ordinary opaque faces only need a neighbor's medium or fire coating.
            // A cutout touching opaque resolves/removes its own side independently.
            (own & 1 != 0 && self.contact[i] & 2 != 0)
                || (own & 2 != 0 && self.contact[i] & 12 != 0)
        })
    }
    /// Sixteen independent cells use a 512-bit logical vector. Loads need no SIMD alignment;
    /// the compiler lowers it to the selected target's vector width, including baseline SSE2.
    fn visibility_row(&self, index: usize) -> [u32; 16] {
        type Lanes = Simd<u32, 16>;
        let emission = Simd::<u8, 16>::from_slice(&self.emission[index..]).cast::<u32>();
        if emission.simd_eq(Lanes::splat(0)).all() {
            return [0; 16];
        }
        let load = |at| Simd::<u16, 16>::from_slice(&self.occlusion[at..]).cast::<u32>();
        let occlusion = (load(index - PLANE) & Lanes::splat(0x041))
            | (load(index + PLANE) & Lanes::splat(0x082))
            | (load(index - ROW) & Lanes::splat(0x104))
            | (load(index + ROW) & Lanes::splat(0x208))
            | (load(index - 1) & Lanes::splat(0x410))
            | (load(index + 1) & Lanes::splat(0x820));
        // Keep fluid and the unculled bucket. Only six direction bits can be occluded.
        let visible = emission & !(occlusion & Lanes::splat(63));
        let matching = (load(index) & Lanes::splat(u32::from(MATCHING))).simd_ne(Lanes::splat(0));
        let exact = visible & matching.select(Lanes::splat(63), occlusion >> 6) & Lanes::splat(63);
        (visible | (exact << 8)).to_array()
    }
    fn exact_visible(&self, index: usize, masks: u32, catalog: &Catalog) -> u32 {
        let mut visible = masks & 127;
        let mut exact = masks >> 8;
        while exact != 0 {
            let face = exact.trailing_zeros() as usize;
            let bit = 1 << face;
            exact &= !bit;
            let other = self.states[(index as isize
                + [
                    -(PLANE as isize),
                    PLANE as isize,
                    -(ROW as isize),
                    ROW as isize,
                    -1,
                    1,
                ][face]) as usize];
            let state = self.states[index];
            if catalog.covers(other.faces[face ^ 1], state.faces[face])
                || self.occlusion[index] & MATCHING != 0 && state.name == other.name
            {
                visible &= !bit;
            }
        }
        visible
    }
    #[cfg(test)]
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
            output.contact[offset..offset + 16].fill(cell.contact);
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

#[cfg(test)]
pub(super) fn compile_slab(
    job: &mut Job,
    catalog: &Catalog,
    sections: &HashMap<Section, SectionData>,
) {
    compile(job, catalog, sections, None);
}
pub(super) fn compile_contacts(
    job: &mut Job,
    catalog: &Catalog,
    sections: &HashMap<Section, SectionData>,
    cells: &std::collections::HashSet<[i32; 3]>,
) {
    compile(job, catalog, sections, Some(cells));
}
fn compile(
    job: &mut Job,
    catalog: &Catalog,
    sections: &HashMap<Section, SectionData>,
    published: Option<&std::collections::HashSet<[i32; 3]>>,
) {
    let contact =
        published
            .filter(|_| catalog.has_contacts())
            .map(|cells| crate::contact::Context {
                catalog,
                sections,
                cells,
                origin: [job.key.0 * 16, job.key.1 * 16, job.key.2 * 16],
            });
    let mut contact_cache = crate::contact::Cache::new();
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
            let row = cells.visibility_row((y + 1) * PLANE + (z + 1) * ROW + 1);
            for (x, masks) in row.into_iter().enumerate() {
                let index = (y + 1) * PLANE + (z + 1) * ROW + x + 1;
                if masks == 0 {
                    continue;
                }
                let position = [
                    job.key.0 * 16 + x as i32,
                    job.key.1 * 16 + (job.first_y + y) as i32,
                    job.key.2 * 16 + z as i32,
                ];
                if let Some(contact) = &contact
                    && cells.needs_contact(index)
                {
                    contact.emit(
                        &mut contact_cache,
                        job,
                        position,
                        cells.exact_visible(index, masks, catalog),
                    );
                    continue;
                }
                if masks & u32::from(FLUID) != 0 {
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
                        false,
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
                if masks & 127 == 0 {
                    continue;
                }
                let visible = cells.exact_visible(index, masks, catalog);
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
                    &mut job.surfaces,
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
    fn ice_matching_masks_preserve_other_owners_and_unculled_model_faces() {
        let mut catalog = Catalog::default();
        catalog.models.insert(
            1,
            Model::Mesh(
                (0..7)
                    .map(|face| Quad {
                        sprite: 0,
                        emission: 0,
                        positions: [[0.; 3]; 4],
                        uvs: [[0.; 2]; 4],
                        face,
                        tint: -1,
                        layer: 2,
                    })
                    .collect(),
            ),
        );
        for (id, name, flags) in [
            (1, "minecraft:ice", 192),
            (2, "minecraft:ice", 192),
            (3, "minecraft:frosted_ice", 192),
            (4, "minecraft:frosted_ice", 192),
            (5, "mod:ice", 192),
            (6, "mod:other_ice", 192),
            (7, "minecraft:ice", 64),
            (8, "minecraft:packed_ice", 32),
            (9, "minecraft:blue_ice", 32),
        ] {
            catalog.states.insert(
                id,
                State {
                    id,
                    name: name.into(),
                    flags,
                    model: 1,
                    ..Default::default()
                },
            );
        }
        catalog.prepare();
        // Explicit host rule expectations, independent of Catalog::hidden's helper.
        for (own, neighbor, hidden) in [
            (1, 2, true),
            (3, 4, true),
            (1, 3, false),
            (3, 1, false),
            (5, 5, false),
            (5, 6, false),
            (7, 7, false),
            (8, 8, false),
            (9, 9, false),
            (1, 5, false),
            (1, 0, false),
        ] {
            for lane in 0..16 {
                let row = 2 * PLANE + 2 * ROW + 1;
                let index = row + lane;
                for (face, offset) in [
                    -(PLANE as isize),
                    PLANE as isize,
                    -(ROW as isize),
                    ROW as isize,
                    -1,
                    1,
                ]
                .into_iter()
                .enumerate()
                {
                    let mut slab = Slab::new();
                    slab.set(index, Cell::lower(own, &catalog));
                    slab.set(
                        (index as isize + offset) as usize,
                        Cell::lower(neighbor, &catalog),
                    );
                    let expected = if hidden { 127 & !(1 << face) } else { 127 };
                    assert_eq!(
                        slab.exact_visible(index, slab.visibility_row(row)[lane], &catalog),
                        expected,
                        "SIMD own={own} neighbor={neighbor} face={face} lane={lane}"
                    );
                    assert_eq!(slab.visible(index, &catalog), expected);
                    assert_ne!(expected & 64, 0);
                }
            }
        }
        assert!(catalog.states[&1].same_boundary(&catalog.states[&2]));
        assert!(catalog.states[&3].same_boundary(&catalog.states[&4]));
        assert!(!catalog.states[&1].same_boundary(&catalog.states[&3]));
        assert!(!catalog.states[&3].same_boundary(&catalog.states[&1]));
    }

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
                            sprite: 0,
                            emission: 0,
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
                    let masks = slab.visibility_row(index - 1)[1];
                    assert_eq!(slab.exact_visible(index, masks, &catalog), expected);
                }
                // Next pair starts with an open/missing halo, then fills each direction in turn.
                for &at in &neighbors {
                    slab.set(at, Cell::lower(u32::MAX, &catalog));
                }
            }
        }
    }

    #[test]
    fn vector_rows_preserve_every_lane_fluid_and_unculled_bits() {
        let catalog = Catalog::default();
        let mut slab = Slab::new();
        let mut random = 173_u64;
        for index in 0..CELLS {
            random = random.wrapping_mul(6364136223846793005).wrapping_add(1);
            slab.emission[index] = (random >> 32) as u8;
            slab.occlusion[index] = (random >> 40) as u16 & 0x1fff;
        }
        for y in 1..5 {
            for z in 1..17 {
                let start = y * PLANE + z * ROW + 1;
                for (x, masks) in slab.visibility_row(start).into_iter().enumerate() {
                    let at = start + x;
                    assert_eq!(
                        slab.exact_visible(at, masks, &catalog),
                        slab.visible(at, &catalog)
                    );
                    assert_eq!(masks & 0xc0, u32::from(slab.emission[at] & 0xc0));
                }
            }
        }
        slab.emission.fill(0);
        assert_eq!(slab.visibility_row(PLANE + ROW + 1), [0; 16]);
    }

    #[test]
    fn single_local_and_global_palettes_preserve_optical_and_emissive_output() {
        use prime_scene::surface::SurfaceFace;
        for kind in [0, 1, 2] {
            let mut catalog = Catalog::default();
            let (flags, name) = match kind {
                0 => (256, "test:glass"),
                1 => (16, "minecraft:water"),
                _ => (16, "minecraft:lava"),
            };
            catalog.states.insert(
                1,
                State {
                    id: 1,
                    flags,
                    model: 1,
                    name: name.into(),
                    fluid: crate::fluid::Fluid {
                        kind,
                        amount: 8,
                        material: 1,
                        ..Default::default()
                    },
                    ..Default::default()
                },
            );
            if kind == 0 {
                catalog.models.insert(
                    1,
                    Model::Mesh(vec![Quad {
                        sprite: 0,
                        emission: 0,
                        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
                        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
                        face: 6,
                        tint: -1,
                        layer: 2,
                    }]),
                );
                catalog.glass_references.insert(1, [1.; 4]);
            } else {
                let mut bytes = Vec::new();
                for n in [1, if kind == 1 { 2 } else { 0 }, 0] {
                    u32_to(&mut bytes, n);
                }
                for _ in 0..3 {
                    u32_to(&mut bytes, 0);
                    for n in [0_f32, 0., 1., 1.] {
                        u32_to(&mut bytes, n.to_bits());
                    }
                }
                let pages = [bytes.as_slice()];
                let (id, material) =
                    crate::fluid::FluidMaterial::read(&mut Reader::new(&pages).unwrap()).unwrap();
                catalog.fluids.insert(id, material);
            }
            catalog.prepare();
            let source = [
                SectionData {
                    palette: vec![1],
                    storage: vec![],
                    bits: 0,
                    per_word: 0,
                },
                SectionData {
                    palette: vec![0, 1],
                    storage: vec![0x1111_1111_1111_1111; 256],
                    bits: 4,
                    per_word: 16,
                },
                SectionData {
                    palette: vec![],
                    storage: vec![0x0000_0001_0000_0001; 2048],
                    bits: 32,
                    per_word: 2,
                },
            ];
            let mut reference = None;
            for data in source {
                let sections = HashMap::from([(Section(0, 0, 0), data)]);
                let published = std::collections::HashSet::from([[0; 3]]);
                let mut faces = Vec::new();
                for first_y in [0, 4, 8, 12] {
                    let mut job = Job {
                        key: Section(0, 0, 0),
                        first_y,
                        layers: Default::default(),
                        surfaces: Default::default(),
                        hacks: Default::default(),
                        compiled: None,
                        tints: Default::default(),
                        color_start: 0,
                    };
                    compile_contacts(&mut job, &catalog, &sections, &published);
                    faces.extend(job.layers.into_iter().flatten().map(SurfaceFace::from_quad));
                    faces.extend(job.surfaces.into_iter().flatten());
                }
                assert!(!faces.is_empty(), "{name}");
                if kind < 2 {
                    assert!(
                        faces
                            .iter()
                            .all(|face| face.optics.is_some_and(|o| o.transmit)),
                        "{name}"
                    );
                    assert!(faces.iter().all(|face| face.media[0] != 0), "{name}");
                } else {
                    assert!(
                        faces.iter().all(
                            |face| face.emission.radiance == [1.5; 3] && face.emission.textured
                        ),
                        "{name}"
                    );
                }
                if let Some(reference) = &reference {
                    assert_eq!(&faces, reference, "{name}");
                } else {
                    reference = Some(faces);
                }
            }
        }
    }
}
