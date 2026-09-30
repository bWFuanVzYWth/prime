use prime_scene::{SourceScene, Texture};
use std::sync::Arc;
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
        })),
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
