//! Real GPU identity and radiance evidence for local coverage-support publications.
use super::*;
use prime_scene::{
    geometry::MeshGeometry,
    scene::{SceneMesh, Texture, TextureSampling},
    spatial::Cell,
    surface::{Emission, SurfaceFace, SurfaceMesh},
};
use realtime_tests::{camera, face, texture};

const EXTENT: [u32; 2] = [31, 17];

pub(super) struct Snapshot {
    pub(super) identities: [Vec<[u32; 2]>; 3],
    pub(super) quads: Vec<[u32; 2]>,
    pub(super) watermark: u32,
    pub(super) reservoirs: Vec<f32>,
    pub(super) rgb: Vec<[f32; 3]>,
}
impl Snapshot {
    pub(super) fn mean(&self, right: bool) -> (f64, [f64; 3]) {
        let mut count = 0usize;
        let mut m = 0.0;
        let mut rgb = [0.0; 3];
        for y in 3..14 {
            for x in if right { 20..31 } else { 0..11 } {
                let pixel = (y * EXTENT[0] + x) as usize;
                m += f64::from(self.reservoirs[pixel]);
                for (target, value) in rgb.iter_mut().zip(self.rgb[pixel]) {
                    *target += f64::from(value.max(0.0));
                }
                count += 1;
            }
        }
        (m / count as f64, rgb.map(|value| value / count as f64))
    }
}

