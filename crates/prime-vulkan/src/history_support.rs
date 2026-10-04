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

// The caller validates complete current/next membership before publishing a Texture.
// Scan only immutable sprite windows, once per changed family, never animation ticks.
pub(crate) fn constant_family_alpha(texture: &Texture) -> Option<u8> {
    let region = texture.region?;
    let sampling = texture.sampling.as_ref()?;
    if sampling.coverage_frames.is_empty() {
        return None;
    }
    let first = sampling.coverage_frames[0];
    let alpha =
        texture.pixels[(first[1] as usize * texture.width as usize + first[0] as usize) * 4 + 3];
    for &[x, y] in sampling.coverage_frames.iter() {
        for row in y as usize..y as usize + region[3] as usize {
            let start = (row * texture.width as usize + x as usize) * 4 + 3;
            for column in 0..region[2] as usize {
                if texture.pixels[start + column * 4] != alpha {
                    return None;
                }
            }
        }
    }
    Some(alpha)
}

pub(crate) fn base_support_same(
    a: &Texture,
    b: &Texture,
    a_constant: Option<u8>,
    b_constant: Option<u8>,
) -> bool {
    base_sample_same(a, b)
        || a.width == b.width
            && a.height == b.height
            && a.region.map(|r| [r[2], r[3]]) == b.region.map(|r| [r[2], r[3]])
            && a_constant.zip(b_constant).is_some_and(|(a, b)| a == b)
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
    fn constant_alpha_preserves_rgb_animation_and_rejects_actual_alpha_changes() {
        let mut source = animated();
        source.pixels = vec![10, 20, 30, 128, 90, 80, 70, 128].into();
        assert_eq!(constant_family_alpha(&source), Some(128));
        let mut current = source.clone();
        current.region = Some([1, 0, 1, 1]);
        let sampling = Arc::make_mut(current.sampling.as_mut().unwrap());
        sampling.next = [0, 0];
        sampling.blend = 0.75;
        assert!(!base_sample_same(&source, &current));
        assert!(base_support_same(&source, &current, Some(128), Some(128)));
        current.pixels = vec![90, 80, 70, 128, 10, 20, 30, 128].into();
        assert_eq!(constant_family_alpha(&current), Some(128));
        assert!(base_support_same(&source, &current, Some(128), Some(128)));
        current.pixels = vec![90, 80, 70, 255, 10, 20, 30, 255].into();
        assert_eq!(constant_family_alpha(&current), Some(255));
        assert!(!base_support_same(&source, &current, Some(128), Some(255)));
        current.pixels = vec![90, 80, 70, 128, 10, 20, 30, 255].into();
        assert_eq!(constant_family_alpha(&current), None);
        assert!(!base_support_same(&source, &current, Some(128), None));
        Arc::make_mut(current.sampling.as_mut().unwrap()).coverage_frames = Arc::from([]);
        assert_eq!(constant_family_alpha(&current), None);
        current.region = None;
        assert_eq!(constant_family_alpha(&current), None);
    }

    #[test]
    fn family_proof_scans_sprite_windows_and_keeps_nonuniform_masks_unknown() {
        let mut source = animated();
        source.width = 4;
        source.pixels = vec![1, 2, 3, 0, 10, 20, 30, 255, 90, 80, 70, 255, 4, 5, 6, 0].into();
        source.region = Some([1, 0, 1, 1]);
        let sampling = Arc::make_mut(source.sampling.as_mut().unwrap());
        sampling.coverage_frames = vec![[1, 0], [2, 0]].into();
        sampling.next = [2, 0];
        assert_eq!(constant_family_alpha(&source), Some(255));
        let mut current = source.clone();
        current.region = Some([1, 0, 2, 1]);
        assert!(!base_support_same(&source, &current, Some(255), Some(255)));
        current = source.clone();
        current.pixels = vec![1, 2, 3, 0, 10, 20, 30, 255, 90, 80, 70, 128, 4, 5, 6, 0].into();
        assert_eq!(constant_family_alpha(&current), None);
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
