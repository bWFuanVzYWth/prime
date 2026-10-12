//! Actual light-page uploads, finite-lattice sparse area sampling and ReSTIR identities.
use super::{Context, Geometry, cpu_profile, shader_tests::run};
use prime_scene::{
    EmissionProposal, InstanceScene, Scene, SceneMesh, Texture, TextureMaterial, TextureSampling,
    geometry::{CompiledQuad, MeshGeometry},
    spatial::Cell,
    surface::{Emission, SurfaceFace, SurfaceMesh},
};
use std::sync::Arc;

const FIRST: [u8; 8] = [255, 0, 64, 0, 0, 32, 0, 128];
const SECOND: [u8; 8] = [0, 0, 255, 0, 0, 32, 0, 128];

fn fixture() -> Scene {
    let extent = [4, 2];
    let color: Arc<[u8]> = vec![255; 4 * 4 * 4].into();
    let mers: Arc<[u8]> = FIRST
        .into_iter()
        .chain(SECOND)
        .flat_map(|e| [0, e, 220, 0])
        .collect();
    let proposal = EmissionProposal::from_frames(
        extent,
        [4, 4],
        &color,
        [4, 4],
        &mers,
        &[[0, 0, 0, 0], [0, 2, 0, 2]],
    )
    .unwrap()
    .unwrap();
    let sampling = Arc::new(TextureSampling {
        levels: Vec::new(),
        next: [0, 2],
        blend: 0.,
        coverage_frames: vec![[0, 0], [0, 2]].into(),
    });
    let plane = |pixels| Texture {
        width: 4,
        height: 4,
        pixels,
        region: Some([0, 0, 4, 2]),
        sampling: Some(sampling.clone()),
        material: None,
    };
    let mut texture = plane(color);
    texture.material = Some(Arc::new(TextureMaterial {
        specular: Some(plane(mers)),
        authored_emission: true,
        emission_proposal: Some(proposal),
        ..Default::default()
    }));
    let mut face = SurfaceFace::from_quad(CompiledQuad {
        positions: [[0., 0., 0.], [2., 0., 0.], [2., 1., 0.], [0., 1., 0.]],
        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
        color: [1.; 4],
        texture_id: 1,
        flags: 0,
    });
    face.emission = Emission {
        radiance: [1.; 3],
        textured: true,
        ..Default::default()
    };
    let mesh = SurfaceMesh::from_resolved(1, vec![face]).unwrap();
    let mut scene = Scene {
        revision: 1,
        ..Default::default()
    };
    scene
        .ready_terrain
        .insert(Cell::containing([0.; 3]).unwrap());
    scene.textures.insert(1, texture);
    scene.meshes.insert(
        (1, 0),
        SceneMesh {
            revision: 1,
            flags: 0,
            origin: [0.; 3],
            triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
        },
    );
    scene
}

fn geometry(context: &Arc<Context>, scene: &Scene) -> Geometry {
    let mut geometry = Geometry::new_with_omm(
        context,
        scene.into(),
        Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap()),
        false,
    )
    .unwrap();
    prepare_top(context, scene, &mut geometry);
    geometry
}
fn prepare_top(context: &Arc<Context>, scene: &Scene, geometry: &mut Geometry) {
    geometry
        .prepare_dynamic(
            context,
            scene,
            &InstanceScene::default(),
            0,
            &mut cpu_profile::FrameCpu::default(),
        )
        .unwrap();
}

