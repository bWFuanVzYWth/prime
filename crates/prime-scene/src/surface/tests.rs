use super::*;

fn quad(x: f32, y: f32) -> SurfaceQuad {
    SurfaceQuad::from_closed(
        CompiledQuad {
            positions: [
                [x, y, 0.0],
                [x + 1.0, y, 0.0],
                [x + 1.0, y + 1.0, 0.0],
                [x, y + 1.0, 0.0],
            ],
            uvs: [[0.25, 0.5], [0.5, 0.5], [0.5, 0.625], [0.25, 0.625]],
            color: [0.25, 0.5, 0.75, 1.0],
            texture_id: 0x1234_0001,
            flags: 0,
        },
        Provenance {
            domain: 1,
            source: (x as i64 + 512 + (y as i64 + 512) * 2048) as u64,
        },
    )
}

#[test]
fn optical_reference_identity_prevents_merge_and_survives_exact_clipping() {
    let mut a = SurfaceFace::from_quad(quad(0., 0.).geometry);
    a.optics = Some(Optics {
        negative: Medium::default(),
        positive: Medium::default(),
        ior_textures: [Some(7), Some(20)],
        transmit: true,
        thin: false,
    });
    let mut b = a.clone();
    b.optics.as_mut().unwrap().ior_textures[1] = Some(90);
    let merged = SurfaceCompiler::new()
        .merge_resolved(vec![a.clone(), b])
        .unwrap();
    assert_eq!(
        merged.len(),
        2,
        "distinct IOR resource owners are distinct material labels"
    );
    let owners: std::collections::BTreeSet<_> = merged
        .iter()
        .map(|face| face.optics.unwrap().ior_textures)
        .collect();
    assert_eq!(owners.len(), 2);
    let clipped = clip_rectangle(
        &a,
        Rectangle {
            axis: 2,
            plane: 0.,
            axes: [0, 1],
            bounds: [0.125, 0.25, 0.875, 0.75],
        },
    );
    assert!(!clipped.is_empty());
    assert!(clipped.iter().all(|face| face.optics == a.optics));
}

fn point_uv(t: &SurfaceFace, point: [f32; 2]) -> Option<[f32; 2]> {
    (0..2).find_map(|half| {
        let triangle = t.geometry.triangle(half);
        let p = triangle.positions;
        let a = [p[1][0] - p[0][0], p[1][1] - p[0][1]];
        let b = [p[2][0] - p[0][0], p[2][1] - p[0][1]];
        let q = [point[0] - p[0][0], point[1] - p[0][1]];
        let det = a[0] * b[1] - a[1] * b[0];
        if det == 0.0 {
            return None;
        }
        let u = (q[0] * b[1] - q[1] * b[0]) / det;
        let v = (a[0] * q[1] - a[1] * q[0]) / det;
        if u < 0.0 || v < 0.0 || u + v > 1.0 {
            return None;
        }
        let uv = std::array::from_fn(|i| {
            triangle.uvs[0][i] * (1.0 - u - v) + triangle.uvs[1][i] * u + triangle.uvs[2][i] * v
        });
        Some(t.repeat.map_or(uv, |m| m.evaluate(uv)))
    })
}

