//! Actual renderer texture uploads and sprite/atlas material consumers, with GPU readback.
use super::{Context, Geometry, cpu_profile, shader_tests::run};
use prime_scene::scene::{InstanceScene, Scene, Triangle};
use prime_scene::{Instance, Prototype, Texture, TextureLevel, TextureMaterial, TextureSampling};
use std::sync::Arc;

const SHADER: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/pbr_texture.spv"));
const NORMAL: [[u8; 4]; 4] = [
    [255, 128, 255, 0],
    [128, 255, 191, 0],
    [128, 128, 128, 64],
    [0, 128, 64, 128],
];
const NORMAL_MIP: [u8; 4] = [128, 128, 160, 192];
const SPECULAR: [[u8; 4]; 4] = [
    [0, 5, 64, 0],
    [255, 231, 254, 254],
    [64, 230, 0, 255],
    [128, 239, 200, 127],
];
const SPECULAR_MIP: [u8; 4] = [112, 239, 200, 96];
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
        specular: Some(image(&SPECULAR, Some(SPECULAR_MIP))),
        authored_emission: true,
        bounds: Some([0.0, 0.0, 0.5, 1.0]),
        ..Default::default()
    }));
    scene.textures.insert(20, first);
    let mut second = image(&[[255; 4]; 4], None);
    second.material = Some(Arc::new(TextureMaterial {
        specular: Some(image(&[[192, 231, 0, 255]; 4], None)),
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
) -> [u32; 32] {
    query_identity(context, geometry, texture, uv, lod, 0x80000002)
}

fn query_identity(
    context: &Arc<Context>,
    geometry: &Geometry,
    texture: u32,
    uv: [f32; 2],
    lod: f32,
    identity: u32,
) -> [u32; 32] {
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
) -> [u32; 32] {
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
    run(context, SHADER, &input, 32, [0, 1], Some(geometry))
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
    let coordinate = uv.map(|v| v * 2.0 - 0.5);
    let lower = coordinate.map(|v| v.floor() as i32);
    let fraction = std::array::from_fn::<_, 2, _>(|i| coordinate[i] - lower[i] as f32);
    let mut result = [0.0; 4];
    for corner in 0..4 {
        let x = (lower[0] + (corner & 1)).clamp(0, 1) as usize;
        let y = (lower[1] + ((corner >> 1) & 1)).clamp(0, 1) as usize;
        let weight = if corner & 1 != 0 {
            fraction[0]
        } else {
            1.0 - fraction[0]
        } * if corner & 2 != 0 {
            fraction[1]
        } else {
            1.0 - fraction[1]
        };
        for channel in 0..4 {
            result[channel] += weight * f32::from(pixels[y * 2 + x][channel]) / 255.0;
        }
    }
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

fn ior(canonical_code: u8) -> f32 {
    let f0 = if canonical_code == 0 || canonical_code >= 231 {
        0.04
    } else {
        (f32::from(canonical_code - 1) / 255.0).clamp(0.02, 0.17)
    };
    (1.0 + f0.sqrt()) / (1.0 - f0.sqrt())
}

fn decode(value: f32) -> f32 {
    if value <= 0.04045 {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).powf(2.4)
    }
}

#[test]
#[ignore = "requires Vulkan; exercises actual texture upload, atlas material lookup and GPU consumers"]
fn gpu_labpbr_texture_upload_resolves_sprite_and_atlas_channels_and_authored_emission() {
    let context = Context::new().unwrap();
    let scene = fixture();
    let geometry = geometry(&context, &scene);
    for uv in [
        [0.25, 0.25],
        [0.75, 0.25],
        [0.25, 0.75],
        [0.75, 0.75],
        [0.5, 0.5],
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
                direct[2], 7,
                "normal, specular and authored emission controls"
            );
            assert_eq!(
                direct[6], 0,
                "the fixture AS query misses outside its triangle"
            );
            let normal = filtered(&NORMAL, uv, Some(NORMAL_MIP), lod);
            let continuous = filtered(&SPECULAR, uv, Some(SPECULAR_MIP), lod);
            let categorical = point(&SPECULAR, uv);
            for (channel, expected) in normal.into_iter().enumerate() {
                close(
                    f32::from_bits(direct[8 + channel]),
                    expected,
                    "normal spatial/mip filtering",
                );
                close(
                    f32::from_bits(atlas[8 + channel]),
                    expected,
                    "atlas-local normal filtering",
                );
            }
            for channel in 0..4 {
                let expected = if channel == 0 || channel == 3 {
                    continuous[channel]
                } else {
                    categorical[channel]
                };
                close(
                    f32::from_bits(direct[12 + channel]),
                    expected,
                    "continuous/categorical specular filtering",
                );
                close(
                    f32::from_bits(atlas[12 + channel]),
                    expected,
                    "atlas-local optical filtering",
                );
            }
            let encoded = (continuous[3] * 255.0).round() as u32;
            let emission = if encoded == 255 {
                0.0
            } else {
                encoded as f32 / 254.0
            };
            for (channel, color) in [0.2f32, 0.4, 0.6].into_iter().enumerate() {
                close(
                    f32::from_bits(direct[16 + channel]),
                    decode(color) * 1.5 * emission,
                    "authored emission replaces host fallback",
                );
            }
            assert_eq!(
                &direct[20..23],
                &[0; 3],
                "one-sided emitter has no back radiance"
            );
            let code = (categorical[1] * 255.0).round() as u8;
            close(f32::from_bits(direct[24]), ior(code), "per-texel glass IOR");
            close(
                f32::from_bits(atlas[24]),
                ior(code),
                "atlas-local glass IOR",
            );
            assert_eq!(
                &direct[25..32],
                &[0.1f32, 0.2, 0.3, 1.333, 0.4, 0.5, 0.6].map(f32::to_bits),
                "current G changes only the negative IOR, preserving extinction and the adjacent reference"
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
            u32::from_le_bytes(ATLAS_COLORS[y * 4 + x]),
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
    let expected = filtered(&NORMAL, [0.0, 1.0], None, 0.0);
    for (channel, expected) in expected.into_iter().enumerate() {
        close(
            f32::from_bits(endpoint[8 + channel]),
            expected,
            "direct sprite endpoint remains clamped",
        );
    }
    for (id, uv, expected_flags) in [
        (90, [0.25, 0.25], 2),
        (1, [0.625, 0.25], 2),
        (99, [0.25, 0.25], 0),
        (1, [0.875, 0.75], 0),
    ] {
        let actual = query(&context, &geometry, id, uv, 0.0);
        assert_eq!(actual[2], expected_flags);
        assert_eq!(&actual[8..12], &[0.5f32, 0.5, 1.0, 0.0].map(f32::to_bits));
        assert_eq!(
            &actual[16..19],
            &[8.0f32, 9.0, 10.0].map(f32::to_bits),
            "missing authored alpha preserves host emission"
        );
        if expected_flags == 0 {
            assert_eq!(
                actual[24],
                1.5f32.to_bits(),
                "absent G preserves the producer IOR"
            );
        }
    }
}

#[test]
#[ignore = "requires Vulkan; checks material frame replacement and stable descriptor/resource reuse"]
fn gpu_labpbr_material_frame_updates_keep_texture_identity_and_reuse_slots() {
    let context = Context::new().unwrap();
    let mut scene = fixture();
    let mut geometry = geometry(&context, &scene);
    let identities = geometry.textures().indices.clone();
    let initial_slots = geometry.textures().retained_slots();
    let normal = scene.textures[&20]
        .material
        .as_ref()
        .unwrap()
        .normal
        .clone();
    for frame in 0..12 {
        let pixel = if frame & 1 == 0 {
            [255, 21, 0, 254]
        } else {
            [0, 81, 254, 0]
        };
        let mut texture = scene.textures[&20].clone();
        texture.material = Some(Arc::new(TextureMaterial {
            normal: normal.clone(),
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
            ior(pixel[1]),
            "animated glass IOR",
        );
        close(
            f32::from_bits(atlas[24]),
            ior(pixel[1]),
            "animated atlas glass IOR",
        );
        // The current face can be opaque or water; positive reference consumption does
        // not depend on the negative dynamic flag or on its current texel coordinates.
        let reference = query_reference(&context, &geometry, 99, [0.125, 0.875], 8.0, 1, Some(20));
        assert_eq!(reference[24], 1.5f32.to_bits());
        close(
            f32::from_bits(reference[28]),
            ior(pixel[1]),
            "animated adjacent reference IOR",
        );
        assert_eq!(
            &reference[29..32],
            &[0.4f32, 0.5, 0.6].map(f32::to_bits),
            "animated adjacent IOR keeps homogeneous extinction"
        );
        if pixel[3] == 0 {
            assert_eq!(
                &direct[16..19],
                &[0; 3],
                "authored zero replaces a positive fallback"
            );
        }
    }
}
