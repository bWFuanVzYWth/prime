use super::*;
use prime_scene::{
    SceneMesh, Texture,
    geometry::{CompiledQuad, MeshGeometry},
    spatial::Cell,
};

pub(crate) fn scene(edge: usize, pattern: &str, flags: u32) -> Scene {
    let mut scene = Scene {
        revision: 1,
        ..Default::default()
    };
    // A nontrivial atlas with distinct RGB/coverage and margins outside the repeated region.
    let pixels = (0..32)
        .flat_map(|y| {
            (0..32).flat_map(move |x| {
                [
                    (x * 7) as u8,
                    (y * 7) as u8,
                    ((x * 3 + y * 5) % 256) as u8,
                    if (x / 2 + y / 2) % 3 == 0 { 0 } else { 255 },
                ]
            })
        })
        .collect::<Vec<_>>();
    scene.textures.insert(
        1,
        Texture {
            region: None,
            sampling: None,
            material: None,
            width: 32,
            height: 32,
            pixels: pixels.into(),
        },
    );
    for cy in 0..edge {
        for cx in 0..edge {
            let mut quads = (0..64)
                .flat_map(|y| {
                    (0..64).map(move |x| {
                        let mut q = CompiledQuad {
                            positions: [
                                [x as f32, y as f32, 0.],
                                [x as f32 + 1., y as f32, 0.],
                                [x as f32 + 1., y as f32 + 1., 0.],
                                [x as f32, y as f32 + 1., 0.],
                            ],
                            uvs: [[0.25, 0.25], [0.75, 0.25], [0.75, 0.75], [0.25, 0.75]],
                            color: [0.7, 0.8, 0.9, if flags == 2 { 0.625 } else { 1. }],
                            texture_id: 1,
                            flags,
                        };
                        match pattern {
                            "tiled" => q.color[0] = if (x / 8 + y / 8) % 2 == 0 { 0.5 } else { 1. },
                            "checker" => q.color[0] = if (x + y) % 2 == 0 { 0.5 } else { 1. },
                            "sloped" => q.positions[2][2] = 0.125,
                            "rotated" => q.uvs.rotate_right(1),
                            "uniform" | "layers" => {}
                            _ => panic!("unknown fixture"),
                        }
                        q
                    })
                })
                .collect::<Vec<_>>();
            if pattern == "layers" {
                for layer in 1..8 {
                    for i in 0..4096 {
                        let mut q = quads[i];
                        q.positions
                            .iter_mut()
                            .for_each(|p| p[2] += layer as f32 * 8.);
                        q.uvs.rotate_right(layer % 4);
                        quads.push(q);
                    }
                }
            }
            let origin = [(cx * 64) as f64, (cy * 64) as f64, 0.];
            scene
                .ready_terrain
                .insert(Cell::containing(origin).unwrap());
            scene.meshes.insert(
                ((cy * edge + cx) as u64, flags),
                SceneMesh {
                    revision: 1,
                    flags,
                    origin,
                    triangles: MeshGeometry::Quads(quads.into()),
                },
            );
        }
    }
    scene
}

pub(crate) fn camera(edge: usize) -> Camera {
    let size = (edge * 64) as f32;
    Camera {
        position: [size * 0.5, size * 0.5, size],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 0.9,
    }
}

#[test]
#[ignore = "windowless bilateral/overlay shading, coverage and single physical geometry"]
fn gpu_compound_sheet_matches_independent_material_oracle_from_both_sides() {
    use prime_scene::surface::{
        Emission, LayerMode, SurfaceDetail, SurfaceFace, SurfaceLayer, SurfaceMesh,
    };
    let base = CompiledQuad {
        positions: [
            [-1., -1., -2.],
            [1., -1., -2.],
            [1., 1., -2.],
            [-1., 1., -2.],
        ],
        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
        color: [1.; 4],
        texture_id: 1,
        flags: 0,
    };
    let mut actual = Renderer::new().unwrap();
    let mut expected = Renderer::new().unwrap();
    let mut revision = 1;
    for mode in [
        LayerMode::Bilateral,
        LayerMode::OverlayFront,
        LayerMode::OverlayBoth,
    ] {
        for back in [false, true] {
            let mut q = SurfaceFace::from_quad(base);
            q.detail = Some(Arc::new(SurfaceDetail {
                mode,
                layer: SurfaceLayer {
                    colors: [[1.; 4]; 4],
                    uvs: base.uvs,
                    texture_id: 2,
                    flags: 1,
                    repeat: None,
                    emission: Emission::default(),
                },
            }));
            let mut scene = Scene {
                revision,
                ..Default::default()
            };
            revision += 1;
            scene
                .ready_terrain
                .insert(Cell::containing([0.; 3]).unwrap());
            for (id, pixels) in [
                (1, vec![255, 0, 0, 255, 0, 255, 0, 255]),
                (2, vec![0, 0, 255, 255, 255, 255, 255, 0]),
            ] {
                scene.textures.insert(
                    id,
                    Texture {
                        region: None,
                        sampling: None,
                        material: None,
                        width: 2,
                        height: 1,
                        pixels: pixels.into(),
                    },
                );
            }
            scene.meshes.insert(
                (1, 0),
                SceneMesh {
                    revision: scene.revision,
                    flags: q.flags(),
                    origin: [0.; 3],
                    triangles: MeshGeometry::Surfaces(Arc::new(
                        SurfaceMesh::from_resolved(scene.revision, vec![q]).unwrap(),
                    )),
                },
            );
            let mut camera = crate::frame::tests::camera();
            if back {
                camera.position = [0., 0., -4.];
                camera.forward = [0., 0., 1.];
                camera.right = [-1., 0., 0.];
            }
            let image = actual.render(&scene, &camera, 96, 64, 19).unwrap();
            let pixels = match mode {
                LayerMode::Bilateral if back => vec![0, 0, 255, 255, 255, 255, 255, 0],
                LayerMode::OverlayBoth => vec![0, 0, 255, 255, 0, 255, 0, 255],
                LayerMode::OverlayFront if !back => vec![0, 0, 255, 255, 0, 255, 0, 255],
                _ => vec![255, 0, 0, 255, 0, 255, 0, 255],
            };
            let flags = u32::from(mode == LayerMode::Bilateral && back);
            scene.textures.insert(
                1,
                Texture {
                    region: None,
                    sampling: None,
                    material: None,
                    width: 2,
                    height: 1,
                    pixels: pixels.into(),
                },
            );
            let mut reference = base;
            reference.flags = flags;
            scene.meshes.insert(
                (1, 0),
                SceneMesh {
                    revision: scene.revision,
                    flags,
                    origin: [0.; 3],
                    triangles: MeshGeometry::Quads(vec![reference].into()),
                },
            );
            let reference_image = expected.render(&scene, &camera, 96, 64, 19).unwrap();
            assert_eq!(image, reference_image, "mode={mode:?}, back={back}");
        }
    }
}

