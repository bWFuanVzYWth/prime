//! Typed production views exercise resource transactions and deferred owned sources.
use super::*;
use prime_abi::{minecraft as input, *};

fn identity(epoch: u64, batch: u64, generation: u64) -> PrimeMcIdentity {
    PrimeMcIdentity {
        abi_version: PRIME_ABI_VERSION,
        source_version: PRIME_MC_SOURCE_VERSION,
        game_version: 262,
        epoch,
        batch,
        resource_generation: generation,
        ..Default::default()
    }
}
fn resources<'a>(
    raw: &'a mut PrimeMcResourceBatch,
    bytes: &'a [u8],
    states: &'a [PrimeMcState],
    models: &'a [PrimeMcModel],
    children: &'a [PrimeMcModelChild],
) -> input::Resources<'a> {
    resources_with_images(raw, bytes, states, models, children, &[], &[])
}
fn resources_with_images<'a>(
    raw: &'a mut PrimeMcResourceBatch,
    bytes: &'a [u8],
    states: &'a [PrimeMcState],
    models: &'a [PrimeMcModel],
    children: &'a [PrimeMcModelChild],
    sprites: &'a [PrimeMcSprite],
    images: &'a [PrimeMcImage],
) -> input::Resources<'a> {
    raw.identity.struct_size = std::mem::size_of::<PrimeMcResourceBatch>() as u32;
    raw.states = states.as_ptr();
    raw.state_count = states.len() as u64;
    raw.models = models.as_ptr();
    raw.model_count = models.len() as u64;
    raw.children = children.as_ptr();
    raw.child_count = children.len() as u64;
    raw.bytes = bytes.as_ptr();
    raw.byte_count = bytes.len() as u64;
    raw.sprites = sprites.as_ptr();
    raw.sprite_count = sprites.len() as u64;
    raw.images = images.as_ptr();
    raw.image_count = images.len() as u64;
    input::Resources::from_parts(
        raw,
        states,
        models,
        &[],
        children,
        &[],
        &[],
        sprites,
        images,
        &[],
        &[],
        &[],
        bytes,
    )
    .unwrap()
}
fn checked_plan<'a>(raw: &'a mut PrimeMcPlan, events: &'a [PrimeMcEvent]) -> input::Plan<'a> {
    raw.identity.struct_size = std::mem::size_of::<PrimeMcPlan>() as u32;
    raw.events = events.as_ptr();
    raw.event_count = events.len() as u64;
    input::Plan::from_parts(raw, events).unwrap()
}

fn prepare(context: &mut TerrainContext, scene: &mut SourceScene, generation: u64) {
    let bytes = b"\xff\xff\xff\xffminecraft:emptyminecraft:air";
    let state = PrimeMcState {
        flags: 1,
        fluid_name: PrimeMcRange {
            offset: 4,
            count: 15,
        },
        name: PrimeMcRange {
            offset: 19,
            count: 13,
        },
        ..Default::default()
    };
    let mut raw = PrimeMcResourceBatch {
        identity: identity(scene.epoch(), 0, generation),
        flags: PRIME_MC_RESOURCE_REPLACE,
        atlas: PrimeMcImage {
            width: 1,
            height: 1,
            pixels: PrimeMcRange {
                offset: 0,
                count: 4,
            },
        },
        ..Default::default()
    };
    context
        .prepare_resources(&resources(&mut raw, bytes, &[state], &[], &[]), scene)
        .unwrap();
}
fn plan(
    context: &mut TerrainContext,
    epoch: u64,
    batch: u64,
    events: &[PrimeMcEvent],
) -> Vec<Section> {
    let mut raw = PrimeMcPlan {
        identity: identity(epoch, batch, context.resource_generation()),
        position: [64., 0.],
        radius: 8,
        min_y: 0,
        max_y: 0,
        source: [-32, 32, -32, 32],
        ..Default::default()
    };
    context
        .plan_typed(&checked_plan(&mut raw, events), epoch, 1)
        .unwrap();
    context.pending.as_ref().unwrap().demand.requests.clone()
}
fn sections(
    context: &mut TerrainContext,
    scene: &mut SourceScene,
    batch: u64,
    requested: &[Section],
    palette: &[u32],
) -> Result<(), String> {
    let values: Vec<_> = requested
        .iter()
        .map(|s| PrimeMcSection {
            x: s.0,
            y: s.1,
            z: s.2,
            present: 1,
            palette: PrimeMcRange {
                offset: 0,
                count: 1,
            },
            ..Default::default()
        })
        .collect();
    let mut raw = PrimeMcSectionBatch {
        identity: identity(scene.epoch(), batch, context.resource_generation()),
        ..Default::default()
    };
    raw.identity.struct_size = std::mem::size_of::<PrimeMcSectionBatch>() as u32;
    raw.sections = values.as_ptr();
    raw.section_count = values.len() as u64;
    raw.palette = palette.as_ptr();
    raw.palette_count = palette.len() as u64;
    context.sections_typed(
        &input::Sections::from_parts(&raw, &values, palette, &[]).unwrap(),
        scene,
    )
}

