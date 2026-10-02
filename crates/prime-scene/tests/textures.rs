use prime_scene::{SourceScene, Texture};
use std::sync::Arc;

fn animated_texture() -> Texture {
    Texture {
        width: 8,
        height: 4,
        pixels: vec![255; 128].into(),
        region: Some([0, 0, 2, 2]),
        sampling: Some(Arc::new(prime_scene::TextureSampling {
            levels: vec![],
            next: [2, 0],
            blend: 0.5,
            coverage_frames: Arc::from([[0, 0], [2, 0], [6, 2]]),
        })),
        material: None,
    }
}

#[test]
fn complete_animation_windows_must_fit_and_include_both_sampled_frames() {
    let image = animated_texture();
    image.validate().unwrap();
    let mut scene = SourceScene::default();
    scene.set_texture(1, image.clone()).unwrap();
    let revision = scene.revision();
    for frames in [
        vec![[0, 0], [2, 0], [7, 0]],
        vec![[0, 0], [2, 0], [0, 3]],
        vec![[0, 0], [2, 0], [u32::MAX, 0]],
        vec![[0, 0], [6, 2]],
        vec![[2, 0], [6, 2]],
    ] {
        let mut bad = image.clone();
        Arc::make_mut(bad.sampling.as_mut().unwrap()).coverage_frames = frames.into();
        assert!(bad.validate().is_err());
        assert!(
            scene
                .set_textures(vec![(2, image.clone()), (3, bad)])
                .is_err()
        );
        assert!(scene.texture(2).is_none());
        assert_eq!(scene.revision(), revision);
    }
    let mut unknown = image;
    Arc::make_mut(unknown.sampling.as_mut().unwrap()).coverage_frames = Arc::from([]);
    unknown.validate().unwrap();
}

#[test]
fn complete_frame_metadata_changes_identity_without_changing_pixel_ownership() {
    let image = animated_texture();
    let mut equal = image.clone();
    let original = &image.sampling.as_ref().unwrap().coverage_frames;
    Arc::make_mut(equal.sampling.as_mut().unwrap()).coverage_frames = original.to_vec().into();
    assert!(!Arc::ptr_eq(
        original,
        &equal.sampling.as_ref().unwrap().coverage_frames
    ));
    assert!(image.same(&equal));
    let mut changed = equal.clone();
    Arc::make_mut(changed.sampling.as_mut().unwrap()).coverage_frames = Arc::from([[0, 0], [2, 0]]);
    changed.validate().unwrap();
    assert!(!image.same(&changed));
    assert!(image.same_backings(&changed));
    let mut scene = SourceScene::default();
    scene.set_texture(1, image).unwrap();
    let revision = scene.revision();
    scene.set_texture(1, equal).unwrap();
    assert_eq!(scene.revision(), revision);
    scene.set_texture(1, changed).unwrap();
    assert_eq!(scene.revision(), revision + 1);
}
#[test]
fn a_late_invalid_sprite_batch_does_not_publish_earlier_textures() {
    let mut scene = SourceScene::default();
    let backing: Arc<[u8]> = vec![255; 64].into();
    let image = Texture {
        width: 4,
        height: 4,
        pixels: backing,
        region: None,
        sampling: None,
        material: None,
    };
    scene.set_texture(1, image.clone()).unwrap();
    let revision = scene.revision();
    let a = Texture {
        region: Some([0, 0, 2, 2]),
        ..image.clone()
    };
    let bad = Texture {
        region: Some([3, 3, 2, 2]),
        ..image
    };
    assert!(scene.set_textures(vec![(2, a.clone()), (3, bad)]).is_err());
    assert!(scene.texture(2).is_none());
    assert_eq!(scene.revision(), revision);
    assert!(
        scene
            .set_textures(vec![(2, a.clone()), (2, a.clone())])
            .is_err()
    );
    scene
        .set_textures(vec![(2, a.clone()), (3, a.clone())])
        .unwrap();
    assert_eq!(scene.revision(), revision + 1);
    assert!(Arc::ptr_eq(
        &scene.texture(1).unwrap().pixels,
        &scene.texture(3).unwrap().pixels
    ));
    scene.set_texture(2, a).unwrap();
    assert_eq!(scene.revision(), revision + 1);
}

