//! CPU upload-record preparation only. No Vulkan device, transfer, AS build or game.
use super::*;
use prime_scene::geometry::{CompiledQuad, MeshGeometry};
use std::{
    collections::hash_map::DefaultHasher,
    hash::{Hash, Hasher},
    hint::black_box,
    io::Write,
    time::Instant,
};

#[test]
#[ignore = "large CPU quad packing; explicit release run with PRIME_PACK_CSV"]
fn quad_packing_cost() {
    let mut file = std::fs::File::create(std::env::var("PRIME_PACK_CSV").unwrap()).unwrap();
    writeln!(
        file,
        "quads,triangles,threads,sample,warmup,pack_ns,output_bytes,output_hash"
    )
    .unwrap();
    let textures = BTreeMap::from([(7, 3), (9, 1)]);
    let workers = CpuWorkers::new(8).unwrap();
    for count in [262_144, 1_572_864] {
        let quads: Vec<_> = (0..count)
            .map(|i| CompiledQuad {
                positions: [
                    [i as f32 * 0.01, -0., 0.],
                    [1., -2., 0.],
                    [2., 3., 4.],
                    [-1., 2., 0.5],
                ],
                uvs: [[-0., 0.], [1., 0.25], [0.75, 1.], [-0.5, 1.25]],
                color: [0.25, -0., 0.5, 0.75],
                texture_id: if i % 3 == 0 { 9 } else { 7 },
                flags: 2,
            })
            .collect();
        let geometry = MeshGeometry::Quads(quads.into());
        let sources: Vec<_> = (0..geometry.len())
            .step_by(16_383)
            .map(|first| Input {
                triangles: geometry.view(first..(first + 16_383).min(geometry.len())),
                offset: Some([16., -32., 64.]),
                flags: Some(2),
            })
            .collect();
        let packing = Plan::new(sources, true).unwrap();
        let mut output = vec![MaybeUninit::uninit(); packing.bytes()];
        let mut samples = Vec::new();
        for sample in 0..45 {
            let start = Instant::now();
            packing
                .pack(
                    &workers,
                    &packing.groups[0],
                    black_box(&mut output),
                    &textures,
                )
                .unwrap();
            samples.push((sample, start.elapsed().as_nanos()));
            black_box(&output);
        }
        // SAFETY: successful packing initializes every byte; the immutable borrow ends before
        // the next workload replaces this allocation. Hashing is outside the timed samples.
        let bytes =
            unsafe { std::slice::from_raw_parts(output.as_ptr().cast::<u8>(), output.len()) };
        let mut hash = DefaultHasher::new();
        bytes.hash(&mut hash);
        for (sample, ns) in samples {
            writeln!(
                file,
                "{count},{},8,{sample},{},{ns},{},{}",
                geometry.len(),
                sample < 5,
                output.len(),
                hash.finish()
            )
            .unwrap();
        }
        file.flush().unwrap();
    }
}