#[cfg(feature = "shader-tests")]
#[test]
#[ignore = "windowless crossed-sheet source UV/coverage oracle from four sides with independent AS triangles"]
fn gpu_cross_bilateral_uvs_match_original_source_triangles_from_four_sides() {
    use prime_scene::surface::{
        Emission, LayerMode, SurfaceDetail, SurfaceFace, SurfaceLayer, SurfaceMesh,
    };

    fn sub(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
        std::array::from_fn(|i| a[i] - b[i])
    }
    fn dot(a: [f64; 3], b: [f64; 3]) -> f64 {
        a.into_iter().zip(b).map(|(a, b)| a * b).sum()
    }
    fn cross(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
        [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ]
    }
    fn facing(source: &CompiledQuad, direction: [f32; 3]) -> bool {
        let p = source.positions.map(|p| p.map(f64::from));
        dot(
            cross(sub(p[1], p[0]), sub(p[2], p[0])),
            direction.map(f64::from),
        ) < 0.
    }
    fn oracle(
        sources: &[CompiledQuad; 4],
        origin: [f32; 3],
        direction: [f32; 3],
        pixels: &[u8],
    ) -> ([f32; 3], [f32; 4]) {
        // Intersect the original source triangles with f64 Moller-Trumbore. Neither the
        // bilateral corner remap nor the shader's record/half addressing participates.
        let origin = origin.map(f64::from);
        let ray = direction.map(f64::from);
        let mut nearest = f64::INFINITY;
        let mut color = None;
        for source in sources.iter().filter(|source| facing(source, direction)) {
            for triangle in source.triangles() {
                let p = triangle.positions.map(|p| p.map(f64::from));
                let e = sub(p[1], p[0]);
                let f = sub(p[2], p[0]);
                let h = cross(ray, f);
                let inverse = 1. / dot(e, h);
                let offset = sub(origin, p[0]);
                let u = dot(offset, h) * inverse;
                let q = cross(offset, e);
                let v = dot(ray, q) * inverse;
                let distance = dot(f, q) * inverse;
                if u < 0. || v < 0. || u + v > 1. || distance <= 0. || distance >= nearest {
                    continue;
                }
                let uv: [f64; 2] = std::array::from_fn(|a| {
                    f64::from(triangle.uvs[0][a]) * (1. - u - v)
                        + f64::from(triangle.uvs[1][a]) * u
                        + f64::from(triangle.uvs[2][a]) * v
                });
                let [x, y] = uv.map(|v| ((v - v.floor()) * 16.).floor() as usize);
                let texel: [f32; 4] = pixels[(y * 16 + x) * 4..][..4]
                    .try_into()
                    .map(|pixel: [u8; 4]| pixel.map(|v| f32::from(v) / 255.))
                    .unwrap();
                if source.flags == 1 && texel[3] < 0.1 {
                    continue;
                }
                nearest = distance;
                color = Some(texel);
            }
        }
        (
            if color.is_some() { [0.; 3] } else { [1.; 3] },
            color.unwrap_or([0.; 4]),
        )
    }
    fn scene(pixels: &[u8], meshes: impl IntoIterator<Item = MeshGeometry>, flags: u32) -> Scene {
        let mut scene = Scene {
            epoch: 1,
            revision: 1,
            ..Default::default()
        };
        scene
            .ready_terrain
            .insert(Cell::containing([0.; 3]).unwrap());
        scene.textures.insert(
            1,
            Texture {
                region: None,
                sampling: None,
                material: None,
                width: 16,
                height: 16,
                pixels: pixels.into(),
            },
        );
        for (id, triangles) in meshes.into_iter().enumerate() {
            scene.meshes.insert(
                (id as u64, flags),
                SceneMesh {
                    revision: 1,
                    flags,
                    origin: [0.; 3],
                    triangles,
                },
            );
        }
        scene
    }
    fn geometry(context: &Arc<Context>, scene: &Scene) -> Geometry {
        let mut geometry = Geometry::new(
            context,
            scene.into(),
            Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap()),
        )
        .unwrap();
        geometry
            .prepare_dynamic(
                context,
                scene,
                &InstanceScene::default(),
                0,
                &mut cpu_profile::FrameCpu::default(),
            )
            .unwrap();
        geometry
    }
    fn query(
        context: &Arc<Context>,
        geometry: &Geometry,
        input: &[u32],
    ) -> Vec<([f32; 3], [f32; 4])> {
        crate::shader_tests::run(
            context,
            prime_shader_tests::optics(),
            input,
            input.len(),
            [0, (input.len() / 12) as u32],
            Some(geometry),
        )
        .as_chunks::<12>()
        .0
        .iter()
        .map(|row| {
            (
                [row[0], row[1], row[2]].map(f32::from_bits),
                [row[8], row[9], row[10], row[11]].map(f32::from_bits),
            )
        })
        .collect()
    }

    let pixels: Vec<u8> = (0..16)
        .flat_map(|y| {
            (0..16).flat_map(move |x| {
                [
                    17 + x * 13,
                    23 + y * 11,
                    ((x * 7 + y * 3) % 16) * 13,
                    if (x + 2 * y) % 5 < 2 { 0 } else { 255 },
                ]
            })
        })
        .collect();
    let context = Context::new().unwrap();
    // Actual vanilla cross.json -> FaceBakery output: the opposite sides reverse all four
    // position corners (a different diagonal) while retaining their own original UV order.
    let low = 0.05000004;
    let high = 0.9499999;
    for flags in [0, 1] {
        let sources = [
            [
                [high, 1., low],
                [high, 0., low],
                [low, 0., high],
                [low, 1., high],
            ],
            [
                [low, 1., high],
                [low, 0., high],
                [high, 0., low],
                [high, 1., low],
            ],
            [
                [low, 1., low],
                [low, 0., low],
                [high, 0., high],
                [high, 1., high],
            ],
            [
                [high, 1., high],
                [high, 0., high],
                [low, 0., low],
                [low, 1., low],
            ],
        ]
        .map(|positions| CompiledQuad {
            positions,
            uvs: [[0., 0.], [0., 1.], [1., 1.], [1., 0.]],
            color: [1.; 4],
            texture_id: 1,
            flags,
        });
        let actual: Vec<_> = [false, true]
            .into_iter()
            .map(|reverse| {
                let faces = [0, 2].map(|pair| {
                    let base = sources[pair + usize::from(reverse)];
                    let back = sources[pair + usize::from(!reverse)];
                    let mut face = SurfaceFace::from_quad(base);
                    face.detail = Some(Arc::new(SurfaceDetail {
                        mode: LayerMode::Bilateral,
                        layer: SurfaceLayer {
                            colors: [back.color; 4],
                            // Match source positions; no assumed U reflection or barycentric flip.
                            uvs: base.positions.map(|p| {
                                back.uvs[back.positions.iter().position(|&b| b == p).unwrap()]
                            }),
                            texture_id: back.texture_id,
                            flags: back.flags,
                            repeat: None,
                            emission: Emission::default(),
                        },
                    }));
                    face
                });
                let mut faces = faces.to_vec();
                if reverse {
                    faces.reverse();
                }
                let geometry = geometry(
                    &context,
                    &scene(
                        &pixels,
                        [MeshGeometry::Surfaces(Arc::new(
                            SurfaceMesh::from_resolved(1, faces).unwrap(),
                        ))],
                        flags,
                    ),
                );
                assert_eq!(geometry.shader_variant(), 2);
                assert_eq!(geometry.surface_memory()[0], 2 * 432);
                geometry
            })
            .collect();
        for direction in [[1., 0., 0.], [-1., 0., 0.], [0., 0., 1.], [0., 0., -1.]] {
            // Only actual front-facing source sheets enter the reference AS. Each original
            // 012/230 triangle is a separate mesh, excluding both pair folding and coincident
            // front/back competition from this oracle.
            let reference = geometry(
                &context,
                &scene(
                    &pixels,
                    sources
                        .iter()
                        .filter(|source| facing(source, direction))
                        .flat_map(|source| source.triangles().map(|triangle| [triangle].into())),
                    flags,
                ),
            );
            assert_eq!(reference.surface_memory()[0], 4 * 176);
            let mut input = Vec::new();
            let mut expected = Vec::new();
            for y in 0..16 {
                for x in 0..16 {
                    // Offset sample centers away from diagonals, cross intersections and texel
                    // boundaries, retaining both source triangle halves and every texture row.
                    let horizontal = low + (high - low) * (x as f32 + 0.375) / 16.;
                    let vertical = (y as f32 + 0.6875) / 16.;
                    let origin = if direction[0] != 0. {
                        [
                            if direction[0] > 0. { -1. } else { 2. },
                            vertical,
                            horizontal,
                        ]
                    } else {
                        [
                            horizontal,
                            vertical,
                            if direction[2] > 0. { -1. } else { 2. },
                        ]
                    };
                    input.extend([origin[0], origin[1], origin[2], 4.].map(f32::to_bits));
                    input.extend([direction[0], direction[1], direction[2], 1.].map(f32::to_bits));
                    input.extend([0.; 4].map(f32::to_bits));
                    expected.push(oracle(&sources, origin, direction, &pixels));
                }
            }
            if flags == 1 {
                assert!(
                    expected
                        .iter()
                        .any(|(visibility, _)| *visibility == [0.; 3])
                );
                assert!(
                    expected
                        .iter()
                        .any(|(visibility, _)| *visibility == [1.; 3])
                );
            }
            let source = query(&context, &reference, &input);
            for (reverse, geometry) in actual.iter().enumerate() {
                let output = query(&context, geometry, &input);
                for (
                    i,
                    ((actual_visibility, actual_color), (expected_visibility, expected_color)),
                ) in output.iter().zip(&expected).enumerate()
                {
                    assert_eq!(
                        actual_visibility, expected_visibility,
                        "flags={flags} direction={direction:?} reverse={reverse} ray={i}"
                    );
                    assert_eq!(
                        source[i].0, *expected_visibility,
                        "source reference visibility ray={i}"
                    );
                    for channel in 0..4 {
                        assert!(
                            (source[i].1[channel] - expected_color[channel]).abs() < 2e-6,
                            "source reference color ray={i} channel={channel}"
                        );
                        assert!(
                            (actual_color[channel] - expected_color[channel]).abs() < 2e-6,
                            "flags={flags} direction={direction:?} reverse={reverse} ray={i} color={actual_color:?} expected={expected_color:?}"
                        );
                    }
                }
            }
        }
    }
}