fn inputs(count: u32) -> Vec<u32> {
    (0..count)
        .flat_map(|i| {
            [
                0.3f32.to_bits(),
                ((i as f32 + 0.5) / count as f32).to_bits(),
                (((i.reverse_bits() >> 8) as f32) / 16777216.).to_bits(),
                i,
            ]
        })
        .collect()
}
fn sample(context: &Arc<Context>, geometry: &Geometry, mode: u32, input: &[u32]) -> Vec<u32> {
    run(
        context,
        prime_shader_tests::emission_alias(),
        input,
        input.len() / 4 * 32,
        [mode, input.len() as u32 / 4],
        Some(geometry),
    )
}
fn close(a: f32, b: f32) {
    assert!(
        (a - b).abs() <= 3e-6 * a.abs().max(b.abs()).max(1.),
        "{a} vs {b}"
    );
}
fn lattice_masses() -> [u32; 8] {
    let scene = fixture();
    let material = scene.textures[&1].material.as_ref().unwrap();
    let MeshGeometry::Surfaces(mesh) = &scene.meshes[&(1, 0)].triangles else {
        unreachable!()
    };
    let alias = prime_scene::emission_alias::EmitterAlias::build(
        &mesh.quads[0],
        material.emission_proposal.as_ref().unwrap(),
    )
    .unwrap();
    std::array::from_fn(|i| {
        let entry = alias.words[alias.words[4] as usize + i];
        if entry == u32::MAX {
            0
        } else {
            alias.words[alias.words[5] as usize + entry as usize * 8 + 2]
        }
    })
}

#[test]
#[ignore = "windowless sparse texel alias NEE, actual quantized area PDF and emissive-hit MIS"]
fn gpu_emission_alias_sparse_support_quantized_pdf_and_actual_halves() {
    let context = Context::new().unwrap();
    let scene = fixture();
    let geometry = geometry(&context, &scene);
    let count = 16384;
    let output = sample(&context, &geometry, 0, &inputs(count));
    let masses = lattice_masses();
    let mut observed = [0u32; 8];
    let mut halves = [0u32; 2];
    let mut integral = 0f64;
    for row in output.chunks_exact(32) {
        assert_eq!(row[0], 1, "no noncontributing geometry rejection");
        assert!(
            row[15] > 0,
            "real sparse table uploaded into light node owner"
        );
        halves[row[3] as usize] += 1;
        let f = |i| f32::from_bits(row[i]);
        let cell = (f(8) * 2.).floor().min(3.) as usize + (f(9) * 2.).floor().min(1.) as usize * 4;
        assert!(
            masses[cell] > 0,
            "only full-animation supported cells are sampled"
        );
        observed[cell] += 1;
        let density = masses[cell] as f32 / 16777216. / 0.25;
        close(f(6), density);
        close(f(7), density);
        close(f(12), f32::from(FIRST[cell]) / 255.);
        let d2 = (f(8) - 0.4).powi(2) + (f(9) - 0.35).powi(2) + (f(10) - 4.).powi(2);
        close(f(11), density * d2 * d2.sqrt() / 4.);
        integral += f64::from(f(12) / density);
    }
    for (cell, n) in observed.into_iter().enumerate() {
        let expected = f64::from(masses[cell]) / 16777216.;
        assert!((f64::from(n) / f64::from(count) - expected).abs() < 0.003);
    }
    assert!(halves.into_iter().all(|n| n > 1024));
    let reference = FIRST
        .into_iter()
        .map(|e| f64::from(e) / 255. * 0.25)
        .sum::<f64>();
    assert!(
        (integral / f64::from(count) / reference - 1.).abs() < 0.004,
        "radiance integral is unchanged: {} vs {reference}",
        integral / f64::from(count)
    );
}