#[test]
fn cutout_square_templates_preserve_sampling_and_cover_irregular_rectangles_once() {
    let input: Vec<_> = (0..7)
        .flat_map(|y| {
            (0..11).map(move |x| {
                let mut q = quad(x as f32, y as f32);
                q.geometry.flags = 1;
                q.geometry.uvs = [[0., 0.], [1., 0.], [1., 1.], [0., 1.]];
                q
            })
        })
        .collect();
    let mut compiler = SurfaceCompiler::new();
    compiler.set_cutout_squares(true);
    let output = compiler.compile(1, &input).unwrap();
    assert!(output.quads.len() > 1);
    for face in &output.quads {
        let xs = face.geometry.positions.map(|p| p[0]);
        let ys = face.geometry.positions.map(|p| p[1]);
        let extent = |v: [f32; 4]| {
            v.into_iter().fold(f32::NEG_INFINITY, f32::max)
                - v.into_iter().fold(f32::INFINITY, f32::min)
        };
        assert_eq!(extent(xs), extent(ys));
        assert!([1., 2., 4.].contains(&extent(xs)));
    }
    for y in 0..7 {
        for x in 0..11 {
            for [u, v] in [[0.23, 0.61], [0.79, 0.13]] {
                let actual: Vec<_> = output
                    .quads
                    .iter()
                    .filter_map(|f| point_uv(f, [x as f32 + u, y as f32 + v]))
                    .collect();
                assert_eq!(actual.len(), 1, "square cover overlap or hole at {x},{y}");
                let expected = [u, v];
                for (actual, expected) in actual[0].into_iter().zip(expected) {
                    assert!((actual - expected).abs() < 1e-6);
                }
            }
        }
    }
    compiler.set_cutout_squares(false);
    assert_eq!(compiler.compile(2, &input).unwrap().quads.len(), 1);
    let mut opaque = input;
    for face in &mut opaque {
        face.geometry.flags = 0;
    }
    compiler.set_cutout_squares(true);
    assert_eq!(compiler.compile(3, &opaque).unwrap().quads.len(), 1);
    for face in &mut opaque {
        face.geometry.flags = 1;
        face.geometry.uvs = [[0.25, 0.5], [0.5, 0.5], [0.5, 0.625], [0.25, 0.625]];
    }
    assert_eq!(
        compiler.compile(4, &opaque).unwrap().quads.len(),
        1,
        "unsupported crop retains ordinary optimal merging"
    );
}

#[test]
fn full_plane_becomes_two_hardware_triangles_with_repeated_atlas_sampling() {
    let input: Vec<_> = (0..64)
        .flat_map(|y| (0..64).map(move |x| quad(x as f32, y as f32)))
        .collect();
    let output = SurfaceCompiler::new().compile(37, &input).unwrap();
    assert_eq!(output.revision, 37);
    assert_eq!(output.quads.len(), 1);
    assert_eq!(output.stats.grid_quads, 4096);
    assert_eq!(output.stats.rectangles, 1);
    assert!(output.lights.nodes.is_empty());
    for y in 0..64 {
        for x in 0..64 {
            for [u, v] in [[0.125, 0.25], [0.25, 0.875], [0.875, 0.625]] {
                let point = [x as f32 + u, y as f32 + v];
                let uv = output
                    .quads
                    .iter()
                    .find_map(|t| point_uv(t, point))
                    .unwrap();
                // Independent original unit-face map; no output mapping reused as oracle.
                let expected = [0.25 + u * 0.25, 0.5 + v * 0.125];
                assert_eq!(uv, expected);
                assert_eq!(output.quads[0].geometry.texture_id, 0x1234_0001);
            }
        }
    }
}

#[test]
fn complete_direction_merges_slab_trapdoor_and_stacked_post_sides() {
    for width in [0.5, 3. / 16., 2. / 16.] {
        for direction in 0..2 {
            for axis in 0..3 {
                for reverse in [false, true] {
                    let mut input = Vec::new();
                    for n in 0..32 {
                        let mut q = quad(0., 0.);
                        for p in &mut q.geometry.positions {
                            p[direction] += -32. + n as f32;
                            p[direction ^ 1] = p[direction ^ 1] * width + 0.375;
                        }
                        // Crop the narrow direction rather than rescaling its texture.
                        for uv in &mut q.geometry.uvs {
                            uv[direction ^ 1] *= width;
                        }
                        if reverse {
                            q.geometry.positions.reverse();
                            q.geometry.uvs.reverse();
                        }
                        q.geometry.positions = q.geometry.positions.map(|p| {
                            let mut out = [0.; 3];
                            out[axis] = 0.125;
                            out[(axis + 1) % 3] = p[0];
                            out[(axis + 2) % 3] = p[1];
                            out
                        });
                        input.push(q);
                    }
                    let result = SurfaceCompiler::new().compile(1, &input).unwrap();
                    assert_eq!(
                        result.quads.len(),
                        1,
                        "width={width}, direction={direction}, axis={axis}"
                    );
                    let mut out = result.quads[0].clone();
                    out.geometry.positions = out
                        .geometry
                        .positions
                        .map(|p| [p[(axis + 1) % 3], p[(axis + 2) % 3], 0.]);
                    for n in 0..32 {
                        for f in [0.125, 0.625, 0.875] {
                            let mut point = [0.; 2];
                            point[direction] = -32. + n as f32 + f;
                            point[direction ^ 1] = 0.375 + width * 0.75;
                            let uv = point_uv(&out, point).unwrap();
                            let mut t = [0.; 2];
                            t[direction] = f;
                            t[direction ^ 1] = 0.75;
                            let mut expected = [0.25 + 0.25 * t[0], 0.5 + 0.125 * t[1]];
                            expected[direction ^ 1] *= width;
                            assert_eq!(uv, expected);
                        }
                    }
                    let repeat = out.repeat.unwrap();
                    assert_eq!(repeat.axes, 1 << direction);
                    // The narrow edge endpoint remains t=1, not frac(1)=0.
                    let mut edge = [0.25; 2];
                    edge[direction ^ 1] = 1.;
                    assert_ne!(repeat.evaluate(edge), repeat.evaluate([0.25, 0.25]));
                }
            }
        }
    }
}

