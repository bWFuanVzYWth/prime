//! Actual renderer texture uploads and sprite/atlas material consumers, with GPU readback.
use super::{Context, Geometry, cpu_profile, shader_tests::run};
use prime_scene::scene::{InstanceScene, Scene, Triangle};
use prime_scene::{Instance, Prototype, Texture, TextureLevel, TextureMaterial, TextureSampling};
use std::sync::Arc;

#[test]
#[ignore = "windowless mapped texture planning: failed source validation preserves GPU tables and owners"]
fn gpu_texture_direct_write_validation_failure_preserves_published_state() {
    use crate::resources::Buffer;
    use ash::vk;
    let context = Context::new().unwrap();
    let scene = fixture();
    let mut uploads = crate::arena::Arena::new(&context, true);
    let mut textures =
        crate::textures::Textures::new(&context, &scene.textures, &mut uploads).unwrap();
    let identities = textures.indices.clone();
    let slots = textures.retained_slots();
    let coverage = textures.coverage_changed.clone();
    let occlusion = textures.occlusion_changed;
    let read = |source: &Buffer| {
        let destination = Buffer::new(
            &context,
            source.size,
            vk::BufferUsageFlags::TRANSFER_DST,
            true,
        )
        .unwrap();
        context
            .submit_named("texture_validation_readback", |command| unsafe {
                context.device.cmd_copy_buffer(
                    command,
                    source.buffer,
                    destination.buffer,
                    &[vk::BufferCopy::default().size(source.size)],
                );
                context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::HOST,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                        .dst_access_mask(vk::AccessFlags::HOST_READ)],
                    &[],
                    &[],
                );
            })
            .unwrap();
        destination.read(source.size as usize).unwrap()
    };
    let metadata = read(&textures.metadata);
    let texels = read(&textures.texels);
    let mut invalid = scene.textures.clone();
    invalid.remove(&90);
    let mut atlas = invalid[&1].clone();
    let material = Arc::make_mut(atlas.material.as_mut().unwrap());
    let lookup = material.coverage.as_mut().unwrap();
    Arc::make_mut(&mut lookup.pixels)[0..4].copy_from_slice(&999u32.to_le_bytes());
    invalid.insert(1, atlas);
    assert!(
        textures
            .update(&context, &invalid, &mut uploads)
            .unwrap_err()
            .contains("sprite missing")
    );
    assert_eq!(textures.indices, identities);
    assert_eq!(textures.retained_slots(), slots);
    assert_eq!(textures.source.len(), scene.textures.len());
    assert_eq!(textures.coverage_changed, coverage);
    assert_eq!(textures.occlusion_changed, occlusion);
    assert_eq!(read(&textures.metadata), metadata);
    assert_eq!(read(&textures.texels), texels);
    assert!(
        !textures
            .update(&context, &scene.textures, &mut uploads)
            .unwrap()
    );
}
const NORMAL: [[u8; 4]; 4] = [
    [255, 128, 0, 85],
    [128, 255, 0, 170],
    [128, 128, 64, 238],
    [1, 128, 128, 0],
];
const NORMAL_MIP: [u8; 4] = [128, 128, 192, 85];
const SPECULAR: [[u8; 4]; 4] = [
    [0, 0, 255, 0],
    [255, 255, 0, 255],
    [0, 0, 191, 0],
    [128, 128, 127, 182],
];
const SPECULAR_MIP: [u8; 4] = [128, 96, 143, 91];
const ATLAS_COLORS: [[u8; 4]; 8] = [
    [16, 32, 48, 255],
    [32, 48, 64, 255],
    [48, 64, 80, 255],
    [64, 80, 96, 255],
    [80, 96, 112, 255],
    [96, 112, 128, 255],
    [112, 128, 144, 255],
    [128, 144, 160, 255],
];

fn image(pixels: &[[u8; 4]; 4], mip: Option<[u8; 4]>) -> Texture {
    Texture {
        width: 2,
        height: 2,
        pixels: pixels.iter().flatten().copied().collect(),
        region: Some([0, 0, 2, 2]),
        sampling: mip.map(|pixel| {
            Arc::new(TextureSampling {
                levels: vec![TextureLevel {
                    width: 1,
                    height: 1,
                    pixels: Arc::from(pixel),
                    region: [0, 0, 1, 1],
                    next: [0; 2],
                }],
                next: [0; 2],
                blend: 0.0,
                coverage_frames: Arc::from([]),
            })
        }),
        material: None,
    }
}

