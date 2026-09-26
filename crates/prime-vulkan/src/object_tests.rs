use super::*;
use crate::plan::{INHERIT, ObjectKey, translation};
use prime_scene::scene::{DynamicScene, Instance, Prototype, Texture, Triangle};

fn quad() -> Vec<Triangle> {
    let corners = [
        [-0.6, -0.6, 0.0],
        [0.6, -0.6, 0.0],
        [0.6, 0.6, 0.0],
        [-0.6, 0.6, 0.0],
    ];
    let colors = [
        [1.0, 0.8, 0.6, 1.0],
        [0.2, 1.0, 0.8, 1.0],
        [0.8, 0.4, 1.0, 1.0],
        [1.0, 1.0, 1.0, 1.0],
    ];
    let uvs = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
    [[0, 1, 2], [2, 3, 0]]
        .map(|indices| Triangle {
            positions: indices.map(|i| corners[i]),
            colors: indices.map(|i| colors[i]),
            uvs: indices.map(|i| uvs[i]),
            texture_id: 7,
            flags: 1,
        })
        .to_vec()
}

fn placement(origin: [f64; 3]) -> Instance {
    Instance {
        revision: 1,
        prototype_id: 1,
        origin,
        transform: translation([0.0; 3]),
        texture_id: INHERIT,
        flags: INHERIT,
        tint: [255; 4],
        uv_transform: [1.0, 1.0, 0.0, 0.0],
    }
}

fn camera() -> Camera {
    Camera {
        position: [0.0, 0.0, 5.0],
        forward: [0.0, 0.0, -1.0],
        right: [1.0, 0.0, 0.0],
        up: [0.0, 1.0, 0.0],
        vertical_fov_radians: 1.0,
    }
}

#[test]
fn local_edge_transform_preserves_small_triangles_below_world_position_ulp() {
    let local: [[f32; 3]; 3] = [
        [-0.0015, -0.0015, 0.0],
        [0.0015, -0.0015, 0.0],
        [0.0015, 0.0015, 0.0],
    ];
    let direction =
        |point: [f32; 3]| [-point[0] + 0.2 * point[1], 1.25 * point[1], 0.25 * point[0]];
    let cross = |a: [f32; 3], b: [f32; 3]| {
        [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ]
    };
    let sub = |a: [f32; 3], b: [f32; 3]| std::array::from_fn(|i| a[i] - b[i]);
    let transformed = local.map(direction);
    let world = transformed.map(|[x, y, z]| [x + 65536.0, y + 65536.0, z]);
    assert_eq!(
        cross(sub(world[1], world[0]), sub(world[2], world[0])),
        [0.0; 3]
    );
    let edge1 = direction(sub(local[1], local[0]));
    let edge2 = direction(sub(local[2], local[0]));
    let normal = cross(edge1, edge2);
    assert!(normal.iter().all(|v| v.is_finite()));
    assert!(normal[2] < -1e-5 && normal[0] < -2e-6);
    let length = |v: [f32; 3]| v.iter().map(|v| v * v).sum::<f32>().sqrt();
    let extent = |a: [f32; 3], b: [f32; 3]| length(a).max(length(b)).max(length(sub(b, a)));
    assert!((extent(edge1, edge2) - extent(sub(edge2, edge1), edge1.map(|v| -v))).abs() < 1e-9);
}

