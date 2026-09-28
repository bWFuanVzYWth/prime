//! Repeated and changing op6 source -> translation -> object planning, optionally GPU completion.
use prime_scene::{
    SourceScene,
    incremental::TranslatedScene,
    protocol::{ABI_VERSION, MAGIC},
    translation::{BatchLimits, Planner},
};
use std::{error::Error, io::Write, time::Instant};

#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

fn packet(quads: usize) -> Vec<u8> {
    let mut out: Vec<_> = [MAGIC, ABI_VERSION, 6, 0]
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect();
    out.extend(1u64.to_le_bytes());
    out.extend(1u64.to_le_bytes());
    for v in [0_f64; 3] {
        out.extend(v.to_le_bytes());
    }
    for v in [1_u32, 0, 0, 0, 4, (quads * 4) as u32, 24, 0, 12, 16] {
        out.extend(v.to_le_bytes());
    }
    for i in 0..quads {
        let x = (i % 128) as f32;
        let z = (i / 128 % 128) as f32;
        let y = (i / 16384) as f32;
        for (dx, dz, u, v) in [
            (0., 0., 0., 0.),
            (0., 0.75, 0., 1.),
            (0.75, 0.75, 1., 1.),
            (0.75, 0., 1., 0.),
        ] {
            for value in [x + dx, y, z + dz] {
                out.extend(value.to_le_bytes());
            }
            out.extend(0xff80_ffffu32.to_le_bytes());
            for value in [u as f32, v as f32] {
                out.extend(value.to_le_bytes());
            }
        }
    }
    out
}

fn main() -> Result<(), Box<dyn Error>> {
    let mut args = std::env::args().skip(1);
    let path = args.next().ok_or("raw-perf OUTPUT.csv [--gpu]")?;
    let gpu = args.next().is_some_and(|v| v == "--gpu");
    let mut file = std::fs::File::create(path)?;
    let samples: usize = std::env::var("PRIME_RAW_SAMPLES").map_or(100, |v| v.parse().unwrap());
    let warmup: usize = std::env::var("PRIME_RAW_WARMUP")
        .map_or(if gpu { 120 } else { 10 }, |v| v.parse().unwrap());
    writeln!(
        file,
        "gpu,quads,case,sample,warmup,bytes,submit_ms,translate_ms,consume_ms,total_ms,gpu_ms,record_ms,geometry_updates,dynamic_changed"
    )?;
    for quads in [1000, 10000, 50000] {
        for mode in ["unchanged", "head_edit", "tail_edit", "origin"] {
            let mut source = SourceScene::default();
            let mut header: Vec<_> = [MAGIC, ABI_VERSION, 1, 0]
                .into_iter()
                .flat_map(u32::to_le_bytes)
                .collect();
            header.extend(1u64.to_le_bytes());
            source.submit(&header)?;
            let mut translated = TranslatedScene::default();
            let mut planner = Planner::new(BatchLimits {
                triangles: 524_288,
                placements: 8_388_607,
            })?;
            #[cfg(feature = "vulkan")]
            let mut host = if gpu {
                Some(prime_vulkan::HostBenchmark::new(1920, 1080)?)
            } else {
                None
            };
            #[cfg(not(feature = "vulkan"))]
            assert!(!gpu, "GPU mode requires the vulkan feature");
            let mut bytes = packet(quads);
            for sample in 0..warmup + samples {
                bytes[24..32].copy_from_slice(&(sample as u64 + 1).to_le_bytes());
                if mode == "origin" {
                    bytes[32..40].copy_from_slice(&((sample % 2) as f64 * 0.125).to_le_bytes());
                } else if mode != "unchanged" {
                    let offset = if mode == "head_edit" {
                        100
                    } else {
                        bytes.len() - 20
                    };
                    bytes[offset..offset + 4]
                        .copy_from_slice(&((sample % 2) as f32 * 0.125).to_le_bytes());
                }
                let start = Instant::now();
                source.submit(&bytes)?;
                let submitted = Instant::now();
                let work = translated.update(&mut source, [0.; 3])?;
                let prepared = Instant::now();
                let (updates, gpu_ms, record_ms): (Option<usize>, Option<f64>, Option<f64>);
                #[cfg(feature = "vulkan")]
                if let Some(host) = &mut host {
                    let camera = prime_scene::Camera {
                        position: [64., 180., 64.],
                        forward: [0., -1., 0.],
                        right: [1., 0., 0.],
                        up: [0., 0., -1.],
                        vertical_fov_radians: 1.,
                    };
                    let frame = host.enqueue_with_instances(
                        translated.input(),
                        source.instance_input(),
                        &camera,
                        0,
                    )?;
                    let completed = host.drain()?;
                    assert_eq!(completed.len(), 1);
                    gpu_ms = Some(completed[0].gpu_ns as f64 / 1e6);
                    record_ms = Some(frame.record_ns as f64 / 1e6);
                    updates = None;
                } else {
                    let plan = planner.plan(&translated.input(), source.instance_input())?;
                    updates = Some(plan.geometry.len());
                    gpu_ms = None;
                    record_ms = None;
                    assert_eq!(plan.triangle_count, quads as u64 * 2);
                    planner.recycle(plan);
                }
                #[cfg(not(feature = "vulkan"))]
                {
                    let plan = planner.plan(&translated.input(), source.instance_input())?;
                    updates = Some(plan.geometry.len());
                    assert_eq!(plan.triangle_count, quads as u64 * 2);
                    planner.recycle(plan);
                    gpu_ms = None;
                    record_ms = None;
                }
                let finished = Instant::now();
                writeln!(
                    file,
                    "{gpu},{quads},{mode},{sample},{},{},{},{},{},{},{},{},{},{}",
                    sample < warmup,
                    bytes.len(),
                    (submitted - start).as_secs_f64() * 1000.,
                    (prepared - submitted).as_secs_f64() * 1000.,
                    (finished - prepared).as_secs_f64() * 1000.,
                    (finished - start).as_secs_f64() * 1000.,
                    gpu_ms.map_or_else(String::new, |v| v.to_string()),
                    record_ms.map_or_else(String::new, |v| v.to_string()),
                    updates.map_or_else(String::new, |v| v.to_string()),
                    work.dynamic_changed
                )?;
            }
            file.flush()?;
        }
    }
    Ok(())
}