fn fixture() -> Scene {
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    let lookup = [20u32, 20, 90, 90, 20, 20, 90, 0];
    scene.textures.insert(
        1,
        Texture {
            width: 4,
            height: 2,
            pixels: ATLAS_COLORS.iter().flatten().copied().collect(),
            region: None,
            sampling: None,
            material: Some(Arc::new(TextureMaterial {
                coverage: Some(Texture {
                    width: 4,
                    height: 2,
                    pixels: lookup.into_iter().flat_map(u32::to_le_bytes).collect(),
                    region: None,
                    sampling: None,
                    material: None,
                }),
                atlas_lookup: true,
                ..Default::default()
            })),
        },
    );
    let mut first = image(&[[255; 4]; 4], None);
    first.material = Some(Arc::new(TextureMaterial {
        normal: Some(image(&NORMAL, Some(NORMAL_MIP))),
        has_normal: true,
        specular: Some(image(&SPECULAR, Some(SPECULAR_MIP))),
        authored_emission: true,
        bounds: Some([0.0, 0.0, 0.5, 1.0]),
        ..Default::default()
    }));
    scene.textures.insert(20, first);
    let mut second = image(&[[255; 4]; 4], None);
    second.material = Some(Arc::new(TextureMaterial {
        normal: Some(image(&[[128, 128, 0, 170]; 4], None)),
        specular: Some(image(&[[255, 0, 63, 0]; 4], None)),
        bounds: Some([0.5, 0.0, 1.0, 1.0]),
        ..Default::default()
    }));
    scene.textures.insert(90, second);
    scene.textures.insert(99, image(&[[255; 4]; 4], None));
    scene
}

fn geometry(context: &Arc<Context>, scene: &Scene) -> Geometry {
    let mut geometry = Geometry::new(
        context,
        scene.into(),
        Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap()),
    )
    .unwrap();
    let mut instances = InstanceScene {
        epoch: 1,
        resource_revision: 1,
        instance_revision: 1,
        ..Default::default()
    };
    instances.prototypes.insert(
        1,
        Prototype {
            revision: 1,
            triangles: Arc::from([Triangle {
                positions: [[0., 0., 0.], [1., 0., 0.], [0., 1., 0.]],
                colors: [[1.; 4]; 3],
                uvs: [[0., 0.], [1., 0.], [0., 1.]],
                texture_id: 1,
                flags: 0,
            }]),
            bounds: [[0.; 3], [1.; 3]],
        },
    );
    instances.instances.insert(
        1,
        Instance {
            revision: 1,
            prototype_id: 1,
            origin: [0.; 3],
            transform: [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0.],
            texture_id: u32::MAX,
            flags: u32::MAX,
            tint: [255; 4],
            uv_transform: [1., 1., 0., 0.],
        },
    );
    geometry
        .prepare_dynamic(
            context,
            scene,
            &instances,
            0,
            &mut cpu_profile::FrameCpu::default(),
        )
        .unwrap();
    geometry
}

fn query(
    context: &Arc<Context>,
    geometry: &Geometry,
    texture: u32,
    uv: [f32; 2],
    lod: f32,
) -> [u32; 48] {
    query_identity(context, geometry, texture, uv, lod, 0x80000002)
}

fn query_identity(
    context: &Arc<Context>,
    geometry: &Geometry,
    texture: u32,
    uv: [f32; 2],
    lod: f32,
    identity: u32,
) -> [u32; 48] {
    query_reference(context, geometry, texture, uv, lod, identity, None)
}

