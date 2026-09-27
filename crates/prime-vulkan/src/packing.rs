//! CPU-only disjoint record packing; workers never see Vulkan allocations.
use prime_scene::{Triangle, workers::CpuWorkers};
use std::collections::BTreeMap;

pub(crate) struct Input<'a> {
    pub triangles: &'a [Triangle],
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
            triangles,
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
    assert_eq!(output.len(), count * 128);
    workers.chunks_mut(output, 4096 * 128, |start, bytes| {
        let first_triangle = start / 128;
        let mut source_index = ends.partition_point(|&end| end <= first_triangle);
        for (triangle_index, record) in (first_triangle..).zip(bytes.as_chunks_mut::<128>().0) {
            while ends[source_index] <= triangle_index {
                source_index += 1;
            }
            let input = &sources[source_index];
            let first = if source_index == 0 {
                0
            } else {
                ends[source_index - 1]
            };
            let triangle = &input.triangles[triangle_index - first];
            if input.flags.is_some_and(|flags| flags != triangle.flags) {
                return Err("Mesh material flags must be uniform".into());
            }
            let mut cursor = 0;
            let mut scalar = |value: f32| {
                record[cursor..cursor + 4].copy_from_slice(&value.to_le_bytes());
                cursor += 4;
            };
            for mut position in triangle.positions {
                if let Some(offset) = input.offset {
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
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
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
                    triangles: &source[..100],
                    offset: None,
                    flags: None,
                },
                Input {
                    triangles: &source[100..],
                    offset: None,
                    flags: Some(1),
                },
            ];
            pack_ranges(&workers, &mut output, &sources, &textures).unwrap();
            assert_eq!(output, scalar);
            let tiny_sources: Vec<_> = source
                .chunks(29)
                .map(|triangles| Input {
                    triangles,
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
