//! Named physical arrays. Container parsing and validation never enter a shader.
use crate::texture_asset::{TextureAsset, TextureSpec};
use ash::vk;
use prime_render_data::atmosphere_medium as medium_bytes;
use safetensors::{Dtype, SafeTensors};

const MEDIUM_BYTES: usize = 203896;
pub(super) struct Physical {
    pub name: &'static str,
    pub extent: [u32; 3],
    pub format: vk::Format,
    bytes: fn() -> &'static [u8],
}
pub(super) const PHYSICAL: &[Physical] = &[
    Physical {
        name: "optical_depth",
        extent: [512, 128, 1],
        format: vk::Format::R16G16B16A16_SFLOAT,
        bytes: prime_render_data::atmosphere_optical_depth,
    },
    Physical {
        name: "scattering_source",
        extent: [3200, 240, 1],
        format: vk::Format::R16G16B16A16_SFLOAT,
        bytes: prime_render_data::atmosphere_scattering_source,
    },
    Physical {
        name: "incident_mean",
        extent: [160, 40, 1],
        format: vk::Format::R32G32B32A32_SFLOAT,
        bytes: prime_render_data::atmosphere_incident_mean,
    },
    Physical {
        name: "ground_radiance",
        extent: [160, 1, 1],
        format: vk::Format::R32G32B32A32_SFLOAT,
        bytes: prime_render_data::atmosphere_ground_radiance,
    },
    Physical {
        name: "rayleigh_source",
        extent: [800, 21, 1],
        format: vk::Format::R32G32B32A32_SFLOAT,
        bytes: prime_render_data::atmosphere_rayleigh_source,
    },
];
impl Physical {
    pub fn parse(&self) -> Result<TextureAsset<'static>, String> {
        self.parse_bytes((self.bytes)())
    }
    fn parse_bytes<'a>(&self, bytes: &'a [u8]) -> Result<TextureAsset<'a>, String> {
        let image = TextureAsset::parse(
            bytes,
            TextureSpec {
                format: self.format,
                extent: [self.extent[0], self.extent[1], 0],
                levels: 1,
                primaries: 0,
            },
        )?;
        for (key, value) in [
            ("schema", "prime.atmosphere.balanced.v1"),
            ("density_scale", "1"),
            ("iterations", "8"),
            (
                "medium_sha256",
                "be1ae66c600c6df21ea730cd24b10bb88b9f6dfa00200539a4cc65420c7aefb5",
            ),
            ("source", "b66b16342afe38e788a5ece5371d3b3a67c5909a"),
            ("incident_directions", "1536"),
            ("source_dimensions", "40,160,20,12"),
            ("wavelength_nm", "450,510,580,650"),
            ("tensor", self.name),
            ("KTXorientation", "rd"),
        ] {
            if image.metadata_text(key)? != value {
                return Err(format!(
                    "Invalid atmosphere texture {} metadata {key}",
                    self.name
                ));
            }
        }
        Ok(image)
    }
}

fn decode_medium(encoded: &[u8]) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::with_capacity(MEDIUM_BYTES);
    crate::texture_asset::decode_zstd_into(
        encoded,
        &mut bytes.spare_capacity_mut()[..MEDIUM_BYTES],
    )?;
    // SAFETY: The exact-size decoder successfully initialized every destination byte.
    unsafe {
        bytes.set_len(MEDIUM_BYTES);
    }
    Ok(bytes)
}
pub(super) fn load_medium() -> Result<Vec<u8>, String> {
    // The decoded Safetensors owner ends after validation/packing; no borrowed tensor view
    // escapes. Packed bytes are then copied into the existing device-upload staging owner.
    medium(&decode_medium(medium_bytes())?)
}
pub(super) const SECTIONS: &[(&str, &[usize])] = &[
    ("heights", &[40, 4]),
    ("profiles", &[50, 7, 4]),
    ("phase", &[4, 1024, 4]),
    ("phase_mass", &[4, 4]),
    ("states", &[40, 160, 4]),
    ("quadrature", &[1024, 4]),
    ("phase_nodes", &[20, 4]),
    ("solar_mapping", &[40, 4, 4]),
    ("optical_heights", &[128, 4]),
    ("cone_nodes", &[12, 4]),
    ("source_coefficients", &[40, 6, 4]),
];