pub(super) fn snapshot(renderer: &Renderer) -> Snapshot {
    let context = &renderer.context;
    let state = renderer.restir.as_ref().unwrap();
    let uniform = state
        .uniform_for_test(0)
        .read(restir::UNIFORM_BYTES as usize)
        .unwrap();
    let counts: [u32; 3] = std::array::from_fn(|index| {
        u32::from_le_bytes(
            uniform[448 + 4 * index..452 + 4 * index]
                .try_into()
                .unwrap(),
        )
    });
    let watermark = u32::from_le_bytes(uniform[460..464].try_into().unwrap());
    let tables = renderer
        .geometry
        .as_ref()
        .unwrap()
        .history_identity_buffers_for_test();
    for (index, table) in tables.iter().enumerate() {
        let address = u64::from_le_bytes(
            uniform[416 + 8 * index..424 + 8 * index]
                .try_into()
                .unwrap(),
        );
        assert_eq!(
            address,
            table.address(),
            "read the exact production identity table"
        );
    }
    let quad_address = u64::from_le_bytes(uniform[440..448].try_into().unwrap());
    let quad_table = renderer
        .geometry
        .as_ref()
        .unwrap()
        .history_quad_buffer_for_test()
        .filter(|_| quad_address != 0);
    assert_eq!(
        quad_address,
        quad_table.map_or(0, Buffer::address),
        "read the exact production per-quad identity table"
    );
    let mut offsets = [0u64; 3];
    let mut bytes = 0u64;
    for (offset, count) in offsets.iter_mut().zip(counts) {
        *offset = bytes;
        bytes += u64::from(count) * 8;
    }
    let quad_offset = bytes;
    let quad_bytes = quad_table.map_or(0, |table| table.size);
    bytes += quad_bytes;
    bytes = bytes.next_multiple_of(16);
    let reservoir_offset = bytes;
    let (storage, source_offset, padded) = state.history_for_test();
    let reservoir_bytes = u64::from(padded) * 80;
    bytes += reservoir_bytes;
    let image_offset = bytes;
    bytes += u64::from(EXTENT[0] * EXTENT[1]) * 16;
    let readback = Buffer::new_readback(context, bytes).unwrap();
    let image = renderer.output.as_ref().unwrap().linear_image();
    context
        .submit_named("restir_history_support_readback", |command| unsafe {
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::MEMORY_WRITE | vk::AccessFlags::MEMORY_READ)
                    .dst_access_mask(vk::AccessFlags::TRANSFER_READ)],
                &[],
                &[],
            );
            for ((table, count), offset) in tables.iter().zip(counts).zip(offsets) {
                if count != 0 {
                    context.device.cmd_copy_buffer(
                        command,
                        table.buffer,
                        readback.buffer,
                        &[vk::BufferCopy::default()
                            .dst_offset(offset)
                            .size(u64::from(count) * 8)],
                    );
                }
            }
            if let Some(table) = quad_table {
                context.device.cmd_copy_buffer(
                    command,
                    table.buffer,
                    readback.buffer,
                    &[vk::BufferCopy::default()
                        .dst_offset(quad_offset)
                        .size(quad_bytes)],
                );
            }
            context.device.cmd_copy_buffer(
                command,
                storage.buffer,
                readback.buffer,
                &[vk::BufferCopy::default()
                    .src_offset(source_offset)
                    .dst_offset(reservoir_offset)
                    .size(reservoir_bytes)],
            );
            context.device.cmd_copy_image_to_buffer(
                command,
                image.image,
                vk::ImageLayout::GENERAL,
                readback.buffer,
                &[vk::BufferImageCopy::default()
                    .buffer_offset(image_offset)
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .layer_count(1),
                    )
                    .image_extent(vk::Extent3D {
                        width: EXTENT[0],
                        height: EXTENT[1],
                        depth: 1,
                    })],
            );
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST | vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(
                        vk::AccessFlags::TRANSFER_READ | vk::AccessFlags::TRANSFER_WRITE,
                    )
                    .dst_access_mask(
                        vk::AccessFlags::HOST_READ
                            | vk::AccessFlags::SHADER_READ
                            | vk::AccessFlags::SHADER_WRITE,
                    )],
                &[],
                &[],
            );
        })
        .unwrap();
    let bytes = readback.read(bytes as usize).unwrap();
    let identities: [Vec<[u32; 2]>; 3] = std::array::from_fn(|index| {
        bytes[offsets[index] as usize..][..counts[index] as usize * 8]
            .as_chunks::<8>()
            .0
            .iter()
            .map(|row| {
                [
                    u32::from_le_bytes(row[..4].try_into().unwrap()),
                    u32::from_le_bytes(row[4..].try_into().unwrap()),
                ]
            })
            .collect()
    });
    // Buffer capacity may contain uninitialized spare bytes. Interpret only the
    // complete range addressed by live static headers copied from the device.
    let quad_count = identities[0]
        .iter()
        .filter(|header| header[1] & 0x8000_0000 != 0)
        .map(|header| header[0] as usize + (header[1] & 0x7fff_ffff) as usize / 2)
        .max()
        .unwrap_or(0);
    assert!(quad_count as u64 * 8 <= quad_bytes);
    let quads = bytes[quad_offset as usize..][..quad_count * 8]
        .as_chunks::<8>()
        .0
        .iter()
        .map(|row| {
            [
                u32::from_le_bytes(row[..4].try_into().unwrap()),
                u32::from_le_bytes(row[4..].try_into().unwrap()),
            ]
        })
        .collect();
    let mut reservoirs = Vec::new();
    for y in 0..EXTENT[1] {
        for x in 0..EXTENT[0] {
            let morton = (0..4).fold(0, |offset, bit| {
                offset | (((x >> bit) & 1) << (2 * bit)) | (((y >> bit) & 1) << (2 * bit + 1))
            });
            let index = ((y / 16) * EXTENT[0].div_ceil(16) + x / 16) * 256 + morton;
            let start = reservoir_offset as usize + index as usize * 80;
            let m = f32::from_le_bytes(bytes[start..start + 4].try_into().unwrap());
            assert!(m.is_finite() && (0.0..=84.0).contains(&m));
            reservoirs.push(m);
        }
    }
    let rgb = bytes[image_offset as usize..]
        .as_chunks::<16>()
        .0
        .iter()
        .map(|pixel| {
            let values: [f32; 4] = std::array::from_fn(|i| {
                f32::from_le_bytes(pixel[i * 4..i * 4 + 4].try_into().unwrap())
            });
            assert!(values.into_iter().all(f32::is_finite));
            let [r, g, b, _] = values;
            [
                1.660_491 * r - 0.587_641_1 * g - 0.072_849_86 * b,
                -0.124_550_48 * r + 1.132_899_9 * g - 0.008_349_422 * b,
                -0.018_150_764 * r - 0.100_578_9 * g + 1.118_729_7 * b,
            ]
        })
        .collect();
    Snapshot {
        identities,
        quads,
        watermark,
        reservoirs,
        rgb,
    }
}

fn mesh(scene: &mut Scene, key: u64, origin: [f64; 3], faces: Vec<SurfaceFace>) {
    scene
        .ready_terrain
        .insert(Cell::containing(origin).unwrap());
    let flags = faces[0].flags();
    scene.meshes.insert(
        (key, flags),
        SceneMesh {
            revision: 1,
            flags,
            origin,
            triangles: MeshGeometry::Surfaces(Arc::new(
                SurfaceMesh::from_resolved(1, faces).unwrap(),
            )),
        },
    );
}

