//! Quad light records. Sampling has direct corners and the same half-area proposal as hits.
use crate::light_grid_cpu::Light;
use crate::resources::{Buffer, Context};
use ash::vk;
#[cfg(feature = "light-sampling-bench")]
use prime_scene::surface::LightNode;
use prime_scene::surface::SurfaceMesh;
use std::{collections::BTreeMap, sync::Arc};

pub(crate) const MAX_RECORDS: u32 = crate::packing::MAX_RECORDS;
pub(crate) const PAGE_BYTES: usize = 64;

pub(crate) struct LightPage {
    pub key: u64,
    pub emitters: Arc<Buffer>,
    pub source: Arc<prime_scene::surface::LightTree>,
    pub method: prime_scene::settings::LightSampling,
    pub lights: Vec<Light>,
    pub tree: Option<TreePage>,
    pub format: u32,
}

pub(crate) struct TreePage {
    pub nodes: Buffer,
    pub root: prime_scene::surface::LightNode,
    pub sphere_root: Option<crate::light_sphere_cpu::Root>,
    pub paths: Vec<u32>,
}

#[cfg(feature = "light-sampling-bench")]
pub(crate) fn node_bytes(nodes: &[LightNode]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(nodes.len() * 32);
    for node in nodes {
        crate::float4(
            &mut bytes,
            [
                node.bounds[0][0],
                node.bounds[0][1],
                node.bounds[0][2],
                node.power,
            ],
        );
        for x in node.bounds[1] {
            crate::float(&mut bytes, x);
        }
        crate::uint(&mut bytes, node.child);
    }
    bytes
}

pub(crate) fn upload_lights(
    context: &Arc<Context>,
    mesh: &SurfaceMesh,
    textures: &BTreeMap<u32, u32>,
    key: u64,
    method: prime_scene::settings::LightSampling,
) -> Result<Option<LightPage>, String> {
    if mesh.lights.emitters.is_empty() {
        return Ok(None);
    }
    let mut page_trace = prime_diagnostics::scope("light.page");
    page_trace.fail();
    page_trace.count("emit", mesh.lights.emitters.len() as u64);
    let mut encode_trace = prime_diagnostics::scope("light.encode");
    encode_trace.fail();
    let format = mesh
        .lights
        .emitters
        .iter()
        .map(|e| crate::packing::format(&mesh.quads[e.quad as usize]))
        .max()
        .unwrap_or(1)
        .max(1);
    let stride = crate::packing::stride(format);
    let mut bytes = Vec::with_capacity(mesh.lights.emitters.len() * stride);
    for emitter in &mesh.lights.emitters {
        let face = &mesh.quads[emitter.quad as usize];
        let mut record = crate::packing::encode(face, format, None, None, textures)?;
        // Position padding carries the area selection probability. Geometry still reads xyz.
        record[12..16].copy_from_slice(&emitter.first_fraction.to_le_bytes());
        bytes.extend_from_slice(&record[..stride]);
    }
    encode_trace.count("bytes", bytes.len() as u64);
    encode_trace.count("fmt", format as u64);
    encode_trace.succeed();
    drop(encode_trace);
    let usage = vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS;
    let emitters = Arc::new(Buffer::upload_device(context, &bytes, usage)?);
    let source = Arc::clone(&mesh.lights);
    let (lights, tree) = sampler(context, &source, method)?;
    page_trace.succeed();
    Ok(Some(LightPage {
        key,
        emitters,
        source,
        method,
        lights,
        tree,
        format: format as u32,
    }))
}

impl LightPage {
    pub(crate) fn rebuild_sampler(
        &self,
        context: &Arc<Context>,
        method: prime_scene::settings::LightSampling,
    ) -> Result<Self, String> {
        let (lights, tree) = sampler(context, &self.source, method)?;
        Ok(Self {
            key: self.key,
            emitters: Arc::clone(&self.emitters),
            source: Arc::clone(&self.source),
            method,
            lights,
            tree,
            format: self.format,
        })
    }
}

