use super::*;
use crate::model::{Catalog, Model};

#[test]
fn authored_labpbr_emission_enters_the_ordinary_model_light_path_and_replaces_host_levels() {
    let state = State {
        id: 1,
        model: 1,
        ..Default::default()
    };
    for (alpha, level, expected) in [(254, 0, 1.5), (127, 15, 0.75), (0, 15, 0.0), (255, 15, 1.5)] {
        let mut q = face();
        q.sprite = 1;
        q.layer = 0;
        q.emission = level;
        let mut sprite = crate::sprite::Sprite {
            name: "test:lamp".into(),
            bounds: [0., 0., 1., 1.],
            extent: [1, 1],
            images: vec![crate::sprite::Image {
                width: 1,
                height: 1,
                pixels: vec![255; 4].into(),
            }],
            frames: vec![],
            interpolate: false,
            material: None,
        };
        let bytes: Vec<_> = [0_u32, 1, 1, 1, 1, u32::from_le_bytes([0, 4, 0, alpha])]
            .into_iter()
            .flat_map(u32::to_le_bytes)
            .collect();
        let pages = [bytes.as_slice()];
        sprite.material = Some(
            crate::labpbr::Material::read(&mut crate::wire::Reader::new(&pages).unwrap(), &sprite)
                .unwrap(),
        );
        let mut catalog = Catalog::default();
        catalog.sprites.insert(1, sprite);
        catalog.models.insert(1, Model::Mesh(vec![q]));
        catalog.states.insert(1, state.clone());
        catalog.prepare();
        let mut layers = Default::default();
        let mut surfaces = Default::default();
        catalog.emit(
            &state,
            [0; 3],
            127,
            &mut layers,
            &mut Default::default(),
            &mut Default::default(),
            &mut surfaces,
        );
        if expected == 0.0 {
            assert_eq!(layers[0].len(), 1);
            assert!(surfaces[0].is_empty());
        } else {
            assert!(layers[0].is_empty());
            assert_eq!(surfaces[0].len(), 1);
            assert_eq!(surfaces[0][0].emission.radiance, [expected; 3]);
            let mesh =
                prime_scene::surface::SurfaceMesh::from_resolved(1, surfaces[0].clone()).unwrap();
            assert_eq!(mesh.lights.emitters.len(), 1);
            assert_eq!(mesh.lights.emitters[0].power, expected);
        }
    }
}
fn face() -> Quad {
    Quad {
        sprite: 0,
        emission: 0,
        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
        face: 6,
        tint: -1,
        layer: 1,
    }
}
fn reverse(mut q: Quad) -> Quad {
    q.positions = [0, 3, 2, 1].map(|i| q.positions[i]);
    q.uvs = [0, 3, 2, 1].map(|i| q.uvs[i]);
    q
}
fn reverse_diagonal(mut q: Quad) -> Quad {
    q.positions.reverse();
    q.uvs.reverse();
    q
}
fn cross_sheet(other: bool) -> Quad {
    let mut q = face();
    q.positions = if other {
        [[1., 1., 0.], [1., 0., 0.], [0., 0., 1.], [0., 1., 1.]]
    } else {
        [[0., 1., 0.], [0., 0., 0.], [1., 0., 1.], [1., 1., 1.]]
    };
    q.uvs = [[0., 0.], [0., 1.], [1., 1.], [1., 0.]];
    q
}
fn uv_at(positions: [[f32; 3]; 4], uvs: [[f32; 2]; 4], p: [f64; 3]) -> [f64; 2] {
    let positions = positions.map(|p| p.map(f64::from));
    let dot = |a: [f64; 3], b: [f64; 3]| a.into_iter().zip(b).map(|(a, b)| a * b).sum::<f64>();
    for [a, b, c] in [[0, 1, 2], [2, 3, 0]] {
        let u = std::array::from_fn(|i| positions[b][i] - positions[a][i]);
        let v = std::array::from_fn(|i| positions[c][i] - positions[a][i]);
        let p = std::array::from_fn(|i| p[i] - positions[a][i]);
        let (uu, uv, vv, pu, pv) = (dot(u, u), dot(u, v), dot(v, v), dot(p, u), dot(p, v));
        let determinant = uu * vv - uv * uv;
        let x = (pu * vv - pv * uv) / determinant;
        let y = (pv * uu - pu * uv) / determinant;
        if x >= -1e-12 && y >= -1e-12 && x + y <= 1. + 1e-12 {
            return std::array::from_fn(|i| {
                f64::from(uvs[a][i]) * (1. - x - y)
                    + f64::from(uvs[b][i]) * x
                    + f64::from(uvs[c][i]) * y
            });
        }
    }
    panic!("query point is outside source triangles");
}
fn normal(positions: [[f32; 3]; 4]) -> [f32; 3] {
    let a: [f32; 3] = std::array::from_fn(|i| positions[1][i] - positions[0][i]);
    let b: [f32; 3] = std::array::from_fn(|i| positions[2][i] - positions[0][i]);
    std::array::from_fn(|i| a[(i + 1) % 3] * b[(i + 2) % 3] - a[(i + 2) % 3] * b[(i + 1) % 3])
}
fn assert_side_uvs(face: &SurfaceFace, source: &Quad) {
    let positions = source.positions.map(|p| [p[0] + 3., p[1] + 4., p[2] + 5.]);
    let front = normal(positions)
        .into_iter()
        .zip(normal(face.geometry.positions))
        .map(|(a, b)| a * b)
        .sum::<f32>()
        > 0.;
    let actual_uvs = if front {
        face.geometry.uvs
    } else {
        face.detail.as_ref().unwrap().layer.uvs
    };
    // Query both source halves and both possible diagonals, including their shared edges.
    for s in [0.125, 0.25, 0.5, 0.75, 0.875] {
        for t in [0.125, 0.25, 0.5, 0.75, 0.875] {
            let point = std::array::from_fn(|i| {
                f64::from(positions[0][i])
                    + s * (f64::from(positions[1][i]) - f64::from(positions[0][i]))
                    + t * (f64::from(positions[3][i]) - f64::from(positions[0][i]))
            });
            let expected = uv_at(positions, source.uvs, point);
            let actual = uv_at(face.geometry.positions, actual_uvs, point);
            assert!(
                (0..2).all(|i| (expected[i] - actual[i]).abs() < 1e-12),
                "side={front} point={point:?} source={expected:?} resolved={actual:?}"
            );
        }
    }
}
fn emit(
    c: &Catalog,
    name: &str,
    visible: u32,
) -> (Vec<CompiledQuad>, Vec<SurfaceFace>, crate::tint::Deferred) {
    let state = State {
        id: 7,
        name: name.into(),
        model: 1,
        ..Default::default()
    };
    let mut layers = Default::default();
    let mut surfaces = Default::default();
    let mut tints = crate::tint::Deferred::default();
    tints.begin(7, [3, 4, 5]);
    c.emit(
        &state,
        [3, 4, 5],
        visible,
        &mut layers,
        &mut Default::default(),
        &mut tints,
        &mut surfaces,
    );
    (
        layers.into_iter().flatten().collect(),
        surfaces.into_iter().flatten().collect(),
        tints,
    )
}
fn catalog(quads: Vec<Quad>) -> Catalog {
    let mut c = Catalog::default();
    c.models.insert(1, Model::Mesh(quads));
    c.prepare();
    c
}
#[test]
fn duplicates_reverse_and_known_inner_shell_are_prepared_once_with_negative_cases() {
    let a = face();
    let mut c = catalog(vec![a.clone(), a.clone(), reverse(a.clone())]);
    assert_eq!(emit(&c, "test:sheet", 127).0.len(), 1);
    let first = c.prepared_for_test(1);
    c.prepare();
    assert_eq!(first, c.prepared_for_test(1));
    let mut inner = reverse(a.clone());
    for p in &mut inner.positions {
        for x in p {
            *x = if *x == 0. {
                0.002 / 16.
            } else {
                1. - 0.002 / 16.
            };
        }
    }
    assert_eq!(
        emit(&catalog(vec![a.clone(), inner.clone()]), "test:sheet", 127)
            .1
            .len(),
        1
    );
    inner.positions[0][2] += 0.00001;
    assert_eq!(
        emit(&catalog(vec![a.clone(), inner]), "test:sheet", 127)
            .0
            .len(),
        2
    );
    let mut warped = a.clone();
    warped.positions[2][2] = 0.1;
    let mut other = reverse(warped.clone());
    other.positions.rotate_left(1);
    other.uvs.rotate_left(1);
    assert_eq!(
        emit(&catalog(vec![warped, other]), "test:sheet", 127)
            .0
            .len(),
        2
    );
}
#[test]
fn independent_cull_conditions_and_side_uvs_survive_definition_preparation() {
    let mut a = face();
    a.face = 2;
    a.tint = 0;
    let mut b = reverse(a.clone());
    b.face = 3;
    b.tint = 1;
    b.uvs[0][0] = 0.25;
    let c = catalog(vec![a.clone(), b.clone()]);
    for visible in [1 << 2, 1 << 3] {
        let (plain, rich, tints) = emit(&c, "test:flower", visible);
        assert_eq!(plain.len(), 1);
        assert!(rich.is_empty());
        assert_eq!(tints.requests.len(), 1);
    }
    let (plain, rich, tints) = emit(&c, "test:flower", 127);
    assert!(plain.is_empty());
    assert_eq!(rich.len(), 1);
    assert_eq!(tints.requests.len(), 2);
    let d = rich[0].detail.as_ref().unwrap();
    assert_eq!(d.mode, LayerMode::Bilateral);
    assert_eq!(d.layer.uvs[0], [0.25, 0.]);
    assert_eq!(rich[0].geometry.uvs[0], [0., 0.]);
}
#[test]
fn affine_cross_sides_keep_source_uvs_across_diagonals_rotations_and_order() {
    for first_rotation in 0..4 {
        for second_rotation in 0..4 {
            for swap in [false, true] {
                let mut quads = Vec::new();
                for other in [false, true] {
                    let mut a = cross_sheet(other);
                    let mut b = reverse_diagonal(a.clone());
                    // Vanilla cross faces use the same numbered UV corners on opposite windings:
                    // their physical U maps differ, so they must remain independent side fields.
                    b.uvs = a.uvs;
                    a.positions.rotate_left(first_rotation);
                    a.uvs.rotate_left(first_rotation);
                    b.positions.rotate_left(second_rotation);
                    b.uvs.rotate_left(second_rotation);
                    quads.extend(if swap { [b, a] } else { [a, b] });
                }
                let c = catalog(quads.clone());
                let (plain, rich, _) = emit(&c, "test:cross", 127);
                assert!(plain.is_empty());
                assert_eq!(rich.len(), 2);
                for (face, sources) in rich.iter().zip(quads.as_chunks::<2>().0) {
                    assert_eq!(face.detail.as_ref().unwrap().mode, LayerMode::Bilateral);
                    for source in sources {
                        assert_side_uvs(face, source);
                    }
                    assert_ne!(face.geometry.uvs, face.detail.as_ref().unwrap().layer.uvs);
                }
            }
        }
    }
}
#[test]
fn affine_odd_diagonal_pairs_keep_independent_activation_and_tints() {
    let mut a = cross_sheet(false);
    a.face = 2;
    a.tint = 0;
    let mut b = reverse_diagonal(a.clone());
    b.uvs = a.uvs;
    b.face = 3;
    b.tint = 1;
    let c = catalog(vec![a.clone(), b.clone()]);
    for source in [&a, &b] {
        let (plain, rich, tints) = emit(&c, "test:cross", 1 << source.face);
        assert_eq!(plain.len(), 1);
        assert!(rich.is_empty());
        assert_eq!(plain[0].uvs, source.uvs);
        assert_eq!(tints.requests.len(), 1);
        assert_eq!(tints.requests[0].slot, source.tint);
    }
    let (plain, rich, tints) = emit(&c, "test:cross", 127);
    assert!(plain.is_empty());
    assert_eq!(rich.len(), 1);
    assert_eq!(tints.requests.len(), 2);
    assert_side_uvs(&rich[0], &a);
    assert_side_uvs(&rich[0], &b);
}
#[test]
fn equivalent_affine_odd_diagonals_collapse_without_a_second_layer() {
    let a = cross_sheet(false);
    let c = catalog(vec![a.clone(), reverse_diagonal(a.clone())]);
    let (plain, rich, _) = emit(&c, "test:sheet", 127);
    assert_eq!(plain.len(), 1);
    assert!(rich.is_empty());
    assert_eq!(plain[0].uvs, a.uvs);
}
#[test]
fn odd_diagonal_proofs_reject_nonaffine_geometry_uvs_and_degenerate_sheets() {
    let a = face();
    let mut cases = Vec::new();
    let mut warped = a.clone();
    warped.positions[2][2] = 0.125;
    cases.push(("nonplanar", warped));
    let mut trapezoid = a.clone();
    trapezoid.positions[2][0] = 0.75;
    cases.push(("trapezoid", trapezoid));
    let mut concave = a.clone();
    concave.positions[2] = [0.25, 0.25, 0.];
    cases.push(("concave", concave));
    let mut collinear = a.clone();
    collinear.positions = [[0., 0., 0.], [1., 1., 1.], [3., 3., 3.], [2., 2., 2.]];
    cases.push(("collinear parallelogram", collinear));
    let mut repeated = a.clone();
    repeated.positions = [[0., 0., 0.], [1., 0., 0.], [2., 0., 0.], [1., 0., 0.]];
    cases.push(("repeated corner", repeated));
    let mut nonaffine = a.clone();
    nonaffine.uvs[2][0] = 0.75;
    cases.push(("nonaffine UV", nonaffine));
    let tiny = 2.0_f32.powi(-100);
    let mut rounded_geometry = a.clone();
    rounded_geometry.positions = [[1., 0., 0.], [0., 0., 0.], [-tiny, 1., 0.], [1., 1., 0.]];
    cases.push(("f64-rounded geometry sum", rounded_geometry));
    let mut rounded_uv = a.clone();
    rounded_uv.uvs = [[1., 0.], [1., 0.], [tiny, 1.], [0., 1.]];
    cases.push(("f64-rounded UV sum", rounded_uv));
    for (label, source) in cases {
        let c = catalog(vec![source.clone(), reverse_diagonal(source)]);
        let (plain, rich, _) = emit(&c, "test:sheet", 127);
        assert_eq!(plain.len(), 2, "{label}");
        assert!(rich.is_empty(), "{label}");
    }
    // Both sides must prove their own affine UVs, even when the other source is regular.
    for bad_side in [0, 1] {
        let mut pair = [a.clone(), reverse_diagonal(a.clone())];
        pair[bad_side].uvs[2][0] = 0.75;
        let (plain, rich, _) = emit(&catalog(pair.into()), "test:sheet", 127);
        assert_eq!(plain.len(), 2, "nonaffine side {bad_side}");
        assert!(rich.is_empty());
    }
}
#[test]
fn nondegenerate_projection_preserves_tiny_areas_without_inventing_collinear_area() {
    let tiny = 2.0_f32.powi(-100);
    let positions = [
        [1., 3., 0.],
        [tiny, 3. * tiny, 0.],
        [-1., -3., 0.],
        [-tiny, -3. * tiny, 0.],
    ];
    assert!(affine(positions));
    assert!(!nondegenerate(positions));
    let thin = [
        [0., 0., 0.],
        [1., 1., 0.],
        [2., 2. + 2.0_f32.powi(-22), 0.],
        [1., 1. + 2.0_f32.powi(-22), 0.],
    ];
    assert!(affine(thin));
    assert!(nondegenerate(thin));
}
#[test]
fn state_bound_overlay_keeps_each_tint_and_does_not_pollute_shared_model() {
    let mut base = face();
    base.layer = 0;
    let mut top = base.clone();
    top.tint = 0;
    top.layer = 1;
    top.uvs = [[0.25, 0.5]; 4];
    let c = catalog(vec![base, top]);
    let (plain, rich, _) = emit(&c, "test:ordinary", 127);
    assert_eq!(plain.len(), 2);
    assert!(rich.is_empty());
    let (plain, rich, tints) = emit(&c, "minecraft:grass_block", 127);
    assert!(plain.is_empty());
    assert_eq!(rich[0].flags(), 0);
    let mut surfaces = [rich, vec![], vec![]];
    let mut layers = Default::default();
    tints.apply(&[0xff40a080], &mut layers, &mut surfaces);
    assert_eq!(surfaces[0][0].geometry.colors, [[1.; 4]; 4]);
    assert_eq!(
        surfaces[0][0].detail.as_ref().unwrap().layer.colors[0],
        [64. / 255., 160. / 255., 128. / 255., 1.]
    );
    let mut solid_torch = face();
    solid_torch.layer = 0;
    assert_eq!(
        flags(
            &State {
                name: "minecraft:redstone_torch".into(),
                ..Default::default()
            },
            &solid_torch
        ),
        1
    );
}

