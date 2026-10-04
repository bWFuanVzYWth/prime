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
    pub source: Arc<prime_scene::surface::LightTree>,
    pub method: prime_scene::settings::LightSampling,
    pub inverse_areas: Vec<f32>,
    pub tree: TreePage,
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
    let (inverse_areas, tree) = sampler(context, &source, method)?;
    page_trace.succeed();
    Ok(Some(LightPage {
        key,
        emitters,
        source,
        method,
        inverse_areas,
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
        let (inverse_areas, tree) = sampler(context, &self.source, method)?;
        Ok(Self {
            key: self.key,
            emitters: Arc::clone(&self.emitters),
            source: Arc::clone(&self.source),
            method,
            inverse_areas,
            tree,
            format: self.format,
        })
    }
}

fn sampler(
    context: &Arc<Context>,
    source: &prime_scene::surface::LightTree,
    method: prime_scene::settings::LightSampling,
) -> Result<(Vec<f32>, TreePage), String> {
    use prime_scene::settings::LightSampling;
    let inverse_areas = {
        let _area_trace = prime_diagnostics::scope("light.areas");
        source
            .emitters
            .iter()
            .map(|emitter| 1.0 / emitter.area)
            .collect()
    };
    let tree = match method {
        LightSampling::TreeSphere => {
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
            page
        }
        LightSampling::Tree => {
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
            tree
        }
    };
    Ok((inverse_areas, tree))
}
