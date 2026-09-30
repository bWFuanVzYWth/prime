//! Independent pre-optimization scalar compiler retained only as a semantic oracle.
use super::*;
pub(super) fn compile_slab(
    job: &mut Job,
    catalog: &Catalog,
    sections: &HashMap<Section, SectionData>,
) {
    let data = &sections[&job.key];
    let neighbors = job.key.neighbors().map(|n| sections.get(&n));
    let missing = model::State::default();
    for y in job.first_y..job.first_y + 4 {
        for z in 0..16 {
            for x in 0..16 {
                let id = data.state(y * 256 + z * 16 + x);
                let state = catalog.states.get(&id).unwrap_or(&missing);
                if state.fluid.kind != 0 {
                    crate::fluid::emit(
                        catalog,
                        state,
                        [x as f32, y as f32, z as f32],
                        |dx, dy, dz| {
                            let (nx, ny, nz) = (x as i32 + dx, y as i32 + dy, z as i32 + dz);
                            let s = Section(
                                job.key.0 + nx.div_euclid(16),
                                job.key.1 + ny.div_euclid(16),
                                job.key.2 + nz.div_euclid(16),
                            );
                            sections
                                .get(&s)
                                .and_then(|data| {
                                    catalog.states.get(&data.state(
                                        (ny.rem_euclid(16) * 256
                                            + nz.rem_euclid(16) * 16
                                            + nx.rem_euclid(16))
                                            as usize,
                                    ))
                                })
                                .unwrap_or(&missing)
                        },
                        &mut job.layers,
                        &mut job.hacks,
                        false,
                    );
                }
                if state.air() {
                    continue;
                }
                let coords = [
                    (x, y.wrapping_sub(1), z),
                    (x, y + 1, z),
                    (x, y, z.wrapping_sub(1)),
                    (x, y, z + 1),
                    (x.wrapping_sub(1), y, z),
                    (x + 1, y, z),
                ];
                let mut visible = 64u32;
                for (face, (nx, ny, nz)) in coords.into_iter().enumerate() {
                    let neighbor = if nx < 16 && ny < 16 && nz < 16 {
                        Some(data)
                    } else {
                        neighbors[face]
                    };
                    let hidden = neighbor
                        .and_then(|d| {
                            catalog
                                .states
                                .get(&d.state((ny & 15) * 256 + (nz & 15) * 16 + (nx & 15)))
                        })
                        .is_some_and(|s| catalog.hidden(state, s, face));
                    if !hidden {
                        visible |= 1 << face;
                    }
                }
                let position = [
                    job.key.0 * 16 + x as i32,
                    job.key.1 * 16 + y as i32,
                    job.key.2 * 16 + z as i32,
                ];
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
