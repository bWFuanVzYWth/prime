//! Independent pre-optimization scalar compiler retained only as a semantic oracle.
use super::*;
pub(super) fn compile_slab(
    job: &mut Job,
    catalog: &Catalog,
    sections: &HashMap<Section, SectionData>,
) {
    let data = &sections[&job.key];
    let neighbors = job.key.neighbors().map(|n| sections.get(&n));
    let missing = model::State {
        flags: 0,
        model: 0,
        name: String::new(),
    };
    for y in job.first_y..job.first_y + 4 {
        for z in 0..16 {
            for x in 0..16 {
                let id = data.state(y * 256 + z * 16 + x);
                let state = catalog.states.get(&id).unwrap_or(&missing);
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
                        .is_some_and(|s| {
                            s.full()
                                || (s.name == state.name
                                    && (s.name == "minecraft:water"
                                        || s.name == "minecraft:lava"
                                        || s.name.ends_with("glass")))
                        });
                    if !hidden {
                        visible |= 1 << face;
                    }
                }
                let position = [
                    job.key.0 * 16 + x as i32,
                    job.key.1 * 16 + y as i32,
                    job.key.2 * 16 + z as i32,
                ];
                catalog.emit(state, position, visible, &mut job.layers, &mut job.hacks);
            }
        }
    }
}
