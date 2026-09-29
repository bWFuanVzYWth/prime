//! Direct resident surface ABI. Rich CPU facts are lowered once at publication;
//! each hit uses base + hardware primitive * 192, without a surface-key or corner dictionary.
use crate::resources::{Buffer, Context};
use ash::vk;
use prime_scene::{
    geometry::TriangleView,
    surface::{LightNode, SurfaceMesh, SurfaceTriangle},
    workers::CpuWorkers,
};
use std::{collections::BTreeMap, mem::MaybeUninit, sync::Arc};

pub(crate) const RECORD_BYTES: u64 = 192;
pub(crate) const MAX_RECORDS: u32 = (1_u64 << 32).div_euclid(RECORD_BYTES) as u32;
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
    let input = crate::packing::Input {
        triangles: TriangleView::Surfaces(&mesh.triangles),
        offset: None,
        flags: None,
    };
    for emitter in &mesh.lights.emitters {
        let surface = &mesh.triangles[emitter.primitive as usize];
        // Sampling has its own complete record: no extra shading-record or corner lookup.
        bytes.extend_from_slice(&record(surface, &input, textures)?);
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

pub(crate) fn pack(
    workers: &CpuWorkers,
    output: &mut [MaybeUninit<u8>],
    sources: &[crate::packing::Input<'_>],
    textures: &BTreeMap<u32, u32>,
) -> Result<(), String> {
    let (records, tail) = output.as_chunks_mut::<192>();
    assert!(tail.is_empty());
    let mut count = 0;
    let ends: Vec<_> = sources
        .iter()
        .map(|s| {
            count += s.triangles.len();
            count
        })
        .collect();
    assert_eq!(records.len(), count);
    workers.chunks_mut(records, 4096, |first, out| {
        let mut source = ends.partition_point(|&end| end <= first);
        for (i, destination) in out.iter_mut().enumerate() {
            let index = first + i;
            while ends[source] <= index {
                source += 1;
            }
            let input = &sources[source];
            let start = if source == 0 { 0 } else { ends[source - 1] };
            let TriangleView::Surfaces(triangles) = input.triangles else {
                return Err("Direct surface upload requires compiled surface records".into());
            };
            *destination =
                record(&triangles[index - start], input, textures)?.map(MaybeUninit::new);
        }
        Ok(())
    })
}

fn record(
    surface: &SurfaceTriangle,
    input: &crate::packing::Input<'_>,
    textures: &BTreeMap<u32, u32>,
) -> Result<[u8; 192], String> {
    if surface.media != [0; 2] {
        return Err(
            "Optical medium transport is not yet supported by the surface prototype renderer"
                .into(),
        );
    }
    let mut bytes = [0; 192];
    bytes[..128].copy_from_slice(&crate::packing::encode_triangle(
        &surface.geometry,
        input.offset,
        input.flags,
        textures,
    )?);
    let mut flags = 0_u32;
    if let Some(map) = surface.repeat {
        if [map.origin, map.du, map.dv]
            .as_flattened()
            .iter()
            .any(|x| !x.is_finite())
        {
            return Err("Invalid repeated texture mapping".into());
        }
        flags |= 1;
        for i in 0..2 {
            let row = [map.du[i], map.dv[i], map.origin[i], 0.0];
            bytes[128 + i * 16..144 + i * 16]
                .copy_from_slice(row.map(f32::to_le_bytes).as_flattened());
        }
    }
    if surface.emission.two_sided {
        flags |= 2;
    }
    bytes[160..172].copy_from_slice(
        surface
            .emission
            .radiance
            .map(f32::to_le_bytes)
            .as_flattened(),
    );
    bytes[172..176].copy_from_slice(&surface.emitter_area_weight.to_le_bytes());
    let identity = [
        surface.media[0],
        surface.media[1],
        surface.emitter.unwrap_or(u32::MAX),
        flags,
    ];
    bytes[176..192].copy_from_slice(identity.map(u32::to_le_bytes).as_flattened());
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::{
        geometry::CompiledQuad,
        surface::{Emission, Provenance, SurfaceCompiler, SurfaceQuad},
    };

    #[test]
    fn expanded_surface_fields_have_fixed_offsets_and_keep_hardware_positions() {
        let mut q = SurfaceQuad::from_closed(
            CompiledQuad {
                positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
                uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
                color: [0.5; 4],
                texture_id: 7,
                flags: 1,
            },
            Provenance::default(),
        );
        q.emission = Emission {
            radiance: [2., 3., 4.],
            two_sided: true,
        };
        let mut b = q.clone();
        b.geometry.positions.iter_mut().for_each(|p| p[0] += 1.);
        let mesh = SurfaceCompiler::new().compile(1, &[q, b]).unwrap();
        let input = crate::packing::Input {
            triangles: TriangleView::Surfaces(&mesh.triangles),
            offset: None,
            flags: Some(1),
        };
        let bytes = record(&mesh.triangles[0], &input, &BTreeMap::from([(7, 2)])).unwrap();
        let f = |i| f32::from_le_bytes(bytes[i..i + 4].try_into().unwrap());
        let u = |i| u32::from_le_bytes(bytes[i..i + 4].try_into().unwrap());
        for (i, p) in mesh.triangles[0].geometry.positions.iter().enumerate() {
            assert_eq!([f(i * 16), f(i * 16 + 4), f(i * 16 + 8)], *p);
        }
        assert_eq!(u(120), 2);
        assert_eq!(u(124), 1);
        assert_eq!(
            [f(128), f(132), f(136), f(144), f(148), f(152)],
            [1., 0., 0., 0., 1., 0.]
        );
        assert_eq!([f(160), f(164), f(168)], [2., 3., 4.]);
        assert_eq!(u(184), 0);
        assert_eq!(u(188), 3);
        assert!(u64::from(MAX_RECORDS) * RECORD_BYTES <= 1_u64 << 32);
        assert!(u64::from(MAX_RECORDS + 1) * RECORD_BYTES > 1_u64 << 32);
    }
}
