//! Explicit production source publication -> host recording -> GPU completion benchmark.
use crate::{Camera, HostBenchmark};
use prime_scene::{
    SourceScene,
    compiled::CompiledQuad,
    incremental::TranslatedScene,
    protocol::{ABI_VERSION, MAGIC},
    workers::CpuWorkers,
};
use std::{io::Write, time::Instant};

#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

#[test]
#[ignore = "large native 1080p terrain update cost; run alone in release without validation"]
fn terrain_upload_cost_matrix() {
    let number =
        |key: &str, default| std::env::var(key).map_or(default, |v| v.parse::<usize>().unwrap());
    let count = number("PRIME_UPLOAD_QUADS", 1024);
    let cells = number("PRIME_UPLOAD_CELLS", 16);
    let samples = number("PRIME_UPLOAD_SAMPLES", 33);
    let warmup = number("PRIME_UPLOAD_WARMUP", 3);
    let pattern = std::env::var("PRIME_UPLOAD_PATTERN").unwrap_or_else(|_| "small".into());
    assert!(["small", "grid", "checker"].contains(&pattern.as_str()));
    assert!((1..=4096).contains(&count) && (1..=64).contains(&cells) && samples > warmup);
    let mut output =
        std::fs::File::create(std::env::var("PRIME_UPLOAD_CSV").expect("set PRIME_UPLOAD_CSV"))
            .unwrap();
    writeln!(output,"sample,warmup,cells,quads_per_section,triangles,prepare_ms,publish_ms,translate_ms,record_ms,enqueue_ms,wait_ms,completed_ms,gpu_ms,gpu_prepare_ms,gpu_render_ms,cpu_ms,resident_triangles").unwrap();
    let header = |op| {
        let mut bytes: Vec<_> = [MAGIC, ABI_VERSION, op, 0]
            .into_iter()
            .flat_map(u32::to_le_bytes)
            .collect();
        bytes.extend(1_u64.to_le_bytes());
        bytes
    };
    let mut source = SourceScene::default();
    source.submit(&header(1)).unwrap();
    let mut texture = header(4);
    texture.extend(
        [1, 1, 1, 0, u32::MAX]
            .into_iter()
            .flat_map(u32::to_le_bytes),
    );
    source.submit(&texture).unwrap();
    let workers = CpuWorkers::new(8).unwrap();
    let mut translated = TranslatedScene::default();
    let mut host = HostBenchmark::new(1920, 1080).unwrap();
    let camera = Camera {
        position: [128., 200., 128.],
        forward: [0., -1., 0.],
        right: [1., 0., 0.],
        up: [0., 0., -1.],
        vertical_fov_radians: 1.,
    };
    println!(
        "terrain upload benchmark: device={}, pattern={pattern}, native 1920x1080, 8 CPU workers, fixed grid and shader budget, completion drained each sample; no MC/FFM or readback",
        host.device_name()
    );
    let mut layers = [Vec::with_capacity(count), Vec::new(), Vec::new()];
    for i in 0..count {
        let (x, y, z, size) = if pattern == "small" {
            (
                (i % 32) as f32 * 0.5,
                (i / 1024) as f32 * 2.,
                (i / 32 % 32) as f32 * 0.5,
                0.4,
            )
        } else {
            (
                (i % 16) as f32,
                (i / 256) as f32 * 4.,
                (i / 16 % 16) as f32,
                1.,
            )
        };
        layers[0].push(CompiledQuad {
            positions: [
                [x, y, z],
                [x, y, z + size],
                [x + size, y, z + size],
                [x + size, y, z],
            ],
            uvs: [[0., 0.], [0., 1.], [1., 1.], [1., 0.]],
            color: [0.25, 0.5, 0.75, 1.],
            texture_id: 1,
            flags: 0,
        });
    }
    for sample in 0..samples {
        // Producer fixture edits are excluded; all output preparation, retirement and GPU work
        // are included. Two exact inputs alternate in both executables.
        for (i, quad) in layers[0].iter_mut().enumerate() {
            if pattern == "small" {
                quad.positions[3][1] = (i / 1024) as f32 * 2. + (sample % 2) as f32 * 0.125;
            } else {
                quad.color[0] = 0.25 + (sample % 2) as f32 * 0.125;
                if pattern == "checker" && (i % 16 + i / 16 % 16) % 2 == 0 {
                    quad.color[0] += 0.25;
                }
            }
        }
        let started = Instant::now();
        let mut sections: Vec<Option<_>> = (0..cells * 64).map(|_| None).collect();
        workers
            .chunks_mut(&mut sections, 1, |first, out| {
                for (i, slot) in out.iter_mut().enumerate() {
                    let key = first + i;
                    let cell = key / 64;
                    let local = key % 64;
                    let origin = [
                        ((cell % 4) * 64 + (local / 16) * 16) as f64,
                        ((local / 4 % 4) * 16) as f64,
                        ((cell / 4) * 64 + (local % 4) * 16) as f64,
                    ];
                    *slot = Some(source.prepare_compiled_quads(key as u64, origin, &[&layers]));
                }
                Ok(())
            })
            .unwrap();
        let sections = sections.into_iter().map(Option::unwrap).collect();
        let prepare_ms = started.elapsed().as_secs_f64() * 1000.;
        let start = Instant::now();
        let mut publication = source
            .publish_compiled(1, sample as u64 + 1, sections, &[])
            .unwrap();
        publication.release_retired(Some(&workers)).unwrap();
        let publish_ms = start.elapsed().as_secs_f64() * 1000.;
        let start = Instant::now();
        translated.update(&mut source, [0.; 3]).unwrap();
        let translate_ms = start.elapsed().as_secs_f64() * 1000.;
        let frame = host
            .enqueue_with_instances(
                translated.input(),
                &prime_scene::InstanceScene::default(),
                &camera,
                0,
            )
            .unwrap();
        let completed = host.drain().unwrap();
        let completed_ms = started.elapsed().as_secs_f64() * 1000.;
        assert_eq!(completed.len(), 1);
        assert_eq!(completed[0].serial, frame.serial);
        let gpu = completed[0];
        let triangles = translated.input().triangle_count();
        assert_eq!(triangles, cells * 64 * count * 2);
        let record_ms = frame.record_ns as f64 / 1e6;
        let cpu_ms = prepare_ms + publish_ms + translate_ms + record_ms;
        writeln!(output,"{sample},{},{cells},{count},{triangles},{prepare_ms},{publish_ms},{translate_ms},{record_ms},{},{},{completed_ms},{},{},{},{cpu_ms},{}",
            sample < warmup, frame.wall_ns as f64 / 1e6, frame.slot_wait_ns as f64 / 1e6,
            gpu.gpu_ns as f64 / 1e6, gpu.preparation_ns.unwrap() as f64 / 1e6, gpu.render_ns.unwrap() as f64 / 1e6, host.triangle_count()).unwrap();
        output.flush().unwrap();
    }
}
