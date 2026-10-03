//! Explicit replay of the actual Java Custom callback -> MeshData -> typed/raw products.
use super::*;
use prime_abi::{
    scene::{DynamicView, InstancesView},
    *,
};
use prime_scene::SourceScene;
use serde_json::Value;

struct Mesh {
    descriptor: PrimeMeshSpan,
    bytes: Vec<u8>,
}

fn integer(value: &Value, field: &str) -> u64 {
    value[field].as_u64().expect(field)
}

fn floats<const N: usize>(value: &Value, field: &str) -> [f32; N] {
    let values = value[field].as_array().expect(field);
    assert_eq!(values.len(), N);
    std::array::from_fn(|i| values[i].as_f64().unwrap() as f32)
}

fn origin(value: &Value, field: &str) -> [f64; 3] {
    let values = value[field].as_array().expect(field);
    assert_eq!(values.len(), 3);
    std::array::from_fn(|i| values[i].as_f64().unwrap())
}

fn header<T>() -> PrimeHeader {
    PrimeHeader {
        struct_size: std::mem::size_of::<T>() as u32,
        abi_version: PRIME_ABI_VERSION,
    }
}

fn mesh(value: &Value) -> Mesh {
    let hex = value["hex"].as_str().unwrap();
    assert!(hex.len().is_multiple_of(2));
    let bytes: Vec<_> = hex
        .as_bytes()
        .as_chunks::<2>()
        .0
        .iter()
        .map(|word| u8::from_str_radix(std::str::from_utf8(word).unwrap(), 16).unwrap())
        .collect();
    let descriptor = PrimeMeshSpan {
        topology: integer(value, "topology") as u32,
        vertex_count: integer(value, "vertex_count") as u32,
        stride: integer(value, "stride") as u32,
        position_offset: integer(value, "position_offset") as u32,
        color_offset: integer(value, "color_offset") as u32,
        uv_offset: integer(value, "uv_offset") as u32,
        texture_id: integer(value, "texture_id") as u32,
        flags: integer(value, "flags") as u32,
        vertices: PrimeByteSpan {
            data: bytes.as_ptr(),
            count: bytes.len() as u64,
        },
    };
    assert_eq!(
        bytes.len(),
        descriptor.vertex_count as usize * descriptor.stride as usize
    );
    Mesh { descriptor, bytes }
}

fn meshes(frame: &Value, field: &str) -> Vec<Mesh> {
    frame[field].as_array().unwrap().iter().map(mesh).collect()
}

fn removals(frame: &Value, field: &str) -> Vec<PrimeRemoval> {
    frame[field]
        .as_array()
        .unwrap()
        .iter()
        .map(|value| PrimeRemoval {
            id: integer(value, "id"),
            revision: integer(value, "revision"),
        })
        .collect()
}

struct Decoded {
    scene: Scene,
    instances: InstanceScene,
    camera: Camera,
    extent: [u32; 2],
}

fn audit_meshes(original: &Mesh, named: &Mesh, raw: &Mesh, instance: &PrimeInstanceSource) {
    let shape = |m: &Mesh| {
        let s = m.descriptor;
        [
            s.topology,
            s.stride,
            s.position_offset,
            s.color_offset,
            s.uv_offset,
        ]
    };
    assert_eq!(shape(original), shape(named));
    assert_eq!(shape(original), shape(raw));
    assert_eq!(original.descriptor.topology, 4);
    assert_eq!(original.descriptor.vertex_count, 8);
    assert_eq!(named.descriptor.vertex_count, 4);
    assert_eq!(raw.descriptor.vertex_count, 4);
    // The actual fixture emits the named callback then an anonymous callback into one Draw.
    // Compare every original strided byte, including fields the PT decoder does not consume.
    let (named_source, raw_source) = original.bytes.split_at(named.bytes.len());
    assert_eq!(named.bytes, named_source);
    assert_eq!(raw.bytes, raw_source);
    assert_eq!(instance.texture_id, original.descriptor.texture_id);
    assert_eq!(instance.flags, original.descriptor.flags);
    assert_eq!(raw.descriptor.texture_id, original.descriptor.texture_id);
    assert_eq!(raw.descriptor.flags, original.descriptor.flags);
    assert_eq!(instance.rgba, u32::MAX);
    assert_eq!(instance.uv_transform, [1.0, 1.0, 0.0, 0.0]);
    assert_eq!(
        instance.transform,
        [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0]
    );
}

