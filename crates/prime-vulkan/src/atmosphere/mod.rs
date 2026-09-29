//! Fixed Prime atmosphere physics; separately scheduled consumer LUTs.
mod asset;
#[cfg(feature = "atmosphere-bake")]
mod bake;
mod gpu;
#[cfg(all(test, feature = "shader-tests"))]
mod tests;
#[cfg(feature = "atmosphere-bake")]
pub use bake::bake_default_atmosphere;

use crate::resources::{Buffer, Context};
use ash::vk;
use gpu::{Compute, Texture, barrier};
use prime_scene::{environment::Environment, scene::Camera};
use safetensors::{Dtype, SafeTensors};
use std::sync::Arc;

pub(crate) const CONSUMER_TYPES: &[vk::DescriptorType] = &[
    vk::DescriptorType::STORAGE_BUFFER,
    vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
    vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
    vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
    vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
];
pub(crate) fn consumer_layout(context: &Context) -> Result<vk::DescriptorSetLayout, String> {
    gpu::descriptor_layout(context, CONSUMER_TYPES)
}

pub(super) struct Atmosphere {
    context: Arc<Context>,
    compute: Compute,
    _medium: Buffer,
    _physical: Vec<Texture>,
    _frames: Vec<Buffer>,
    _sky: Texture,
    _transmittance: Texture,
    _aerial_radiance: Texture,
    _aerial_transmittance: Texture,
    shadow_cells: Buffer,
    shadow_epoch: u32,
    shadow_key: Option<([u32; 4], u64)>,
    aerial_key: Option<[u32; 23]>,
    aerial_t_key: Option<[u32; 13]>,
    pub aerial_updates: u64,
    pub aerial_t_updates: u64,
    frame_keys: [Option<[u32; 24]>; crate::FRAME_SLOTS],
    sky_key: Option<[u32; 2]>,
    transmittance_key: Option<u32>,
    pub sky_updates: u64,
    pub transmittance_updates: u64,
}