#[cfg(feature = "shader-tests")]
#[test]
#[ignore = "windowless actual optical boundaries, order-independent absorption and initial medium"]
fn gpu_optical_boundaries_match_beer_lambert_and_fresnel_from_both_sides_and_inside() {
    use prime_scene::surface::{Medium, Optics, SurfaceFace, SurfaceMesh};
    let mut renderer = Renderer::new().unwrap();
    let camera = Camera {
        position: [2., 2., 0.],
        forward: [0., 0., 1.],
        right: [-1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 1.,
    };
    for (index, ior) in [1_f32, 1.333, 1.5].into_iter().enumerate() {
        let medium = Medium {
            ior,
            extinction: [0.2, 0.5, 0.9],
        };
        let faces = [1., 3.].map(|z| {
            let mut positions = [[1., 1., z], [3., 1., z], [3., 3., z], [1., 3., z]];
            if z == 1. {
                positions.reverse();
            }
            let mut face = SurfaceFace::from_quad(CompiledQuad {
                positions,
                uvs: [[0.5; 2]; 4],
                color: [1.; 4],
                texture_id: 0,
                flags: 0,
            });
            face.media = [7, 0];
            face.optics = Some(Optics {
                negative: medium,
                positive: Medium::default(),
                ior_textures: [None; 2],
                transmit: true,
                thin: false,
            });
            face
        });
        let mesh = SurfaceMesh::from_resolved(1, faces.into()).unwrap();
        let mut scene = Scene {
            epoch: 1,
            revision: index as u64 + 1,
            ..Default::default()
        };
        scene
            .ready_terrain
            .insert(Cell::containing([0.; 3]).unwrap());
        scene.meshes.insert(
            (1, 0),
            SceneMesh {
                revision: index as u64 + 1,
                flags: 2,
                origin: [0.; 3],
                triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
            },
        );
        renderer.render(&scene, &camera, 8, 8, 19).unwrap();
        let mut inputs = Vec::new();
        for (z, dir, inside) in [
            (0., 1., false),
            (4., -1., false),
            (2., 1., true),
            (2., -1., true),
        ] {
            inputs.extend([2., 2., z, 6., 0., 0., dir, if inside { ior } else { 1. }]);
            inputs.extend(if inside { [0.2, 0.5, 0.9, 0.] } else { [0.; 4] });
        }
        let input: Vec<_> = inputs.into_iter().map(f32::to_bits).collect();
        let output = crate::shader_tests::run(
            &renderer.context,
            prime_shader_tests::optics(),
            &input,
            48,
            [0, 4],
            renderer.geometry.as_ref(),
        );
        let f = ((ior - 1.) / (ior + 1.)).powi(2);
        for i in 0..4 {
            assert_eq!(f32::from_bits(output[i * 12 + 3]), 1.);
            let (distance, boundaries) = if i < 2 { (2., 2) } else { (1., 1) };
            for (c, sigma) in [0.2_f32, 0.5, 0.9].into_iter().enumerate() {
                let expected = (1. - f).powi(boundaries) * (-sigma * distance).exp();
                let actual = f32::from_bits(output[i * 12 + c]);
                assert!(
                    (actual - expected).abs() < 2e-6,
                    "ior={ior} ray={i} channel={c}: {actual} != {expected}"
                );
            }
            assert_eq!(
                f32::from_bits(output[i * 12 + 4]),
                if i < 2 { 1. } else { ior }
            );
            assert_eq!(
                f32::from_bits(output[i * 12 + 5]),
                if i < 2 { ior } else { 1. }
            );
        }
    }
}

#[cfg(feature = "shader-tests")]
#[test]
#[ignore = "windowless optical visibility with opaque/cutout/alpha blockers and primitive reordering"]
fn gpu_optical_visibility_preserves_coverage_and_absorption_with_blocker_reordering() {
    use prime_scene::surface::{Medium, Optics, SurfaceFace, SurfaceMesh};

    fn query(context: &Arc<Context>, scene: &Scene, input: &[u32]) -> Vec<[f32; 3]> {
        let mut geometry = Geometry::new(
            context,
            scene.into(),
            Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap()),
        )
        .unwrap();
        geometry
            .prepare_dynamic(
                context,
                scene,
                &InstanceScene::default(),
                0,
                &mut cpu_profile::FrameCpu::default(),
            )
            .unwrap();
        crate::shader_tests::run(
            context,
            prime_shader_tests::optics(),
            input,
            input.len(),
            [0, (input.len() / 12) as u32],
            Some(&geometry),
        )
        .as_chunks::<12>()
        .0
        .iter()
        .map(|row| [row[0], row[1], row[2]].map(f32::from_bits))
        .collect()
    }
    fn rays(medium: Medium, thin: bool) -> Vec<u32> {
        let mut input = Vec::new();
        for i in 0..16 {
            let x = 1.125 + i as f32 / 16.;
            for (z, direction, inside) in [
                (0., 1., false),
                (5., -1., false),
                (2., 1., true),
                (2., -1., true),
            ] {
                input.extend(
                    [
                        x,
                        1.75,
                        z,
                        6.,
                        0.,
                        0.,
                        direction,
                        if inside && !thin { medium.ior } else { 1. },
                    ]
                    .map(f32::to_bits),
                );
                input.extend(
                    if inside && !thin {
                        [
                            medium.extinction[0],
                            medium.extinction[1],
                            medium.extinction[2],
                            0.,
                        ]
                    } else {
                        [0.; 4]
                    }
                    .map(f32::to_bits),
                );
            }
        }
        input
    }
    fn sheet(z: f32, flags: u32, alpha: f32) -> CompiledQuad {
        CompiledQuad {
            positions: [[1., 1., z], [3., 1., z], [3., 3., z], [1., 3., z]],
            uvs: [[0.5; 2]; 4],
            color: [1., 1., 1., alpha],
            texture_id: 0,
            flags,
        }
    }
    fn scene(faces: &[SurfaceFace], blocker: Option<CompiledQuad>, reverse: bool) -> Scene {
        let mut scene = Scene {
            epoch: 1,
            revision: 1,
            ..Default::default()
        };
        scene
            .ready_terrain
            .insert(Cell::containing([0.; 3]).unwrap());
        if !faces.is_empty() {
            let mut faces = faces.to_vec();
            if reverse {
                faces.reverse();
            }
            scene.meshes.insert(
                (if reverse { 2 } else { 1 }, 0),
                SceneMesh {
                    revision: 1,
                    flags: 2,
                    origin: [0.; 3],
                    triangles: MeshGeometry::Surfaces(Arc::new(
                        SurfaceMesh::from_resolved(1, faces).unwrap(),
                    )),
                },
            );
        }
        if let Some(blocker) = blocker {
            scene.meshes.insert(
                (if reverse { 1 } else { 2 }, 0),
                SceneMesh {
                    revision: 1,
                    flags: blocker.flags,
                    origin: [0.; 3],
                    triangles: MeshGeometry::Quads(vec![blocker].into()),
                },
            );
        }
        scene
    }

    let context = Context::new().unwrap();
    let air_rays = rays(Medium::default(), false);
    let mut blockers = Vec::new();
    for z in [0.5, 2.25, 4.] {
        for (flags, alpha) in [(0, 1.), (1, 0.), (1, 1.), (2, 0.), (2, 1.), (2, 0.5)] {
            let blocker = sheet(z, flags, alpha);
            let reference = query(&context, &scene(&[], Some(blocker), false), &air_rays);
            assert!(reference.iter().all(|v| *v == [0.; 3] || *v == [1.; 3]));
            if flags == 2 && alpha == 0.5 {
                let covered = reference
                    .iter()
                    .step_by(4)
                    .filter(|v| **v == [0.; 3])
                    .count();
                assert!(
                    covered > 0 && covered < 16,
                    "random coverage must exercise both decisions"
                );
            }
            blockers.push((blocker, reference));
        }
    }
    for (ior, thin) in [(1.333_f32, false), (1.5, false), (1.5, true)] {
        let medium = Medium {
            ior,
            extinction: [0.2, 0.5, 0.9],
        };
        let faces = [1., 3.].map(|z| {
            let mut quad = sheet(z, 0, 1.);
            if z == 1. {
                quad.positions.reverse();
            }
            let mut face = SurfaceFace::from_quad(quad);
            face.media = [7, 0];
            face.optics = Some(Optics {
                negative: medium,
                positive: Medium::default(),
                ior_textures: [None; 2],
                transmit: true,
                thin,
            });
            face
        });
        let input = rays(medium, thin);
        let baseline = query(&context, &scene(&faces, None, false), &input);
        let f = ((ior - 1.) / (ior + 1.)).powi(2);
        for (i, value) in baseline.iter().enumerate() {
            let boundaries = if i % 4 < 2 { 2 } else { 1 };
            let distance = if thin {
                boundaries as f32 * 0.0625
            } else {
                boundaries as f32
            };
            let transmission = if thin { (1. - f) / (1. + f) } else { 1. - f };
            for channel in 0..3 {
                let expected =
                    transmission.powi(boundaries) * (-medium.extinction[channel] * distance).exp();
                assert!(
                    (value[channel] - expected).abs() < 2e-6,
                    "ior={ior} thin={thin} ray={i} value={value:?} expected={expected}"
                );
            }
        }
        for (blocker, reference) in &blockers {
            for reverse in [false, true] {
                let actual = query(&context, &scene(&faces, Some(*blocker), reverse), &input);
                for (i, value) in actual.iter().enumerate() {
                    for channel in 0..3 {
                        let expected = baseline[i][channel] * reference[i][channel];
                        assert!(
                            (value[channel] - expected).abs() < 2e-6,
                            "ior={ior} thin={thin} reverse={reverse} blocker={blocker:?} ray={i} value={value:?} expected={expected}"
                        );
                    }
                }
            }
        }
    }
}