fn query_reference(
    context: &Arc<Context>,
    geometry: &Geometry,
    texture: u32,
    uv: [f32; 2],
    lod: f32,
    identity: u32,
    positive_texture: Option<u32>,
) -> [u32; 48] {
    let input = [
        [
            geometry.textures().index(texture).unwrap(),
            uv[0].to_bits(),
            uv[1].to_bits(),
            lod.to_bits(),
        ],
        [0.2f32, 0.4, 0.6, 1.0].map(f32::to_bits),
        [
            8.0f32.to_bits(),
            9.0f32.to_bits(),
            10.0f32.to_bits(),
            identity,
        ],
        [
            positive_texture.map_or(0, |id| geometry.textures().index(id).unwrap()),
            0,
            0,
            0,
        ],
    ]
    .concat();
    run(
        context,
        prime_shader_tests::pbr_texture(),
        &input,
        48,
        [0, 1],
        Some(geometry),
    )
    .try_into()
    .unwrap()
}

fn close(actual: f32, expected: f32, label: &str) {
    assert!(
        actual.is_finite() && (actual - expected).abs() <= 3e-6,
        "{label}: {actual} != {expected}"
    );
}

fn filtered(pixels: &[[u8; 4]; 4], uv: [f32; 2], mip: Option<[u8; 4]>, lod: f32) -> [f32; 4] {
    let mut result = point(pixels, uv);
    if let Some(mip) = mip {
        let fraction = lod.clamp(0.0, 1.0);
        for channel in 0..4 {
            result[channel] += (f32::from(mip[channel]) / 255.0 - result[channel]) * fraction;
        }
    }
    result
}

fn point(pixels: &[[u8; 4]; 4], uv: [f32; 2]) -> [f32; 4] {
    let [x, y] = uv.map(|v| (v.clamp(0.0, 1.0) * 2.0).floor().min(1.0) as usize);
    pixels[y * 2 + x].map(|v| f32::from(v) / 255.0)
}

fn ior(byte: u8) -> f32 {
    1.0 + 1.5 * f32::from(byte) / 255.0
}

fn decode(value: f32) -> f32 {
    if value <= 0.04045 {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).powf(2.4)
    }
}

#[test]
#[ignore = "requires Vulkan; actual source/atlas color and emitter consumers preserve original coverage alpha"]
fn gpu_canonical_atlas_shared_color_override_keeps_original_coverage() {
    let context = Context::new().unwrap();
    let mut scene = fixture();
    let mut source = scene.textures[&20].clone();
    source.pixels = [200u8, 100, 50, 32].repeat(4).into();
    scene.textures.insert(20, source);
    let geometry = geometry(&context, &scene);
    let direct = query(&context, &geometry, 20, [0.75, 0.25], 0.0);
    let atlas = query(&context, &geometry, 1, [0.375, 0.25], 0.0);
    assert_eq!(direct[7], u32::from_le_bytes([200, 100, 50, 32]));
    assert_eq!(atlas[7], u32::from_le_bytes([200, 100, 50, 255]));
    assert_eq!(
        &direct[16..19],
        &atlas[16..19],
        "both emitter consumers use shared C RGB"
    );
    for (channel, (color, tint)) in [(200.0, 0.2), (100.0, 0.4), (50.0, 0.6)]
        .into_iter()
        .enumerate()
    {
        close(
            f32::from_bits(direct[16 + channel]),
            decode(color / 255.0 * tint) * 1.5,
            "converted shared C reaches actual source emission",
        );
    }
}

