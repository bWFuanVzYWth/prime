//! Quad light records. Sampling has direct corners and the same half-area proposal as hits.
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
    #[cfg(test)]
    pub source: Arc<prime_scene::surface::LightTree>,
    // Stable material row; None retains the legacy compact-emitter identity.
    pub static_page: Option<u32>,
    pub inverse_areas: Vec<f32>,
    pub tree: TreePage,
    pub format: u32,
}

pub(crate) struct TreePage {
    pub nodes: Buffer,
    pub root: prime_scene::surface::LightNode,
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
        // The second corner's unused w lane carries the physical quad mapping.
        record[28..32].copy_from_slice(&emitter.quad.to_le_bytes());
        bytes.extend_from_slice(&record[..stride]);
    }
    encode_trace.count("bytes", bytes.len() as u64);
    encode_trace.count("fmt", format as u64);
    encode_trace.succeed();
    drop(encode_trace);
    let usage = vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS;
    let emitters = Arc::new(Buffer::upload_device(context, &bytes, usage)?);
    let (inverse_areas, tree) = sampler(context, &mesh.lights)?;
    page_trace.succeed();
    Ok(Some(LightPage {
        key,
        emitters,
        #[cfg(test)]
        source: Arc::clone(&mesh.lights),
        static_page: None,
        inverse_areas,
        tree,
        format: format as u32,
    }))
}

fn sampler(
    context: &Arc<Context>,
    source: &prime_scene::surface::LightTree,
) -> Result<(Vec<f32>, TreePage), String> {
    let inverse_areas = {
        let _area_trace = prime_diagnostics::scope("light.areas");
        source
            .emitters
            .iter()
            .map(|emitter| 1.0 / emitter.area)
            .collect()
    };
    let mut trace = prime_diagnostics::scope("lt.local");
    trace.fail();
    let distance = crate::light_distance_cpu::Tree::local(&source.nodes, &source.emitters)?;
    let bytes = crate::light_distance_cpu::node_bytes(&distance.nodes);
    trace.count("nodes", distance.nodes.len() as u64);
    trace.count("bytes", bytes.len() as u64);
    let tree = TreePage {
        nodes: Buffer::upload_device(context, &bytes, vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS)?,
        root: source.nodes[0],
        paths: distance.paths,
    };
    trace.succeed();
    Ok((inverse_areas, tree))
}