fn audit_triangles(mesh: &Mesh, triangles: &[prime_scene::Triangle]) {
    assert_eq!(triangles.len(), 2);
    for (triangle, corners) in triangles.iter().zip([[0, 1, 2], [2, 3, 0]]) {
        assert_eq!(triangle.texture_id, mesh.descriptor.texture_id);
        assert_eq!(triangle.flags, mesh.descriptor.flags);
        for (corner, vertex) in corners.into_iter().enumerate() {
            let base = vertex * mesh.descriptor.stride as usize;
            let bits = |offset, axis| {
                u32::from_le_bytes(
                    mesh.bytes[base + offset + axis * 4..base + offset + axis * 4 + 4]
                        .try_into()
                        .unwrap(),
                )
            };
            for (axis, value) in triangle.positions[corner].iter().enumerate() {
                assert_eq!(
                    value.to_bits(),
                    bits(mesh.descriptor.position_offset as usize, axis)
                );
            }
            for (axis, value) in triangle.uvs[corner].iter().enumerate() {
                assert_eq!(
                    value.to_bits(),
                    bits(mesh.descriptor.uv_offset as usize, axis)
                );
            }
            for channel in 0..4 {
                let byte = mesh.bytes[base + mesh.descriptor.color_offset as usize + channel];
                assert_eq!(triangle.colors[corner][channel], f32::from(byte) / 255.0);
            }
        }
    }
}

fn products() -> Vec<Decoded> {
    let path = std::env::var("PRIME_NAMED_CUSTOM_PRODUCT")
        .expect("explicit actual Java CustomCpuSmoke JSON product path");
    let value: Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
    let frames = value.as_array().unwrap();
    assert_eq!(frames.len(), 3, "fresh, decimal motion, changed shape");
    let mut source = SourceScene::default();
    source.reset_world(integer(&frames[0], "epoch")).unwrap();
    let mut result = Vec::new();
    for frame in frames {
        let original = meshes(frame, "original");
        let named = meshes(frame, "prototypes");
        let raw = meshes(frame, "raw");
        assert_eq!((original.len(), named.len(), raw.len()), (1, 1, 1));
        assert_eq!(
            frame["original"][0]["mechanical_ranges"],
            serde_json::json!([])
        );
        assert_eq!(
            frame["original"][0]["routed_ranges"],
            serde_json::json!([[0, 4]])
        );
        let prototypes: Vec<_> = frame["prototypes"]
            .as_array()
            .unwrap()
            .iter()
            .zip(&named)
            .map(|(value, mesh)| PrimePrototypeSource {
                id: integer(value, "id"),
                revision: integer(value, "revision"),
                spans: &mesh.descriptor,
                count: 1,
            })
            .collect();
        let instances: Vec<_> = frame["instances"]
            .as_array()
            .unwrap()
            .iter()
            .map(|value| PrimeInstanceSource {
                id: integer(value, "id"),
                revision: integer(value, "revision"),
                prototype_id: integer(value, "prototype_id"),
                origin: origin(value, "origin"),
                transform: floats(value, "transform"),
                uv_transform: floats(value, "uv_transform"),
                rgba: integer(value, "rgba") as u32,
                texture_id: integer(value, "texture_id") as u32,
                flags: integer(value, "flags") as u32,
                ..Default::default()
            })
            .collect();
        assert_eq!(instances.len(), 1);
        audit_meshes(&original[0], &named[0], &raw[0], &instances[0]);
        assert_eq!(
            instances[0].origin.map(f64::to_bits),
            origin(frame, "raw_origin").map(f64::to_bits),
            "actual camera origin, without entity/base translation"
        );
        let prototype_removals = removals(frame, "prototype_removals");
        let instance_removals = removals(frame, "instance_removals");
        let batch = PrimeInstanceBatch {
            header: header::<PrimeInstanceBatch>(),
            epoch: integer(frame, "epoch"),
            sequence: integer(frame, "sequence"),
            prototypes: prototypes.as_ptr(),
            prototype_count: prototypes.len() as u64,
            prototype_removals: prototype_removals.as_ptr(),
            prototype_removal_count: prototype_removals.len() as u64,
            instances: instances.as_ptr(),
            instance_count: instances.len() as u64,
            instance_removals: instance_removals.as_ptr(),
            instance_removal_count: instance_removals.len() as u64,
        };
        // SAFETY: All DTOs and payload owners remain immutable and live through synchronous decode.
        source
            .submit_instances_typed(unsafe { InstancesView::read(&batch).unwrap() })
            .unwrap();
        let spans: Vec<_> = raw.iter().map(|m| m.descriptor).collect();
        let dynamic = PrimeDynamicBatch {
            header: header::<PrimeDynamicBatch>(),
            epoch: integer(frame, "raw_epoch"),
            sequence: integer(frame, "raw_sequence"),
            origin: origin(frame, "raw_origin"),
            spans: spans.as_ptr(),
            count: spans.len() as u64,
        };
        // SAFETY: Same synchronous owned DTO/payload contract as the instance batch above.
        source
            .submit_dynamic_typed(unsafe { DynamicView::read(&dynamic).unwrap() })
            .unwrap();
        let scene = source.translate([0.0; 3]).unwrap();
        let current = source.instances();
        assert_eq!((current.instances.len(), current.prototypes.len()), (1, 1));
        let instance = current.instances.values().next().unwrap();
        assert_eq!(instance.prototype_id, prototypes[0].id);
        audit_triangles(
            &named[0],
            &current.prototypes[&instance.prototype_id].triangles,
        );
        audit_triangles(&raw[0], &scene.dynamic.triangles);
        let camera = Camera {
            position: dynamic.origin.map(|value| value as f32),
            forward: floats(frame, "camera_forward"),
            right: floats(frame, "camera_right"),
            up: floats(frame, "camera_up"),
            vertical_fov_radians: frame["camera_fov"].as_f64().unwrap() as f32,
        };
        result.push(Decoded {
            scene,
            instances: InstanceScene {
                epoch: current.epoch,
                resource_revision: current.resource_revision,
                instance_revision: current.instance_revision,
                prototypes: current.prototypes.clone(),
                instances: current.instances.clone(),
            },
            camera,
            extent: [
                integer(frame, "width") as u32,
                integer(frame, "height") as u32,
            ],
        });
    }
    let ids: Vec<_> = result
        .iter()
        .map(|frame| *frame.instances.instances.first_key_value().unwrap().0)
        .collect();
    assert_eq!(ids, vec![ids[0]; 3], "actual stable named source identity");
    assert_eq!(result[0].extent, result[1].extent);
    assert_eq!(result[0].extent, result[2].extent);
    println!(
        "actual Java product: {path}; 3 typed/raw frames, original strided bytes and attributes exact"
    );
    result
}