#[test]
fn typed_resource_catalog_and_workers_survive_world_reset_without_pixel_copy() {
    let workers = Arc::new(CpuWorkers::new(2).unwrap());
    let mut context = TerrainContext::with_workers(workers.clone());
    let mut scene = SourceScene::with_workers(workers.clone());
    scene.reset_world(1).unwrap();
    prepare(&mut context, &mut scene, 1);
    let pixel = scene.texture(1).unwrap().pixels.clone();
    let state_address = &context.catalog.states[&0] as *const _;
    let requests = plan(&mut context, 1, 1, &[]);
    sections(&mut context, &mut scene, 1, &requests, &[0]).unwrap();
    scene.reset_world(2).unwrap();
    let requests = plan(&mut context, 2, 1, &[]);
    sections(&mut context, &mut scene, 1, &requests, &[0]).unwrap();
    assert!(Arc::ptr_eq(context.cpu_workers().unwrap(), &workers));
    assert!(Arc::ptr_eq(&pixel, &scene.texture(1).unwrap().pixels));
    assert_eq!(state_address, &context.catalog.states[&0] as *const _);
    assert_eq!(context.catalog.states[&0].name, "minecraft:air");
    assert_eq!(context.resource_generation(), 1);
}

#[test]
fn typed_explicit_world_clear_releases_queued_sources_without_another_plan() {
    let mut context = TerrainContext::default();
    let mut scene = SourceScene::default();
    scene.reset_world(1).unwrap();
    prepare(&mut context, &mut scene, 1);
    let events: Vec<_> = [0, 4, 8]
        .map(|x| PrimeMcEvent {
            kind: 1,
            x,
            ..Default::default()
        })
        .into();
    let requested = plan(&mut context, 1, 1, &events);
    sections(&mut context, &mut scene, 1, &requested, &[0]).unwrap();
    assert_eq!(context.chunks.pending_cells(), 2);
    assert!(!context.chunks.sources().is_empty());
    let definitions = PrimeMcBiomeDefinitions {
        permutation: std::array::from_fn(|i| i as u32),
        ..Default::default()
    };
    context.chunks.biome_sources_mut().definitions =
        Some(biome_source::Definitions::from_typed(&definitions, &[]).unwrap());
    plan(&mut context, 1, 2, &[]); // Include an unfinished demand in the retired world.
    let pixel = scene.texture(1).unwrap().pixels.clone();
    let state = &context.catalog.states[&0] as *const _;
    scene.reset_world(2).unwrap();
    context.clear_world(2);
    assert!(context.chunks.sources().is_empty());
    assert_eq!(context.chunks.pending_cells(), 0);
    assert!(context.chunks.biome_sources().definitions.is_none());
    assert!(context.pending.is_none() && context.awaiting_colors.is_none());
    assert_eq!((context.epoch, context.last_batch), (2, 0));
    assert_eq!(state, &context.catalog.states[&0] as *const _);
    assert!(Arc::ptr_eq(&pixel, &scene.texture(1).unwrap().pixels));
    assert_eq!(context.resource_generation(), 1);
}