pub(super) fn medium(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let (_, metadata) = SafeTensors::read_metadata(bytes).map_err(|e| e.to_string())?;
    let metadata = metadata
        .metadata()
        .as_ref()
        .ok_or("Atmosphere metadata missing")?;
    for (key, expected) in [
        ("schema", "prime.atmosphere.medium.v1"),
        ("wavelength_nm", "450,510,580,650"),
        ("species", "inso,waso,soot,suso"),
        (
            "phase_coordinate",
            "u=cbrt((1-mu)/2); nodes=(i+0.5)/1024; linear-u; clamp-endpoints",
        ),
        ("phase_unit", "sr^-1"),
        ("phase_mu", "+1=forward"),
        ("distance_unit", "km"),
        ("density_scale", "1"),
    ] {
        if metadata.get(key).map(String::as_str) != Some(expected) {
            return Err(format!("Unsupported atmosphere metadata {key}"));
        }
    }
    let tensors = SafeTensors::deserialize(bytes).map_err(|e| e.to_string())?;
    let mut output = Vec::with_capacity(199584);
    for &(name, shape) in SECTIONS {
        let tensor = tensors.tensor(name).map_err(|e| e.to_string())?;
        if tensor.dtype() != Dtype::F32 || tensor.shape() != shape {
            return Err(format!("Invalid atmosphere array {name}"));
        }
        for word in tensor.data().as_chunks::<4>().0 {
            let value = f32::from_le_bytes(*word);
            if !value.is_finite() || (matches!(name, "phase" | "phase_mass") && value < 0.0) {
                return Err(format!("Invalid atmosphere value in {name}"));
            }
        }
        output.extend_from_slice(tensor.data());
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    #[test]
    fn packed_physical_textures_decode_to_independently_locked_source_bytes() {
        // Digests captured from the old Safetensors payloads before conversion, rather
        // than trusting decoded values or hashes generated by the runtime KTX reader.
        for (source, expected) in PHYSICAL.iter().zip([
            "dd2ed26cc9829352aa8b3a45731ce964848459991436a220c3eb8387e85e8e78",
            "9ada6228c63ab7af7a6c47028a75327bd050762262c32efc3d2c9d6b81bf175a",
            "92a2bac6be0c39dd55f4ca4834dcb38a269e8699944b5939dc607a97dd39a626",
            "223621430e36488e04dbb36bd12d27dc2ab0b9282ad08a256ba892513f9911d3",
            "cf36b541450c8841fef762ba93f2953657b33392972363aad82b32e6f091d277",
        ]) {
            let image = source.parse().unwrap();
            assert_eq!(image.level_extent(0), source.extent);
            let mut decoded = Vec::<u8>::with_capacity(image.level_size(0));
            image
                .decode_level_into(0, &mut decoded.spare_capacity_mut()[..image.level_size(0)])
                .unwrap();
            // SAFETY: A successful exact-size level decoder initializes this whole range.
            unsafe {
                decoded.set_len(image.level_size(0));
            }
            assert_eq!(
                format!("{:x}", Sha256::digest(&decoded)),
                expected,
                "{}",
                source.name
            );
        }
        let decoded = decode_medium(medium_bytes()).unwrap();
        assert_eq!(
            format!("{:x}", Sha256::digest(decoded)),
            "01605794ba83b35cf0375d6c5e8a394e20964372ecaff81e52dda02cfc9aa461"
        );
    }

    #[test]
    fn physical_texture_semantic_metadata_is_required() {
        let source = &PHYSICAL[3];
        for key in [
            "schema",
            "tensor",
            "KTXorientation",
            "medium_sha256",
            "density_scale",
            "wavelength_nm",
            "source_dimensions",
        ] {
            let mut bytes = (source.bytes)().to_vec();
            let offset = u32::from_le_bytes(bytes[56..60].try_into().unwrap()) as usize;
            let size = u32::from_le_bytes(bytes[60..64].try_into().unwrap()) as usize;
            let mut entry = key.as_bytes().to_vec();
            entry.push(0);
            let at = bytes[offset..offset + size]
                .windows(entry.len())
                .position(|window| window == entry)
                .unwrap();
            bytes[offset + at + entry.len()] = b'?';
            assert!(source.parse_bytes(&bytes).is_err(), "{key}");
        }
    }

    #[test]
    fn medium_rejects_wrong_frame_size_metadata_dtype_shape_and_phase_sign() {
        assert!(decode_medium(&zstd::bulk::compress(&[0; 16], 22).unwrap()).is_err());
        let raw = decode_medium(medium_bytes()).unwrap();
        let (_, info) = SafeTensors::read_metadata(&raw).unwrap();
        let metadata = info.metadata().as_ref().unwrap().clone();
        let tensors = SafeTensors::deserialize(&raw).unwrap();
        let mut wrong_metadata = metadata.clone();
        wrong_metadata.insert("phase_unit".into(), "not sr^-1".into());
        let encoded =
            safetensors::tensor::serialize(tensors.tensors(), Some(wrong_metadata)).unwrap();
        assert!(medium(&encoded).is_err());
        for (dtype, shape) in [(Dtype::I32, vec![40, 4]), (Dtype::F32, vec![160])] {
            let mut views = tensors.tensors();
            let (_, view) = views
                .iter_mut()
                .find(|(name, _)| name == "heights")
                .unwrap();
            *view = safetensors::tensor::TensorView::new(dtype, shape, view.data()).unwrap();
            let encoded = safetensors::tensor::serialize(views, Some(metadata.clone())).unwrap();
            assert!(medium(&encoded).is_err());
        }
        let phase = tensors.tensor("phase").unwrap();
        let offset = phase.data().as_ptr() as usize - raw.as_ptr() as usize;
        let mut negative = raw.clone();
        negative[offset..offset + 4].copy_from_slice(&(-1_f32).to_le_bytes());
        assert!(medium(&negative).is_err());
    }

    #[test]
    fn physical_phase_preserves_forward_peak_and_spherical_mass() {
        let bytes = load_medium().unwrap();
        assert_eq!(bytes.len(), 199584);
        let phase = |species: usize, node: usize, lane: usize| {
            let start = 6240 + (species * 1024 + node) * 16 + lane * 4;
            f32::from_le_bytes(bytes[start..start + 4].try_into().unwrap()) as f64
        };
        assert_eq!(phase(0, 0, 0), 257.3616027832031);
        // Integrate the actual linear-u interpolant against dOmega=12*pi*u^2 du.
        for species in 0..4 {
            for lane in 0..4 {
                let mut nodes = vec![(0.0, phase(species, 0, lane))];
                nodes.extend(
                    (0..1024).map(|i| ((i as f64 + 0.5) / 1024.0, phase(species, i, lane))),
                );
                nodes.push((1.0, phase(species, 1023, lane)));
                let mut mass = 0.0;
                for pair in nodes.windows(2) {
                    let (u, p) = pair[0];
                    let (v, q) = pair[1];
                    let d = v - u;
                    let delta = q - p;
                    mass += d
                        * (p * u * u
                            + (2.0 * p * u * d + delta * u * u) / 2.0
                            + (p * d * d + 2.0 * delta * u * d) / 3.0
                            + delta * d * d / 4.0);
                }
                mass *= 12.0 * std::f64::consts::PI;
                let start = 71776 + species * 16 + lane * 4;
                let stored = f32::from_le_bytes(bytes[start..start + 4].try_into().unwrap()) as f64;
                assert!(
                    (mass - stored).abs() < 4e-7,
                    "species={species} lane={lane}: {mass} vs {stored}"
                );
            }
        }
    }

    #[test]
    fn invalid_container_and_physical_value_are_rejected() {
        let encoded = medium_bytes();
        assert!(decode_medium(&encoded[..encoded.len() - 1]).is_err());
        let mut corrupt = decode_medium(encoded).unwrap();
        assert!(medium(&corrupt[..corrupt.len() - 1]).is_err());
        let header = u64::from_le_bytes(corrupt[..8].try_into().unwrap()) as usize + 8;
        corrupt[header..header + 4].copy_from_slice(&f32::NAN.to_le_bytes());
        assert!(medium(&corrupt).is_err());
    }
}
