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
    pub emitters: Buffer,
    pub lights: Vec<Light>,
    pub format: u32,
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
    let usage = vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS;
    let emitters = Buffer::upload_device(context, &bytes, usage)?;
    Ok(Some(LightPage {
        key,
        emitters,
        lights: mesh.lights.emitters.iter().map(summary).collect(),
        format: format as u32,
    }))
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