#[test]
fn contact_clipping_preserves_constant_tint_exactly_at_fractional_boundaries() {
    let mut q = quad(0., 0.).geometry;
    q.color = [1.; 4];
    let source = SurfaceFace::from_quad(q);
    for x in 1..37 {
        for y in 1..41 {
            let mut patch = source.clone();
            for p in &mut patch.geometry.positions {
                p[0] *= x as f32 / 37.;
                p[1] *= y as f32 / 41.;
            }
            let pairs = intersect_surfaces(&patch, &source).unwrap();
            assert!(!pairs.is_empty());
            for (face, layer) in pairs {
                assert_eq!(face.geometry.colors, [[1.; 4]; 4]);
                assert_eq!(layer.colors, [[1.; 4]; 4], "{x}, {y}");
            }
        }
    }
}

#[test]
fn contact_clipping_keeps_gradient_tints_in_the_source_range() {
    let mut source = SurfaceFace::from_quad(quad(0., 0.).geometry);
    for mask in 1..15u32 {
        source.geometry.colors = std::array::from_fn(|i| [((mask >> i) & 1) as f32; 4]);
        for x in 1..37 {
            for y in 1..41 {
                let mut patch = source.clone();
                for p in &mut patch.geometry.positions {
                    p[0] *= x as f32 / 37.;
                    p[1] *= y as f32 / 41.;
                }
                for (face, layer) in intersect_surfaces(&patch, &source).unwrap() {
                    for color in face.geometry.colors.iter().chain(&layer.colors) {
                        assert!(
                            color.iter().all(|v| (0. ..=1.).contains(v)),
                            "mask={mask} x={x} y={y}: {color:?}"
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn partial_strips_preserve_gaps_overlaps_material_changes_and_non_affine_uv() {
    let slab = |x| {
        let mut q = quad(x, 0.);
        for p in &mut q.geometry.positions {
            p[1] *= 0.5;
        }
        q
    };
    let mut input = vec![slab(0.), slab(1.), slab(3.), slab(4.)];
    let mut compiler = SurfaceCompiler::new();
    assert_eq!(compiler.compile(1, &input).unwrap().quads.len(), 2);
    input[1].geometry.color[0] = 0.;
    assert_eq!(compiler.compile(1, &input).unwrap().quads.len(), 3);
    input[3].geometry.uvs[2][0] += 0.125;
    assert_eq!(compiler.compile(1, &input).unwrap().quads.len(), 4);
    input = vec![slab(0.), slab(0.), slab(1.)];
    assert_eq!(compiler.compile(1, &input).unwrap().quads.len(), 3);
    input = vec![slab(0.), slab(0.5), slab(1.)];
    assert_eq!(compiler.compile(1, &input).unwrap().quads.len(), 3);
}

#[test]
fn sparse_holes_and_labels_preserve_exact_coverage_and_each_material() {
    let input: Vec<_> = (0..16)
        .flat_map(|y| {
            (0..16).filter_map(move |x| {
                if (x * 13 + y * 7) % 11 < 3 {
                    return None;
                }
                let mut q = quad(x as f32, y as f32);
                q.geometry.texture_id = 70_000 + (x / 4 + y / 3) % 3;
                q.geometry.color[0] = ((x / 7) % 2) as f32;
                Some(q)
            })
        })
        .collect();
    let out = SurfaceCompiler::new().compile(1, &input).unwrap();
    for y in 0..16 {
        for x in 0..16 {
            let hits: Vec<_> = out
                .quads
                .iter()
                .filter(|t| point_uv(t, [x as f32 + 0.375, y as f32 + 0.625]).is_some())
                .collect();
            let source = input
                .iter()
                .find(|q| q.geometry.positions[0] == [x as f32, y as f32, 0.0]);
            if let Some(q) = source {
                assert!(!hits.is_empty());
                for t in hits {
                    assert_eq!(t.geometry.texture_id, q.geometry.texture_id);
                    assert_eq!(t.geometry.colors, [q.geometry.color; 4]);
                }
            } else {
                assert!(hits.is_empty());
            }
        }
    }
}

#[test]
fn all_axes_windings_uv_orientations_and_negative_tiles_are_supported() {
    for axis in 0..3 {
        for reverse in [false, true] {
            for rotation in 0..4 {
                let input: Vec<_> = (0..4)
                    .map(|i| {
                        let mut q = quad(-64.0 + i as f32, -1.0);
                        let transform = |p: [f32; 3]| {
                            let mut r = [0.0; 3];
                            r[axis] = -0.125;
                            r[(axis + 1) % 3] = p[0];
                            r[(axis + 2) % 3] = p[1];
                            r
                        };
                        q.geometry.positions = q.geometry.positions.map(transform);
                        q.geometry.uvs.rotate_left(rotation);
                        if reverse {
                            q.geometry.positions.reverse();
                            q.geometry.uvs.reverse();
                        }
                        q
                    })
                    .collect();
                let out = SurfaceCompiler::new().compile(0, &input).unwrap();
                assert_eq!(
                    out.quads.len(),
                    1,
                    "axis={axis} reverse={reverse} rotation={rotation}"
                );
                let p = out.quads[0].geometry.positions;
                let a = (axis + 1) % 3;
                let b = (axis + 2) % 3;
                let normal = (p[1][a] - p[0][a]) * (p[2][b] - p[0][b])
                    - (p[1][b] - p[0][b]) * (p[2][a] - p[0][a]);
                assert_eq!(normal < 0.0, reverse);
                assert!(
                    out.quads.iter().all(|t| t
                        .geometry
                        .positions
                        .iter()
                        .all(|p| p[axis] == -0.125))
                );
            }
        }
    }
}

#[test]
fn unknown_layers_non_affine_uv_thin_shells_and_motion_domains_are_not_guessed() {
    let mut input = vec![quad(0.0, 0.0), quad(1.0, 0.0)];
    input[1].provenance.domain = 2;
    let mut compiler = SurfaceCompiler::new();
    assert_eq!(compiler.compile(0, &input).unwrap().quads.len(), 2);
    input[1] = input[0].clone();
    input[1].geometry.color[0] = 0.125; // coincident but potentially meaningful coverage layer
    assert_eq!(
        compiler.compile(0, &input).unwrap().stats.passthrough_quads,
        2
    );
    input[1]
        .geometry
        .positions
        .iter_mut()
        .for_each(|p| p[2] += 0.000_001);
    assert_eq!(compiler.compile(0, &input).unwrap().quads.len(), 2);
    input = vec![quad(0.0, 0.0), quad(1.0, 0.0)];
    input[1].geometry.uvs[2][0] += 0.125; // two individually affine triangles, not one affine quad
    let out = compiler.compile(0, &input).unwrap();
    assert_eq!(out.stats.passthrough_quads, 1);
    assert_eq!(out.quads.len(), 2);
    // An f64-only equality would silently round 1 - 2^-100 to 1 and invent a new UV map.
    for q in &mut input {
        q.geometry.uvs = [
            [2_f32.powi(-100), 0.],
            [1., 0.],
            [1., 1.],
            [2_f32.powi(-100), 1.],
        ];
    }
    let out = compiler.compile(0, &input).unwrap();
    assert_eq!(out.stats.passthrough_quads, 2);
}

#[test]
fn only_explicit_relationships_remove_interfaces_or_duplicate_triangles() {
    let mut a = quad(0.0, 0.0);
    let mut b = a.clone();
    let mut compiler = SurfaceCompiler::new();
    assert_eq!(
        compiler
            .compile(0, &[a.clone(), b.clone()])
            .unwrap()
            .quads
            .len(),
        2
    );
    a.rule = SurfaceRule::Duplicate(9);
    b.rule = SurfaceRule::Duplicate(9);
    b.geometry.positions = [
        a.geometry.positions[0],
        a.geometry.positions[3],
        a.geometry.positions[2],
        a.geometry.positions[1],
    ];
    b.geometry.uvs = [
        a.geometry.uvs[0],
        a.geometry.uvs[3],
        a.geometry.uvs[2],
        a.geometry.uvs[1],
    ];
    let out = compiler.compile(1, &[a.clone(), b]).unwrap();
    assert_eq!(out.quads.len(), 1);
    assert_eq!(out.stats.removed_duplicates, 1);
    a.rule = SurfaceRule::Interface {
        negative: 4,
        positive: 4,
    };
    let out = compiler.compile(2, &[a.clone()]).unwrap();
    assert!(out.quads.is_empty());
    a.rule = SurfaceRule::Interface {
        negative: 4,
        positive: 5,
    };
    let out = compiler.compile(3, &[a.clone()]).unwrap();
    assert_eq!(out.quads[0].media, [4, 5]);
    a.emission.radiance = [1.0; 3];
    assert!(compiler.compile(4, &[a]).is_err());
}

#[test]
fn lights_are_built_from_final_geometry_and_forward_reverse_pdfs_agree() {
    let mut input: Vec<_> = (0..8).map(|x| quad(x as f32, 0.0)).collect();
    for q in &mut input {
        q.emission = Emission {
            textured: false,
            radiance: [2.0; 3],
            two_sided: true,
        };
    }
    let out = SurfaceCompiler::new().compile(10, &input).unwrap();
    assert_eq!(out.quads.len(), 1);
    assert_eq!(out.lights.emitters.len(), 1);
    assert_eq!(out.lights.nodes.len(), 1);
    for (i, emitter) in out.lights.emitters.iter().enumerate() {
        assert_eq!(emitter.quad, i as u32);
        assert_eq!(out.quads[i].emitter, Some(i as u32));
        assert_eq!(emitter.area, 8.0);
        assert_eq!(emitter.power, 32.0);
        assert_eq!(emitter.radiance, [2.0; 3]);
        assert_eq!(out.lights.area_pdf(i as u32), 0.125);
    }
    let mut counts = [0; 1];
    for i in 0..10_000 {
        let (id, pdf) = out.lights.select((i as f32 + 0.5) / 10_000.0).unwrap();
        counts[id as usize] += 1;
        assert_eq!(pdf, out.lights.selection_pdf(id));
        let point = out.lights.emitters[id as usize].sample_position([0.25, 0.75]);
        assert!(point_uv(&out.quads[id as usize], [point[0], point[1]]).is_some());
    }
    assert_eq!(counts, [10000]);
    assert!(out.lights.select(1.0).is_none());
    assert_eq!(out.lights.area_pdf(99), 0.0);
}

#[test]
fn invalid_emission_fails_and_empty_or_degenerate_emitters_have_no_mass() {
    let mut compiler = SurfaceCompiler::new();
    assert!(
        compiler
            .compile(0, &[])
            .unwrap()
            .lights
            .select(0.5)
            .is_none()
    );
    let mut q = quad(0.0, 0.0);
    q.emission.radiance = [1.0; 3];
    q.geometry.positions = [[0.0; 3]; 4];
    assert!(
        compiler
            .compile(0, &[q.clone()])
            .unwrap()
            .lights
            .nodes
            .is_empty()
    );
    q.emission.radiance[0] = f32::NAN;
    assert!(compiler.compile(0, &[q]).is_err());
}

#[test]
fn quad_lights_sample_unequal_halves_by_area_and_never_sample_the_degenerate_half() {
    let mut source = quad(0., 0.);
    source.geometry.positions = [[0., 0., 0.], [2., 0., 0.], [2., 1., 0.], [0., 3., 0.]];
    source.emission.radiance = [2.; 3];
    let mut compiler = SurfaceCompiler::new();
    let out = compiler.compile(1, &[source.clone()]).unwrap();
    let e = &out.lights.emitters[0];
    assert_eq!((e.area, e.first_fraction), (4., 0.25));
    assert_eq!(out.lights.area_pdf(0), 0.25);
    assert_eq!(
        out.quads[0].emitter_area_weight / out.lights.nodes[0].power,
        0.25
    );
    let mut first = 0;
    for i in 0..10000 {
        let p = e.sample_position([(i as f32 + 0.5) / 10000., 0.37]);
        assert!(point_uv(&out.quads[0], [p[0], p[1]]).is_some());
        first += usize::from(p[1] < p[0] * 0.5);
    }
    assert_eq!(first, 2500);
    for zero_first in [false, true] {
        source.geometry.positions = if zero_first {
            [[0., 0., 0.], [0., 0., 0.], [2., 0., 0.], [0., 1., 0.]]
        } else {
            [[0., 0., 0.], [2., 0., 0.], [0., 1., 0.], [0., 1., 0.]]
        };
        let out = compiler.compile(2, &[source.clone()]).unwrap();
        assert_eq!(out.lights.emitters.len(), 1);
        let e = &out.lights.emitters[0];
        assert_eq!(
            (e.area, e.first_fraction),
            (1., if zero_first { 0. } else { 1. })
        );
        for sample in [[0., 0.], [0.25, 0.75], [1., 1.]] {
            let p = e.sample_position(sample);
            assert!(p.iter().all(|x| x.is_finite()));
            assert!(p[0] >= 0. && p[1] >= 0. && p[0] / 2. + p[1] <= 1.000001);
        }
    }
}

#[test]
fn terrain_bridge_preserves_unchanged_pages_and_rebuilds_rich_light_identity() {
    use crate::{
        geometry::MeshGeometry,
        translation::{TerrainGeometry, TerrainMember},
    };
    use std::sync::Arc;
    let mut compiler = SurfaceCompiler::new();
    let quads = [quad(0., 0.), quad(1., 0.)];
    let mut geometry = TerrainGeometry {
        flags: quads[0].geometry.flags,
        triangle_count: 4,
        members: vec![TerrainMember {
            triangles: MeshGeometry::Quads(
                quads.iter().map(|q| q.geometry).collect::<Vec<_>>().into(),
            ),
            range: 0..4,
            offset: [16., 0., 0.],
        }],
    };
    let merged = compiler.compile_terrain(2, &geometry).unwrap().unwrap();
    assert_eq!(merged.quads.len(), 1);
    assert!(
        merged
            .quads
            .iter()
            .flat_map(|t| t.geometry.positions)
            .all(|p| p[0] >= 16.)
    );
    geometry.members[0].range = 1..3; // half of each quad; no inferred quad across the boundary
    geometry.triangle_count = 2;
    assert!(compiler.compile_terrain(3, &geometry).unwrap().is_none());
    let mut light = quads[0].clone();
    light.emission.radiance = [1.; 3];
    let rich = compiler.compile(4, &[light]).unwrap();
    geometry.members[0].triangles = MeshGeometry::Surfaces(Arc::new(rich));
    geometry.members[0].range = 1..2;
    geometry.triangle_count = 1;
    let moved = compiler.compile_terrain(5, &geometry).unwrap().unwrap();
    assert_eq!(moved.quads[0].emitter, Some(0));
    assert_eq!(moved.lights.emitters[0].quad, 0);
    assert_eq!(moved.lights.emitters[0].area, 0.5);
    assert!(
        moved.lights.emitters[0]
            .positions
            .iter()
            .all(|p| p[0] >= 16.)
    );
}

#[test]
fn contact_clipping_preserves_triangle_interpolation_and_secondary_uvs() {
    for warped_uv in [false, true] {
        let mut face = SurfaceFace::from_quad(quad(0., 0.).geometry);
        if warped_uv {
            face.geometry.uvs[2] = [0.73, 0.94];
        }
        let layer = SurfaceLayer {
            uvs: face.geometry.uvs.map(|uv| [1. - uv[1], uv[0] * 2.]),
            colors: face.geometry.colors,
            texture_id: 9,
            flags: 1,
            repeat: None,
            emission: Default::default(),
        };
        face.detail = Some(Arc::new(SurfaceDetail {
            mode: LayerMode::Bilateral,
            layer,
        }));
        let clipped = clip_rectangle(
            &face,
            Rectangle {
                bounds: [0.125, 0.375, 0.875, 0.75],
                ..Rectangle::from_face(&face).unwrap()
            },
        );
        assert!(!clipped.is_empty());
        if !warped_uv {
            assert_eq!(clipped.len(), 1);
        }
        for x in 0..37 {
            for y in 0..31 {
                let point = [(x as f32 + 0.37) / 37., (y as f32 + 0.31) / 31.];
                let actual = clipped.iter().find_map(|f| point_uv(f, point));
                let inside =
                    point[0] > 0.125 && point[0] < 0.875 && point[1] > 0.375 && point[1] < 0.75;
                assert_eq!(actual.is_some(), inside);
                if let Some(uv) = actual {
                    let expected = point_uv(&face, point).unwrap();
                    for i in 0..2 {
                        assert!((uv[i] - expected[i]).abs() < 2e-6);
                    }
                    let secondary = clipped
                        .iter()
                        .find_map(|f| {
                            let mut q = f.clone();
                            q.geometry.uvs = q.detail.as_ref().unwrap().layer.uvs;
                            point_uv(&q, point)
                        })
                        .unwrap();
                    assert!((secondary[0] - (1. - expected[1])).abs() < 2e-6);
                    assert!((secondary[1] - 2. * expected[0]).abs() < 2e-6);
                }
            }
        }
    }
}

#[test]
fn trapezoid_contacts_with_distinct_diagonals_preserve_both_fields_and_union_area() {
    let mut a = SurfaceFace::from_quad(quad(0., 0.).geometry);
    a.geometry.uvs[2] = [0.83, 0.91];
    let mut b = a.clone();
    b.geometry.positions = [
        [0.25, 0., 0.],
        [1.25, 0., 0.],
        [1.25, 0.6, 0.],
        [0.25, 0.9, 0.],
    ];
    b.geometry.positions.rotate_left(1);
    b.geometry.uvs = [[0., 1.], [0.91, 0.8], [1., 0.], [0., 0.]];
    let outside = subtract_surface(&a, &b).unwrap();
    let inside = intersect_surfaces(&a, &b).unwrap();
    let area: f64 = outside
        .iter()
        .map(|f| f.geometry.areas().iter().sum::<f64>())
        .sum::<f64>()
        + inside
            .iter()
            .map(|(f, _)| f.geometry.areas().iter().sum::<f64>())
            .sum::<f64>();
    assert!((area - 1.).abs() < 1e-6);
    for x in 0..41 {
        for y in 0..37 {
            let point = [(x as f32 + 0.31) / 41., (y as f32 + 0.27) / 37.];
            let expected = point_uv(&b, point);
            let part = inside.iter().find(|(f, _)| point_uv(f, point).is_some());
            assert_eq!(expected.is_some(), part.is_some());
            if let Some((face, layer)) = part {
                let uv = point_uv(face, point).unwrap();
                let original = point_uv(&a, point).unwrap();
                for i in 0..2 {
                    assert!((uv[i] - original[i]).abs() < 2e-6);
                }
                let mut secondary = face.clone();
                secondary.geometry.uvs = layer.uvs;
                let uv = point_uv(&secondary, point).unwrap();
                for (value, expected) in uv.into_iter().zip(expected.unwrap()) {
                    assert!((value - expected).abs() < 2e-6);
                }
                assert!(outside.iter().all(|f| point_uv(f, point).is_none()));
            } else {
                assert!(outside.iter().any(|f| point_uv(f, point).is_some()));
            }
        }
    }
}