#[test]
#[ignore = "windowless nonrectangular clipped emitter, padded alias columns and zero-area second half"]
fn gpu_emission_alias_clipped_triangle_uses_real_cells_without_geometry_rejection() {
    let context = Context::new().unwrap();
    let mut scene = fixture();
    let MeshGeometry::Surfaces(mesh) = &scene.meshes[&(1, 0)].triangles else {
        unreachable!()
    };
    let mut face = mesh.quads[0].clone();
    face.geometry = prime_scene::geometry::Quad::from_triangle(prime_scene::Triangle {
        positions: [[0., 0., 0.], [2., 0., 0.], [1., 1., 0.]],
        colors: [[1.; 4]; 3],
        uvs: [[0.1, 0.1], [0.95, 0.15], [0.4, 0.95]],
        texture_id: 1,
        flags: 0,
    });
    let proposal = scene.textures[&1]
        .material
        .as_ref()
        .unwrap()
        .emission_proposal
        .as_ref()
        .unwrap();
    let alias = prime_scene::emission_alias::EmitterAlias::build(&face, proposal).unwrap();
    assert_eq!(
        alias.words[7], 3,
        "fourth active source cell lies outside the actual triangle"
    );
    assert_eq!(
        alias.words[2] + 1,
        4,
        "a real padded column is exercised on GPU"
    );
    let uv = face.geometry.triangle(0).uvs;
    scene.meshes.get_mut(&(1, 0)).unwrap().triangles =
        MeshGeometry::Surfaces(Arc::new(SurfaceMesh::from_resolved(1, vec![face]).unwrap()));
    let geometry = geometry(&context, &scene);
    let mut counts = [0; 8];
    let mut boundary_nulls = 0;
    for row in sample(&context, &geometry, 0, &inputs(8192)).chunks_exact(32) {
        assert_eq!(row[0], 1);
        assert_eq!(row[3], 0, "never sample the collapsed second hardware half");
        let bary = [f32::from_bits(row[4]), f32::from_bits(row[5])];
        let expected_uv: [f32; 2] = std::array::from_fn(|a| {
            uv[0][a] + bary[0] * (uv[1][a] - uv[0][a]) + bary[1] * (uv[2][a] - uv[0][a])
        });
        let tex = [f32::from_bits(row[28]), f32::from_bits(row[29])];
        for a in 0..2 {
            close(tex[a], expected_uv[a]);
        }
        let cell = (tex[0] * 4.).floor() as usize + (tex[1] * 2.).floor() as usize * 4;
        let index = alias.words[alias.words[4] as usize + cell];
        if index == u32::MAX {
            // A finite FP32 patch edge may belong to the adjacent dark texel under point
            // sampling. It is a null contribution with the same forward/reverse density.
            assert!(
                tex.into_iter()
                    .zip([4., 2.])
                    .any(|(v, size)| { (v * size - (v * size).round()).abs() <= 1e-6 })
            );
            assert_eq!(f32::from_bits(row[6]), 0.);
            assert_eq!(f32::from_bits(row[7]), 0.);
            assert_eq!(&row[12..15], &[0; 3]);
            boundary_nulls += 1;
            continue;
        }
        counts[cell] += 1;
        let row_offset = alias.words[5] as usize + index as usize * 8;
        let expected = f32::from_bits(alias.words[row_offset + 5]);
        close(f32::from_bits(row[6]), expected);
        close(f32::from_bits(row[7]), expected);
        close(f32::from_bits(row[12]), f32::from(FIRST[cell]) / 255.);
    }
    assert_eq!(counts[7], 0);
    assert!(counts[0] > 128 && counts[2] > 128 && counts[5] > 128);
    assert!(
        boundary_nulls > 0 && boundary_nulls < 16,
        "explicit edge witness without significant rejection"
    );
}

#[test]
#[ignore = "windowless ReSTIR sparse emitter actual half/bary identity, replay density and unchanged RNG domain"]
fn gpu_emission_alias_restir_keeps_actual_clipped_endpoint_and_rng() {
    let context = Context::new().unwrap();
    let scene = fixture();
    let geometry = geometry(&context, &scene);
    let output = sample(&context, &geometry, 1, &inputs(4096));
    let mut active = 0;
    let mut halves = [0; 2];
    for (seed, row) in output.chunks_exact(32).enumerate() {
        if f32::from_bits(row[28]) >= 0.5 {
            continue;
        }
        active += 1;
        halves[row[3] as usize] += 1;
        assert_eq!(row[0], 1);
        assert_eq!(row[16] & 1, row[3]);
        assert_eq!(
            [row[18], row[19]],
            [row[4], row[5]],
            "actual sampled barycentrics persisted"
        );
        let rng = (0..7).fold(seed as u32, |rng, _| {
            rng.wrapping_mul(1664525).wrapping_add(1013904223)
        });
        assert_eq!(row[25], rng, "same seven preconsumed dimensions");
        for i in 0..3 {
            close(f32::from_bits(row[20 + i]), f32::from_bits(row[8 + i]));
        }
        close(f32::from_bits(row[24]), f32::from_bits(row[23]));
        close(f32::from_bits(row[26]), f32::from_bits(row[6]));
    }
    assert!(active > 1500 && halves.into_iter().all(|n| n > 128));
}

