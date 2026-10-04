//! Windowless production K1 traversal, branch weighting, guide independence and seed ABI tests.
use super::{Context, Geometry, cpu_profile, shader_tests::run_primary};
use prime_scene::{
    SceneMesh, Texture, TextureMaterial,
    geometry::{CompiledQuad, MeshGeometry},
    scene::{InstanceScene, Scene},
    spatial::Cell,
    surface::{Medium, Optics, SurfaceFace, SurfaceMesh},
    workers::CpuWorkers,
};
use std::sync::Arc;

const CASES: usize = 256;
const ACTIVE: u32 = 1 << 8;
const REFRACTED: u32 = 1 << 9;

#[derive(Clone, Copy, Debug)]
struct Report([u32; 32]);

impl Report {
    fn value(self, plane: usize, component: usize) -> f32 {
        f32::from_bits(self.0[plane * 4 + component])
    }

    fn flags(self) -> u32 {
        self.0[15]
    }

    fn active(self) -> bool {
        self.flags() & ACTIVE != 0
    }

    fn surface(self, depth: f32, steps: u32) {
        assert_eq!(self.value(0, 3), 1., "guide status: {self:?}");
        near(self.value(0, 0), 2.);
        near(self.value(0, 1), 2.);
        near(self.value(0, 2), depth);
        near(self.value(1, 2), depth);
        assert_eq!(self.0[11] & 0x7f, steps, "PSR step count: {self:?}");
        assert_eq!(self.value(5, 3), 1., "one guide terminal per sample");
        assert_eq!(self.value(6, 1), 1., "finite PSR surface");
    }

    fn prefix(self, expected: f32) {
        for channel in 0..3 {
            near(self.value(4, channel), expected);
        }
    }
}

fn near(actual: f32, expected: f32) {
    assert!(
        actual.is_finite() && (actual - expected).abs() <= 3e-5 * expected.abs().max(1.),
        "{actual} != {expected}"
    );
}

fn face(z: f32, medium: Option<(Medium, bool)>, outward_positive: bool) -> SurfaceFace {
    let mut positions = [[1., 1., z], [3., 1., z], [3., 3., z], [1., 3., z]];
    if !outward_positive {
        positions.reverse();
    }
    let mut face = SurfaceFace::from_quad(CompiledQuad {
        positions,
        uvs: [[0.5; 2]; 4],
        color: [1.; 4],
        texture_id: 0,
        flags: 0,
    });
    if let Some((medium, thin)) = medium {
        face.media = [7, 0];
        face.optics = Some(Optics {
            negative: medium,
            positive: Medium::default(),
            ior_textures: [None; 2],
            transmit: true,
            thin,
        });
    }
    face
}

fn scene(faces: Vec<SurfaceFace>) -> Scene {
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    scene
        .ready_terrain
        .insert(Cell::containing([0.; 3]).unwrap());
    let mut groups = std::collections::BTreeMap::<u32, Vec<SurfaceFace>>::new();
    for face in faces {
        groups.entry(face.flags()).or_default().push(face);
    }
    for (flags, faces) in groups {
        scene.meshes.insert(
            (1, flags),
            SceneMesh {
                revision: 1,
                flags,
                origin: [0.; 3],
                triangles: MeshGeometry::Surfaces(Arc::new(
                    SurfaceMesh::from_resolved(1, faces).unwrap(),
                )),
            },
        );
    }
    scene
}

fn layers(count: u32, extinction: f32) -> Scene {
    let medium = Medium {
        ior: 1.5,
        extinction: [extinction; 3],
    };
    let mut faces: Vec<_> = (1..=count)
        .map(|z| face(z as f32, Some((medium, true)), false))
        .collect();
    faces.push(face((count + 1) as f32, None, false));
    scene(faces)
}

fn run(context: &Arc<Context>, scene: &Scene, budgets: impl Fn(usize) -> u32) -> Vec<Report> {
    let mut geometry =
        Geometry::new(context, scene.into(), Arc::new(CpuWorkers::new(1).unwrap())).unwrap();
    let instances = InstanceScene {
        epoch: 1,
        ..Default::default()
    };
    geometry
        .prepare_dynamic(
            context,
            scene,
            &instances,
            0,
            &mut cpu_profile::FrameCpu::default(),
        )
        .unwrap();
    let input: Vec<u32> = (0..CASES)
        .flat_map(|i| {
            [
                17 ^ (i as u32).wrapping_mul(0x9e3779b9),
                i as u32,
                budgets(i),
                0,
            ]
        })
        .collect();
    // The shader owns 8 report + 11 common (including companion) + 2 optical + 1 prefix float4 per case.
    let output = run_primary(
        context,
        prime_shader_tests::primary(),
        &input,
        CASES * 22 * 4,
        [0, CASES as u32],
        &geometry,
    );
    output[..CASES * 32]
        .as_chunks::<32>()
        .0
        .iter()
        .copied()
        .map(Report)
        .collect()
}