fn fixture() -> (Scene, InstanceScene, Camera) {
    let mut scene = Scene {
        revision: 1,
        epoch: 1,
        ..Default::default()
    };
    for (key, origin) in [(1, [48., 0., 0.]), (2, [64., 0., 0.])] {
        mesh(&mut scene, key, origin, vec![face(0.)]);
    }
    for (key, origin, x, color, texture_id, flags) in [
        (3, [48., 0., 0.], 8., [20., 0., 0.], 7, 1),
        (4, [64., 0., 0.], 6., [0., 20., 0.], 0, 0),
    ] {
        let mut lamp = face(3.);
        lamp.geometry.positions = [
            [x, 6., 3.],
            [x + 2., 6., 3.],
            [x + 2., 10., 3.],
            [x, 10., 3.],
        ];
        lamp.geometry.colors = [[1.; 4]; 4];
        lamp.geometry.texture_id = texture_id;
        lamp.geometry.flags = flags;
        lamp.emission = Emission {
            radiance: color,
            two_sided: true,
            textured: false,
        };
        mesh(&mut scene, key, origin, vec![lamp]);
    }
    let mut wall = face(0.);
    wall.geometry.positions = [[0., 0., 0.], [0., 16., 0.], [0., 16., 3.5], [0., 0., 3.5]];
    mesh(&mut scene, 5, [64., 0., 0.], vec![wall]);
    scene.textures.insert(7, texture(1, 1, vec![255; 4]));
    scene.textures.insert(9, texture(1, 1, vec![255; 4]));
    let triangles = [[0, 1, 2], [0, 2, 3]].map(|indices| prime_scene::Triangle {
        positions: indices.map(|index| face(0.).geometry.positions[index]),
        colors: [[1.; 4]; 3],
        uvs: [[0.5; 2]; 3],
        texture_id: 7,
        flags: 1,
    });
    let mut instances = InstanceScene {
        epoch: 1,
        resource_revision: 1,
        instance_revision: 1,
        ..Default::default()
    };
    instances.prototypes.insert(
        1,
        prime_scene::Prototype {
            revision: 1,
            triangles: Arc::from(triangles),
            bounds: [[0., 0., 0.], [16., 16., 0.]],
        },
    );
    for (key, texture_id, x) in [(1, u32::MAX, 128.), (2, 9, 160.)] {
        instances.instances.insert(
            key,
            prime_scene::Instance {
                revision: 1,
                prototype_id: 1,
                origin: [x, 0., 0.],
                transform: [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0.],
                texture_id,
                flags: u32::MAX,
                tint: [255; 4],
                uv_transform: [1., 1., 0., 0.],
            },
        );
    }
    let mut camera = camera();
    camera.position[0] = 64.;
    (scene, instances, camera)
}

fn render(
    renderer: &mut Renderer,
    scene: &Scene,
    instances: &InstanceScene,
    camera: &Camera,
    sequence: &mut u32,
) {
    *sequence += 1;
    eprintln!(
        "support render begin: sequence={} revision={} sampler={:?}",
        *sequence, scene.revision, renderer.settings.light_sampling
    );
    renderer
        .render_with_instances(scene, instances, camera, EXTENT[0], EXTENT[1], *sequence)
        .unwrap();
    let state = renderer.restir.as_ref().unwrap();
    eprintln!(
        "support render end: sequence={} revision={} sampler={:?} temporal={} update={}",
        *sequence,
        scene.revision,
        renderer.settings.light_sampling,
        state.temporal_this_frame,
        state.dynamic_update_this_frame
    );
}

fn changed(before: &Snapshot, after: &Snapshot, domain: usize) -> (usize, usize) {
    assert_eq!(
        before.identities[domain].len(),
        after.identities[domain].len()
    );
    let (old_records, new_records) = if domain == 0 {
        assert_eq!(
            before.identities[0], after.identities[0],
            "coverage edit must not rewrite physical page ranges or alias headers"
        );
        assert_eq!(before.quads.len(), after.quads.len());
        (&before.quads, &after.quads)
    } else {
        (&before.identities[domain], &after.identities[domain])
    };
    let mut changed = 0;
    let mut preserved = 0;
    for (old, new) in old_records.iter().zip(new_records) {
        assert_eq!(
            old[1], new[1],
            "coverage support changes retain physical primitive count"
        );
        if old[1] == 0 {
            continue;
        }
        if new[0] > after.watermark {
            changed += 1;
        } else {
            assert_eq!(old, new, "unaffected device identity remains valid");
            preserved += 1;
        }
    }
    (changed, preserved)
}