#[test]
#[ignore = "requires Vulkan; checks actual near texel boundaries and hit/NEE mip0 radiance"]
fn gpu_canonical_material_nearest_detail_and_mip0_emission_match_hit_and_nee() {
    let context = Context::new().unwrap();
    let mut scene = fixture();
    let colors = [
        [32, 64, 128, 255],
        [224, 128, 32, 128],
        [12, 190, 70, 64],
        [200, 30, 240, 255],
    ];
    let color_mip = [150, 120, 110, 200];
    let mut color = image(&colors, Some(color_mip));
    color.material = scene.textures[&20].material.clone();
    scene.textures.insert(20, color);
    let geometry = geometry(&context, &scene);
    for uv in [
        [0.4, 0.25],
        [0.49, 0.25],
        [0.5f32.next_down(), 0.25],
        [0.5, 0.25],
        [0.5f32.next_up(), 0.25],
        [0.6, 0.25],
        [0.25, 0.6],
        [0.6, 0.6],
    ] {
        let pixel = point(&SPECULAR, uv);
        let base = point(&colors, uv);
        for lod in [0.0, 0.6, 1.0, 3.0] {
            let actual = query(&context, &geometry, 20, uv, lod);
            let atlas = query(&context, &geometry, 1, [uv[0] * 0.5, uv[1]], lod);
            for channel in 0..4 {
                let expected = filtered(&SPECULAR, uv, Some(SPECULAR_MIP), lod)[channel];
                close(
                    f32::from_bits(actual[36 + channel]),
                    expected,
                    "actual material detail and mip",
                );
                if lod == 0.0 {
                    close(
                        f32::from_bits(actual[12 + channel]),
                        pixel[channel],
                        "mip0 retains each MERS texel",
                    );
                }
            }
            for (channel, tint) in [0.2, 0.4, 0.6].into_iter().enumerate() {
                let radiance = decode(base[channel] * tint) * 1.5 * pixel[1];
                close(
                    f32::from_bits(actual[16 + channel]),
                    radiance,
                    "NEE mip0 RGB and G",
                );
                close(
                    f32::from_bits(actual[40 + channel]),
                    radiance,
                    "closest-hit mip0 RGB and G",
                );
                close(
                    f32::from_bits(atlas[40 + channel]),
                    radiance,
                    "atlas closest-hit mip0 RGB and G",
                );
                let filtered_color = filtered(&colors, uv, Some(color_mip), lod)[channel] * tint;
                close(
                    f32::from_bits(actual[44 + channel]),
                    filtered_color,
                    "scattering Color still follows ray-cone mip",
                );
            }
            if uv[0] < 0.5 && uv[1] < 0.5 {
                assert_eq!(
                    &actual[40..43],
                    &[0; 3],
                    "inactive emitter texel stays dark even when mip G is positive"
                );
            }
        }
    }
}

