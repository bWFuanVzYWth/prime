//! Synchronous disjoint record packing; workers borrow output bytes, never Vulkan handles.
use prime_scene::Triangle;
use prime_scene::geometry::{CompiledQuad, TriangleView};
use prime_scene::workers::CpuWorkers;
use std::collections::BTreeMap;
use std::mem::MaybeUninit;

#[cfg(test)]
mod perf;

pub(crate) struct Input<'a> {
    pub triangles: TriangleView<'a>,
    pub offset: Option<[f32; 3]>,
    pub flags: Option<u32>,
}

#[cfg(test)]
fn pack(
    workers: &CpuWorkers,
    output: &mut [u8],
    triangles: &[Triangle],
    flags: Option<u32>,
    textures: &BTreeMap<u32, u32>,
) -> Result<(), String> {
    pack_ranges(
        workers,
        output,
        &[Input {
            triangles: triangles.into(),
            offset: None,
            flags,
        }],
        textures,
    )
}

pub(crate) fn pack_ranges(
    workers: &CpuWorkers,
    output: &mut [u8],
    sources: &[Input<'_>],
    textures: &BTreeMap<u32, u32>,
) -> Result<(), String> {
    let (records, tail) = output.as_chunks_mut::<128>();
    assert!(tail.is_empty());
    pack_records(workers, records, sources, textures, |out, record| {
        *out = record
    })
}

pub(crate) fn pack_uninit_ranges(
    workers: &CpuWorkers,
    output: &mut [MaybeUninit<u8>],
    sources: &[Input<'_>],
    textures: &BTreeMap<u32, u32>,
) -> Result<(), String> {
    let (records, tail) = output.as_chunks_mut::<128>();
    assert!(tail.is_empty());
    pack_records(workers, records, sources, textures, |out, record| {
        *out = record.map(MaybeUninit::new);
    })
}

fn pack_records<T: Send>(
    workers: &CpuWorkers,
    output: &mut [T],
    sources: &[Input<'_>],
    textures: &BTreeMap<u32, u32>,
    write: impl Fn(&mut T, [u8; 128]) + Sync,
) -> Result<(), String> {
    // Flatten only span metadata. Fragmented source geometry stays shared and each worker's
    // inner loop reads a contiguous slice, without a fragment search for every triangle.
    let contiguous;
    let sources = if sources
        .iter()
        .any(|s| matches!(s.triangles, TriangleView::QuadFragments { .. }))
    {
        contiguous = sources
            .iter()
            .flat_map(|source| {
                source.triangles.contiguous().map(move |triangles| Input {
                    triangles,
                    offset: source.offset,
                    flags: source.flags,
                })
            })
            .collect::<Vec<_>>();
        &contiguous
    } else {
        sources
    };
    let mut count = 0usize;
    let one;
    let many;
    let ends: &[usize] = if sources.len() == 1 {
        count = sources[0].triangles.len();
        one = [count];
        &one
    } else {
        many = sources
            .iter()
            .map(|source| {
                count += source.triangles.len();
                count
            })
            .collect::<Vec<_>>();
        &many
    };
    assert_eq!(output.len(), count);
    workers.chunks_mut(output, 4096, |first_triangle, records| {
        let mut source_index = ends.partition_point(|&end| end <= first_triangle);
        let mut triangle_index = first_triangle;
        let mut remaining = records;
        while !remaining.is_empty() {
            while ends[source_index] <= triangle_index {
                source_index += 1;
            }
            let input = &sources[source_index];
            let first = if source_index == 0 {
                0
            } else {
                ends[source_index - 1]
            };
            let count = remaining.len().min(ends[source_index] - triangle_index);
            let (destinations, tail) = remaining.split_at_mut(count);
            let local = triangle_index - first;
            match input.triangles {
                TriangleView::Triangles(values) => {
                    for (destination, triangle) in destinations.iter_mut().zip(&values[local..]) {
                        write(destination, triangle_record(triangle, input, textures)?);
                    }
                }
                TriangleView::Quads { values, first, .. } => {
                    let mut quad = (first + local) / 2;
                    let mut destinations = destinations;
                    // Capacity/source/worker splits can start or end halfway through a quad.
                    // Only the consumed half is validated in those cases.
                    if (first + local) % 2 != 0 {
                        let (head, tail) = destinations.split_first_mut().unwrap();
                        write(
                            head,
                            triangle_record(&values[quad].triangle(1), input, textures)?,
                        );
                        destinations = tail;
                        quad += 1;
                    }
                    let (pairs, tail) = destinations.as_chunks_mut::<2>();
                    for pair in pairs {
                        let records = quad_records(&values[quad], input, textures)?;
                        write(&mut pair[0], records[0]);
                        write(&mut pair[1], records[1]);
                        quad += 1;
                    }
                    if let Some(last) = tail.first_mut() {
                        write(
                            last,
                            triangle_record(&values[quad].triangle(0), input, textures)?,
                        );
                    }
                }
                TriangleView::QuadFragments { .. } => unreachable!("fragments normalized above"),
                TriangleView::Surfaces(_) => {
                    return Err(
                        "Custom surface records require the surface compiler renderer".into(),
                    );
                }
            }
            remaining = tail;
            triangle_index += count;
        }
        Ok(())
    })
}

fn triangle_record(
    triangle: &Triangle,
    input: &Input<'_>,
    textures: &BTreeMap<u32, u32>,
) -> Result<[u8; 128], String> {
    encode_triangle(triangle, input.offset, input.flags, textures)
}

pub(crate) fn encode_triangle(
    triangle: &Triangle,
    offset: Option<[f32; 3]>,
    flags: Option<u32>,
    textures: &BTreeMap<u32, u32>,
) -> Result<[u8; 128], String> {
    if flags.is_some_and(|flags| flags != triangle.flags) {
        return Err("Mesh material flags must be uniform".into());
    }
    let mut record = [0; 128];
    let mut cursor = 0;
    let mut scalar = |value: f32| {
        record[cursor..cursor + 4].copy_from_slice(&value.to_le_bytes());
        cursor += 4;
    };
    for mut position in triangle.positions {
        if let Some(offset) = offset {
            for i in 0..3 {
                position[i] += offset[i];
            }
        }
        if position.iter().any(|v| !v.is_finite()) {
            return Err("Non-finite vertex".into());
        }
        for value in position {
            scalar(value);
        }
        scalar(0.0);
    }
    for color in triangle.colors {
        if color
            .iter()
            .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
        {
            return Err("Invalid vertex tint".into());
        }
        for value in color {
            scalar(value);
        }
    }
    for uv in triangle.uvs {
        if uv.iter().any(|v| !v.is_finite()) {
            return Err("Invalid texture coordinate".into());
        }
        for value in uv {
            scalar(value);
        }
    }
    let texture = textures
        .get(&triangle.texture_id)
        .ok_or("Texture has not been captured")?;
    record[120..124].copy_from_slice(&texture.to_le_bytes());
    record[124..128].copy_from_slice(&triangle.flags.to_le_bytes());
    Ok(record)
}

/// A pair consumes four distinct corners and one uniform color/material. Transform and
/// validate those once, then write the exact established two 128-byte triangle records.
fn quad_records(
    quad: &CompiledQuad,
    input: &Input<'_>,
    textures: &BTreeMap<u32, u32>,
) -> Result<[[u8; 128]; 2], String> {
    if input.flags.is_some_and(|flags| flags != quad.flags) {
        return Err("Mesh material flags must be uniform".into());
    }
    if quad
        .color
        .iter()
        .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
    {
        return Err("Invalid vertex tint".into());
    }
    let color = quad.color.map(f32::to_le_bytes);
    let texture = textures
        .get(&quad.texture_id)
        .ok_or("Texture has not been captured")?;
    let mut positions = [[0; 16]; 4];
    let mut uvs = [[0; 8]; 4];
    for i in 0..4 {
        let mut position = quad.positions[i];
        if let Some(offset) = input.offset {
            for axis in 0..3 {
                position[axis] += offset[axis];
            }
        }
        if position.iter().any(|v| !v.is_finite()) {
            return Err("Non-finite vertex".into());
        }
        if quad.uvs[i].iter().any(|v| !v.is_finite()) {
            return Err("Invalid texture coordinate".into());
        }
        positions[i][..12].copy_from_slice(position.map(f32::to_le_bytes).as_flattened());
        uvs[i].copy_from_slice(quad.uvs[i].map(f32::to_le_bytes).as_flattened());
    }
    let mut records = [[0; 128]; 2];
    for (record, corners) in records.iter_mut().zip([[0, 1, 2], [2, 3, 0]]) {
        for (i, corner) in corners.into_iter().enumerate() {
            record[i * 16..i * 16 + 16].copy_from_slice(&positions[corner]);
            record[48 + i * 16..64 + i * 16].copy_from_slice(color.as_flattened());
            record[96 + i * 8..104 + i * 8].copy_from_slice(&uvs[corner]);
        }
        record[120..124].copy_from_slice(&texture.to_le_bytes());
        record[124..128].copy_from_slice(&quad.flags.to_le_bytes());
    }
    Ok(records)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn compact_mapped_records_match_scalar_wire_with_offsets_materials_and_odd_splits() {
        use prime_scene::geometry::{CompiledQuad, MeshGeometry};
        let textures = BTreeMap::from([(7, 3), (9, 1)]);
        let quads: Vec<_> = (0..8_003)
            .map(|i| CompiledQuad {
                positions: [
                    [-0., i as f32, 0.],
                    [1., -2., 0.],
                    [2., 3., 4.],
                    [-1., 2., 0.5],
                ],
                uvs: [[-0., 0.], [1., 0.25], [0.75, 1.], [-0.5, 1.25]],
                color: [0.25, -0., 0.5, 0.75],
                texture_id: 7,
                flags: 2,
            })
            .collect();
        let compact = MeshGeometry::QuadFragments(std::sync::Arc::new(
            prime_scene::geometry::QuadFragments::new([
                vec![],
                quads[..1].to_vec(),
                quads[1..2_003].to_vec(),
                vec![],
                quads[2_003..].to_vec(),
                vec![],
            ]),
        ));
        let raw = Triangle {
            positions: [[0.; 3], [1., 0., 0.], [0., 1., 0.]],
            colors: [
                [0.1, 0.2, 0.3, 1.],
                [0.4, 0.5, 0.6, 1.],
                [0.7, 0.8, 0.9, 1.],
            ],
            uvs: [[0.; 2]; 3],
            texture_id: 9,
            flags: 0,
        };
        let raw_source = [raw];
        let sources = [
            Input {
                triangles: (&raw_source[..]).into(),
                offset: None,
                flags: Some(0),
            },
            Input {
                triangles: compact.view(0..3),
                offset: Some([16., -32., 64.]),
                flags: Some(2),
            },
            Input {
                triangles: compact.view(3..3),
                offset: None,
                flags: Some(2),
            },
            Input {
                triangles: compact.view(3..compact.len()),
                offset: Some([16., -32., 64.]),
                flags: Some(2),
            },
        ];
        let mut expected = Vec::new();
        crate::plan::pack_triangle(&mut expected, &raw, 1);
        // Independent corner expansion and scalar reference, including non-planar fourth corners.
        for quad in &quads {
            for corners in [[0, 1, 2], [2, 3, 0]] {
                let triangle = Triangle {
                    positions: corners.map(|i| {
                        std::array::from_fn(|axis| quad.positions[i][axis] + [16., -32., 64.][axis])
                    }),
                    colors: [quad.color; 3],
                    uvs: corners.map(|i| quad.uvs[i]),
                    texture_id: 7,
                    flags: 2,
                };
                crate::plan::pack_triangle(&mut expected, &triangle, 3);
            }
        }
        for threads in [1, 4] {
            let workers = CpuWorkers::new(threads).unwrap();
            let mut bytes = vec![MaybeUninit::new(0xcd); expected.len() + 32];
            pack_uninit_ranges(
                &workers,
                &mut bytes[16..16 + expected.len()],
                &sources,
                &textures,
            )
            .unwrap();
            // SAFETY: Both guard regions were initialized above; a successful pack writes every
            // byte of every output record. This also checks that no padding byte was omitted.
            let actual: Vec<_> = bytes
                .into_iter()
                .map(|b| unsafe { b.assume_init() })
                .collect();
            assert_eq!(&actual[..16], &[0xcd; 16]);
            assert_eq!(&actual[16..16 + expected.len()], expected);
            assert_eq!(&actual[16 + expected.len()..], &[0xcd; 16]);
            let mut output = vec![0; expected.len()];
            pack_ranges(&workers, &mut output, &sources, &textures).unwrap();
            assert_eq!(output, expected);
            for fault in 0..5 {
                let mut bad = quads[0];
                match fault {
                    0 => bad.positions[3][1] = f32::NAN,
                    1 => bad.color[3] = 1.01,
                    2 => bad.uvs[3][0] = f32::INFINITY,
                    3 => bad.flags = 1,
                    4 => bad.texture_id = 100,
                    _ => unreachable!(),
                }
                let geometry = MeshGeometry::Quads(vec![bad; 5_000].into());
                let mut output = vec![MaybeUninit::uninit(); geometry.len() * 128];
                assert!(
                    pack_uninit_ranges(
                        &workers,
                        &mut output,
                        &[Input {
                            triangles: geometry.view(0..geometry.len()),
                            offset: None,
                            flags: Some(2)
                        }],
                        &textures
                    )
                    .is_err(),
                    "fault {fault}"
                );
            }
        }
    }
    #[test]
    fn parallel_pack_matches_scalar_wire_for_split_sources_and_failures() {
        let triangle = Triangle {
            positions: [[-0.0, 1.0, 0.0], [2.0, 0.0, 1.0], [-2.0, 1.0, 0.0]],
            colors: [[0.2, 0.3, 0.4, 0.7]; 3],
            uvs: [[0.5, -0.5]; 3],
            texture_id: 7,
            flags: 1,
        };
        let source = vec![triangle; 30_001];
        let mut scalar = Vec::new();
        for value in &source {
            crate::plan::pack_triangle(&mut scalar, value, 3);
        }
        let textures = BTreeMap::from([(7, 3)]);
        for threads in [1, 4] {
            let workers = CpuWorkers::new(threads).unwrap();
            let mut output = vec![0; scalar.len()];
            let sources = [
                Input {
                    triangles: (&source[..100]).into(),
                    offset: None,
                    flags: None,
                },
                Input {
                    triangles: (&source[100..]).into(),
                    offset: None,
                    flags: Some(1),
                },
            ];
            pack_ranges(&workers, &mut output, &sources, &textures).unwrap();
            assert_eq!(output, scalar);
            let tiny_sources: Vec<_> = source
                .chunks(29)
                .map(|triangles| Input {
                    triangles: triangles.into(),
                    offset: None,
                    flags: Some(1),
                })
                .collect();
            pack_ranges(&workers, &mut output, &tiny_sources, &textures).unwrap();
            assert_eq!(
                output, scalar,
                "many small sources share one disjoint batch"
            );
            assert!(pack_ranges(&workers, &mut output, &sources, &BTreeMap::new()).is_err());
        }
    }
    #[test]
    fn partial_quad_validates_only_consumed_corners_and_reports_late_fragment_errors() {
        use prime_scene::geometry::{MeshGeometry, QuadFragments};
        use std::sync::Arc;
        let good = CompiledQuad {
            positions: [[0.; 3]; 4],
            uvs: [[0.; 2]; 4],
            color: [1.; 4],
            texture_id: 7,
            flags: 2,
        };
        let textures = BTreeMap::from([(7, 3)]);
        let workers = CpuWorkers::new(4).unwrap();
        for fault in 0..5 {
            let mut bad = good;
            match fault {
                0 => bad.positions[3][1] = f32::NAN,
                1 => bad.uvs[3][0] = f32::INFINITY,
                2 => bad.color[3] = 1.01,
                3 => bad.flags = 1,
                _ => bad.texture_id = 100,
            }
            let geometry = MeshGeometry::QuadFragments(Arc::new(QuadFragments::new([
                vec![],
                vec![good; 4_103],
                vec![],
                vec![bad],
                vec![],
            ])));
            let input = |range| Input {
                triangles: geometry.view(range),
                offset: None,
                flags: Some(2),
            };
            let mut output = vec![0; geometry.len() * 128];
            assert!(
                pack_ranges(
                    &workers,
                    &mut output,
                    &[input(0..geometry.len())],
                    &textures
                )
                .is_err()
            );
            let mut half = [0; 128];
            assert_eq!(
                pack_ranges(&workers, &mut half, &[input(8_206..8_207)], &textures).is_ok(),
                fault < 2
            );
            assert!(pack_ranges(&workers, &mut half, &[input(8_207..8_208)], &textures).is_err());
            pack_ranges(&workers, &mut [], &[input(8_208..8_208)], &textures).unwrap();
        }
    }
    #[test]
    #[ignore = "CPU cost matrix; explicitly run this filter in release mode without GPU tests"]
    fn packing_cost_matrix() {
        use std::{fmt::Write, hint::black_box, time::Instant};
        let mut csv = String::from("threads,triangles,sample,warmup,pack_ns,output_bytes\n");
        let textures = BTreeMap::from([(7, 3)]);
        for count in [128_usize, 32_768, 262_144] {
            let source: Vec<_> = (0..count)
                .map(|i| Triangle {
                    positions: [
                        [i as f32 * 0.01, 0.0, 0.0],
                        [1.0, 1.0, 0.0],
                        [0.0, 1.0, 0.0],
                    ],
                    colors: [[0.2, 0.3, 0.4, 0.7]; 3],
                    uvs: [[0.5, 0.25]; 3],
                    texture_id: 7,
                    flags: 1,
                })
                .collect();
            let mut reference = vec![0_u8; count * 128];
            pack(
                &CpuWorkers::new(1).unwrap(),
                &mut reference,
                &source,
                Some(1),
                &textures,
            )
            .unwrap();
            for threads in [1, 2, 4, 8] {
                let workers = CpuWorkers::new(threads).unwrap();
                let mut output = vec![0; reference.len()];
                for sample in 0..70 {
                    let start = Instant::now();
                    pack(
                        &workers,
                        black_box(&mut output),
                        black_box(&source),
                        Some(1),
                        &textures,
                    )
                    .unwrap();
                    let ns = start.elapsed().as_nanos();
                    black_box(&output);
                    writeln!(
                        csv,
                        "{threads},{count},{sample},{},{ns},{}",
                        sample < 10,
                        output.len()
                    )
                    .unwrap();
                }
                assert_eq!(output, reference);
            }
        }
        let output = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../artifacts/packing-cpu.csv");
        std::fs::create_dir_all(output.parent().unwrap()).unwrap();
        std::fs::write(&output, csv).unwrap();
        println!(
            "CPU pack samples saved to {}; setup/allocation and GPU excluded",
            output.display()
        );
    }
}