#[test]
#[ignore = "requires an exclusive Vulkan ray-query GPU; tests translation precision"]
fn gpu_small_affine_prototype_preserves_normals_and_rebased_image() {
    let mut prototype = quad();
    for triangle in &mut prototype {
        for point in &mut triangle.positions {
            for value in point {
                *value *= 0.0025;
            }
        }
        triangle.texture_id = 0;
        triangle.flags = 0;
        triangle.colors = [[0.8, 0.5, 0.2, 1.0]; 3];
    }
    let mut scene = Scene {
        epoch: 1,
        ..Default::default()
    };
    let mut source = InstanceScene {
        epoch: 1,
        resource_revision: 1,
        instance_revision: 1,
        ..Default::default()
    };
    source.prototypes.insert(
        1,
        Prototype {
            revision: 1,
            triangles: prototype.into(),
            bounds: [[-0.0015, -0.0015, 0.0], [0.0015, 0.0015, 0.0]],
        },
    );
    let mut instance = placement([0.0; 3]);
    instance.transform = [
        -1.0, 0.2, 0.0, 0.0, 0.0, 1.25, 0.0, 0.0, 0.25, 0.0, 1.0, 0.0,
    ];
    source.instances.insert(1, instance);
    let mut camera = camera();
    camera.position = [0.0, 0.0, 1.0];
    camera.vertical_fov_radians = 0.0101;
    let mut renderer = Renderer::new().unwrap();
    let near = renderer
        .render_with_instances(&scene, &source, &camera, 64, 64, 0)
        .unwrap();
    source.instances.get_mut(&1).unwrap().origin = [65536.0, 65536.0, 0.0];
    source.instance_revision += 1;
    camera.position = [65536.0, 65536.0, 1.0];
    let far = renderer
        .render_with_instances(&scene, &source, &camera, 64, 64, 0)
        .unwrap();
    let changed = near
        .as_chunks::<4>()
        .0
        .iter()
        .zip(far.as_chunks::<4>().0)
        .filter(|(a, b)| a != b)
        .count();
    // This deliberately bypasses production rebasing to put the quad below
    // world-position ULP. Ray/object inverse-transform rounding can change its
    // primary coverage, so entire-image equality is not a valid oracle here.
    // For pixels that hit in both images, the lone uniform quad has the same
    // normal, sun visibility and secondary sky ray at the same RNG seed.
    let orange = |p: &&[u8; 4]| p[0] > p[2];
    let near_colored = near.as_chunks::<4>().0.iter().filter(orange).count();
    let far_colored = far.as_chunks::<4>().0.iter().filter(orange).count();
    let common: Vec<_> = near
        .as_chunks::<4>()
        .0
        .iter()
        .zip(far.as_chunks::<4>().0)
        .filter(|(a, b)| a[0] > a[2] && b[0] > b[2])
        .collect();
    let common_changed = common.iter().filter(|(a, b)| a != b).count();
    eprintln!(
        "precision diagnostics: near={near_colored}, far={far_colored}, common={}, common_changed={common_changed}, coverage_changed={changed}",
        common.len()
    );
    assert!(near_colored > 200 && far_colored > 200 && common.len() > 200);
    assert_eq!(common_changed, 0, "translated local normal changed shading");
    let center = 32 * 64 + 32;
    assert!(near.as_chunks::<4>().0[center][0] > near.as_chunks::<4>().0[center][2]);
    assert_eq!(
        near.as_chunks::<4>().0[center],
        far.as_chunks::<4>().0[center]
    );

    // The actual host contract subtracts the same f64 anchor from the instance
    // origin and camera before GPU use. At this world position that must restore
    // the complete near-origin image, including its primary silhouette.
    scene.anchor = [65536.0, 65536.0, 0.0];
    camera.position = [0.0, 0.0, 1.0];
    let rebased = renderer
        .render_with_instances(&scene, &source, &camera, 64, 64, 0)
        .unwrap();
    assert_eq!(near, rebased, "camera-relative rebasing changed the image");
}

fn bake(prototype: &[Triangle], source: &InstanceScene) -> Vec<Triangle> {
    let mut result = Vec::new();
    for instance in source.instances.values() {
        for triangle in prototype {
            let mut transformed = *triangle;
            for point in &mut transformed.positions {
                let original = *point;
                *point = std::array::from_fn(|row| {
                    (0..3)
                        .map(|i| instance.transform[row * 4 + i] * original[i])
                        .sum::<f32>()
                        + instance.transform[row * 4 + 3]
                        + instance.origin[row] as f32
                });
            }
            for color in &mut transformed.colors {
                for (channel, tint) in color.iter_mut().zip(instance.tint) {
                    let encoded = (*channel * 255.0).round() as u32;
                    *channel = (encoded * u32::from(tint) / 255) as f32 / 255.0;
                }
            }
            for uv in &mut transformed.uvs {
                uv[0] = uv[0] * instance.uv_transform[0] + instance.uv_transform[2];
                uv[1] = uv[1] * instance.uv_transform[1] + instance.uv_transform[3];
            }
            if instance.texture_id != INHERIT {
                transformed.texture_id = instance.texture_id;
            }
            if instance.flags != INHERIT {
                transformed.flags = instance.flags;
            }
            result.push(transformed);
        }
    }
    result
}

