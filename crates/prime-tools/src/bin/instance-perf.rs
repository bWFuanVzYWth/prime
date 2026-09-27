//! Native-1080p direct-scene instance benchmark. No Minecraft, FFM or op7 decoding.
#[cfg(feature = "vulkan")]
mod benchmark {
    use prime_scene::{Camera, Instance, InstanceScene, Prototype, Scene, SceneMesh, Triangle};
    use prime_vulkan::{GpuProfile, HostBenchmark};
    use std::{collections::BTreeMap, error::Error, fmt::Write, time::Instant};

    const SEED: u32 = 0x1357_2468;
    const COUNTS: [usize; 3] = [1_000, 10_000, 20_000];

    struct Options {
        samples: usize,
        warmup: usize,
        rounds: usize,
        csv: String,
        diagnostic: Option<String>,
    }
    impl Options {
        fn parse() -> Result<Self, Box<dyn Error>> {
            let mut result = Self {
                samples: 60,
                warmup: 8,
                rounds: 3,
                csv: "artifacts/prototype-instances/gpu-instance-perf.csv".into(),
                diagnostic: None,
            };
            let mut args = std::env::args().skip(1);
            while let Some(key) = args.next() {
                if key == "--help" {
                    println!(
                        "instance-perf [--samples 60] [--warmup 8] [--rounds 3] [--csv path] [--diagnostic image.png]\nDiagnostic writes the fixed 20k scene at 32 samples and does not run timings.\nFixed native 1920x1080, seed 0x13572468, 1000/10000/20000 instances, one 48-triangle prototype. First frame, stationary, 1% pose and 1% identity churn; two submissions in flight. Direct Scene excludes Minecraft, Java, FFM, protocol decoding and presentation. Enable PRIME_PROFILE=1, disable PRIME_VK_VALIDATION. Churn replaces identities at the same poses to isolate membership cost; no geometry change. Full sample CSV preserves outliers."
                    );
                    std::process::exit(0);
                }
                let value = args.next().ok_or("Missing argument value")?;
                match key.as_str() {
                    "--samples" => result.samples = value.parse()?,
                    "--warmup" => result.warmup = value.parse()?,
                    "--rounds" => result.rounds = value.parse()?,
                    "--csv" => result.csv = value,
                    "--diagnostic" => result.diagnostic = Some(value),
                    _ => return Err(format!("Unknown option {key}").into()),
                }
            }
            if !(1..=1000).contains(&result.samples)
                || result.warmup > 100
                || !(1..=10).contains(&result.rounds)
            {
                return Err("samples 1..1000, warmup 0..100, rounds 1..10 required".into());
            }
            if std::env::var_os("PRIME_VK_VALIDATION").is_some_and(|v| v != "0") {
                return Err("Disable PRIME_VK_VALIDATION for performance measurements".into());
            }
            Ok(result)
        }
    }

