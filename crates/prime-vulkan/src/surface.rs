//! Quad light records. Sampling has direct corners and the same half-area proposal as hits.
use crate::resources::{Buffer, Context};
use ash::vk;
use prime_scene::surface::{LightNode, SurfaceMesh};
use std::{collections::BTreeMap, sync::Arc};

pub(crate) const RECORD_BYTES: u64 = 240;
pub(crate) const MAX_RECORDS: u32 = crate::packing::MAX_RECORDS;
pub(crate) const PAGE_BYTES: usize = 64;

pub(crate) struct LightPage {
    pub nodes: Buffer,
    pub emitters: Buffer,
    pub root: LightNode,
}

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
) -> Result<Option<LightPage>, String> {
    let Some(&root) = mesh.lights.nodes.first() else {
        return Ok(None);
    };
    let mut bytes = Vec::with_capacity(mesh.lights.emitters.len() * RECORD_BYTES as usize);
    for emitter in &mesh.lights.emitters {
        let face = &mesh.quads[emitter.quad as usize];
        let mut record = crate::packing::encode(face, 1, None, None, textures)?;
        // Position padding carries the area selection probability. Geometry still reads xyz.
        record[12..16].copy_from_slice(&emitter.first_fraction.to_le_bytes());
        bytes.extend_from_slice(&record);
    }
    let usage = vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS;
    let emitters = Buffer::upload_device(context, &bytes, usage)?;
    let nodes = Buffer::upload_device(context, &node_bytes(&mesh.lights.nodes), usage)?;
    Ok(Some(LightPage {
        nodes,
        emitters,
        root,
    }))
}