fn assert_support_event(renderer: &Renderer, before: &Snapshot, label: &str, report: &mut String) {
    let state = renderer.restir.as_ref().unwrap();
    assert!(state.temporal_this_frame && state.dynamic_update_this_frame);
    let after = snapshot(renderer);
    let static_rows = changed(before, &after, 0);
    let dynamic_rows = changed(before, &after, 1);
    let emitter_rows = changed(before, &after, 2);
    assert_eq!(
        static_rows.0, 1,
        "only the red covered lamp uses texture7; its same-Cell floor must retain support"
    );
    assert!(
        static_rows.1 > 0,
        "unaffected actual static quad records remain valid"
    );
    assert_eq!(
        dynamic_rows,
        (1, 1),
        "INHERIT consumes texture7; override9 preserves support"
    );
    assert!(
        emitter_rows.0 == 0 && emitter_rows.1 > 0,
        "stable static NEE endpoints use the changed quad guard, not a compact emitter page stamp"
    );
    let (before_m, before_rgb) = before.mean(true);
    let (after_m, green) = after.mean(true);
    let (_, red) = after.mean(false);
    assert!(
        after_m > 4.,
        "actual unaffected GPU history contributes: {before_m} -> {after_m}"
    );
    assert!(
        green[1] > 0.02,
        "unaffected green emitter remains active: {green:?}"
    );
    assert!(
        red[0] < 0.02,
        "transparent covered emitter contributes no cached red ghost: {red:?}"
    );
    eprintln!(
        "ReSTIR support {label}: static={static_rows:?}, dynamic={dynamic_rows:?}, emitter={emitter_rows:?}, accepted={}, neighborM={before_m:.3}->{after_m:.3}, neighborRGB={before_rgb:?}->{green:?}, transparentRGB={red:?}",
        after.watermark
    );
    report.push_str(&format!(
        "{label},{},{},{},{},{},{},{},{before_m},{after_m},{},{},{}\n",
        after.watermark,
        static_rows.0,
        static_rows.1,
        dynamic_rows.0,
        dynamic_rows.1,
        emitter_rows.0,
        emitter_rows.1,
        red[0],
        green[1],
        red[2]
    ));
}

#[test]
#[ignore = "windowless production support identities, covered emitters, sprite phases and dynamic overrides"]
fn gpu_restir_support_changes_reject_only_affected_endpoints_without_ghosts() {
    support_fixture();
}