    fn hash(mut value: u32) -> u32 {
        value ^= value >> 16;
        value = value.wrapping_mul(0x7feb_352d);
        value ^= value >> 15;
        value = value.wrapping_mul(0x846c_a68b);
        value ^ (value >> 16)
    }
    fn quad(output: &mut Vec<Triangle>, points: [[f32; 3]; 4]) {
        let uv = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
        for indices in [[0, 1, 2], [2, 3, 0]] {
            output.push(Triangle {
                positions: indices.map(|i| points[i]),
                colors: [[1.0; 4]; 3],
                uvs: indices.map(|i| uv[i]),
                texture_id: 0,
                flags: 0,
            });
        }
    }
    fn cuboid(output: &mut Vec<Triangle>, [x0, y0, z0]: [f32; 3], [x1, y1, z1]: [f32; 3]) {
        for points in [
            [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]],
            [[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]],
            [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]],
            [[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]],
            [[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]],
            [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]],
        ] {
            quad(output, points);
        }
    }
    fn fixture(count: usize) -> (Scene, InstanceScene, Camera) {
        let mut scene = Scene {
            epoch: 1,
            revision: 1,
            ..Default::default()
        };
        let mut ground = Vec::new();
        quad(
            &mut ground,
            [
                [-180.0, 0.0, 120.0],
                [180.0, 0.0, 120.0],
                [180.0, 0.0, -120.0],
                [-180.0, 0.0, -120.0],
            ],
        );
        for triangle in &mut ground {
            triangle.colors = [[0.6, 0.65, 0.7, 1.0]; 3];
        }
        scene.meshes.insert(
            (1, 0),
            SceneMesh {
                revision: 1,
                flags: 0,
                origin: [0.0; 3],
                triangles: ground.into(),
            },
        );
        let mut triangles = Vec::new();
        cuboid(&mut triangles, [-0.45, 0.02, -0.45], [0.45, 0.65, 0.45]);
        cuboid(&mut triangles, [-0.48, 0.65, -0.48], [0.48, 0.85, 0.48]);
        cuboid(&mut triangles, [-0.06, 0.5, 0.45], [0.06, 0.73, 0.51]);
        cuboid(&mut triangles, [-0.2, 0.85, -0.15], [0.2, 0.95, 0.15]);
        let mut source = InstanceScene {
            epoch: 1,
            resource_revision: 1,
            instance_revision: 1,
            ..Default::default()
        };
        source.prototypes.insert(
            1,
            Prototype {
                revision: 1,
                triangles: triangles.into(),
                bounds: [[-0.48, 0.02, -0.48], [0.48, 0.95, 0.51]],
            },
        );
        for index in 0..count {
            // A fixed permutation spreads every prefix over the same 200x100 footprint.
            let cell = index * 7919 % 20_000;
            let noise = hash(index as u32 ^ SEED);
            let tint = [
                160 + (noise & 63) as u8,
                130 + ((noise >> 8) & 63) as u8,
                90 + ((noise >> 16) & 63) as u8,
                255,
            ];
            source.instances.insert(
                index as u64 + 1,
                Instance {
                    revision: 1,
                    prototype_id: 1,
                    origin: [
                        ((cell % 200) as f64 - 99.5) * 1.5,
                        0.0,
                        ((cell / 200) as f64 - 49.5) * 1.5,
                    ],
                    transform: [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0],
                    texture_id: u32::MAX,
                    flags: u32::MAX,
                    tint,
                    uv_transform: [1.0, 1.0, 0.0, 0.0],
                },
            );
        }
        let length = (85.0f32.powi(2) + 120.0f32.powi(2)).sqrt();
        let camera = Camera {
            position: [0.0, 85.0, 120.0],
            forward: [0.0, -85.0 / length, -120.0 / length],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 120.0 / length, -85.0 / length],
            vertical_fov_radians: 0.98,
        };
        (scene, source, camera)
    }

    fn mutate(
        source: &mut InstanceScene,
        live_ids: &mut [u64],
        phase: &str,
        frame: usize,
        next_id: &mut u64,
    ) {
        if phase == "steady" {
            return;
        }
        let changes = live_ids.len() / 100;
        for offset in 0..changes {
            let index = (frame * changes + offset) % live_ids.len();
            let id = live_ids[index];
            if phase == "pose_1pct" {
                let instance = source.instances.get_mut(&id).unwrap();
                instance.transform[3] += if frame.is_multiple_of(2) {
                    0.125
                } else {
                    -0.125
                };
                instance.revision += 1;
            } else {
                let mut instance = source.instances.remove(&id).unwrap();
                instance.revision += 1;
                source.instances.insert(*next_id, instance);
                live_ids[index] = *next_id;
                *next_id += 1;
            }
        }
        source.instance_revision += 1;
    }

    struct Row {
        serial: u64,
        round: usize,
        count: usize,
        phase: &'static str,
        kind: &'static str,
        frame: usize,
        mutation: u64,
        wall: u64,
        record: u64,
        wait: u64,
        submit: u64,
        gpu: u64,
        preparation: Option<u64>,
        render: Option<u64>,
        allocations: u64,
        upload: u64,
        rebuilt: u32,
        resident: u32,
    }
    fn ns(start: Instant) -> u64 {
        start.elapsed().as_nanos() as u64
    }
    fn percentile(values: &[u64], fraction: f64) -> f64 {
        let mut values = values.to_vec();
        values.sort_unstable();
        values[((values.len() as f64 * fraction).ceil() as usize).saturating_sub(1)] as f64 / 1e6
    }
    #[allow(clippy::too_many_arguments)]
    fn frame(
        host: &mut HostBenchmark,
        scene: &Scene,
        source: &InstanceScene,
        camera: &Camera,
        round: usize,
        count: usize,
        phase: &'static str,
        kind: &'static str,
        frame: usize,
        mutation: u64,
    ) -> Result<Row, String> {
        let before = host.profile_snapshot().unwrap_or_default();
        let sample = host.enqueue_with_instances(scene, source, camera, frame as u32)?;
        let after: GpuProfile = host.profile_snapshot().unwrap_or_default();
        let work = host.instance_work();
        if phase != "initial" && work.rebuilt_blas != 0 {
            return Err("Instance-only workload unexpectedly rebuilt prototype BLAS".into());
        }
        if work.resident_blas != 1 || work.instances as usize != count {
            return Err("Prototype sharing invariant failed".into());
        }
        Ok(Row {
            serial: sample.serial,
            round,
            count,
            phase,
            kind,
            frame,
            mutation,
            wall: sample.wall_ns,
            record: sample.record_ns,
            wait: sample.slot_wait_ns,
            submit: sample.submit_ns,
            gpu: 0,
            preparation: None,
            render: None,
            allocations: after.allocations - before.allocations,
            upload: after.uploaded_bytes - before.uploaded_bytes,
            rebuilt: work.rebuilt_blas,
            resident: work.resident_blas,
        })
    }
    fn finish(host: &mut HostBenchmark, rows: &mut [Row]) -> Result<(), String> {
        let completed: BTreeMap<_, _> = host
            .drain()?
            .into_iter()
            .map(|sample| (sample.serial, sample))
            .collect();
        for row in rows {
            let sample = completed.get(&row.serial).ok_or("Missing GPU sample")?;
            row.gpu = sample.gpu_ns;
            row.preparation = sample.preparation_ns;
            row.render = sample.render_ns;
        }
        Ok(())
    }
    pub fn run() -> Result<(), Box<dyn Error>> {
        let options = Options::parse()?;
        if let Some(path) = &options.diagnostic {
            // Separate diagnostic execution: never included in benchmark samples.
            let (scene, source, camera) = fixture(20_000);
            let mut renderer = prime_vulkan::Renderer::new()?;
            let mut pixels = Vec::new();
            for sample in 0..32 {
                pixels =
                    renderer.render_with_instances(&scene, &source, &camera, 1920, 1080, sample)?;
            }
            let output = std::fs::File::create(path)?;
            let mut encoder = png::Encoder::new(std::io::BufWriter::new(output), 1920, 1080);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            encoder.write_header()?.write_image_data(&pixels)?;
            println!(
                "Saved identical 20000-instance fixture diagnostic (32 samples; excluded from timings): {path}"
            );
            return Ok(());
        }
        let mut rows = Vec::new();
        println!(
            "native=1920x1080 seed={SEED} max_bounces=4 prototype_triangles=48 counts={COUNTS:?} samples={} warmup={} rounds={} input=direct_scene no_Minecraft_FFM_decode",
            options.samples, options.warmup, options.rounds
        );
        for round in 0..options.rounds {
            for count in COUNTS {
                let (scene, mut source, camera) = fixture(count);
                let mut ids: Vec<_> = source.instances.keys().copied().collect();
                let mut next_id = count as u64 + 1;
                let mut host = HostBenchmark::new(1920, 1080)?;
                if host.profile_snapshot().is_none() {
                    return Err("Set PRIME_PROFILE=1 to retain upload/allocation counters".into());
                }
                println!("round={round} instances={count} GPU={}", host.device_name());
                let start = rows.len();
                rows.push(frame(
                    &mut host, &scene, &source, &camera, round, count, "initial", "initial", 0, 0,
                )?);
                finish(&mut host, &mut rows[start..])?;
                for phase in ["steady", "pose_1pct", "churn_1pct"] {
                    let start = rows.len();
                    let batch = Instant::now();
                    for index in 0..options.warmup + options.samples {
                        let mutation = Instant::now();
                        mutate(&mut source, &mut ids, phase, index, &mut next_id);
                        let mutation = ns(mutation);
                        let kind = if index < options.warmup {
                            "warmup"
                        } else {
                            "sample"
                        };
                        rows.push(frame(
                            &mut host, &scene, &source, &camera, round, count, phase, kind, index,
                            mutation,
                        )?);
                    }
                    finish(&mut host, &mut rows[start..])?;
                    let batch_ms = batch.elapsed().as_secs_f64() * 1000.0;
                    let measured: Vec<_> = rows[start..]
                        .iter()
                        .filter(|row| row.kind == "sample")
                        .collect();
                    let gpu: Vec<_> = measured.iter().map(|row| row.gpu).collect();
                    let cpu: Vec<_> = measured.iter().map(|row| row.record).collect();
                    println!(
                        "  {phase}: GPU ms p50={:.3} p95={:.3} max={:.3}; CPU record ms p50={:.3} p95={:.3} max={:.3}; batch incl warmup/drain={batch_ms:.3}ms",
                        percentile(&gpu, 0.5),
                        percentile(&gpu, 0.95),
                        percentile(&gpu, 1.0),
                        percentile(&cpu, 0.5),
                        percentile(&cpu, 0.95),
                        percentile(&cpu, 1.0)
                    );
                }
                if host
                    .profile_snapshot()
                    .is_some_and(|p| p.readback_bytes != 0 || p.submissions != 0)
                {
                    return Err(
                        "Borrowed path unexpectedly read back or submitted independently".into(),
                    );
                }
            }
        }
        let mut csv = String::from(
            "round,instances,phase,kind,frame,serial,width,height,seed,prototype_triangles,fixture_update_ns,enqueue_ns,record_ns,slot_wait_ns,submit_ns,gpu_ns,allocations,uploaded_bytes,rebuilt_blas,resident_blas,source_mode,protocol_decode_ns,gpu_preparation_ns,gpu_render_ns\n",
        );
        for row in rows {
            writeln!(
                csv,
                "{},{},{},{},{},{},1920,1080,{SEED},48,{},{},{},{},{},{},{},{},{},{},direct_scene,,{},{}",
                row.round,
                row.count,
                row.phase,
                row.kind,
                row.frame,
                row.serial,
                row.mutation,
                row.wall,
                row.record,
                row.wait,
                row.submit,
                row.gpu,
                row.allocations,
                row.upload,
                row.rebuilt,
                row.resident,
                row.preparation.map_or_else(String::new, |v| v.to_string()),
                row.render.map_or_else(String::new, |v| v.to_string()),
            )?;
        }
        if let Some(parent) = std::path::Path::new(&options.csv).parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&options.csv, csv)?;
        println!("Saved {}", options.csv);
        Ok(())
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn fixed_prefix_and_one_percent_updates_never_modify_shared_geometry() {
            let (_, small, _) = fixture(1000);
            let (_, mut large, _) = fixture(10_000);
            assert_eq!(large.prototypes[&1].triangles.len(), 48);
            for (id, instance) in &small.instances {
                assert_eq!(large.instances[id].origin, instance.origin);
                assert_eq!(large.instances[id].tint, instance.tint);
            }
            let geometry = large.prototypes[&1].triangles.as_ptr();
            let mut ids: Vec<_> = large.instances.keys().copied().collect();
            let mut next_id = 10_001;
            mutate(&mut large, &mut ids, "pose_1pct", 0, &mut next_id);
            assert_eq!(
                large
                    .instances
                    .values()
                    .filter(|v| v.transform[3] != 0.0)
                    .count(),
                100
            );
            mutate(&mut large, &mut ids, "churn_1pct", 0, &mut next_id);
            assert_eq!(large.instances.len(), 10_000);
            assert_eq!(next_id, 10_101);
            assert_eq!(large.prototypes[&1].triangles.as_ptr(), geometry);
            assert_eq!(large.resource_revision, 1);
        }
    }
}

#[cfg(feature = "vulkan")]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    benchmark::run()
}

#[cfg(not(feature = "vulkan"))]
fn main() {
    eprintln!("instance-perf requires the vulkan feature");
}