#[test]
#[ignore = "requires Vulkan; exercises actual texture upload, atlas material lookup and GPU consumers"]
fn gpu_canonical_texture_upload_resolves_sprite_and_atlas_channels_and_authored_emission() {
    let context = Context::new().unwrap();
    let scene = fixture();
    let geometry = geometry(&context, &scene);
    for uv in [
        [0.25, 0.25],
        [0.75, 0.25],
        [0.25, 0.75],
        [0.75, 0.75],
        [0.5, 0.5],
        [0.1, 0.1],
        [0.4, 0.25],
        [0.49, 0.25],
        [0.5f32.next_down(), 0.25],
        [0.5, 0.25],
        [0.5f32.next_up(), 0.25],
        [0.51, 0.25],
        [0.75, 0.49],
        [0.75, 0.5],
        [0.75, 0.51],
        [0., 0.],
    ] {
        for lod in [0.0f32, 0.49, 0.51, 1.0] {
            let direct = query(&context, &geometry, 20, uv, lod);
            let atlas = query(&context, &geometry, 1, [uv[0] * 0.5, uv[1]], lod);
            assert_eq!(
                &direct[..4],
                &atlas[..4],
                "atlas must resolve the sprite material descriptors"
            );
            assert_eq!(
                direct[2], 23,
                "normal, specular and authored emission controls"
            );
            assert_eq!(
                direct[6], 0,
                "the fixture AS query misses outside its triangle"
            );
            let mut normal = filtered(&NORMAL, uv, Some(NORMAL_MIP), lod);
            normal[3] = point(&NORMAL, uv)[3];
            let continuous = filtered(&SPECULAR, uv, Some(SPECULAR_MIP), lod);
            let ior_point = point(&NORMAL, uv);
            for (channel, expected) in normal.into_iter().enumerate() {
                close(
                    f32::from_bits(direct[8 + channel]),
                    expected,
                    "normal nearest-per-level/mip filtering",
                );
                close(
                    f32::from_bits(atlas[8 + channel]),
                    expected,
                    "atlas-local normal filtering",
                );
            }
            for channel in 0..4 {
                let expected = continuous[channel];
                close(
                    f32::from_bits(direct[12 + channel]),
                    expected,
                    "all-channel MERS filtering",
                );
                close(
                    f32::from_bits(atlas[12 + channel]),
                    expected,
                    "atlas-local optical filtering",
                );
            }
            // Radiance has one mip0 point domain, shared by closest hits and light sampling.
            // Scattering material parameters still use the requested ray-cone mip level.
            let emission = point(&SPECULAR, uv)[1];
            for (channel, color) in [0.2f32, 0.4, 0.6].into_iter().enumerate() {
                close(
                    f32::from_bits(direct[16 + channel]),
                    decode(color) * 1.5 * emission,
                    "authored emission replaces host fallback",
                );
                close(
                    f32::from_bits(direct[40 + channel]),
                    f32::from_bits(direct[16 + channel]),
                    "actual closest hit and emitter query use the same radiance",
                );
            }
            for channel in 0..4 {
                close(
                    f32::from_bits(direct[32 + channel]),
                    normal[channel],
                    "actual closest-hit Aux sampling",
                );
                close(
                    f32::from_bits(direct[36 + channel]),
                    continuous[channel],
                    "actual closest-hit MERS sampling",
                );
            }
            assert_eq!(
                &direct[20..23],
                &[0; 3],
                "one-sided emitter has no back radiance"
            );
            let code = (ior_point[3] * 255.0).round() as u8;
            close(f32::from_bits(direct[24]), ior(code), "per-texel glass IOR");
            close(
                f32::from_bits(atlas[24]),
                ior(code),
                "atlas-local glass IOR",
            );
            assert_eq!(
                &direct[25..32],
                &[0.1f32, 0.2, 0.3, 1.333, 0.4, 0.5, 0.6].map(f32::to_bits),
                "current Aux A changes only the negative IOR, preserving extinction and the adjacent reference"
            );
            let water = query_identity(&context, &geometry, 20, uv, lod, 1);
            assert_eq!(
                &water[24..28],
                &[1.5f32, 0.1, 0.2, 0.3].map(f32::to_bits),
                "a constant medium identity does not read the per-texel glass IOR"
            );
        }
    }
    // Raw atlas UVs follow base-color wrap, including exact 1 and negative coordinates.
    // Direct sprite views retain their independent endpoint-clamp contract.
    for (uv, sprite) in [
        ([0.0f32, 1.0], 20),
        ([1.0, 0.25], 20),
        ([1.125, 1.25], 20),
        ([-0.375, -0.75], 90),
        ([0.75, 1.25], 90),
        ([2.875, -0.25], 0),
        ([-1.125, 2.75], 0),
    ] {
        let wrapped = uv.map(|v| v - v.floor());
        let actual = query(&context, &geometry, 1, uv, 0.0);
        let canonical = query(&context, &geometry, 1, wrapped, 0.0);
        assert_eq!(
            actual, canonical,
            "all atlas consumers resolve the same wrapped source texel"
        );
        let [x, y] = [(wrapped[0] * 4.0) as usize, (wrapped[1] * 2.0) as usize];
        assert_eq!(
            actual[7],
            if sprite == 0 {
                u32::from_le_bytes(ATLAS_COLORS[y * 4 + x])
            } else {
                u32::MAX
            },
            "base sample uses the same atlas support as material identity"
        );
        if sprite == 0 {
            assert_eq!(&actual[..4], &[0; 4]);
        } else {
            let local = [
                wrapped[0] * 2.0 - if sprite == 90 { 1.0 } else { 0.0 },
                wrapped[1],
            ];
            let direct = query(&context, &geometry, sprite, local, 0.0);
            assert_eq!(&actual[..4], &direct[..4]);
            assert_eq!(&actual[8..], &direct[8..]);
            for (channel, expected) in local.into_iter().enumerate() {
                close(
                    f32::from_bits(actual[4 + channel]),
                    expected,
                    "wrapped atlas-local UV",
                );
            }
        }
    }
    let endpoint = query(&context, &geometry, 20, [0.0, 1.0], 0.0);
    let mut expected = filtered(&NORMAL, [0.0, 1.0], None, 0.0);
    expected[3] = point(&NORMAL, [0.0, 1.0])[3];
    for (channel, expected) in expected.into_iter().enumerate() {
        close(
            f32::from_bits(endpoint[8 + channel]),
            expected,
            "direct sprite endpoint remains clamped",
        );
    }
    for (id, uv, expected_flags) in [
        (90, [0.25, 0.25], 3),
        (1, [0.625, 0.25], 3),
        (99, [0.25, 0.25], 0),
        (1, [0.875, 0.75], 0),
    ] {
        let actual = query(&context, &geometry, id, uv, 0.0);
        assert_eq!(actual[2], expected_flags);
        let expected_ior = if expected_flags & 1 != 0 {
            170.0 / 255.0
        } else {
            85.0 / 255.0
        };
        for (channel, expected) in [128.0 / 255.0, 128.0 / 255.0, 0.0, expected_ior]
            .into_iter()
            .enumerate()
        {
            close(
                f32::from_bits(actual[8 + channel]),
                expected,
                "IOR-only aux does not imply a normal map",
            );
        }
        assert_eq!(
            &actual[16..19],
            &[8.0f32, 9.0, 10.0].map(f32::to_bits),
            "missing authored alpha preserves host emission"
        );
        if expected_flags == 0 {
            assert_eq!(
                actual[24],
                1.5f32.to_bits(),
                "absent Aux preserves the producer IOR"
            );
        }
    }
}

