use super::*;
use prime_scene::{
    Triangle,
    geometry::{CompiledQuad, MeshGeometry, QuadFragments},
    surface::RepeatUv,
};
use std::sync::Arc;

fn quad() -> CompiledQuad {
    CompiledQuad {
        positions: [[-0., 0., 0.], [1., 0., 0.], [1., 1., 0.25], [0., 1., 0.]],
        uvs: [[-0., 0.], [1., 0.125], [0.75, 1.], [-0.25, 0.5]],
        color: [0.125, 0.5, 0.75, 1.],
        texture_id: 7,
        flags: 1,
    }
}

#[test]
fn optical_reference_uses_existing_padding_and_keeps_repeat_coordinates() {
    use prime_scene::surface::{Medium, Optics, SurfaceFace};
    let mut face = SurfaceFace::from_quad(quad());
    face.repeat = Some(RepeatUv {
        origin: [0.25, 0.5],
        du: [0.125, 0.],
        dv: [0., 0.25],
        axes: 3,
    });
    face.optics = Some(Optics {
        negative: Medium::default(),
        positive: Medium::default(),
        ior_textures: [Some(7), Some(90)],
        transmit: false,
        thin: false,
    });
    let textures = BTreeMap::from([(7, 3), (90, 11)]);
    let packed = encode(&face, 2, None, None, &textures).unwrap();
    assert_eq!(u32::from_le_bytes(packed[188..192].try_into().unwrap()), 11);
    for (at, expected) in [
        (176, 0.125f32),
        (180, 0.),
        (184, 0.25),
        (192, 0.),
        (196, 0.25),
        (200, 0.5),
    ] {
        assert_eq!(
            f32::from_le_bytes(packed[at..at + 4].try_into().unwrap()),
            expected
        );
    }
    assert!(encode(&face, 2, None, None, &BTreeMap::from([(7, 3)])).is_err());
    face.optics.as_mut().unwrap().ior_textures = [None; 2];
    let packed = encode(&face, 2, None, None, &textures).unwrap();
    assert_eq!(packed[188..192], [0; 4]);
}
fn input(triangles: TriangleView<'_>) -> Input<'_> {
    Input {
        triangles,
        offset: None,
        flags: None,
    }
}
fn bytes(plan: &Plan<'_>, threads: usize) -> Vec<u8> {
    let mut result = vec![0; plan.bytes()];
    plan.pack_bytes(
        &CpuWorkers::new(threads).unwrap(),
        &mut result,
        &BTreeMap::from([(7, 3)]),
    )
    .unwrap();
    result
}
fn decode(data: &[u8], half: usize) -> Triangle {
    let f = |at| f32::from_le_bytes(data[at..at + 4].try_into().unwrap());
    let indices = [[0, 1, 2], [2, 3, 0]][half];
    Triangle {
        positions: indices.map(|i| std::array::from_fn(|a| f(i * 16 + a * 4))),
        colors: indices.map(|i| {
            let start = 64 + i * 16;
            std::array::from_fn(|a| f(start + a * 4))
        }),
        uvs: indices.map(|i| [f(128 + i * 8), f(132 + i * 8)]),
        texture_id: u32::from_le_bytes(data[160..164].try_into().unwrap()),
        flags: u32::from_le_bytes(data[164..168].try_into().unwrap()),
    }
}
fn exact(actual: Triangle, mut expected: Triangle) {
    expected.texture_id = 3;
    assert_eq!(
        actual.positions.map(|p| p.map(f32::to_bits)),
        expected.positions.map(|p| p.map(f32::to_bits))
    );
    assert_eq!(
        actual.colors.map(|p| p.map(f32::to_bits)),
        expected.colors.map(|p| p.map(f32::to_bits))
    );
    assert_eq!(
        actual.uvs.map(|p| p.map(f32::to_bits)),
        expected.uvs.map(|p| p.map(f32::to_bits))
    );
    assert_eq!(
        (actual.texture_id, actual.flags),
        (expected.texture_id, expected.flags)
    );
}

#[test]
fn unstructured_pairs_and_independent_triangles_preserve_every_corner_bit() {
    let mut q: Quad = quad().into();
    q.colors[1] = [0.13, 0.27, 0.53, 0.71];
    q.colors[3] = [0.83, 0.19, 0.31, 0.97];
    let mut independent = q.triangle(0);
    independent.positions[0][2] = -3.;
    let source = [q.triangle(0), q.triangle(1), independent];
    for split_formats in [false, true] {
        let plan = Plan::new([input((&source[..]).into())], split_formats).unwrap();
        assert_eq!(
            (plan.groups[0].format, plan.groups[0].count, plan.bytes()),
            (0, 2, 352)
        );
        let packed = bytes(&plan, 4);
        exact(decode(&packed, 0), source[0]);
        exact(decode(&packed, 1), source[1]);
        exact(decode(&packed[176..], 0), independent);
        let degenerate = decode(&packed[176..], 1);
        assert_eq!(degenerate.positions[0], degenerate.positions[1]);
        assert_eq!(degenerate.colors[0], degenerate.colors[1]);
        assert_eq!(degenerate.uvs[0], degenerate.uvs[1]);
    }
    let mut discontinuous = source[..2].to_vec();
    discontinuous[1].uvs[0][0] += 0.125;
    let split = Plan::new([input(discontinuous.as_slice().into())], false).unwrap();
    assert_eq!(split.groups[0].count, 2);
    let packed = bytes(&split, 1);
    for (i, t) in discontinuous.into_iter().enumerate() {
        exact(decode(&packed[i * 176..], 0), t);
    }
}

#[test]
fn partial_quad_views_and_fragment_worker_boundaries_keep_only_consumed_halves() {
    let q = quad();
    let geometry = MeshGeometry::QuadFragments(Arc::new(QuadFragments::new([
        vec![q; 4097],
        vec![],
        vec![q; 5],
    ])));
    for range in [0..8204, 1..8203, 1..2, 0..1, 8193..8194, 4..4] {
        let plan = Plan::new([input(geometry.view(range.clone()))], true).unwrap();
        let packed = bytes(&plan, 1);
        assert_eq!(packed, bytes(&plan, 4));
        let mut actual = Vec::new();
        for record in packed.as_chunks::<176>().0 {
            for half in 0..2 {
                let t = decode(record, half);
                if t.positions[0] == t.positions[1] || t.positions[1] == t.positions[2] {
                    continue;
                }
                actual.push(t);
            }
        }
        assert_eq!(actual.len(), range.len());
        for (t, i) in actual.into_iter().zip(range) {
            exact(t, geometry.triangle(i));
        }
    }
}

#[test]
fn partial_views_validate_only_consumed_corners_and_join_after_late_fragment_errors() {
    let mut q = quad();
    q.positions[3][0] = f32::NAN;
    q.uvs[3][1] = f32::INFINITY;
    let source = MeshGeometry::Quads(vec![q].into());
    let textures = BTreeMap::from([(7, 3)]);
    for range in [0..1, 1..2, 0..2] {
        let plan = Plan::new([input(source.view(range.clone()))], true).unwrap();
        let result = plan.pack_bytes(
            &CpuWorkers::new(4).unwrap(),
            &mut vec![0; plan.bytes()],
            &textures,
        );
        assert_eq!(result.is_ok(), range == (0..1));
    }
    let fragments =
        MeshGeometry::QuadFragments(Arc::new(QuadFragments::new([vec![quad(); 8192], vec![q]])));
    let plan = Plan::new([input(fragments.view(0..fragments.len()))], true).unwrap();
    for threads in [1, 4] {
        assert!(
            plan.pack_bytes(
                &CpuWorkers::new(threads).unwrap(),
                &mut vec![0; plan.bytes()],
                &textures,
            )
            .is_err()
        );
    }
}

#[test]
fn real_field_requirements_split_plain_and_extended_ranges_without_promotion() {
    let plain = closed(quad().into());
    let mut colored = plain.clone();
    colored.geometry.colors[2][0] = 0.37;
    let mut rich = plain.clone();
    rich.repeat = Some(RepeatUv {
        axes: 3,
        origin: [0.25, 0.5],
        du: [0.5, 0.],
        dv: [0., 0.125],
    });
    let mut both = rich.clone();
    both.geometry = colored.geometry;
    let faces = [rich, plain, both, colored];
    let view = TriangleView::Surfaces {
        values: &faces,
        first: 0,
        count: 8,
    };
    let plan = Plan::new([input(view)], true).unwrap();
    assert_eq!(
        plan.groups
            .iter()
            .map(|g| (g.format, g.count))
            .collect::<Vec<_>>(),
        vec![(0, 2), (1, 2)]
    );
    assert_eq!(plan.bytes(), 176 * 2 + 240 * 2);
    let packed = bytes(&plan, 2);
    let mut offset = 0;
    for (format, index) in [(0, 1), (0, 3), (1, 0), (1, 2)] {
        exact(
            decode(&packed[offset..], 0),
            faces[index].geometry.triangle(0),
        );
        exact(
            decode(&packed[offset..], 1),
            faces[index].geometry.triangle(1),
        );
        if format != 0 {
            let extra = offset + 176;
            let f = |i| f32::from_le_bytes(packed[i..i + 4].try_into().unwrap());
            assert_eq!([f(extra), f(extra + 4), f(extra + 8)], [0.5, 0., 0.25]);
            assert_eq!(
                u32::from_le_bytes(packed[extra + 60..extra + 64].try_into().unwrap()),
                13
            );
        }
        offset += stride(format);
    }
}

#[test]
fn source_validation_and_offset_apply_before_any_gpu_publication() {
    let workers = CpuWorkers::new(2).unwrap();
    let textures = BTreeMap::from([(7, 3)]);
    let q = quad();
    for kind in 0..6 {
        let mut q = q;
        match kind {
            0 => q.positions[3][1] = f32::NAN,
            1 => q.color[0] = 1.5,
            2 => q.uvs[3][0] = f32::INFINITY,
            3 => q.texture_id = 9,
            4 => {}
            5 => q.positions[0][0] = f32::MAX,
            _ => unreachable!(),
        }
        let source = [q];
        let plan = Plan::new(
            [Input {
                triangles: TriangleView::Quads {
                    values: &source,
                    first: 0,
                    count: 2,
                },
                offset: Some(if kind == 5 {
                    [f32::MAX; 3]
                } else {
                    [16., 0., 0.]
                }),
                flags: Some(if kind == 4 { 0 } else { 1 }),
            }],
            true,
        )
        .unwrap();
        assert!(
            plan.pack_bytes(&workers, &mut vec![0; plan.bytes()], &textures)
                .is_err()
        );
    }
    let source = [q];
    let plan = Plan::new(
        [Input {
            triangles: TriangleView::Quads {
                values: &source,
                first: 0,
                count: 2,
            },
            offset: Some([16., 2., -1.]),
            flags: Some(1),
        }],
        true,
    )
    .unwrap();
    let packed = bytes(&plan, 2);
    let mut expected = q.triangle(1);
    for p in &mut expected.positions {
        for a in 0..3 {
            p[a] += [16., 2., -1.][a];
        }
    }
    exact(decode(&packed, 1), expected);
}