#[test]
#[ignore = "windowless quad addressing and corner interpolation versus independently padded triangles"]
fn gpu_quad_halves_match_independent_triangles_with_nonplanar_varying_attributes() {
    use prime_scene::{Triangle, geometry::Quad};
    let q = Quad {
        positions: [
            [-1., -1., -2.],
            [1., -1., -2.],
            [1., 1., -1.75],
            [-1., 1., -2.],
        ],
        colors: [
            [0.93, 0.11, 0.27, 0.8],
            [0.13, 0.87, 0.41, 0.5],
            [0.29, 0.37, 0.91, 0.9],
            [0.73, 0.61, 0.17, 0.6],
        ],
        uvs: [[0., 0.], [1., 0.125], [0.875, 1.], [0.25, 0.875]],
        texture_id: 1,
        flags: 0,
    };
    let camera = crate::frame::tests::camera();
    let mut pairs = Renderer::new().unwrap();
    let mut singles = Renderer::new().unwrap();
    let empty_image = pairs
        .render(&Scene::default(), &camera, 192, 128, 0)
        .unwrap();
    for flags in 0..3 {
        let mut q = q;
        q.flags = flags;
        let valid = [q.triangle(0), q.triangle(1)];
        let empty = Triangle {
            positions: [[20.; 3]; 3],
            colors: [[0.; 4]; 3],
            uvs: [[0.; 2]; 3],
            texture_id: 1,
            flags,
        };
        let make = |triangles: Vec<Triangle>| {
            let mut scene = Scene {
                revision: u64::from(flags) + 1,
                ..Default::default()
            };
            scene
                .ready_terrain
                .insert(Cell::containing([0.; 3]).unwrap());
            scene.textures.insert(
                1,
                Texture {
                    region: None,
                    sampling: None,
                    material: None,
                    width: 2,
                    height: 2,
                    pixels: vec![
                        255, 128, 32, 255, 16, 255, 128, 0, 32, 64, 255, 255, 255, 255, 64, 255,
                    ]
                    .into(),
                },
            );
            scene.meshes.insert(
                (0, flags),
                SceneMesh {
                    revision: scene.revision,
                    flags,
                    origin: [0.; 3],
                    triangles: triangles.into(),
                },
            );
            scene
        };
        let paired = make(valid.to_vec());
        // Zero-area separators prevent the reference's triangles from being paired by the
        // exact shared-corner check. Every valid reference hit uses half zero of its own quad.
        let independent = make(vec![valid[0], empty, valid[1], empty]);
        let a = pairs.render(&paired, &camera, 192, 128, 0).unwrap();
        let b = singles.render(&independent, &camera, 192, 128, 0).unwrap();
        assert_ne!(a, empty_image, "quad oracle must contain visible geometry");
        assert_eq!(
            a, b,
            "full quad and independent degenerate quads: flags={flags}"
        );
    }
}

