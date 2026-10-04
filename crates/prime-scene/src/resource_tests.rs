use super::*;
use crate::{incremental::TranslatedScene, workers::CpuWorkers};

fn pixel(value: u8) -> Texture {
    Texture {
        width: 1,
        height: 1,
        pixels: Arc::from([value, 0, 0, 255]),
        region: None,
        sampling: None,
        material: None,
    }
}

#[test]
fn world_reset_retains_resource_identity_pixels_and_shared_workers() {
    let workers = Arc::new(CpuWorkers::new(2).unwrap());
    let mut source = SourceScene::with_workers(workers.clone());
    source.reset_world(1).unwrap();
    source
        .publish_resource_textures(7, vec![(1, pixel(17)), (0x4000_0001, pixel(29))])
        .unwrap();
    source.set_texture(2, pixel(81)).unwrap();
    let backing = source.texture(1).unwrap().pixels.clone();
    let mut scene = TranslatedScene::default();
    scene.update(&mut source, [0.; 3]).unwrap();
    let resources = scene.input().resource_identity();
    source.reset_world(2).unwrap();
    scene.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(scene.input().resource_identity(), resources);
    assert_eq!(scene.input().resource_generation(), 7);
    assert!(source.texture(2).is_none());
    assert!(Arc::ptr_eq(&source.texture(1).unwrap().pixels, &backing));
    assert!(Arc::ptr_eq(source.cpu_workers().unwrap(), &workers));
    assert!(Arc::ptr_eq(&scene.input().textures[&1].pixels, &backing));
}

#[test]
fn resource_residency_does_not_depend_on_mesh_references_or_dynamic_retire() {
    let mut source = SourceScene::default();
    source.reset_world(1).unwrap();
    source
        .publish_resource_textures(1, vec![(1, pixel(1)), (0x4000_0001, pixel(2))])
        .unwrap();
    let sprite = 0x4000_0001;
    source.texture_lifetime.acquire(sprite);
    source.texture_lifetime.retire(sprite);
    source.texture_lifetime.release(sprite);
    source.collect_textures().unwrap();
    assert!(source.texture(sprite).is_some());
    source.set_texture(3, pixel(3)).unwrap();
    source.texture_lifetime.acquire(3);
    source.texture_lifetime.retire(3);
    source.collect_textures().unwrap();
    assert!(source.texture(3).is_some());
    source.texture_lifetime.release(3);
    source.collect_textures().unwrap();
    assert!(source.texture(3).is_none());
}

#[test]
fn identical_dynamic_republication_renews_owner_only_after_complete_validation() {
    let mut source = SourceScene::default();
    let texture = pixel(37);
    source.set_texture(3, texture.clone()).unwrap();
    let revision = source.revision();
    source.texture_lifetime.retire(3);
    source.set_texture(3, texture.clone()).unwrap();
    source.collect_textures().unwrap();
    assert!(source.texture(3).is_some());
    assert_eq!(source.revision(), revision);
    source.texture_lifetime.retire(3);
    let invalid = Texture {
        pixels: Arc::from([1]),
        ..pixel(0)
    };
    assert!(
        source
            .set_textures(vec![(3, texture), (4, invalid)])
            .is_err()
    );
    source.collect_textures().unwrap();
    assert!(source.texture(3).is_none());
    assert!(source.texture(4).is_none());
}

#[test]
fn resource_replacement_is_atomic_retires_old_ids_and_keeps_snapshot_pixels() {
    let mut source = SourceScene::default();
    source.reset_world(1).unwrap();
    source
        .publish_resource_textures(2, vec![(1, pixel(2)), (0x4000_0001, pixel(8))])
        .unwrap();
    let old = source.texture(1).unwrap().pixels.clone();
    let old_identity = source.resources;
    let revision = source.revision();
    let invalid = Texture {
        pixels: Arc::from([1]),
        ..pixel(0)
    };
    assert!(
        source
            .publish_resource_textures(3, vec![(1, pixel(3)), (4, invalid)])
            .is_err()
    );
    assert_eq!(source.revision(), revision);
    assert_eq!(source.resources, old_identity);
    assert!(Arc::ptr_eq(&source.texture(1).unwrap().pixels, &old));
    assert!(source.publish_resource_textures(1, vec![]).is_err());
    source.set_texture(2, pixel(42)).unwrap();
    source.texture_lifetime.retire(2);
    source
        .publish_resource_textures(3, vec![(1, pixel(3)), (0x4000_0002, pixel(9))])
        .unwrap();
    assert_ne!(source.resources, old_identity);
    assert!(source.texture(0x4000_0001).is_none());
    assert_eq!(old[0], 2);
    assert_eq!(source.texture(1).unwrap().pixels[0], 3);
    source.collect_textures().unwrap();
    assert!(source.texture(2).is_none());
}

#[test]
fn same_generation_views_keep_publication_owner_and_distinct_owners_do_not_alias() {
    let mut source = SourceScene::default();
    source
        .publish_resource_textures(9, vec![(1, pixel(1))])
        .unwrap();
    let source_id = source.id;
    let resource_id = source.resources;
    source
        .publish_resource_textures(9, vec![(1, pixel(2)), (0x4000_0001, pixel(3))])
        .unwrap();
    assert_eq!(source.id, source_id);
    assert_eq!(source.resources, resource_id);
    assert!(source.is_resource_texture(0x4000_0001));
    let mut other = SourceScene::default();
    other
        .publish_resource_textures(9, vec![(1, pixel(1))])
        .unwrap();
    assert_ne!(source.resources, other.resources);
}

#[test]
fn catalog_replacement_preserves_world_publication_owner_but_world_reset_changes_it() {
    let mut source = SourceScene::default();
    source.reset_world(1).unwrap();
    source
        .publish_resource_textures(1, vec![(1, pixel(17))])
        .unwrap();
    let world = source.id;
    let mut translated = TranslatedScene::default();
    translated.update(&mut source, [0.; 3]).unwrap();
    let publication = translated.input().publication();
    let resources = translated.input().resource_identity();
    let terrain_generation = translated.input().terrain_resource_generation;

    // New packing can replace every numeric sprite ID without replacing the world domain.
    source
        .publish_resource_textures(2, vec![(1, pixel(29)), (0x4000_0001, pixel(31))])
        .unwrap();
    assert_eq!(source.id, world);
    assert_eq!(source.epoch(), 1);
    translated.update(&mut source, [0.; 3]).unwrap();
    assert!(publication.same_owner(translated.input().publication()));
    assert_ne!(resources, translated.input().resource_identity());
    assert_eq!(translated.input().resource_generation(), 2);
    assert!(translated.input().terrain_resource_generation > terrain_generation);
    assert_eq!(translated.input().textures[&1].pixels[0], 29);

    source.reset_world(2).unwrap();
    assert_ne!(source.id, world);
    translated.update(&mut source, [0.; 3]).unwrap();
    assert!(!publication.same_owner(translated.input().publication()));
    assert_eq!(translated.input().epoch, 2);
    assert_eq!(translated.input().resource_generation(), 2);
}