#[test]
fn deterministic_multipart_prepares_cross_child_relations_but_weighted_keeps_selection() {
    let a = face();
    let mut b = reverse(a.clone());
    b.sprite = 2;
    let mut c = Catalog::default();
    c.models.insert(1, Model::Multipart(vec![2, 3]));
    c.models.insert(2, Model::Mesh(vec![a]));
    c.models.insert(3, Model::Alias(4));
    c.models.insert(4, Model::Mesh(vec![b]));
    c.states.insert(
        7,
        State {
            id: 7,
            model: 1,
            ..Default::default()
        },
    );
    c.prepare();
    let (plain, rich, _) = emit(&c, "test:multipart", 127);
    assert!(plain.is_empty());
    assert_eq!(rich.len(), 1);
    assert_eq!(rich[0].detail.as_ref().unwrap().mode, LayerMode::Bilateral);
    let prepared = c.prepared_for_test(1);
    c.prepare();
    assert_eq!(c.prepared_for_test(1), prepared);

    let mut selected = Catalog::default();
    selected.models.insert(1, Model::Multipart(vec![2, 3]));
    selected.models.insert(2, Model::Mesh(vec![face()]));
    selected.models.insert(3, Model::Weighted(vec![(1, 4)], 1));
    selected
        .models
        .insert(4, Model::Mesh(vec![reverse(face())]));
    selected.states.insert(
        7,
        State {
            id: 7,
            model: 1,
            ..Default::default()
        },
    );
    selected.prepare();
    let (plain, rich, _) = emit(&selected, "test:weighted", 127);
    assert_eq!(plain.len(), 2);
    assert!(rich.is_empty());
}