#[test]
#[ignore = "windowless GPU behavioral comparison; run with synchronization validation"]
fn gpu_surface_rectangles_preserve_texture_coverage_and_replacements() {
    let mut reference = Renderer::new().unwrap();
    let mut compiled = Renderer::new().unwrap();
    for flags in 0..3 {
        for (step, pattern) in ["uniform", "tiled", "checker", "sloped", "rotated"]
            .into_iter()
            .enumerate()
        {
            let mut input = scene(1, pattern, flags);
            input.revision = u64::from(flags) * 5 + step as u64 + 1;
            for mesh in input.meshes.values_mut() {
                mesh.revision = input.revision;
            }
            let b = compiled.render(&input, &camera(1), 512, 320, 0).unwrap();
            // Supply the exact source triangulation as generic triangles. The same renderer
            // accepts this independent geometry reference without an alternate compiler mode.
            for mesh in input.meshes.values_mut() {
                mesh.triangles = mesh.triangles.iter().collect::<Vec<_>>().into();
            }
            let a = reference.render(&input, &camera(1), 512, 320, 0).unwrap();
            assert_eq!(reference.geometry.as_ref().unwrap().triangle_count, 8192);
            let changed = a
                .as_chunks::<4>()
                .0
                .iter()
                .zip(b.as_chunks::<4>().0)
                .filter(|(a, b)| a != b)
                .count();
            let abs: u64 = a
                .iter()
                .zip(&b)
                .map(|(a, b)| u64::from(a.abs_diff(*b)))
                .sum();
            println!(
                "surface pixels flags={flags} pattern={pattern}: changed={changed}/163840 mean_abs={:.6} triangles={}->{}",
                abs as f64 / a.len() as f64,
                reference.geometry.as_ref().unwrap().triangle_count,
                compiled.geometry.as_ref().unwrap().triangle_count
            );
            // Retriangulation changes floating-point barycentrics at texture/coverage edges.
            // The independent reference bounds those rare boundary differences, not whole-image
            // mean alone (which could hide a small but completely wrong surface).
            assert!(changed <= 64, "too many changed pixels: {changed}");
            assert!(abs as f64 / (a.len() as f64) < 0.025);
            let expected = match pattern {
                "uniform" | "rotated" => 2,
                "tiled" => 128,
                _ => 8192,
            };
            assert_eq!(compiled.geometry.as_ref().unwrap().triangle_count, expected);
        }
    }
}

#[test]
#[ignore = "native 1080p steady GPU measurement; run alone in release without validation"]
fn surface_steady_cost_matrix() {
    use std::io::Write;
    let samples = std::env::var("PRIME_SURFACE_SAMPLES").map_or(120, |v| v.parse::<u32>().unwrap());
    let rounds = std::env::var("PRIME_SURFACE_ROUNDS").map_or(3, |v| v.parse::<u32>().unwrap());
    let warmup = std::env::var("PRIME_SURFACE_WARMUP").map_or(1024, |v| v.parse::<u32>().unwrap());
    let pattern_filter = std::env::var("PRIME_SURFACE_PATTERN").ok();
    let mut csv =
        std::fs::File::create(std::env::var("PRIME_SURFACE_CSV").expect("set PRIME_SURFACE_CSV"))
            .unwrap();
    writeln!(
        csv,
        "round,pattern,flags,sample,warmup,triangles,record_ns,wall_ns,gpu_ns,prepare_ns,render_ns"
    )
    .unwrap();
    for round in 0..rounds {
        for pattern in ["uniform", "tiled", "checker", "sloped", "layers"] {
            if pattern_filter.as_ref().is_some_and(|p| p != pattern) {
                continue;
            }
            for flags in [0, 1, 2] {
                let input = scene(4, pattern, flags);
                let mut host = HostBenchmark::new(1920, 1080).unwrap();
                println!(
                    "surface steady: round={round} pattern={pattern} flags={flags} device={} native=1920x1080 warmup={warmup} samples={samples}",
                    host.device_name()
                );
                for sample in 0..samples + warmup {
                    let frame = host.enqueue(&input, &camera(4), sample).unwrap();
                    // Completion per sample gives unambiguous CPU/GPU attribution. It does
                    // not simulate a game's presentation or CPU/GPU overlap.
                    let completed = host.drain().unwrap();
                    assert_eq!(completed.len(), 1);
                    let gpu = completed[0];
                    writeln!(
                        csv,
                        "{round},{pattern},{flags},{sample},{},{},{},{},{},{},{}",
                        sample < warmup,
                        host.triangle_count(),
                        frame.record_ns,
                        frame.wall_ns,
                        gpu.gpu_ns,
                        gpu.preparation_ns.unwrap_or(0),
                        gpu.render_ns.unwrap_or(0)
                    )
                    .unwrap();
                }
                csv.flush().unwrap();
            }
        }
    }
}