#[test]
#[ignore = "requires Vulkan; production K1 queries, independent guide traversal and BDA seed replay"]
fn gpu_primary_guides_continue_after_lighting_termination_and_respect_query_budget() {
    let context = Context::new().unwrap();

    // Both primary coins and later roulette vary, but preferred transmission has one endpoint.
    // Four queries are just enough to reach the opaque terminal after three delta interfaces.
    let output = run(&context, &layers(3, 0.), |i| if i % 2 == 0 { 4 } else { 3 });
    let mut active = 0;
    let mut inactive = 0;
    for (i, result) in output.iter().copied().enumerate() {
        if i % 2 == 0 {
            result.surface(4., 3);
            assert_eq!(
                result.value(1, 3),
                1.,
                "static straight thin chain has known motion"
            );
            if result.active() {
                active += 1;
                assert_eq!(result.flags() & 0xff, 3);
                near(result.value(7, 2), 4.);
            } else {
                inactive += 1;
            }
        } else {
            assert_eq!(
                result.value(0, 3),
                3.,
                "last query is still delta, not a guide surface"
            );
            assert_eq!(result.value(5, 3), 1.);
            assert!(!result.active());
        }
    }
    assert!(
        active > 8 && inactive > 8,
        "exercise both shared and replayed guide paths"
    );

    // At sigma=50 the first transmitted beta is about .08; after the next delta it is
    // below .01, so roulette samples above .25 certainly terminate before the terminal.
    let output = run(&context, &layers(3, 50.), |_| 8);
    let mut killed_before_terminal = 0;
    for result in output {
        result.surface(4., 3);
        if result.value(5, 0) < 0.5 && result.value(5, 1) > 0.25 {
            assert!(!result.active());
            result.prefix(0.);
            killed_before_terminal += 1;
        }
    }
    assert!(
        killed_before_terminal > 32,
        "exercise lighting roulette death followed by guide replay"
    );

    // Thin-wall transmitted response underflows to zero. Only reflection remains in the
    // lighting estimator, but geometric transmission remains the stable guide choice.
    for result in run(&context, &layers(3, 1e6), |_| 8) {
        result.surface(4., 3);
        assert!(!result.active());
        result.prefix(0.04);
        assert_eq!(result.value(5, 2), 1.);
    }

    // Thick absorption terminates lighting during a segment, independently of geometric
    // refraction. The guide crosses both boundaries and publishes a static target-plane proxy.
    let medium = Medium {
        ior: 1.5,
        extinction: [1e6; 3],
    };
    let thick = scene(vec![
        face(1., Some((medium, false)), false),
        face(2., Some((medium, false)), true),
        face(3., None, false),
    ]);
    let mut zero_after_beer = 0;
    for result in run(&context, &thick, |_| 8) {
        result.surface(3., 2);
        assert_ne!(result.0[11] & REFRACTED, 0);
        assert_eq!(result.value(1, 3), 1.);
        assert!(!result.active());
        if result.value(5, 0) < 0.5 {
            result.prefix(0.);
            zero_after_beer += 1;
        }
    }
    assert!(zero_after_beer > 32);
}

#[test]
#[ignore = "requires Vulkan; primary conditional delta energy and first rough landing behavior"]
fn gpu_primary_half_selection_preserves_delta_energy_and_leaves_rough_first_surface_to_k2() {
    let context = Context::new().unwrap();
    let mut reflected = 0;
    let mut transmitted = 0;
    // Lossless thin wall: R=2F/(1+F), T=(1-F)/(1+F); the independent half selection
    // multiplies each physical conditional estimate by two, with conditional PDF one.
    let fresnel = ((1.5_f32 - 1.) / (1.5 + 1.)).powi(2);
    for result in run(&context, &layers(1, 0.), |_| 2) {
        result.surface(2., 1);
        assert_eq!(result.value(5, 2), 1.);
        near(result.value(4, 3), 1.);
        if result.value(5, 0) < 0.5 {
            assert!(result.active());
            assert_eq!(result.flags() & 0xff, 1);
            for channel in 0..3 {
                near(
                    result.value(3, channel),
                    2. * (1. - fresnel) / (1. + fresnel),
                );
            }
            result.prefix(0.);
            transmitted += 1;
        } else {
            assert!(!result.active());
            result.prefix(4. * fresnel / (1. + fresnel));
            near(result.value(6, 3), 1.); // Reflection guide completed its environment endpoint.
            reflected += 1;
        }
    }
    assert!(reflected > 32 && transmitted > 32);

    // A rough optical first surface is a terminal landing even with a one-query budget.
    // It never invokes the first-delta guide epilogue or samples a lighting continuation.
    let medium = Medium {
        ior: 1.5,
        extinction: [0.; 3],
    };
    let mut rough_face = face(1., Some((medium, true)), false);
    rough_face.geometry.texture_id = 1;
    let mut rough = scene(vec![rough_face, face(2., None, false)]);
    let image = |pixels: Vec<u8>| Texture {
        width: 1,
        height: 1,
        pixels: pixels.into(),
        region: None,
        sampling: None,
        material: None,
    };
    let mut texture = image(vec![255; 4]);
    texture.material = Some(Arc::new(TextureMaterial {
        specular: Some(image(vec![128, 5, 0, 0])),
        ..Default::default()
    }));
    rough.textures.insert(1, texture);
    for result in run(&context, &rough, |_| 1) {
        result.surface(1., 0);
        assert!(result.active());
        assert_eq!(result.flags() & 0xff, 0);
        assert_eq!(result.value(5, 2), 0.);
        assert!(result.value(7, 3) > 0.1);
        for channel in 0..3 {
            near(result.value(3, channel), 1.);
        }
        result.prefix(0.);
    }
    for result in run(&context, &layers(1, 0.), |_| 1) {
        assert_eq!(result.value(0, 3), 3.);
        assert!(!result.active());
        assert_eq!(result.value(5, 2), 0.);
    }
}