#[test]
fn typed_sections_copy_once_before_deferred_budgeted_compilation() {
    let mut context = TerrainContext::default();
    let mut scene = SourceScene::default();
    scene.reset_world(1).unwrap();
    prepare(&mut context, &mut scene, 1);
    let events: Vec<_> = [0, 4, 8]
        .map(|x| PrimeMcEvent {
            kind: 1,
            x,
            ..Default::default()
        })
        .into();
    let requests = plan(&mut context, 1, 1, &events);
    let mut palette = [0];
    sections(&mut context, &mut scene, 1, &requests, &palette).unwrap();
    palette[0] = 999;
    assert_eq!(context.stats.compiled_cells, 1);
    assert_eq!(context.chunks.pending_cells(), 2);
    for source in context.chunks.sources().values() {
        assert_eq!(source.state(0), 0);
    }
    for batch in [2, 3] {
        let requests = plan(&mut context, 1, batch, &[]);
        assert!(requests.is_empty());
        sections(&mut context, &mut scene, batch, &requests, &palette).unwrap();
        assert_eq!(context.stats.compiled_cells, 1);
    }
    assert_eq!(context.chunks.pending_cells(), 0);
}

#[test]
fn typed_resource_failure_preserves_old_catalog_pixels_and_generation() {
    let mut context = TerrainContext::default();
    let mut scene = SourceScene::default();
    scene.reset_world(1).unwrap();
    prepare(&mut context, &mut scene, 1);
    let old = scene.texture(1).unwrap().pixels.clone();
    let revision = scene.revision();
    let mut raw = PrimeMcResourceBatch {
        identity: identity(1, 0, 2),
        flags: PRIME_MC_RESOURCE_REPLACE,
        atlas: PrimeMcImage {
            width: 1,
            height: 1,
            pixels: PrimeMcRange {
                offset: 0,
                count: 4,
            },
        },
        ..Default::default()
    };
    for models in [
        vec![PrimeMcModel {
            id: 1,
            kind: 2,
            ..Default::default()
        }],
        vec![
            PrimeMcModel {
                id: 1,
                kind: 4,
                ..Default::default()
            },
            PrimeMcModel {
                id: 1,
                kind: 0,
                ..Default::default()
            },
        ],
    ] {
        assert!(
            context
                .prepare_resources(
                    &resources(&mut raw, &[17; 4], &[], &models, &[]),
                    &mut scene
                )
                .is_err()
        );
        assert_eq!(context.resource_generation(), 1);
        assert_eq!(scene.revision(), revision);
        assert!(Arc::ptr_eq(&old, &scene.texture(1).unwrap().pixels));
        assert!(context.catalog.states.contains_key(&0));
    }
    let model = PrimeMcModel {
        id: 1,
        kind: 2,
        children: PrimeMcRange {
            offset: 0,
            count: 2,
        },
        ..Default::default()
    };
    let children = [
        PrimeMcModelChild {
            weight: u32::MAX,
            model: 0,
        },
        PrimeMcModelChild {
            weight: 1,
            model: 0,
        },
    ];
    assert!(
        context
            .prepare_resources(
                &resources(&mut raw, &[17; 4], &[], &[model], &children),
                &mut scene
            )
            .is_err()
    );
    assert_eq!(scene.revision(), revision);
    prepare(&mut context, &mut scene, 2);
    assert_eq!(context.resource_generation(), 2);
    assert_eq!(scene.resource_generation(), 2);
}

#[test]
fn typed_invalid_frame_and_incomplete_section_response_do_not_admit_work() {
    let mut context = TerrainContext::default();
    let mut scene = SourceScene::default();
    scene.reset_world(1).unwrap();
    prepare(&mut context, &mut scene, 1);
    for mut raw in [
        PrimeMcPlan {
            identity: identity(1, 1, 1),
            position: [f64::NAN, 0.],
            ..Default::default()
        },
        PrimeMcPlan {
            identity: identity(1, 1, 1),
            min_y: 4,
            max_y: 3,
            ..Default::default()
        },
        PrimeMcPlan {
            identity: identity(1, 1, 1),
            radius: 2_000_001,
            ..Default::default()
        },
    ] {
        assert!(
            context
                .plan_typed(&checked_plan(&mut raw, &[]), 1, 8)
                .is_err()
        );
        assert!(context.pending.is_none());
    }
    let requests = plan(
        &mut context,
        1,
        1,
        &[PrimeMcEvent {
            kind: 1,
            ..Default::default()
        }],
    );
    assert!(!requests.is_empty());
    let revision = scene.revision();
    assert!(sections(&mut context, &mut scene, 1, &[], &[0]).is_err());
    assert!(context.pending.is_some());
    assert_eq!(context.chunks.pending_cells(), 0);
    assert_eq!(scene.revision(), revision);
    sections(&mut context, &mut scene, 1, &requests, &[0]).unwrap();
}