fn support_fixture() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Realtime,
        light_sampling: prime_scene::settings::LightSampling::Tree,
        bounces: 1,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        auto_exposure_compensation: 0.1,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer = Renderer::with_settings_and_workers(
        settings,
        Arc::new(prime_scene::workers::CpuWorkers::configured().unwrap()),
    )
    .unwrap();
    renderer.set_diagnostics(false).unwrap();
    let mut environment = renderer.environment;
    environment.sun_direction = [0., -1., 0.];
    renderer.set_environment(environment).unwrap();
    let (mut scene, instances, camera) = fixture();
    let mut sequence = 0;
    let mut report = String::from(
        "event,accepted_revision,static_changed,static_preserved,dynamic_changed,dynamic_preserved,emitter_changed,emitter_preserved,neighbor_M_before,neighbor_M_after,transparent_R,neighbor_G,transparent_B\n",
    );
    for _ in 0..12 {
        render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    }
    // RGB-only animation has identical coverage/coating support over its complete family.
    // Exercise the real texture cache and all three device identity domains before varying alpha.
    let constant = Texture {
        width: 2,
        height: 1,
        pixels: Arc::from([255, 64, 32, 255, 32, 64, 255, 255]),
        region: Some([0, 0, 1, 1]),
        sampling: Some(Arc::new(TextureSampling {
            levels: vec![],
            next: [0, 0],
            blend: 0.,
            coverage_frames: Arc::from([[0, 0], [1, 0]]),
        })),
        material: None,
    };
    constant.validate().unwrap();
    scene.textures.insert(7, constant.clone());
    scene.revision += 1;
    for _ in 0..8 {
        render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    }
    let constant_before = snapshot(&renderer);
    let mut phase = constant;
    phase.region = Some([1, 0, 1, 1]);
    let sampling = Arc::make_mut(phase.sampling.as_mut().unwrap());
    sampling.next = [0, 0];
    sampling.blend = 0.5;
    phase.validate().unwrap();
    scene.textures.insert(7, phase);
    scene.revision += 1;
    render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    assert!(
        renderer.restir.as_ref().unwrap().dynamic_update_this_frame,
        "RGB resources must still update cached radiance/PDF"
    );
    let constant_after = snapshot(&renderer);
    assert_eq!(
        constant_before.identities, constant_after.identities,
        "constant-alpha RGB animation incorrectly stamped static/dynamic/emitter support"
    );
    assert_eq!(
        constant_before.quads, constant_after.quads,
        "constant-alpha RGB phase changed per-quad support"
    );
    assert!(
        constant_after.mean(true).0 > 4.,
        "actual history lost on RGB-only animation"
    );
    eprintln!("ReSTIR constant-alpha RGB phase retained all three device identity domains");
    // The same spatially varying mask exists in every immutable sprite frame.
    // Publish once, then change phase/blend through the actual texture cache.
    let invariant = Texture {
        width: 4,
        height: 1,
        pixels: Arc::from([
            255, 32, 16, 255, 16, 32, 255, 0, 32, 255, 64, 255, 255, 64, 32, 0,
        ]),
        region: Some([0, 0, 2, 1]),
        sampling: Some(Arc::new(TextureSampling {
            levels: vec![],
            next: [0, 0],
            blend: 0.,
            coverage_frames: Arc::from([[0, 0], [2, 0]]),
        })),
        material: None,
    };
    invariant.validate().unwrap();
    scene.textures.insert(7, invariant.clone());
    scene.revision += 1;
    for _ in 0..8 {
        render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    }
    let invariant_before = snapshot(&renderer);
    let mut phase = invariant;
    phase.region = Some([2, 0, 2, 1]);
    let sampling = Arc::make_mut(phase.sampling.as_mut().unwrap());
    sampling.next = [0, 0];
    sampling.blend = 0.75;
    phase.validate().unwrap();
    scene.textures.insert(7, phase);
    scene.revision += 1;
    render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    let state = renderer.restir.as_ref().unwrap();
    assert!(state.temporal_this_frame && state.dynamic_update_this_frame);
    let invariant_after = snapshot(&renderer);
    assert_eq!(
        invariant_before.identities, invariant_after.identities,
        "invariant nonuniform alpha phase changed a legacy device support domain"
    );
    assert_eq!(
        invariant_before.quads, invariant_after.quads,
        "invariant nonuniform alpha phase changed actual per-quad support"
    );
    assert!(
        invariant_after.mean(true).0 > 4.,
        "invariant nonuniform alpha phase lost actual GPU temporal reuse"
    );
    eprintln!(
        "ReSTIR invariant nonuniform alpha phase/blend retained device support: neighborM={:.3}->{:.3}",
        invariant_before.mean(true).0,
        invariant_after.mean(true).0
    );
    let before = snapshot(&renderer);
    assert!(
        before.mean(false).1[0] > 0.1,
        "covered red emitter must actually contribute"
    );
    scene
        .textures
        .insert(7, texture(1, 1, vec![255, 255, 255, 0]));
    scene.revision += 1;
    render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    assert_support_event(&renderer, &before, "base-alpha", &mut report);

    let frames: Arc<[[u32; 2]]> = Arc::from([[0, 0], [1, 0]]);
    let opaque = Texture {
        width: 2,
        height: 1,
        pixels: Arc::from([255, 255, 255, 255, 255, 255, 255, 0]),
        region: Some([0, 0, 1, 1]),
        sampling: Some(Arc::new(TextureSampling {
            levels: vec![],
            next: [0, 0],
            blend: 0.,
            coverage_frames: frames.clone(),
        })),
        material: None,
    };
    opaque.validate().unwrap();
    scene.textures.insert(7, opaque.clone());
    scene.revision += 1;
    for _ in 0..8 {
        render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    }
    let before = snapshot(&renderer);
    let mut interpolated = opaque.clone();
    let sampling = Arc::make_mut(interpolated.sampling.as_mut().unwrap());
    sampling.next = [1, 0];
    // Sprite blend is [0, 1); alpha 0.05 has zero cutout support (< 0.1).
    sampling.blend = 0.95;
    assert!(Arc::ptr_eq(&sampling.coverage_frames, &frames));
    interpolated.validate().unwrap();
    scene.textures.insert(7, interpolated);
    scene.revision += 1;
    render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    assert_support_event(&renderer, &before, "same-proof-next-blend", &mut report);

    scene.textures.insert(7, opaque.clone());
    scene.revision += 1;
    for _ in 0..8 {
        render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    }
    let before = snapshot(&renderer);
    let mut region = opaque;
    region.region = Some([1, 0, 1, 1]);
    assert!(Arc::ptr_eq(
        &region.sampling.as_ref().unwrap().coverage_frames,
        &frames
    ));
    region.validate().unwrap();
    scene.textures.insert(7, region);
    scene.revision += 1;
    render(&mut renderer, &scene, &instances, &camera, &mut sequence);
    assert_support_event(&renderer, &before, "same-proof-region", &mut report);
    let directory =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../artifacts/restir-pt-rr");
    std::fs::create_dir_all(&directory).unwrap();
    std::fs::write(directory.join("history-support.csv"), report).unwrap();
}
