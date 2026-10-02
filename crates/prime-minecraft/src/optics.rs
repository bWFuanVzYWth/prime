//! Version-specific homogeneous material calibration. GPU records contain only optical values.
use crate::model::{Catalog, State};
use prime_scene::surface::{Medium, Optics, SurfaceFace};

/// A varying LabPBR IOR field retains its resource identity. The current surface samples its
/// canonical code; a neighboring medium samples its current frame at the source midpoint.
pub(crate) const DYNAMIC_IOR_MEDIUM: u32 = 0x8000_0000;

pub(crate) fn fresnel_code_ior(code: u8) -> f32 {
    let f0 = if code == 0 || code >= 231 {
        0.04
    } else {
        (f32::from(code - 1) / 255.).clamp(0.02, 0.17)
    };
    let root = f0.sqrt();
    (1. + root) / (1. - root)
}

pub(crate) fn water() -> Medium {
    Medium {
        ior: 1.333,
        extinction: [0.2916, 0.04444, 0.010182],
    }
}
pub(crate) fn glass(reference: [f32; 4]) -> Medium {
    let extinction = if reference[3] < 0.5 / 255. {
        [0.; 3]
    } else {
        // Previous translator's calibration: encoded over-white at opacity .4, then EOTF
        // and Rec.2020. The declared medium uses the immutable source midpoint.
        let linear = std::array::from_fn::<_, 3, _>(|i| {
            let encoded = 0.6 + 0.4 * reference[i];
            if encoded <= 0.04045 {
                encoded / 12.92
            } else {
                ((encoded + 0.055) / 1.055).powf(2.4)
            }
        });
        [
            [0.6274039, 0.32928303, 0.043313067],
            [0.06909729, 0.9195404, 0.011362316],
            [0.01639144, 0.08801331, 0.89559525],
        ]
        .map(|row| {
            -row.iter()
                .zip(linear)
                .map(|(a, b)| a * b)
                .sum::<f32>()
                .clamp(1e-3, 1.)
                .ln()
        })
    };
    Medium {
        ior: fresnel_code_ior(0),
        extinction,
    }
}
pub(crate) fn assign(catalog: &Catalog, state: &State, face: &mut SurfaceFace, fluid: bool) {
    let (id, inside, thin) = if fluid && state.fluid.kind == 1 {
        (1, water(), false)
    } else if !fluid
        && let Some(value) = catalog
            .optical_materials
            .get(&(state.id, face.geometry.texture_id))
    {
        *value
    } else {
        return;
    };
    face.media[0] = id;
    face.optics = Some(Optics {
        negative: inside,
        positive: Medium::default(),
        ior_textures: [
            (id & DYNAMIC_IOR_MEDIUM != 0).then_some(face.geometry.texture_id),
            None,
        ],
        transmit: true,
        thin,
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        labpbr::Material,
        model::{Model, Quad},
        sprite::{Image, Sprite, texture},
        wire::Reader,
    };

    fn source_sprite(green: [u8; 4]) -> Sprite {
        let mut sprite = Sprite {
            name: "test:glass".into(),
            bounds: [0., 0., 1., 1.],
            extent: [4, 1],
            images: vec![Image {
                width: 4,
                height: 1,
                pixels: [255, 255, 255, 0].repeat(4).into(),
            }],
            frames: Vec::new(),
            coverage_frames: Default::default(),
            interpolate: false,
            material: None,
        };
        let mut bytes = Vec::new();
        for value in [0u32, 1, 4, 1, 4] {
            bytes.extend(value.to_le_bytes());
        }
        for code in green {
            bytes.extend([128, code, 0, 255]);
        }
        let pages = [bytes.as_slice()];
        sprite.material = Some(Material::read(&mut Reader::new(&pages).unwrap(), &sprite).unwrap());
        sprite
    }

    fn quad(sprite: u32) -> Quad {
        Quad {
            positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
            uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
            face: 6,
            tint: -1,
            layer: 2,
            sprite,
            emission: 0,
        }
    }

    #[test]
    fn canonical_dielectric_codebook_preserves_clamps_and_conductor_fallback() {
        for code in 0u8..=255 {
            let ior = fresnel_code_ior(code);
            let recovered = ((ior - 1.) / (ior + 1.)).powi(2);
            let expected = if code == 0 || code >= 231 {
                0.04
            } else {
                (f32::from(code - 1) / 255.).clamp(0.02, 0.17)
            };
            assert!((recovered - expected).abs() < 1e-7, "code {code}");
            assert!(ior.is_finite() && ior > 1.);
        }
        assert_eq!(fresnel_code_ior(0), 1.5);
        assert_eq!(fresnel_code_ior(230), fresnel_code_ior(45));
        assert_eq!(fresnel_code_ior(231), 1.5);
        assert_eq!(fresnel_code_ior(239), 1.5);
    }

    #[test]
    fn resource_ior_and_medium_identity_follow_uniform_and_varying_clear_glass() {
        let mut catalog = Catalog::default();
        for (id, codes) in [
            (1, [10; 4]),
            (2, [10; 4]),
            (3, [80; 4]),
            (4, [10, 80, 80, 10]),
            (5, [20, 80, 80, 20]),
        ] {
            catalog.sprites.insert(id, source_sprite(codes));
            catalog
                .glass_references
                .insert(texture(id), [1., 1., 1., 0.]);
            catalog.models.insert(id, Model::Mesh(vec![quad(id)]));
            catalog.states.insert(
                id,
                State {
                    id,
                    flags: 256,
                    model: id,
                    name: "test:glass".into(),
                    ..Default::default()
                },
            );
        }
        catalog.prepare();
        let media: Vec<_> = (1..=5)
            .map(|id| catalog.optical_materials[&(id, texture(id))])
            .collect();
        assert_eq!(media[0].0, media[1].0);
        assert_ne!(media[0].0, media[2].0);
        assert_eq!(media[0].1.ior, fresnel_code_ior(11));
        assert_eq!(media[2].1.ior, fresnel_code_ior(81));
        for &(id, medium, thin) in &media[..3] {
            assert_eq!(id & DYNAMIC_IOR_MEDIUM, 0);
            assert_eq!(medium.extinction, [0.; 3]);
            assert!(thin);
        }
        for &(id, medium, _) in &media[3..] {
            assert_ne!(id & DYNAMIC_IOR_MEDIUM, 0);
            assert_eq!(medium.ior, fresnel_code_ior(81));
            assert_eq!(medium.extinction, [0.; 3]);
        }
        assert_ne!(media[3].0, media[4].0);
        let mut face = SurfaceFace::from_quad(prime_scene::compiled::CompiledQuad {
            positions: quad(4).positions,
            uvs: quad(4).uvs,
            color: [1.; 4],
            texture_id: texture(4),
            flags: 2,
        });
        assign(&catalog, &catalog.states[&4], &mut face, false);
        assert_eq!(face.media, [media[3].0, 0]);
        assert_eq!(face.optics.unwrap().negative, media[3].1);
        assert_eq!(face.optics.unwrap().ior_textures, [Some(texture(4)), None]);
        let water_state = State {
            fluid: crate::fluid::Fluid {
                kind: 1,
                ..Default::default()
            },
            ..catalog.states[&4].clone()
        };
        assign(&catalog, &water_state, &mut face, true);
        assert_eq!(face.media, [1, 0]);
        assert_eq!(face.optics.unwrap().negative, water());
    }
}