impl Atmosphere {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        let half = vk::Format::R16G16B16A16_SFLOAT;
        let float = vk::Format::R32G32B32A32_SFLOAT;
        for format in [half, float] {
            if !context.supports_linear_sampling(format) {
                return Err(format!("Atmosphere requires linear sampling of {format:?}"));
            }
        }
        let bytes = include_bytes!("../../assets/atmosphere/default.safetensors");
        let (_, metadata) = SafeTensors::read_metadata(bytes).map_err(|e| e.to_string())?;
        let metadata = metadata
            .metadata()
            .as_ref()
            .ok_or("Atmosphere cache metadata missing")?;
        for (key, value) in [
            ("schema", "prime.atmosphere.balanced.v1"),
            ("density_scale", "1"),
            ("iterations", "8"),
            (
                "medium_sha256",
                "be1ae66c600c6df21ea730cd24b10bb88b9f6dfa00200539a4cc65420c7aefb5",
            ),
        ] {
            if metadata.get(key).map(String::as_str) != Some(value) {
                return Err(format!("Invalid atmosphere cache {key}"));
            }
        }
        let tensors = SafeTensors::deserialize(bytes).map_err(|e| e.to_string())?;
        let medium = Buffer::upload_device(
            context,
            &asset::medium(asset::MEDIUM)?,
            vk::BufferUsageFlags::STORAGE_BUFFER,
        )?;
        let mut physical = vec![];
        for (name, extent, dtype, format) in [
            ("optical_depth", [512, 128, 1], Dtype::F16, half),
            ("scattering_source", [3200, 240, 1], Dtype::F16, half),
            ("incident_mean", [160, 40, 1], Dtype::F32, float),
            ("ground_radiance", [160, 1, 1], Dtype::F32, float),
            ("rayleigh_source", [800, 21, 1], Dtype::F32, float),
        ] {
            let data = tensors.tensor(name).map_err(|e| e.to_string())?;
            if data.dtype() != dtype || data.shape() != [extent[1] as usize, extent[0] as usize, 4]
            {
                return Err(format!("Invalid atmosphere cache tensor {name}"));
            }
            let image = Texture::new(context, extent, format)?;
            image.upload(context, data.data())?;
            physical.push(image);
        }
        let sky = Texture::new(context, [256, 256, 1], float)?;
        let transmittance = Texture::new(context, [8193, 1, 1], half)?;
        let aerial_radiance = Texture::new(context, [128, 256, 128], half)?;
        let aerial_transmittance = Texture::new(context, [128, 64, 128], half)?;
        let c = vk::DescriptorType::COMBINED_IMAGE_SAMPLER;
        let i = vk::DescriptorType::SAMPLED_IMAGE;
        let b = vk::DescriptorType::STORAGE_BUFFER;
        let w = vk::DescriptorType::STORAGE_IMAGE;
        let shadow_cells = Buffer::new(
            context,
            512 * 512 * 5 * 16,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?;
        let compute = Compute::new(
            context,
            &[
                &[c, c, i, i, i, b],
                CONSUMER_TYPES,
                &[w, w, w, w, b],
                &[vk::DescriptorType::ACCELERATION_STRUCTURE_KHR, b, b, b, b],
            ],
            crate::FRAME_SLOTS,
            &[
                include_bytes!(concat!(env!("OUT_DIR"), "/atmosphere_prepare.spv")),
                include_bytes!(concat!(env!("OUT_DIR"), "/atmosphere_sky_update.spv")),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/atmosphere_transmittance_update.spv"
                )),
                include_bytes!(concat!(env!("OUT_DIR"), "/atmosphere_aerial_update.spv")),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/atmosphere_aerial_transmittance_update.spv"
                )),
                include_bytes!(concat!(env!("OUT_DIR"), "/atmosphere_shadow_demand.spv")),
                include_bytes!(concat!(env!("OUT_DIR"), "/atmosphere_shadow_resolve.spv")),
            ],
            96,
        )?;
        let mut frames = vec![];
        for slot in 0..crate::FRAME_SLOTS {
            frames.push(Buffer::new(
                context,
                400,
                vk::BufferUsageFlags::STORAGE_BUFFER,
                false,
            )?);
            for (binding, image) in physical.iter().enumerate() {
                compute.image(
                    0,
                    slot,
                    binding as u32,
                    if binding < 2 { c } else { i },
                    image,
                );
            }
            compute.buffer(0, slot, 5, &medium);
            compute.buffer(1, slot, 0, &frames[slot]);
            compute.buffer(2, slot, 4, &shadow_cells);
            for (binding, image) in [
                &sky,
                &transmittance,
                &aerial_radiance,
                &aerial_transmittance,
            ]
            .into_iter()
            .enumerate()
            {
                compute.image(1, slot, binding as u32 + 1, c, image);
                compute.image(2, slot, binding as u32, w, image);
            }
        }
        Ok(Self {
            context: context.clone(),
            compute,
            _medium: medium,
            _physical: physical,
            _frames: frames,
            _sky: sky,
            _transmittance: transmittance,
            _aerial_radiance: aerial_radiance,
            _aerial_transmittance: aerial_transmittance,
            shadow_cells,
            shadow_epoch: 0,
            shadow_key: None,
            aerial_key: None,
            aerial_t_key: None,
            aerial_updates: 0,
            aerial_t_updates: 0,
            frame_keys: [None; crate::FRAME_SLOTS],
            sky_key: None,
            transmittance_key: None,
            sky_updates: 0,
            transmittance_updates: 0,
        })
    }
    pub fn descriptor(&self, slot: usize) -> vk::DescriptorSet {
        self.compute.sets[1][slot]
    }
    pub fn prepare(
        &mut self,
        environment: Environment,
        camera: &Camera,
        aspect: f32,
        slot: usize,
        scene: Option<(&crate::geometry::Geometry, u64)>,
    ) -> Result<(), String> {
        let radius = environment.eye_radius_km();
        let mut words = [0u32; 24];
        let floats = [
            environment.sun_direction[0],
            environment.sun_direction[1],
            environment.sun_direction[2],
            12.5,
            camera.position[0],
            camera.position[1],
            camera.position[2],
            radius,
            camera.forward[0],
            camera.forward[1],
            camera.forward[2],
            (camera.vertical_fov_radians * 0.5).tan(),
            camera.right[0],
            camera.right[1],
            camera.right[2],
            aspect,
            camera.up[0],
            camera.up[1],
            camera.up[2],
            0.,
            0.,
            0.,
            0.,
            0.,
        ];
        for (out, value) in words.iter_mut().zip(floats) {
            *out = value.to_bits();
        }
        let mut aerial_key = [0u32; 23];
        aerial_key[..20].copy_from_slice(&words[..20]);
        let revision = scene.map_or(0, |(_, revision)| revision);
        aerial_key[20] = revision as u32;
        aerial_key[21] = (revision >> 32) as u32;
        aerial_key[22] = u32::from(scene.is_some());
        let aerial = self.aerial_key != Some(aerial_key);
        let mut aerial_t_key = [0u32; 13];
        aerial_t_key[0] = radius.to_bits();
        aerial_t_key[1..].copy_from_slice(&words[8..20]);
        let aerial_t = self.aerial_t_key != Some(aerial_t_key);
        // A fixed plane per 2 km slab gives cached columns an invariant finite ray segment.
        let plane = ((camera
            .position
            .iter()
            .zip(environment.sun_direction)
            .map(|(a, b)| a * b)
            .sum::<f32>()
            / 2048.0)
            .floor()
            + 0.5)
            * 2048.0;
        let shadow_key = ([words[0], words[1], words[2], plane.to_bits()], revision);
        let clear_shadow = self.shadow_key != Some(shadow_key) || self.shadow_epoch >= 0x7ffffffe;
        if aerial {
            self.shadow_epoch = if clear_shadow {
                1
            } else {
                self.shadow_epoch + 1
            };
        }
        words[20] = self.shadow_epoch;
        words[21] = if scene.is_some() { 1f32.to_bits() } else { 0 };
        words[22] = plane.to_bits();
        if aerial && let Some((geometry, _)) = scene {
            self.compute.acceleration(3, slot, 0, geometry.top.handle());
            for (binding, buffer) in [
                &geometry.textures.metadata,
                &geometry.textures.texels,
                &geometry.objects.metadata,
                &geometry.static_bases,
            ]
            .into_iter()
            .enumerate()
            {
                self.compute.buffer(3, slot, binding as u32 + 1, buffer);
            }
        }
        let sky_key = [radius.to_bits(), environment.sun_direction[1].to_bits()];
        let sky = self.sky_key != Some(sky_key);
        let transmittance = self.transmittance_key != Some(radius.to_bits());
        let frame = self.frame_keys[slot] != Some(words);
        if !(sky || transmittance || frame || aerial || aerial_t) {
            return Ok(());
        }
        let push = words.map(u32::to_le_bytes);
        self.context.submit_named("atmosphere_update", |command| {
            barrier(&self.context, command);
            if frame {
                self.compute
                    .dispatch(command, 0, slot, push.as_flattened(), [1, 1, 1]);
                barrier(&self.context, command);
            }
            if aerial && scene.is_some() {
                if clear_shadow {
                    unsafe {
                        self.context.device.cmd_fill_buffer(
                            command,
                            self.shadow_cells.buffer,
                            0,
                            self.shadow_cells.size,
                            0x80000000,
                        );
                    }
                    barrier(&self.context, command);
                }
                self.compute
                    .dispatch(command, 5, slot, push.as_flattened(), [1, 256, 1]);
                barrier(&self.context, command);
                self.compute
                    .dispatch(command, 6, slot, push.as_flattened(), [64, 64, 5]);
                barrier(&self.context, command);
            }
            if aerial {
                self.compute
                    .dispatch(command, 3, slot, push.as_flattened(), [1, 256, 1]);
            }
            if aerial_t {
                self.compute
                    .dispatch(command, 4, slot, push.as_flattened(), [128, 64, 1]);
            }
            if sky {
                self.compute
                    .dispatch(command, 1, slot, push.as_flattened(), [1, 256, 1]);
            }
            if transmittance {
                self.compute
                    .dispatch(command, 2, slot, push.as_flattened(), [129, 1, 1]);
            }
            barrier(&self.context, command);
        })?;
        self.aerial_key = Some(aerial_key);
        self.aerial_t_key = Some(aerial_t_key);
        if scene.is_some() {
            self.shadow_key = Some(shadow_key);
        }
        self.aerial_updates += u64::from(aerial);
        self.aerial_t_updates += u64::from(aerial_t);
        self.frame_keys[slot] = Some(words);
        self.sky_key = Some(sky_key);
        self.transmittance_key = Some(radius.to_bits());
        self.sky_updates += u64::from(sky);
        self.transmittance_updates += u64::from(transmittance);
        Ok(())
    }
}