#[test]
#[ignore = "windowless mixed record strides, replacement and in-flight resource ownership"]
fn gpu_surface_mixed_records_follow_material_ranges_and_format_replacement() {
    let mut compiled = Renderer::new().unwrap();
    let mut host = HostBenchmark::new(128, 128).unwrap();
    for revision in 1..=8 {
        let mut input = scene(1, "uniform", 0);
        // This test isolates record stride/ownership from repeated-atlas boundary rounding,
        // which has a separate high-frequency texture/coverage image comparison above.
        input.textures.insert(
            1,
            Texture {
                region: None,
                sampling: None,
                material: None,
                width: 1,
                height: 1,
                pixels: Arc::from([99, 133, 177, 255]),
            },
        );
        for flags in 1..=2 {
            if revision == 4 && flags == 1 {
                continue;
            }
            let pattern = if (revision + u64::from(flags)) % 2 == 0 {
                "sloped"
            } else {
                "tiled"
            };
            for (key, mut mesh) in scene(1, pattern, flags).meshes {
                mesh.origin[2] = f64::from(flags) * 4.;
                input.meshes.insert(key, mesh);
            }
        }
        input.revision = revision;
        for mesh in input.meshes.values_mut() {
            mesh.revision = revision;
        }
        let b = compiled.render(&input, &camera(1), 256, 256, 11).unwrap();
        let mut fresh = Renderer::new().unwrap();
        assert_eq!(
            fresh.render(&input, &camera(1), 256, 256, 11).unwrap(),
            b,
            "format replacement must match a fresh compiled scene"
        );
        // Two publications can remain in flight while the next one changes an allocation's
        // stride and physical page. Validation observes the production queue dependencies.
        host.enqueue(&input, &camera(1), 11).unwrap();
    }
    host.drain().unwrap();
}