#[test]
fn budgets_count_shared_backings_and_all_animation_frames_and_mips() {
    use prime_scene::{TextureLevel, TextureSampling};
    let mut scene = SourceScene::default();
    let mut t = Texture {
        width: 8,
        height: 8,
        pixels: vec![255; 256].into(),
        region: Some([0, 0, 2, 2]),
        sampling: Some(Arc::new(TextureSampling {
            levels: vec![TextureLevel {
                width: 4,
                height: 4,
                pixels: vec![127; 64].into(),
                region: [0, 0, 1, 1],
                next: [1, 0],
            }],
            next: [2, 0],
            blend: 0.5,
            coverage_frames: Arc::from([]),
        })),
        material: None,
    };
    scene
        .set_textures(vec![(2, t.clone()), (3, t.clone())])
        .unwrap();
    assert_eq!(scene.validate_textures(&[]).unwrap(), 320);
    t.region = Some([2, 0, 2, 2]);
    scene.set_texture(2, t.clone()).unwrap();
    assert_eq!(scene.validate_textures(&[]).unwrap(), 320);
    t.pixels = vec![32; 256].into();
    scene.set_texture(2, t.clone()).unwrap();
    assert_eq!(scene.validate_textures(&[]).unwrap(), 576);
    scene.set_texture(3, t).unwrap();
    assert_eq!(scene.validate_textures(&[]).unwrap(), 320);
}

#[test]
fn canonical_material_backings_share_ownership_and_descriptor_updates_keep_the_budget() {
    use prime_scene::TextureMaterial;
    let mut scene = SourceScene::default();
    let mut color = Texture {
        width: 2,
        height: 2,
        pixels: vec![255; 16].into(),
        region: None,
        sampling: None,
        material: None,
    };
    let plane = Texture {
        pixels: vec![128; 16].into(),
        ..color.clone()
    };
    color.material = Some(Arc::new(TextureMaterial {
        normal: Some(plane.clone()),
        specular: Some(plane.clone()),
        authored_emission: true,
        ..Default::default()
    }));
    scene
        .set_textures(vec![(1, color.clone()), (2, color.clone())])
        .unwrap();
    assert_eq!(scene.validate_textures(&[]).unwrap(), 32);
    let mut material = color.material.as_ref().unwrap().as_ref().clone();
    material.bounds = Some([0., 0., 0.5, 0.5]);
    color.material = Some(Arc::new(material));
    scene.set_texture(2, color.clone()).unwrap();
    assert_eq!(scene.validate_textures(&[]).unwrap(), 32);
    let mut material = color.material.as_ref().unwrap().as_ref().clone();
    material.normal.as_mut().unwrap().pixels = vec![64; 16].into();
    color.material = Some(Arc::new(material));
    scene.set_texture(2, color).unwrap();
    assert_eq!(scene.validate_textures(&[]).unwrap(), 48);
}

#[test]
fn malformed_material_extents_controls_and_nesting_cannot_publish() {
    use prime_scene::TextureMaterial;
    let base = Texture {
        width: 2,
        height: 2,
        pixels: vec![255; 16].into(),
        region: None,
        sampling: None,
        material: None,
    };
    let mut scene = SourceScene::default();
    scene.set_texture(1, base.clone()).unwrap();
    let revision = scene.revision();
    for material in [
        TextureMaterial {
            normal: Some(Texture {
                width: 1,
                height: 1,
                pixels: vec![128; 4].into(),
                ..base.clone()
            }),
            ..Default::default()
        },
        TextureMaterial {
            normal: Some(Texture {
                material: Some(Arc::new(TextureMaterial::default())),
                ..base.clone()
            }),
            ..Default::default()
        },
        TextureMaterial {
            authored_emission: true,
            ..Default::default()
        },
        TextureMaterial {
            atlas_lookup: true,
            ..Default::default()
        },
        TextureMaterial {
            bounds: Some([0., 0., f32::NAN, 1.]),
            ..Default::default()
        },
    ] {
        let texture = Texture {
            material: Some(Arc::new(material)),
            ..base.clone()
        };
        assert!(
            scene
                .set_textures(vec![(2, base.clone()), (3, texture)])
                .is_err()
        );
        assert!(scene.texture(2).is_none());
        assert_eq!(scene.revision(), revision);
    }
}