#[test]
#[ignore = "requires explicit actual Java CustomCpuSmoke exported products; no GPU"]
fn actual_custom_products_preserve_meshdata_typed_fields_and_remaining_raw() {
    products();
}

#[test]
#[ignore = "requires actual Java exported products and a Vulkan ray-query GPU; named motion/FG replay"]
fn gpu_actual_custom_products_use_accepted_named_barycentric_motion() {
    let frames = products();
    let context = Context::new().unwrap();
    let mut fixture = Fixture::with_features(
        &context,
        &frames[0].scene,
        frames[0].extent,
        &frames[0].instances,
        3,
    );
    let unknown = |snapshot: &Snapshot| {
        assert!(snapshot.0[6].iter().all(|status| *status == 11));
        for channel in [1, 7, 9] {
            assert!(snapshot.channel(channel).iter().all(|value| *value == 0.0));
        }
    };
    unknown(&fixture.run(frames[0].camera, frames[0].camera, [0.0; 2], 211, 1));
    fixture._geometry.objects.commit_motion();
    fixture.update_instances(&frames[1].scene, &frames[1].instances);
    for jitter in [[0.0; 2], [0.25, -0.25]] {
        let actual = fixture.run(frames[1].camera, frames[0].camera, jitter, 212, 1);
        actual.resolved();
        assert!(actual.0[6].iter().all(|status| *status == 8));
        actual.corresponding_motion(
            [frames[0].camera, frames[1].camera],
            frames[1].extent,
            jitter,
            frames[1].scene.anchor,
            [&frames[0].instances, &frames[1].instances],
        );
    }
    fixture._geometry.objects.commit_motion();
    fixture.update_instances(&frames[2].scene, &frames[2].instances);
    unknown(&fixture.run(frames[2].camera, frames[1].camera, [0.0; 2], 213, 1));
}