fn sampler(
    context: &Arc<Context>,
    source: &prime_scene::surface::LightTree,
    method: prime_scene::settings::LightSampling,
) -> Result<(Vec<Light>, Option<TreePage>), String> {
    use prime_scene::settings::LightSampling;
    let lights = {
        let _summary_trace = prime_diagnostics::scope("light.summary");
        source
            .emitters
            .iter()
            .map(|emitter| {
                if method != LightSampling::Grid {
                    Light {
                        power: emitter.power,
                        inv_area: 1.0 / emitter.area,
                        ..Light::default()
                    }
                } else {
                    summary(emitter)
                }
            })
            .collect()
    };
    let tree = if method == LightSampling::TreeSphere {
        let mut trace = prime_diagnostics::scope("ls.local");
        trace.fail();
        let tree = crate::light_sphere_cpu::Tree::local(&source.emitters)?;
        let bytes = crate::light_sphere_cpu::node_bytes(&tree.nodes);
        trace.count("nodes", tree.nodes.len() as u64);
        trace.count("bytes", bytes.len() as u64);
        let page = TreePage {
            nodes: Buffer::upload_device(
                context,
                &bytes,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            )?,
            root: source.nodes[0],
            sphere_root: Some(tree.root()),
            paths: tree.paths,
        };
        trace.succeed();
        Some(page)
    } else if method == LightSampling::Tree {
        let mut trace = prime_diagnostics::scope("lt.local");
        trace.fail();
        let distance = crate::light_distance_cpu::Tree::local(&source.nodes, &source.emitters)?;
        let bytes = crate::light_distance_cpu::node_bytes(&distance.nodes);
        trace.count("nodes", distance.nodes.len() as u64);
        trace.count("bytes", bytes.len() as u64);
        let tree = TreePage {
            nodes: Buffer::upload_device(
                context,
                &bytes,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            )?,
            root: source.nodes[0],
            sphere_root: None,
            paths: distance.paths,
        };
        trace.succeed();
        Some(tree)
    } else {
        None
    };
    Ok((lights, tree))
}

fn summary(emitter: &prime_scene::surface::Emitter) -> Light {
    let positions = emitter.positions.map(|p| p.map(f64::from));
    let triangles = [[0, 1, 2], [2, 3, 0]];
    let weights = [
        f64::from(emitter.first_fraction),
        1.0 - f64::from(emitter.first_fraction),
    ];
    let means = triangles.map(|indices| {
        std::array::from_fn::<_, 3, _>(|a| {
            indices.iter().map(|&i| positions[i][a]).sum::<f64>() / 3.0
        })
    });
    let center: [f64; 3] =
        std::array::from_fn(|a| weights[0] * means[0][a] + weights[1] * means[1][a]);
    // The uniform-area second central moment equals (|u|²+|v|²)/3 for a rectangle.
    // Area weighting also handles arbitrary quads and a repeated degenerate corner.
    let extent = triangles
        .iter()
        .enumerate()
        .map(|(half, indices)| {
            let within: f64 = indices
                .iter()
                .flat_map(|&i| (0..3).map(move |a| (positions[i][a] - means[half][a]).powi(2)))
                .sum::<f64>()
                / 12.0;
            let between: f64 = (0..3).map(|a| (means[half][a] - center[a]).powi(2)).sum();
            weights[half] * (within + between)
        })
        .sum::<f64>();
    Light {
        center: center.map(|x| x as f32),
        power: emitter.power,
        inv_area: 1.0 / emitter.area,
        extent: extent as f32,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn light_summary_matches_rectangular_softening_and_degenerate_triangle_area() {
        let mut emitter = prime_scene::surface::Emitter {
            quad: 0,
            positions: [[-2., -1., 0.], [2., -1., 0.], [2., 1., 0.], [-2., 1., 0.]],
            first_fraction: 0.5,
            radiance: [1.; 3],
            area: 8.,
            power: 8.,
            two_sided: false,
        };
        let rectangle = summary(&emitter);
        assert_eq!(rectangle.center, [0.; 3]);
        assert_eq!(rectangle.inv_area, 0.125);
        assert_eq!(rectangle.extent, 5. / 3.);
        emitter.positions[3] = emitter.positions[2];
        emitter.first_fraction = 1.0;
        emitter.area = 4.;
        let triangle = summary(&emitter);
        assert_eq!(triangle.center, [2. / 3., -1. / 3., 0.]);
        assert_eq!(triangle.inv_area, 0.25);
        assert!(triangle.extent > 0. && triangle.extent < rectangle.extent);
    }
}