#[cfg(feature = "shader-tests")]
#[test]
#[ignore = "windowless emitter half-area sampling, bidirectional PDF and scene replacement"]
fn gpu_surface_emitters_preserve_half_area_sampling_and_scene_replacement() {
    use prime_scene::surface::{Emission, Provenance, SurfaceCompiler, SurfaceQuad};
    let mut renderer = Renderer::new().unwrap();
    renderer
        .configure(RenderSettings {
            ray_reconstruction: false,
            ..Default::default()
        })
        .unwrap();
    let mut scene = Scene {
        revision: 1,
        ..Default::default()
    };
    let mut compiler = SurfaceCompiler::new();
    for i in 0..3 {
        let q = SurfaceQuad {
            geometry: CompiledQuad {
                positions: match i {
                    1 => [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [1., 1., 0.]],
                    2 => [[0., 0., 0.], [2., 0., 0.], [2., 1., 0.], [0., 3., 0.]],
                    _ => [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
                },
                uvs: [[0.; 2]; 4],
                color: [1.; 4],
                texture_id: 0,
                flags: 0,
            },
            provenance: Provenance {
                domain: 0,
                source: i,
            },
            emission: Emission {
                textured: false,
                radiance: [(1 << i) as f32; 3],
                two_sided: true,
            },
            rule: prime_scene::surface::SurfaceRule::Preserve,
        };
        let mesh = compiler.compile(1, &[q]).unwrap();
        let origin = [i as f64 * 64., 0., 0.];
        scene
            .ready_terrain
            .insert(Cell::containing(origin).unwrap());
        scene.meshes.insert(
            (i, 0),
            SceneMesh {
                revision: 1,
                flags: 0,
                origin,
                triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
            },
        );
    }
    let samples = 8192_u32;
    let inputs: Vec<_> = (0..samples)
        .flat_map(|i| {
            [
                ((i as f32 + 0.5) / samples as f32).to_bits(),
                (((i.reverse_bits() >> 19) as f32 + 0.5) / samples as f32).to_bits(),
                0.75_f32.to_bits(),
                0,
            ]
        })
        .collect();
    for stage in 0..3 {
        if stage == 1 {
            scene.meshes.remove(&(1, 0));
        } else if stage == 2 {
            scene.meshes.clear();
        }
        scene.revision += 1;
        renderer.render(&scene, &camera(3), 64, 64, 0).unwrap();
        let out = crate::shader_tests::run(
            &renderer.context,
            prime_shader_tests::emitter_sampling(),
            &inputs,
            samples as usize * 16,
            [0, samples],
            renderer.geometry.as_ref(),
        );
        let mut counts = [0; 3];
        let mut first_halves = [0; 3];
        for row in out.as_chunks::<16>().0 {
            if stage == 2 {
                assert_eq!(row[0], 0);
                continue;
            }
            assert_eq!(row[0], 1);
            let f = |i| f32::from_bits(row[i]);
            let x = f(4);
            let source = (x / 64.).floor() as usize;
            assert!(source < 3 && (stage == 0 || source != 1));
            counts[source] += 1;
            let local_x = x - source as f32 * 64.;
            first_halves[source] +=
                usize::from(f(5) < local_x * if source == 2 { 0.5 } else { 1. });
            if source == 1 {
                assert!(
                    f(5) <= local_x + 1e-6,
                    "zero-area half must never be sampled"
                );
            }
            assert_eq!(row[3], 1);
            // Surviving light page IDs remain stable when an earlier page is removed.
            assert_eq!(row[1], source as u32);
            assert_eq!(row[2], 0);
            assert_eq!([f(8), f(9), f(10)], [(1 << source) as f32; 3]);
            let area_pdf = f(7);
            assert!(area_pdf.is_finite() && area_pdf > 0.);
            let distance_squared = f(4) * f(4) + f(5) * f(5) + (f(6) - 1000.).powi(2);
            let expected_solid_pdf = area_pdf * distance_squared * distance_squared.sqrt() / 1000.;
            assert!(
                (f(11) / expected_solid_pdf - 1.).abs() < 2e-6,
                "stage={stage} source={source}: forward={area_pdf} reverse={}",
                f(11)
            );
            assert_eq!([f(12), f(13)], [0., 0.]);
            assert!((f(14) - 1.).abs() <= 2. * f32::EPSILON);
            assert!(f(15).is_finite() && f(15) > 0.);
        }
        if stage < 2 {
            for (i, count) in counts.into_iter().enumerate() {
                if stage == 1 && i == 1 {
                    assert_eq!(count, 0, "removed light must not be sampled");
                    continue;
                }
                assert!(count >= 128, "insufficient half-area samples: {counts:?}");
                let observed = first_halves[i] as f64 / count as f64;
                assert!(
                    (observed - [0.5, 1.0, 0.25][i]).abs() < 0.02,
                    "quad half sampling stage={stage} source={i}: {observed}"
                );
            }
        }
    }
}

fn lit_scene(revision: u64, emission: f32) -> Scene {
    use prime_scene::surface::{Emission, Provenance, SurfaceCompiler, SurfaceQuad};
    let patch = |positions, radiance| SurfaceQuad {
        geometry: CompiledQuad {
            positions,
            uvs: [[0.; 2]; 4],
            color: [0.5, 0.5, 0.5, 1.],
            texture_id: 0,
            flags: 0,
        },
        provenance: Provenance {
            domain: 1,
            source: if radiance == 0. { 0 } else { 1 },
        },
        emission: Emission {
            textured: false,
            radiance: [radiance; 3],
            two_sided: false,
        },
        rule: prime_scene::surface::SurfaceRule::Preserve,
    };
    let floor = patch(
        [
            [-16., -16., 0.],
            [16., -16., 0.],
            [16., 16., 0.],
            [-16., 16., 0.],
        ],
        0.,
    );
    // Facing the receiver, outside the camera frustum. A one-bounce image can only see its
    // contribution by actually selecting it through the world/local trees and tracing a shadow.
    let light = patch(
        [[2., 2., 4.], [2., 4., 4.], [4., 4., 4.], [4., 2., 4.]],
        emission,
    );
    let mesh = SurfaceCompiler::new()
        .compile(revision, &[floor, light])
        .unwrap();
    let mut scene = Scene {
        revision,
        ..Default::default()
    };
    scene
        .ready_terrain
        .insert(Cell::containing([0.; 3]).unwrap());
    scene.meshes.insert(
        (0, 0),
        SceneMesh {
            revision,
            flags: 0,
            origin: [0.; 3],
            triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
        },
    );
    scene
}

#[test]
#[ignore = "windowless direct-light transport and completion-owned resource replacement"]
fn gpu_surface_lights_illuminate_receivers_and_retire_with_inflight_frames() {
    let camera = Camera {
        position: [0., 0., 6.],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 0.9,
    };
    let mut renderer = Renderer::new().unwrap();
    renderer
        .configure(RenderSettings {
            mode: RenderMode::Offline,
            bounces: 1,
            sun: 1. / 256.,
            sky: 1. / 256.,
            ..Default::default()
        })
        .unwrap();
    let dark = renderer
        .render(&lit_scene(1, 0.), &camera, 128, 128, 0)
        .unwrap();
    let lit = renderer
        .render(&lit_scene(2, 12.), &camera, 128, 128, 0)
        .unwrap();
    let removed = renderer
        .render(&lit_scene(3, 0.), &camera, 128, 128, 0)
        .unwrap();
    assert_eq!(
        removed, dark,
        "removed lights must leave neither stale IDs nor stale radiance"
    );
    let energy = |pixels: &[u8]| {
        pixels
            .as_chunks::<4>()
            .0
            .iter()
            .map(|p| u64::from(p[0]) + u64::from(p[1]) + u64::from(p[2]))
            .sum::<u64>()
    };
    println!(
        "one-bounce surface light: dark={} lit={}",
        energy(&dark),
        energy(&lit)
    );
    assert!(energy(&lit) > energy(&dark) * 4);
    drop(renderer);

    let mut host = HostBenchmark::new(128, 128).unwrap();
    for revision in 1..=24 {
        let mut scene = lit_scene(
            revision,
            if revision % 3 == 0 {
                0.
            } else {
                4. + revision as f32
            },
        );
        // Rebase while older pointers/trees are still consumed by the queue as well as replacing
        // emitter contents. The host only waits when its two-slot ring is full.
        scene.anchor[0] = (revision % 2) as f64 * 64.;
        let frame = host.enqueue(&scene, &camera, 0).unwrap();
        assert_eq!(frame.serial, revision);
    }
    host.drain().unwrap();
}

#[cfg(feature = "shader-tests")]
#[test]
#[ignore = "windowless production sprite sampling, shared backing and metadata-only animation"]
fn sprite_frames_mips_and_endpoints_share_pixels_without_rebuilding_geometry() {
    use prime_scene::{TextureLevel, TextureSampling};
    let mut renderer = Renderer::new().unwrap();
    let camera = crate::frame::tests::camera();
    let pixels: Arc<[u8]> = (0..4)
        .flat_map(|y| (0..10).flat_map(move |x| [x * 20, y * 40, 50, if x < 5 { 64 } else { 255 }]))
        .collect::<Vec<_>>()
        .into();
    let mip: Arc<[u8]> = (0..2)
        .flat_map(|_| {
            (0..4).flat_map(|x| {
                if x < 2 {
                    [80, 100, 120, 32]
                } else {
                    [160, 180, 200, 224]
                }
            })
        })
        .collect::<Vec<_>>()
        .into();
    let mut scene = crate::frame::tests::plane();
    let shared = Texture {
        width: 10,
        height: 4,
        pixels: pixels.clone(),
        region: None,
        sampling: None,
        material: None,
    };
    scene.textures.insert(1, shared.clone());
    let sprite = Texture {
        region: Some([1, 0, 4, 4]),
        sampling: Some(Arc::new(TextureSampling {
            next: [5, 0],
            blend: 0.25,
            coverage_frames: Arc::from([]),
            levels: vec![TextureLevel {
                width: 4,
                height: 2,
                pixels: mip,
                region: [0, 0, 2, 2],
                next: [2, 0],
            }],
        })),
        ..shared
    };
    scene.textures.insert(2, sprite.clone());
    scene.textures.insert(3, sprite);
    renderer.render(&scene, &camera, 8, 8, 1).unwrap();
    let retained = renderer
        .geometry
        .as_ref()
        .unwrap()
        .textures()
        .retained_slots();
    assert_eq!(
        retained.1,
        2 + 40 + 8,
        "atlas and multiple sprite views share each backing once"
    );
    let samples = [
        [0., 0., 0., 0.],
        [1., 1., 0., 0.],
        [0.4, 0.6, 0.5, 0.],
        [0.4, 0.6, 2_f32.sqrt() / 4., 0.],
    ];
    let input: Vec<_> = samples.into_iter().flatten().map(f32::to_bits).collect();
    for frame in 0..2 {
        if frame == 1 {
            let t = scene.textures.get_mut(&2).unwrap();
            t.region = Some([5, 0, 4, 4]);
            let s = Arc::make_mut(t.sampling.as_mut().unwrap());
            s.next = [1, 0];
            s.blend = 0.5;
            s.levels[0].region = [2, 0, 2, 2];
            s.levels[0].next = [0, 0];
            scene.revision += 1;
            renderer.render(&scene, &camera, 8, 8, 2).unwrap();
            let g = renderer.geometry.as_ref().unwrap();
            assert_eq!(g.rebuilt_clusters, 0);
            assert_eq!(
                g.textures().retained_slots(),
                retained,
                "animation only changes descriptors"
            );
        }
        let g = renderer.geometry.as_ref().unwrap();
        let output = crate::shader_tests::run(
            &renderer.context,
            prime_shader_tests::texture(),
            &input,
            16,
            [g.textures().index(2).unwrap(), 4],
            Some(g),
        );
        let base = if frame == 0 {
            [
                [40., 0., 50., 112.],
                [100., 120., 50., 112.],
                [60., 80., 50., 112.],
            ]
        } else {
            [
                [60., 0., 50., 160.],
                [120., 120., 50., 160.],
                [80., 80., 50., 160.],
            ]
        };
        let lower = if frame == 0 {
            [100., 120., 140., 80.]
        } else {
            [120., 140., 160., 128.]
        };
        for (i, expected) in [
            base[0],
            base[1],
            lower,
            std::array::from_fn(|a| (base[2][a] + lower[a]) * 0.5),
        ]
        .into_iter()
        .enumerate()
        {
            for (c, value) in expected.into_iter().enumerate() {
                let actual = f32::from_bits(output[i * 4 + c]);
                assert!(
                    (actual - value / 255.).abs() < 2e-6,
                    "frame={frame} sample={i} c={c}: {actual} != {}",
                    value / 255.
                );
            }
        }
    }
    scene.textures.remove(&3);
    scene.revision += 1;
    renderer.render(&scene, &camera, 8, 8, 3).unwrap();
    assert_eq!(
        renderer
            .geometry
            .as_ref()
            .unwrap()
            .textures()
            .retained_slots()
            .1,
        retained.1
    );
}

#[cfg(feature = "shader-tests")]
#[test]
#[ignore = "windowless compound/textured emission support and bidirectional PDF"]
fn compound_emitters_sample_the_visible_layer_without_leaking_hidden_emission() {
    use prime_scene::surface::{
        Emission, LayerMode, SurfaceDetail, SurfaceFace, SurfaceLayer, SurfaceMesh,
    };
    let mut renderer = Renderer::new().unwrap();
    renderer
        .configure(RenderSettings {
            ray_reconstruction: false,
            ..Default::default()
        })
        .unwrap();
    let geometry = prime_scene::geometry::Quad {
        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
        colors: [[1.; 4]; 4],
        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
        texture_id: 0,
        flags: 0,
    };
    let samples = 1024;
    let input: Vec<_> = (0..samples)
        .flat_map(|i| {
            [
                0.5_f32.to_bits(),
                ((i as f32 + 0.5) / samples as f32).to_bits(),
                (((i * 31 % samples) as f32 + 0.5) / samples as f32).to_bits(),
                0,
            ]
        })
        .collect();
    for mode in [
        LayerMode::Bilateral,
        LayerMode::OverlayFront,
        LayerMode::OverlayBoth,
    ] {
        let face = SurfaceFace {
            geometry,
            repeat: None,
            emission: Emission {
                radiance: [2., 0., 0.],
                two_sided: true,
                textured: false,
            },
            media: [0; 2],
            emitter: None,
            emitter_area_weight: 0.,
            optics: None,
            detail: Some(Arc::new(SurfaceDetail {
                mode,
                layer: SurfaceLayer {
                    colors: [[1.; 4]; 4],
                    uvs: geometry.uvs,
                    texture_id: 1,
                    flags: if mode == LayerMode::Bilateral { 0 } else { 1 },
                    repeat: None,
                    emission: Emission {
                        radiance: [3.; 3],
                        two_sided: true,
                        textured: true,
                    },
                },
            })),
        };
        let mut scene = Scene {
            revision: mode as u64,
            ..Default::default()
        };
        scene
            .ready_terrain
            .insert(Cell::containing([0.; 3]).unwrap());
        scene.meshes.insert(
            (0, 0),
            SceneMesh {
                revision: scene.revision,
                flags: 0,
                origin: [0.; 3],
                triangles: MeshGeometry::Surfaces(Arc::new(
                    SurfaceMesh::from_resolved(scene.revision, vec![face]).unwrap(),
                )),
            },
        );
        scene.textures.insert(
            1,
            Texture {
                width: 2,
                height: 1,
                pixels: Arc::from([0, 255, 0, 0, 0, 255, 0, 255]),
                region: None,
                sampling: None,
                material: None,
            },
        );
        renderer.render(&scene, &camera(1), 8, 8, 0).unwrap();
        for back in [0, 1] {
            let out = crate::shader_tests::run(
                &renderer.context,
                prime_shader_tests::emitter_sampling(),
                &input,
                samples * 16,
                [back, samples as u32],
                renderer.geometry.as_ref(),
            );
            for row in out.as_chunks::<16>().0 {
                assert_eq!(row[0], 1);
                let f = |i| f32::from_bits(row[i]);
                let top = if mode == LayerMode::Bilateral {
                    back == 1
                } else {
                    (mode == LayerMode::OverlayBoth || back == 0) && f(4) >= 0.5
                };
                assert_eq!(
                    [f(8), f(9), f(10)],
                    if top { [0., 3., 0.] } else { [2., 0., 0.] }
                );
                assert!((f(7) - 1.).abs() < 1e-6);
                let receiver_z = if back == 1 { -1000. } else { 1000. };
                let distance_squared = f(4) * f(4) + f(5) * f(5) + (f(6) - receiver_z).powi(2);
                let expected_solid_pdf = distance_squared * distance_squared.sqrt() / 1000.;
                assert!(
                    (f(11) / expected_solid_pdf - 1.).abs() < 2e-6,
                    "forward/reverse mismatch {mode:?} {back}: {}",
                    f(11)
                );
            }
        }
    }
}
