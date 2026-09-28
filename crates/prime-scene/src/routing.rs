//! Appearance routing. All inputs are owned values; compilation cannot query the host.
use crate::{
    protocol::{Reader, validate_triangle_capacity},
    scene::{Mesh, MeshVersion, SectionSequence, SourceScene, Triangle},
    workers::CpuWorkers,
};
use std::{collections::BTreeMap, sync::Arc};

mod fluid;
mod particles;
#[cfg(test)]
mod tests;

#[derive(Default)]
pub(crate) struct SourceRoutes {
    geometry: BTreeMap<u64, Geometry>,
    workers: Option<CpuWorkers>,
}

struct Geometry {
    quads: Vec<Quad>,
    tint_count: usize,
}

#[derive(Clone)]
struct Quad {
    positions: [[f32; 3]; 4],
    colors: [[u8; 4]; 4],
    uvs: [[f32; 2]; 4],
    layer: u32,
    face: u32,
    tint: u32,
}

struct Placement<'a> {
    geometry: &'a Geometry,
    offset: [f32; 3],
    visible: u32,
    tints: &'a [u8],
}

struct QuadJob<'a> {
    quad: &'a Quad,
    offset: [f32; 3],
    tint: [u8; 4],
}

// Retention is proved against published content, with no second source cache or hash identity.
enum LayerPlan {
    Retain(u32),
    Replace(u32, Mesh),
}
impl LayerPlan {
    fn layer(&self) -> u32 {
        match self {
            Self::Retain(layer) | Self::Replace(layer, _) => *layer,
        }
    }
}

fn quad_triangles(job: &QuadJob<'_>, layer: u32) -> [Triangle; 2] {
    let positions: [[f32; 3]; 4] = std::array::from_fn(|vertex| {
        std::array::from_fn(|axis| job.offset[axis] + job.quad.positions[vertex][axis])
    });
    let colors: [[f32; 4]; 4] = std::array::from_fn(|vertex| {
        std::array::from_fn(|channel| {
            let encoded =
                u32::from(job.quad.colors[vertex][channel]) * u32::from(job.tint[channel]) / 255;
            encoded as f32 / 255.0
        })
    });
    [[0, 1, 2], [2, 3, 0]].map(|corners| Triangle {
        positions: corners.map(|i| positions[i]),
        colors: corners.map(|i| colors[i]),
        uvs: corners.map(|i| job.quad.uvs[i]),
        texture_id: 1,
        flags: layer,
    })
}
fn matching_prefix(mesh: &Mesh, origin: [f64; 3], layer: u32, jobs: &[QuadJob<'_>]) -> usize {
    if mesh.origin.map(f64::to_bits) != origin.map(f64::to_bits)
        || mesh.texture_id != 1
        || mesh.flags != layer
        || mesh.triangles.len() != jobs.len() * 2
    {
        return 0;
    }
    let matches = |i: usize, job: &QuadJob<'_>| {
        quad_triangles(job, layer)
            .iter()
            .zip((i * 2..i * 2 + 2).map(|index| mesh.triangles.triangle(index)))
            .all(|(a, b)| {
                a.positions.map(|p| p.map(f32::to_bits)) == b.positions.map(|p| p.map(f32::to_bits))
                    && a.colors.map(|p| p.map(f32::to_bits))
                        == b.colors.map(|p| p.map(f32::to_bits))
                    && a.uvs.map(|p| p.map(f32::to_bits)) == b.uvs.map(|p| p.map(f32::to_bits))
            })
    };
    jobs.iter()
        .enumerate()
        .position(|(i, job)| !matches(i, job))
        .unwrap_or(jobs.len())
}

const EMPTY_TRIANGLE: Triangle = Triangle {
    positions: [[0.0; 3]; 3],
    colors: [[0.0; 4]; 3],
    uvs: [[0.0; 2]; 3],
    texture_id: 1,
    flags: 0,
};

fn count(input: &mut Reader<'_>, minimum_bytes: usize) -> Result<usize, String> {
    let count = input.u32()? as usize;
    if count > (input.data.len() - input.offset) / minimum_bytes {
        return Err("truncated routed source records".into());
    }
    Ok(count)
}