#[test]
#[ignore = "requires Vulkan; checks material frame replacement and stable descriptor/resource reuse"]
fn gpu_canonical_material_frame_updates_keep_texture_identity_and_reuse_slots() {
    let context = Context::new().unwrap();
    let mut scene = fixture();
    let mut geometry = geometry(&context, &scene);
    let identities = geometry.textures().indices.clone();
    let initial_slots = geometry.textures().retained_slots();
    for frame in 0..12 {
        let pixel = if frame & 1 == 0 {
            [255, 255, 0, 0]
        } else {
            [0, 0, 255, 255]
        };
        let ior_byte = if frame & 1 == 0 { 21 } else { 81 };
        let mut texture = scene.textures[&20].clone();
        texture.material = Some(Arc::new(TextureMaterial {
            normal: Some(image(
                &[[128, 128, 0, ior_byte]; 4],
                Some([128, 128, 0, ior_byte]),
            )),
            has_normal: true,
            specular: Some(image(&[pixel; 4], Some(pixel))),
            authored_emission: true,
            bounds: Some([0.0, 0.0, 0.5, 1.0]),
            ..Default::default()
        }));
        scene.revision += 1;
        scene.textures.insert(20, texture);
        geometry.update(&context, (&scene).into()).unwrap();
        assert_eq!(
            geometry.textures().indices,
            identities,
            "animated material identity is stable"
        );
        assert_eq!(
            geometry.textures().retained_slots(),
            initial_slots,
            "same-extent frame replacement reuses arenas"
        );
        let direct = query(&context, &geometry, 20, [0.25, 0.25], 0.3);
        let atlas = query(&context, &geometry, 1, [0.125, 0.25], 0.3);
        for channel in 0..4 {
            let expected = f32::from(pixel[channel]) / 255.0;
            close(
                f32::from_bits(direct[12 + channel]),
                expected,
                "replacement material frame",
            );
            close(
                f32::from_bits(atlas[12 + channel]),
                expected,
                "atlas follows stable frame descriptor",
            );
        }
        close(
            f32::from_bits(direct[24]),
            ior(ior_byte),
            "animated glass IOR",
        );
        close(
            f32::from_bits(atlas[24]),
            ior(ior_byte),
            "animated atlas glass IOR",
        );
        // The current face can be opaque or water; positive reference consumption does
        // not depend on the negative dynamic flag or on its current texel coordinates.
        let reference = query_reference(&context, &geometry, 99, [0.125, 0.875], 8.0, 1, Some(20));
        assert_eq!(reference[24], 1.5f32.to_bits());
        close(
            f32::from_bits(reference[28]),
            ior(ior_byte),
            "animated adjacent reference IOR",
        );
        assert_eq!(
            &reference[29..32],
            &[0.4f32, 0.5, 0.6].map(f32::to_bits),
            "animated adjacent IOR keeps homogeneous extinction"
        );
        if pixel[1] == 0 {
            assert_eq!(
                &direct[16..19],
                &[0; 3],
                "authored zero replaces a positive fallback"
            );
        }
    }
}
