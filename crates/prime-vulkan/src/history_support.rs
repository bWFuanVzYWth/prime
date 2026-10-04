//! CPU source support for cached coverage/coating leaves. OMM union proofs are not
//! proofs that the currently sampled base texel still selects the same leaf.
use prime_scene::Texture;
use std::{collections::BTreeSet, sync::Arc};

pub(crate) fn base_sample_same(a: &Texture, b: &Texture) -> bool {
    if a.width != b.width
        || a.height != b.height
        || !Arc::ptr_eq(&a.pixels, &b.pixels)
        || a.region != b.region
    {
        return false;
    }
    let region = a.region.unwrap_or([0, 0, a.width, a.height]);
    let sample = |texture: &Texture| {
        texture
            .sampling
            .as_ref()
            .map_or(([region[0], region[1]], 0_f32.to_bits()), |sampling| {
                (sampling.next, sampling.blend.to_bits())
            })
    };
    sample(a) == sample(b)
}

pub(crate) fn placement_depends_on(
    texture_override: u32,
    prototype_dependencies: &BTreeSet<u32>,
    changed: &BTreeSet<u32>,
) -> bool {
    if texture_override == u32::MAX {
        !prototype_dependencies.is_disjoint(changed)
    } else {
        texture_override != 0 && changed.contains(&texture_override)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::scene::{TextureLevel, TextureSampling};

    fn animated() -> Texture {
        Texture {
            width: 2,
            height: 1,
            pixels: vec![255; 8].into(),
            region: Some([0, 0, 1, 1]),
            sampling: Some(Arc::new(TextureSampling {
                levels: Vec::new(),
                next: [1, 0],
                blend: 0.,
                coverage_frames: vec![[0, 0], [1, 0]].into(),
            })),
            material: None,
        }
    }

    #[test]
    fn immutable_frame_family_does_not_hide_current_phase_changes() {
        let source = animated();
        let mut current = source.clone();
        current.region = Some([1, 0, 1, 1]);
        assert!(Arc::ptr_eq(&source.pixels, &current.pixels));
        assert!(Arc::ptr_eq(
            &source.sampling.as_ref().unwrap().coverage_frames,
            &current.sampling.as_ref().unwrap().coverage_frames,
        ));
        assert!(!base_sample_same(&source, &current));
        current = source.clone();
        Arc::make_mut(current.sampling.as_mut().unwrap()).next = [0, 0];
        assert!(!base_sample_same(&source, &current));
        current = source.clone();
        Arc::make_mut(current.sampling.as_mut().unwrap()).blend = 0.5;
        assert!(!base_sample_same(&source, &current));
    }

    #[test]
    fn base_replacement_and_dimensions_change_support_but_mips_and_proof_do_not() {
        let source = animated();
        assert!(base_sample_same(&source, &source.clone()));
        let mut current = source.clone();
        current.pixels = source.pixels.to_vec().into();
        assert!(!base_sample_same(&source, &current));
        current = source.clone();
        current.width += 1;
        assert!(!base_sample_same(&source, &current));
        current = source.clone();
        let sampling = Arc::make_mut(current.sampling.as_mut().unwrap());
        sampling.coverage_frames = vec![[0, 0]].into();
        sampling.levels.push(TextureLevel {
            width: 1,
            height: 1,
            pixels: vec![0; 4].into(),
            region: [0, 0, 1, 1],
            next: [0, 0],
        });
        assert!(base_sample_same(&source, &current));
    }

    #[test]
    fn dynamic_effective_texture_respects_explicit_white_override_and_inherit() {
        let dependencies = BTreeSet::from([7, 9]);
        let changes = BTreeSet::from([7]);
        assert!(placement_depends_on(u32::MAX, &dependencies, &changes));
        assert!(!placement_depends_on(0, &dependencies, &changes));
        assert!(!placement_depends_on(9, &dependencies, &changes));
        assert!(placement_depends_on(7, &BTreeSet::new(), &changes));
        assert!(!placement_depends_on(
            u32::MAX,
            &dependencies,
            &BTreeSet::new()
        ));
    }
}