impl SourceRoutes {
    /// Definitions precede section uses; retirements follow the last use in a sealed source batch.
    /// Compilation consumes definitions synchronously. Published meshes never borrow this dictionary.
    pub(crate) fn resources(&mut self, mut input: Reader<'_>) -> Result<(), String> {
        let definitions = count(&mut input, 16)?;
        let removals = count(&mut input, 8)?;
        let mut added = BTreeMap::new();
        for _ in 0..definitions {
            let id = input.u64()?;
            let quad_count = count(&mut input, 108)?;
            let tint_count = input.u32()? as usize;
            if id == 0
                || self.geometry.contains_key(&id)
                || added.contains_key(&id)
                || tint_count > quad_count
            {
                return Err("invalid routed geometry identity or tint count".into());
            }
            let mut quads = Vec::with_capacity(quad_count);
            for _ in 0..quad_count {
                let layer = input.u32()?;
                let face = input.u32()?;
                let tint = input.u32()?;
                if layer > 2 || face > 6 || tint != u32::MAX && tint as usize >= tint_count {
                    return Err("invalid routed quad attributes".into());
                }
                let mut quad = Quad {
                    positions: [[0.0; 3]; 4],
                    colors: [[0; 4]; 4],
                    uvs: [[0.0; 2]; 4],
                    layer,
                    face,
                    tint,
                };
                for vertex in 0..4 {
                    quad.positions[vertex] = input.vector()?;
                    if quad.positions[vertex].iter().any(|p| p.abs() > 4096.0) {
                        return Err("routed local geometry out of range".into());
                    }
                    quad.colors[vertex] = input.take(4)?.try_into().unwrap();
                    quad.uvs[vertex] = [input.f32()?, input.f32()?];
                }
                quads.push(quad);
            }
            added.insert(id, Geometry { quads, tint_count });
        }
        let mut retired = Vec::with_capacity(removals);
        for _ in 0..removals {
            let id = input.u64()?;
            if !self.geometry.contains_key(&id) || added.contains_key(&id) {
                return Err("unknown routed geometry retirement".into());
            }
            retired.push(id);
        }
        input.finish()?;
        retired.sort_unstable();
        if retired.windows(2).any(|p| p[0] == p[1]) {
            return Err("duplicate routed geometry retirement".into());
        }
        self.geometry.append(&mut added);
        for id in retired {
            self.geometry.remove(&id);
        }
        Ok(())
    }
}

