//! Named physical arrays. Container parsing and validation never enter a shader.
use safetensors::{Dtype, SafeTensors};

pub(super) const MEDIUM: &[u8] = include_bytes!("../../assets/atmosphere/medium.safetensors");
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

    #[test]
    fn physical_phase_preserves_forward_peak_and_spherical_mass() {
        let bytes = medium(MEDIUM).unwrap();
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
        assert!(medium(&MEDIUM[..MEDIUM.len() - 1]).is_err());
        let mut corrupt = MEDIUM.to_vec();
        let header = u64::from_le_bytes(corrupt[..8].try_into().unwrap()) as usize + 8;
        corrupt[header..header + 4].copy_from_slice(&f32::NAN.to_le_bytes());
        assert!(medium(&corrupt).is_err());
    }
}