#[test]
#[ignore = "requires an exclusive Vulkan ray-query GPU; run with synchronization validation"]
fn gpu_affine_material_instances_match_baked_source_and_ten_thousand_share_one_blas() {
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    scene.textures.insert(
        7,
        Texture {
            width: 2,
            height: 2,
            pixels: vec![
                255, 90, 30, 255, 40, 255, 80, 255, 30, 80, 255, 255, 255, 255, 255, 255,
            ]
            .into(),
        },
    );
    scene.textures.insert(
        9,
        Texture {
            width: 2,
            height: 2,
            pixels: vec![
                10, 40, 255, 255, 255, 30, 90, 255, 80, 255, 10, 255, 90, 40, 255, 255,
            ]
            .into(),
        },
    );
    let prototype = quad();
    let mut source = InstanceScene {
        epoch: 1,
        resource_revision: 1,
        instance_revision: 1,
        ..Default::default()
    };
    source.prototypes.insert(
        1,
        Prototype {
            revision: 1,
            triangles: prototype.clone().into(),
            bounds: [[-0.6, -0.6, 0.0], [0.6, 0.6, 0.0]],
        },
    );
    let mut a = placement([-1.5, 0.0, 0.0]);
    a.transform = [-1.25, 0.3, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0];
    a.tint = [103, 199, 217, 209];
    a.flags = 0;
    let mut b = placement([0.0, 0.0, 0.0]);
    b.transform = [0.8, 0.0, 0.0, 0.0, -0.25, 1.2, 0.0, 0.0, 0.0, 0.0, 1.5, 0.0];
    b.flags = 2;
    b.tint = [213, 97, 187, 173];
    b.texture_id = 9;
    b.uv_transform = [0.5, -0.5, 0.5, 0.75];
    source.instances.insert(1, a);
    source.instances.insert(2, b);
    source.instances.insert(3, placement([1.5, 0.0, 0.0]));
    let mut renderer = Renderer::new().unwrap();
    let instanced = renderer
        .render_with_instances(&scene, &source, &camera(), 96, 64, 7)
        .unwrap();
    let mut reference = Renderer::new().unwrap();
    scene.dynamic = DynamicScene {
        revision: 1,
        origin: [0.0; 3],
        triangles: bake(&prototype, &source).into(),
    };
    let baked = reference.render(&scene, &camera(), 96, 64, 7).unwrap();
    let different = instanced
        .as_chunks::<4>()
        .0
        .iter()
        .zip(baked.as_chunks::<4>().0)
        .filter(|(a, b)| a != b)
        .count();
    assert!(
        different <= 8,
        "affine/material instance differed from actual baked vertices at {different} pixels"
    );
    scene.dynamic = DynamicScene::default();
    let address = renderer.geometry.as_ref().unwrap().objects.addresses();
    let material_buffer = renderer.geometry.as_ref().unwrap().objects.data.buffer;
    for id in 4..=10_000 {
        source
            .instances
            .insert(id, placement([1000.0 + id as f64 * 2.0, 0.0, 0.0]));
    }
    source.instance_revision += 1;
    renderer
        .render_with_instances(&scene, &source, &camera(), 32, 24, 0)
        .unwrap();
    let geometry = renderer.geometry.as_ref().unwrap();
    assert_eq!(geometry.objects.instances.len(), 10_000);
    assert_eq!(geometry.objects.addresses(), address);
    assert_eq!(geometry.objects.rebuilt, 0);
    assert_eq!(geometry.objects.data.buffer, material_buffer);
    source.instances.get_mut(&123).unwrap().transform[3] = 0.25;
    source.instance_revision += 1;
    renderer
        .render_with_instances(&scene, &source, &camera(), 32, 24, 0)
        .unwrap();
    assert_eq!(renderer.geometry.as_ref().unwrap().objects.rebuilt, 0);
    source.instances.remove(&2);
    source.instance_revision += 1;
    renderer
        .render_with_instances(&scene, &source, &camera(), 32, 24, 0)
        .unwrap();
    assert_eq!(
        renderer.geometry.as_ref().unwrap().objects.instances.len(),
        9_999
    );
    assert_eq!(
        renderer.geometry.as_ref().unwrap().objects.addresses(),
        address
    );
    assert_eq!(renderer.geometry.as_ref().unwrap().objects.rebuilt, 0);
    source.prototypes.get_mut(&1).unwrap().revision += 1;
    source.resource_revision += 1;
    renderer
        .render_with_instances(&scene, &source, &camera(), 32, 24, 0)
        .unwrap();
    assert_eq!(renderer.geometry.as_ref().unwrap().objects.rebuilt, 1);
}

#[test]
#[ignore = "requires an exclusive Vulkan ray-query GPU; run with synchronization validation"]
fn gpu_raw_spatial_buckets_preserve_unaffected_blas_on_birth_move_and_remove() {
    let triangle = |x| Triangle {
        positions: [[x, 1.0, 0.0], [x + 1.0, 1.0, 0.0], [x, 2.0, 0.0]],
        colors: [[1.0; 4]; 3],
        uvs: [[0.0; 2]; 3],
        flags: 0,
        texture_id: 0,
    };
    let mut scene = Scene {
        epoch: 1,
        dynamic: DynamicScene {
            revision: 1,
            origin: [0.0; 3],
            triangles: vec![triangle(1.0), triangle(1001.0)].into(),
        },
        ..Default::default()
    };
    let mut renderer = Renderer::new().unwrap();
    renderer.render(&scene, &camera(), 16, 16, 0).unwrap();
    let before = renderer.geometry.as_ref().unwrap().objects.addresses();
    assert_eq!(before.len(), 2);
    scene.dynamic.triangles = vec![triangle(1.0), triangle(1001.0), triangle(2001.0)].into();
    scene.dynamic.revision += 1;
    renderer.render(&scene, &camera(), 16, 16, 0).unwrap();
    let geometry = renderer.geometry.as_ref().unwrap();
    assert_eq!(geometry.objects.rebuilt, 1);
    for (key, address) in &before {
        assert_eq!(geometry.objects.addresses()[key], *address);
    }
    scene.dynamic.triangles = vec![triangle(1.25), triangle(2001.0)].into();
    scene.dynamic.revision += 1;
    renderer.render(&scene, &camera(), 16, 16, 0).unwrap();
    let geometry = renderer.geometry.as_ref().unwrap();
    assert_eq!(geometry.objects.rebuilt, 1);
    assert!(
        !geometry
            .objects
            .addresses()
            .contains_key(&ObjectKey::Raw([62, 0, 0], 0))
    );
    assert_eq!(
        geometry.objects.addresses()[&ObjectKey::Raw([0, 0, 0], 0)],
        before[&ObjectKey::Raw([0, 0, 0], 0)]
    );
}