impl SourceScene {
    pub(crate) fn route_section(&mut self, mut input: Reader<'_>) -> Result<(), String> {
        let key = input.u64()?;
        let sequence = SectionSequence(input.u64()?);
        let origin = input.origin()?;
        let placements = count(&mut input, 28)?;
        let fluids = count(&mut input, 4)?;
        if sequence <= self.section_completed
            || sequence <= self.removed.get(&key).copied().unwrap_or_default()
            || self
                .meshes
                .range((key, 0)..=(key, u32::MAX))
                .any(|(_, mesh)| mesh.revision.observed_at() >= sequence)
        {
            return Err("stale routed section sequence".into());
        }
        let mut draws = Vec::with_capacity(placements);
        for _ in 0..placements {
            let id = input.u64()?;
            let geometry = self
                .routing
                .geometry
                .get(&id)
                .ok_or("missing routed geometry")?;
            let offset = input.vector()?;
            let visible = input.u32()?;
            let tints = count(&mut input, 4)?;
            if visible & !127 != 0
                || visible & 64 == 0
                || tints != geometry.tint_count
                || offset.iter().any(|p| p.abs() > 4096.0)
            {
                return Err("invalid routed placement".into());
            }
            // Borrow just this synchronous packet; avoid a heap allocation per placement.
            let colors = input.take(tints * 4)?;
            draws.push(Placement {
                geometry,
                offset,
                visible,
                tints: colors,
            });
        }
        let mut fluid_quads = Vec::new();
        for _ in 0..fluids {
            fluid::decode(&mut input, &mut fluid_quads)?;
        }
        input.finish()?;
        let mut jobs: [Vec<QuadJob<'_>>; 3] = Default::default();
        for draw in &draws {
            for quad in &draw.geometry.quads {
                if draw.visible & (1 << quad.face) != 0 {
                    jobs[quad.layer as usize].push(QuadJob {
                        quad,
                        offset: draw.offset,
                        tint: if quad.tint == u32::MAX {
                            [255; 4]
                        } else {
                            draw.tints[quad.tint as usize * 4..][..4]
                                .try_into()
                                .unwrap()
                        },
                    });
                }
            }
        }
        for quad in &fluid_quads {
            jobs[quad.layer as usize].push(QuadJob {
                quad,
                offset: [0.0; 3],
                tint: [255; 4],
            });
        }
        let old_count: usize = self
            .meshes
            .range((key, 0)..=(key, u32::MAX))
            .map(|(_, mesh)| mesh.triangles.len())
            .sum();
        let new_count = jobs.iter().try_fold(0usize, |total, jobs| {
            total
                .checked_add(
                    jobs.len()
                        .checked_mul(2)
                        .ok_or("routed geometry size overflow")?,
                )
                .ok_or("routed geometry size overflow")
        })?;
        validate_triangle_capacity(
            self.triangle_count - old_count
                + new_count
                + self.dynamic.triangles.len()
                + self.instances.triangle_count(),
        )?;
        if new_count != 0 && !self.textures.contains_key(&1) {
            return Err("routed terrain requires the block atlas".into());
        }
        let mut compiled = Vec::with_capacity(3);
        for (layer, jobs) in jobs.iter().enumerate() {
            if jobs.is_empty() {
                continue;
            }
            let previous = self.meshes.get(&(key, layer as u32));
            let retained =
                previous.map_or(0, |old| matching_prefix(old, origin, layer as u32, jobs));
            if retained == jobs.len() {
                compiled.push(LayerPlan::Retain(layer as u32));
                continue;
            }
            if self.routing.workers.is_none() {
                self.routing.workers = Some(CpuWorkers::configured()?);
            }
            // A trusted-length iterator initializes the Arc allocation directly. No
            // second full-size Vec allocation/copy is needed at publication.
            let mut triangles: Arc<[Triangle]> =
                std::iter::repeat_n(EMPTY_TRIANGLE, jobs.len() * 2).collect();
            let output = Arc::get_mut(&mut triangles).unwrap();
            if retained != 0 {
                for (target, triangle) in output[..retained * 2]
                    .iter_mut()
                    .zip(previous.unwrap().triangles.iter())
                {
                    *target = triangle;
                }
            }
            // A late mismatch must not cause already compared geometry to be expanded twice.
            self.routing.workers.as_ref().unwrap().chunks_mut(
                &mut output[retained * 2..],
                2048,
                |start, output| {
                    for (i, pair) in output.as_chunks_mut::<2>().0.iter_mut().enumerate() {
                        pair.copy_from_slice(&quad_triangles(
                            &jobs[retained + start / 2 + i],
                            layer as u32,
                        ));
                    }
                    Ok(())
                },
            )?;
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            for triangle in triangles.iter() {
                for position in triangle.positions {
                    for axis in 0..3 {
                        bounds[0][axis] = bounds[0][axis].min(position[axis]);
                        bounds[1][axis] = bounds[1][axis].max(position[axis]);
                    }
                }
            }
            compiled.push(LayerPlan::Replace(
                layer as u32,
                Mesh {
                    revision: MeshVersion::captured(sequence),
                    origin,
                    triangles: triangles.into(),
                    bounds,
                    texture_id: 1,
                    flags: layer as u32,
                },
            ));
        }
        self.publish_routed(key, sequence, origin, compiled, new_count, old_count)
    }

    fn publish_routed(
        &mut self,
        key: u64,
        sequence: SectionSequence,
        origin: [f64; 3],
        compiled: Vec<LayerPlan>,
        new_count: usize,
        old_count: usize,
    ) -> Result<(), String> {
        let removed: Vec<_> = self
            .meshes
            .range((key, 0)..=(key, u32::MAX))
            .filter(|((_, layer), _)| !compiled.iter().any(|next| next.layer() == *layer))
            .map(|(id, _)| *id)
            .collect();
        let moved = self.sections.origin(key) != Some(&origin);
        let revision = if moved
            || !removed.is_empty()
            || compiled
                .iter()
                .any(|plan| matches!(plan, LayerPlan::Replace(..)))
        {
            self.revision
                .checked_add(1)
                .ok_or("scene revision exhausted")?
        } else {
            self.revision
        };
        for id in removed {
            let old = self.meshes.remove(&id).unwrap();
            self.texture_lifetime.release(old.texture_id);
            self.edits.meshes.insert(id);
        }
        for plan in compiled {
            let LayerPlan::Replace(layer, mesh) = plan else {
                continue;
            };
            self.texture_lifetime.acquire(mesh.texture_id);
            if let Some(old) = self.meshes.insert((key, layer), mesh) {
                self.texture_lifetime.release(old.texture_id);
            }
            self.edits.meshes.insert((key, layer));
        }
        self.triangle_count = self.triangle_count - old_count + new_count;
        self.revision = revision;
        self.removed.insert(key, sequence);
        if moved {
            if let Some(&old) = self.sections.origin(key) {
                self.edits.availability(old);
            }
            self.edits.availability(origin);
            self.sections.publish(key, origin);
        }
        Ok(())
    }
}
