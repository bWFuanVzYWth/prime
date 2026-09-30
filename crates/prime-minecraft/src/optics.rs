//! Version-specific homogeneous material calibration. GPU records contain only optical values.
use crate::model::{Catalog, State};
use prime_scene::surface::{Medium, Optics, SurfaceFace};

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
        ior: 1.5,
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
        transmit: true,
        thin,
    });
}