#[test]
#[ignore = "windowless alias source owners survive animation and invalidate only on proposal replacement"]
fn gpu_emission_alias_animation_retains_light_owner_and_reload_rebuilds() {
    let context = Context::new().unwrap();
    let mut scene = fixture();
    let mut geometry = geometry(&context, &scene);
    let before = geometry.light_sampling_snapshot();
    let source = scene.textures.get_mut(&1).unwrap();
    Arc::make_mut(source.sampling.as_mut().unwrap()).blend = 0.5;
    Arc::make_mut(
        Arc::make_mut(source.material.as_mut().unwrap())
            .specular
            .as_mut()
            .unwrap()
            .sampling
            .as_mut()
            .unwrap(),
    )
    .blend = 0.5;
    scene.revision += 1;
    geometry.update(&context, (&scene).into()).unwrap();
    prepare_top(&context, &scene, &mut geometry);
    let animated = geometry.light_sampling_snapshot();
    // Completion-driven BLAS compaction may replace its handle on this update. The
    // immutable emitter owner witnesses whether the source animation rebuilt a light page.
    assert!(
        Arc::ptr_eq(&before.pages[0].1, &animated.pages[0].1),
        "animation does not upload alias"
    );
    for row in sample(&context, &geometry, 0, &inputs(1024)).chunks_exact(32) {
        assert_eq!(row[0], 1);
        let x = f32::from_bits(row[8]);
        let y = f32::from_bits(row[9]);
        let cell = (x * 2.).floor().min(3.) as usize + (y * 2.).floor().min(1.) as usize * 4;
        let mixed =
            ((u32::from(FIRST[cell]) + u32::from(SECOND[cell])) as f32 * 0.5).round() / 255.;
        close(f32::from_bits(row[12]), mixed);
    }
    let source = scene.textures.get_mut(&1).unwrap();
    let color = source.pixels.clone();
    let material = Arc::make_mut(source.material.as_mut().unwrap());
    let mers = material.specular.as_mut().unwrap();
    let mut changed = mers.pixels.to_vec();
    changed[1 * 4 + 1] = 255;
    changed[(8 + 1) * 4 + 1] = 255;
    mers.pixels = changed.into();
    material.emission_proposal = EmissionProposal::from_frames(
        [4, 2],
        [4, 4],
        &color,
        [4, 4],
        &mers.pixels,
        &[[0, 0, 0, 0], [0, 2, 0, 2]],
    )
    .unwrap();
    scene.revision += 1;
    geometry
        .update_limited(&context, (&scene).into(), 0)
        .unwrap();
    prepare_top(&context, &scene, &mut geometry);
    assert!(
        !geometry.light_sampling_snapshot().has_lights,
        "old support withdrawn even at zero build quota"
    );
    assert!(
        sample(&context, &geometry, 0, &inputs(64))
            .chunks_exact(32)
            .all(|row| row[0] == 0)
    );
    geometry.update(&context, (&scene).into()).unwrap();
    prepare_top(&context, &scene, &mut geometry);
    let reloaded = geometry.light_sampling_snapshot();
    assert!(
        !Arc::ptr_eq(&animated.pages[0].1, &reloaded.pages[0].1),
        "source owner change republished alias"
    );
    assert_ne!(
        animated.pages[0].0, reloaded.pages[0].0,
        "withdrawn source generation cannot retain its previous emitter identity"
    );
    assert!(
        sample(&context, &geometry, 0, &inputs(1024))
            .chunks_exact(32)
            .any(|row| {
                let uv = [f32::from_bits(row[28]), f32::from_bits(row[29])];
                let cell = (uv[0] * 4.).floor() as usize + (uv[1] * 2.).floor() as usize * 4;
                cell == 1 && f32::from_bits(row[12]) > 0. && f32::from_bits(row[6]) > 0.
            }),
        "newly emitting source cell has actual NEE support after publication"
    );
}
