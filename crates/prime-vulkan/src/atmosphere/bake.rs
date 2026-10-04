//! Offline asset preparation using the original balanced solver, unchanged numerically.
use super::{
    asset,
    gpu::{Compute, Texture, barrier},
};
use crate::resources::{Buffer, Context};
use ash::vk;
use safetensors::{
    Dtype,
    tensor::{TensorView, serialize},
};
use std::collections::HashMap;

const C: vk::DescriptorType = vk::DescriptorType::COMBINED_IMAGE_SAMPLER;
const I: vk::DescriptorType = vk::DescriptorType::SAMPLED_IMAGE;
const W: vk::DescriptorType = vk::DescriptorType::STORAGE_IMAGE;
const B: vk::DescriptorType = vk::DescriptorType::STORAGE_BUFFER;
const HALF: vk::Format = vk::Format::R16G16B16A16_SFLOAT;
const FLOAT: vk::Format = vk::Format::R32G32B32A32_SFLOAT;

pub fn bake_default_atmosphere(path: &std::path::Path) -> Result<(), String> {
    let context = Context::new()?;
    eprintln!(
        "[Prime atmosphere] Offline default asset on {}",
        context.name
    );
    let medium = Buffer::upload(
        &context,
        &asset::load_medium()?,
        vk::BufferUsageFlags::STORAGE_BUFFER,
    )?;
    let optical = Texture::new(&context, [512, 128, 1], HALF)?;
    let mut banks = Vec::new();
    for _ in 0..2 {
        banks.push([
            Texture::new(&context, [3200, 240, 1], HALF)?,
            Texture::new(&context, [160, 40, 1], FLOAT)?,
            Texture::new(&context, [160, 1, 1], FLOAT)?,
            Texture::new(&context, [800, 21, 1], FLOAT)?,
        ]);
    }
    let directions = Buffer::new(
        &context,
        4 * 160 * 1536 * 16,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        false,
    )?;
    let incoming = Buffer::new(
        &context,
        4 * 160 * 1536 * 16,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        false,
    )?;
    let moments = Buffer::new(
        &context,
        4 * 160 * 7 * 16,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        false,
    )?;
    let compute = Compute::new(
        &context,
        &[&[C, C, I, I, I, B, W, W, B, B, B, W, W]],
        3,
        &[
            prime_shaders::atmosphere_bake_transmittance(),
            prime_shaders::atmosphere_bake_directions(),
            prime_shaders::atmosphere_bake_incident(),
            prime_shaders::atmosphere_bake_moments(),
            prime_shaders::atmosphere_bake_multi_scattering(),
            prime_shaders::atmosphere_bake_ground(),
        ],
        16,
    )?;
    for slot in 0..3 {
        let bank = slot % 2;
        for (binding, ty, image) in [
            (0, C, &optical),
            (1, C, &banks[bank][0]),
            (2, I, &banks[bank][1]),
            (3, I, &banks[bank][2]),
            (4, I, &banks[bank][3]),
            (
                6,
                W,
                if slot == 2 {
                    &optical
                } else {
                    &banks[1 - bank][0]
                },
            ),
            (7, W, &banks[1 - bank][1]),
            (11, W, &banks[1 - bank][2]),
            (12, W, &banks[1 - bank][3]),
        ] {
            compute.image(0, slot, binding, ty, image);
        }
        for (binding, buffer) in [
            (5, &medium),
            (8, &directions),
            (9, &incoming),
            (10, &moments),
        ] {
            compute.buffer(0, slot, binding, buffer);
        }
    }
    let started = std::time::Instant::now();
    context.submit_named("atmosphere_bake", |command| {
        barrier(&context, command);
        for bank in &banks {
            for texture in bank {
                texture.clear(&context, command);
            }
        }
        barrier(&context, command);
        let run = |stage, bank, first: u32, count: u32, iteration: u32, groups| {
            let push = [first, count, iteration, 0].map(u32::to_le_bytes);
            compute.dispatch(command, stage, bank, push.as_flattened(), groups);
            barrier(&context, command);
        };
        run(0, 2, 0, 0, 0, [64, 16, 1]);
        run(5, 1, 0, 0, 0, [3, 1, 1]);
        for iteration in 1..=8 {
            let bank = ((iteration - 1) & 1) as usize;
            for first in (0u32..40).step_by(4) {
                run(1, bank, first, 4, iteration, [24, 640, 1]);
                run(2, bank, first, 4, iteration, [24, 640, 1]);
                run(3, bank, first, 4, iteration, [10, 1, 1]);
                if first < 20 {
                    run(4, bank, first, 4, iteration, [400, 6, 1]);
                }
                if first == 0 {
                    run(5, bank, first, 4, iteration, [3, 1, 1]);
                }
            }
        }
    })?;
    eprintln!(
        "[Prime atmosphere] Solve submit + completion: {:?}",
        started.elapsed()
    );
    let specs = [
        ("optical_depth", &optical, Dtype::F16, vec![128, 512, 4]),
        (
            "scattering_source",
            &banks[0][0],
            Dtype::F16,
            vec![240, 3200, 4],
        ),
        ("incident_mean", &banks[0][1], Dtype::F32, vec![40, 160, 4]),
        ("ground_radiance", &banks[0][2], Dtype::F32, vec![1, 160, 4]),
        (
            "rayleigh_source",
            &banks[0][3],
            Dtype::F32,
            vec![21, 800, 4],
        ),
    ];
    let bytes = specs
        .iter()
        .map(|(_, image, _, _)| image.read(&context))
        .collect::<Result<Vec<_>, _>>()?;
    let tensors = specs
        .iter()
        .zip(&bytes)
        .map(|((name, _, dtype, shape), data)| {
            TensorView::new(*dtype, shape.clone(), data)
                .map(|view| (*name, view))
                .map_err(|e| e.to_string())
        })
        .collect::<Result<Vec<_>, _>>()?;
    let metadata: HashMap<_, _> = [
        ("schema", "prime.atmosphere.balanced.v1"),
        ("source", "b66b16342afe38e788a5ece5371d3b3a67c5909a"),
        (
            "medium_sha256",
            "be1ae66c600c6df21ea730cd24b10bb88b9f6dfa00200539a4cc65420c7aefb5",
        ),
        ("density_scale", "1"),
        ("iterations", "8"),
        ("incident_directions", "1536"),
        ("source_dimensions", "40,160,20,12"),
        ("wavelength_nm", "450,510,580,650"),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v.to_owned()))
    .collect();
    let encoded = serialize(tensors, Some(metadata)).map_err(|e| e.to_string())?;
    std::fs::write(path, &encoded).map_err(|e| e.to_string())?;
    eprintln!(
        "[Prime atmosphere] Wrote {} bytes to {}",
        encoded.len(),
        path.display()
    );
    Ok(())
}