#[test]
fn typed_append_keeps_prior_material_lookup_and_empty_or_invalid_batches_are_atomic() {
    let mut context = TerrainContext::default();
    let mut scene = SourceScene::default();
    scene.reset_world(1).unwrap();
    let bytes = [255; 12]; // Two atlas texels and one optional material texel.
    let images = [
        PrimeMcImage {
            width: 1,
            height: 1,
            ..Default::default()
        },
        PrimeMcImage {
            width: 1,
            height: 1,
            pixels: PrimeMcRange {
                offset: 8,
                count: 4,
            },
        },
    ];
    let sprite = |id, bounds| PrimeMcSprite {
        id,
        bounds,
        extent: [1, 1],
        images: PrimeMcRange {
            offset: 0,
            count: 1,
        },
        normal_image: 1,
        specular_image: u32::MAX,
        ..Default::default()
    };
    let mut raw = PrimeMcResourceBatch {
        identity: identity(1, 0, 1),
        flags: PRIME_MC_RESOURCE_REPLACE,
        atlas: PrimeMcImage {
            width: 2,
            height: 1,
            pixels: PrimeMcRange {
                offset: 0,
                count: 8,
            },
        },
        ..Default::default()
    };
    let first = [sprite(1, [0., 0., 0.5, 1.])];
    let input = resources_with_images(&mut raw, &bytes, &[], &[], &[], &first, &images);
    context.prepare_resources(&input, &mut scene).unwrap();
    let first_material = scene
        .texture(sprite::texture(1))
        .unwrap()
        .material
        .clone()
        .unwrap();
    raw.flags = 0;
    raw.atlas = Default::default(); // APPEND borrows the already-published atlas.
    let second = [sprite(2, [0.5, 0., 1., 1.])];
    let input = resources_with_images(&mut raw, &bytes, &[], &[], &[], &second, &images);
    context.prepare_resources(&input, &mut scene).unwrap();
    let lookup = scene
        .texture(1)
        .unwrap()
        .material
        .as_ref()
        .unwrap()
        .coverage
        .as_ref()
        .unwrap()
        .pixels
        .clone();
    let ids: Vec<_> = lookup
        .as_chunks::<4>()
        .0
        .iter()
        .map(|v| u32::from_le_bytes(*v))
        .collect();
    assert_eq!(ids, [sprite::texture(1), sprite::texture(2)]);
    assert!(Arc::ptr_eq(
        &first_material,
        scene
            .texture(sprite::texture(1))
            .unwrap()
            .material
            .as_ref()
            .unwrap()
    ));
    let revision = scene.revision();
    context
        .prepare_resources(&resources(&mut raw, &[], &[], &[], &[]), &mut scene)
        .unwrap();
    assert_eq!(scene.revision(), revision);
    let malformed = [PrimeMcSprite {
        images: PrimeMcRange {
            offset: u64::MAX,
            count: 1,
        },
        ..sprite(3, [0., 0., 0.5, 1.])
    }];
    let input = resources_with_images(&mut raw, &bytes, &[], &[], &[], &malformed, &images);
    assert!(context.prepare_resources(&input, &mut scene).is_err());
    assert_eq!(scene.revision(), revision);
    assert_eq!(context.catalog.sprites.len(), 2);
    assert_eq!(context.resource_generation(), 1);
    assert!(Arc::ptr_eq(
        &lookup,
        &scene
            .texture(1)
            .unwrap()
            .material
            .as_ref()
            .unwrap()
            .coverage
            .as_ref()
            .unwrap()
            .pixels
    ));
}
