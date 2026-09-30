//! Reproducible native-1080p GPU-only benchmark of the borrowed host Vulkan path.
//! Example: cargo run --release --bin perf -- --frames 20
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;
#[cfg(feature = "vulkan")]
mod benchmark {
    use prime_scene::{Camera, Scene, SceneMesh, Texture, Triangle};
    use prime_vulkan::{GpuProfile, HostBenchmark};
    use std::{collections::BTreeMap, error::Error, sync::Arc, time::Instant};

    struct Options {
        grid: usize,
        widths: Vec<u32>,
        frames: u32,
        warmup: u32,
        cutout_every: usize,
        seed: u32,
        label: String,
        csv: Option<String>,
        dynamic_counts: Vec<u32>,
    }

    impl Options {
        fn parse() -> Result<Self, Box<dyn Error>> {
            let mut options = Self {
                grid: 128,
                widths: vec![1920],
                frames: 20,
                warmup: 3,
                cutout_every: 0,
                seed: 0x1357_2468,
                label: "baseline".into(),
                csv: None,
                dynamic_counts: Vec::new(),
            };
            let mut args = std::env::args().skip(1);
            while let Some(arg) = args.next() {
                if arg == "--help" {
                    println!(
                        "perf [--grid 128] [--widths 1920] [--frames 20] [--warmup 3] [--cutout-every 0] [--seed 324478056] [--dynamic-counts 1000,10000,50000] [--label baseline] [--csv output.csv]\nNative 1920×1080 only. Deterministic cubes; grid²×12+2 static triangles. Optional dynamic counts add mixed opaque/cutout/alpha triangles, updated in one complete snapshot per frame. Every Nth static cube is cutout when N>0. Two submissions in flight; direct storage image; no pixel readback, checksum or extra presentation copy. GPU timestamps always enabled; PRIME_PROFILE=1 adds resource counters. Isolated updates drain before/after; steady/camera/dynamic drain only at phase boundaries. Direct Scene input excludes Minecraft, FFM and protocol decoding; fixture_update_ns separately measures synthetic mutation, not decode. Dynamic batch completion includes that mutation. No performance assertions."
                    );
                    std::process::exit(0);
                }
                let value = args
                    .next()
                    .ok_or_else(|| format!("Missing value for {arg}"))?;
                match arg.as_str() {
                    "--grid" => options.grid = value.parse()?,
                    "--widths" => {
                        options.widths =
                            value.split(',').map(str::parse).collect::<Result<_, _>>()?
                    }
                    "--frames" => options.frames = value.parse()?,
                    "--warmup" => options.warmup = value.parse()?,
                    "--cutout-every" => options.cutout_every = value.parse()?,
                    "--seed" => options.seed = value.parse()?,
                    "--label" => options.label = value,
                    "--csv" => options.csv = Some(value),
                    "--dynamic-counts" => {
                        options.dynamic_counts =
                            value.split(',').map(str::parse).collect::<Result<_, _>>()?;
                    }
                    _ => return Err(format!("Unknown argument {arg}; use --help").into()),
                }
            }
            if !(1..=192).contains(&options.grid)
                || options.widths != [1920]
                || !(1..=1000).contains(&options.frames)
                || options.warmup > 100
                || options
                    .dynamic_counts
                    .iter()
                    .any(|count| !(1..=500_000).contains(count))
                || options.dynamic_counts.len() > 8
            {
                return Err(
                    "Require grid=1..192, widths=1920 (native 1920×1080 only), frames=1..1000, warmup=0..100, at most eight dynamic counts in 1..500000".into(),
                );
            }
            Ok(options)
        }
    }

    fn hash(mut value: u32) -> u32 {
        value ^= value >> 16;
        value = value.wrapping_mul(0x7feb_352d);
        value ^= value >> 15;
        value = value.wrapping_mul(0x846c_a68b);
        value ^ (value >> 16)
    }

    fn quad(
        triangles: &mut Vec<Triangle>,
        points: [[f32; 3]; 4],
        tint: [f32; 4],
        texture_id: u32,
        flags: u32,
    ) {
        let uvs = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
        for indices in [[0, 1, 2], [2, 3, 0]] {
            triangles.push(Triangle {
                positions: indices.map(|i| points[i]),
                colors: [tint; 3],
                uvs: indices.map(|i| uvs[i]),
                texture_id,
                flags,
            });
        }
    }

    // Align the centered edit patch to one mesh while preserving the old grid's
    // positions and exact edited cube set, including non-multiple-of-16 grids.
    fn patch_identity(grid: usize, x: usize, z: usize) -> (u64, [f32; 3]) {
        let start = (grid - grid.min(16)) / 2;
        let offset = (16 - start % 16) % 16;
        let columns = (grid + offset).div_ceil(16);
        let px = (x + offset) / 16;
        let pz = (z + offset) / 16;
        let half = grid as f32 * 1.25 * 0.5;
        (
            1 + (pz * columns + px) as u64,
            [
                (px * 16).saturating_sub(offset) as f32 * 1.25 - half,
                0.0,
                (pz * 16).saturating_sub(offset) as f32 * 1.25 - half,
            ],
        )
    }

    fn fixture(options: &Options) -> (Scene, Camera) {
        let mut scene = Scene {
            revision: 1,
            ..Default::default()
        };
        let mut patches = BTreeMap::<(u64, u32), ([f32; 3], Vec<Triangle>)>::new();
        let mut opaque = Vec::with_capacity(16 * 16 * 4);
        let mut cutout = Vec::with_capacity(16 * 16 * 4);
        for y in 0..16 {
            for x in 0..16 {
                let value = if ((x / 4) + (y / 4)) % 2 == 0 {
                    220
                } else {
                    135
                };
                opaque.extend_from_slice(&[value, value, value, 255]);
                cutout.extend_from_slice(&[80, 185, 75, if (x + y) % 4 < 2 { 255 } else { 0 }]);
            }
        }
        scene.textures.insert(
            1,
            Texture {
                region: None,
                sampling: None,
                width: 16,
                height: 16,
                pixels: opaque.into(),
            },
        );
        scene.textures.insert(
            2,
            Texture {
                region: None,
                sampling: None,
                width: 16,
                height: 16,
                pixels: cutout.into(),
            },
        );
        let span = options.grid as f32 * 1.25;
        let half = span * 0.5;
        let mut ground = Vec::with_capacity(2);
        quad(
            &mut ground,
            [
                [-half, 0.0, half],
                [half, 0.0, half],
                [half, 0.0, -half],
                [-half, 0.0, -half],
            ],
            [0.65, 0.7, 0.6, 1.0],
            1,
            0,
        );
        scene.meshes.insert(
            (0, 0),
            SceneMesh {
                revision: 1,
                flags: 0,
                origin: [0.0; 3],
                triangles: ground.into(),
            },
        );
        for z in 0..options.grid {
            for x in 0..options.grid {
                let index = z * options.grid + x;
                let noise = hash(index as u32 ^ options.seed);
                let x0 = x as f32 * 1.25 - half;
                let z0 = z as f32 * 1.25 - half;
                let x1 = x0 + 1.0;
                let z1 = z0 + 1.0;
                let height = 0.75 + (noise & 7) as f32 * 0.5;
                let tint = [
                    0.55 + ((noise >> 4) & 15) as f32 * 0.025,
                    0.55 + ((noise >> 8) & 15) as f32 * 0.025,
                    0.55 + ((noise >> 12) & 15) as f32 * 0.025,
                    1.0,
                ];
                let cutout =
                    options.cutout_every != 0 && index.is_multiple_of(options.cutout_every);
                let texture = if cutout { 2 } else { 1 };
                let flags = u32::from(cutout);
                let (key, origin) = patch_identity(options.grid, x, z);
                let (_, triangles) = patches
                    .entry((key, flags))
                    .or_insert_with(|| (origin, Vec::new()));
                for points in [
                    [
                        [x0, 0.0, z1],
                        [x1, 0.0, z1],
                        [x1, height, z1],
                        [x0, height, z1],
                    ],
                    [
                        [x1, 0.0, z0],
                        [x0, 0.0, z0],
                        [x0, height, z0],
                        [x1, height, z0],
                    ],
                    [
                        [x0, 0.0, z0],
                        [x0, 0.0, z1],
                        [x0, height, z1],
                        [x0, height, z0],
                    ],
                    [
                        [x1, 0.0, z1],
                        [x1, 0.0, z0],
                        [x1, height, z0],
                        [x1, height, z1],
                    ],
                    [
                        [x0, height, z1],
                        [x1, height, z1],
                        [x1, height, z0],
                        [x0, height, z0],
                    ],
                    [[x0, 0.0, z0], [x1, 0.0, z0], [x1, 0.0, z1], [x0, 0.0, z1]],
                ] {
                    let local = points.map(|point| std::array::from_fn(|i| point[i] - origin[i]));
                    quad(triangles, local, tint, texture, flags);
                }
            }
        }
        for ((key, flags), (origin, triangles)) in patches {
            scene.meshes.insert(
                (key, flags),
                SceneMesh {
                    revision: 1,
                    flags,
                    origin: origin.map(f64::from),
                    triangles: triangles.into(),
                },
            );
        }
        let pitch = 0.22_f32;
        let camera = Camera {
            position: [0.0, 8.0, half + 5.0],
            forward: [0.0, -pitch.sin(), -pitch.cos()],
            right: [1.0, 0.0, 0.0],
            up: [0.0, pitch.cos(), -pitch.sin()],
            vertical_fov_radians: 65.0_f32.to_radians(),
        };
        scene.ready_terrain = scene
            .meshes
            .values()
            .map(|mesh| prime_scene::spatial::Cell::containing(mesh.origin).unwrap())
            .collect();
        (scene, camera)
    }

    struct Record {
        device: String,
        phase: String,
        iteration: u32,
        wall_ms: f64,
        serial: u64,
        slot_wait_ns: u64,
        record_ns: u64,
        submit_ns: u64,
        gpu_ns: Option<u64>,
        isolated_completion_ms: Option<f64>,
        batch_frames: u32,
        profile: Option<[u64; 6]>,
        dynamic_triangles: usize,
        dynamic_snapshots: u32,
        fixture_update_ns: Option<u64>,
    }

    impl Record {
        fn cpu(device: &str, phase: &str, wall_ms: f64) -> Self {
            Self {
                device: device.into(),
                phase: phase.into(),
                iteration: 0,
                wall_ms,
                serial: 0,
                slot_wait_ns: 0,
                record_ns: 0,
                submit_ns: 0,
                gpu_ns: None,
                isolated_completion_ms: None,
                batch_frames: 0,
                profile: None,
                dynamic_triangles: 0,
                dynamic_snapshots: 0,
                fixture_update_ns: None,
            }
        }
    }

    fn profile_values(p: GpuProfile) -> [u64; 6] {
        [
            p.allocations,
            p.allocation_ns,
            p.allocated_bytes,
            p.upload_ns,
            p.uploaded_bytes,
            p.readback_bytes,
        ]
    }

    fn timed_frame(
        renderer: &mut HostBenchmark,
        scene: &Scene,
        camera: &Camera,
        phase: &str,
        iteration: u32,
        sample: u32,
    ) -> Result<Record, String> {
        let before = renderer.profile_snapshot();
        let frame = renderer.enqueue(scene, camera, sample)?;
        let profile = before.zip(renderer.profile_snapshot()).map(|(a, b)| {
            let a = profile_values(a);
            let b = profile_values(b);
            std::array::from_fn(|i| b[i].saturating_sub(a[i]))
        });
        Ok(Record {
            device: renderer.device_name().into(),
            phase: phase.into(),
            iteration,
            wall_ms: frame.wall_ns as f64 / 1e6,
            serial: frame.serial,
            slot_wait_ns: frame.slot_wait_ns,
            record_ns: frame.record_ns,
            submit_ns: frame.submit_ns,
            gpu_ns: None,
            isolated_completion_ms: None,
            batch_frames: 0,
            profile,
            dynamic_triangles: scene.dynamic.triangles.len(),
            dynamic_snapshots: u32::from(scene.dynamic.revision != 0),
            fixture_update_ns: None,
        })
    }

    fn complete(renderer: &mut HostBenchmark, records: &mut [Record]) -> Result<(), String> {
        for sample in renderer.drain()? {
            let record = records
                .iter_mut()
                .find(|r| r.serial == sample.serial)
                .ok_or("GPU completion has no matching CPU record")?;
            record.gpu_ns = Some(sample.gpu_ns);
        }
        Ok(())
    }

    fn isolated_frame(
        renderer: &mut HostBenchmark,
        scene: &Scene,
        camera: &Camera,
        phase: &str,
        sample: u32,
        records: &mut Vec<Record>,
    ) -> Result<(), String> {
        complete(renderer, records)?;
        let start = Instant::now();
        records.push(timed_frame(renderer, scene, camera, phase, 0, sample)?);
        complete(renderer, records)?;
        records.last_mut().unwrap().isolated_completion_ms =
            Some(start.elapsed().as_secs_f64() * 1000.0);
        Ok(())
    }

    fn csv_text(value: &str) -> String {
        format!("\"{}\"", value.replace('"', "\"\""))
    }

    fn dynamic_triangles(count: u32, seed: u32) -> Arc<Vec<Triangle>> {
        (0..count)
            .map(|index| {
                let noise = hash(index ^ seed);
                let x = (noise & 1023) as f32 / 1023.0 * 16.0 - 8.0;
                let y = ((noise >> 10) & 1023) as f32 / 1023.0 * 6.0 - 5.0;
                let z = -4.0 - ((noise >> 20) & 1023) as f32 / 1023.0 * 32.0;
                let flags = index % 3;
                Triangle {
                    positions: [[x, y, z], [x + 0.15, y, z], [x, y + 0.2, z]],
                    colors: [[0.9, 0.7, 0.4, if flags == 2 { 0.35 } else { 1.0 }]; 3],
                    uvs: [[0.0, 0.0], [1.0, 0.0], [0.0, 1.0]],
                    texture_id: if flags == 1 { 2 } else { 1 },
                    flags,
                }
            })
            .collect::<Vec<_>>()
            .into()
    }

    fn mutate_dynamic(scene: &mut Scene, frame: u32) -> u64 {
        let start = Instant::now();
        // Actual local vertex changes exercise the single dynamic upload/build.
        // This is synthetic scene mutation, explicitly outside native record time.
        let delta = (((frame + 1) as f32 * 0.07).sin() - (frame as f32 * 0.07).sin()) * 0.1;
        for triangle in Arc::make_mut(&mut scene.dynamic.triangles) {
            triangle.positions[2][1] += delta;
        }
        scene.dynamic.revision += 1;
        start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
    }

    fn dynamic_phase(
        renderer: &mut HostBenchmark,
        scene: &mut Scene,
        camera: &Camera,
        options: &Options,
        count: u32,
        records: &mut Vec<Record>,
    ) -> Result<String, String> {
        complete(renderer, records)?;
        let phase = format!("dynamic_{count}");
        let start = Instant::now();
        scene.dynamic.triangles = dynamic_triangles(count, options.seed);
        scene.dynamic.origin =
            std::array::from_fn(|i| scene.anchor[i] + f64::from(camera.position[i]));
        scene.dynamic.revision += 1;
        let mut generated = Record::cpu(
            renderer.device_name(),
            &format!("{phase}_fixture_cpu"),
            start.elapsed().as_secs_f64() * 1000.0,
        );
        generated.dynamic_triangles = count as usize;
        records.push(generated);
        isolated_frame(
            renderer,
            scene,
            camera,
            &format!("{phase}_initial"),
            options.seed,
            records,
        )?;
        for frame in 0..options.warmup {
            let mutation = mutate_dynamic(scene, frame);
            let mut record = timed_frame(
                renderer,
                scene,
                camera,
                &format!("{phase}_warmup"),
                frame,
                options.seed.wrapping_add(frame + 1),
            )?;
            record.fixture_update_ns = Some(mutation);
            records.push(record);
        }
        complete(renderer, records)?;
        let start = Instant::now();
        for frame in 0..options.frames {
            let mutation = mutate_dynamic(scene, frame + options.warmup);
            let mut record = timed_frame(
                renderer,
                scene,
                camera,
                &phase,
                frame,
                options.seed.wrapping_add(frame + options.warmup + 1),
            )?;
            record.fixture_update_ns = Some(mutation);
            records.push(record);
        }
        complete(renderer, records)?;
        let mut batch = Record::cpu(
            renderer.device_name(),
            &format!("{phase}_batch_completion"),
            start.elapsed().as_secs_f64() * 1000.0,
        );
        batch.batch_frames = options.frames;
        batch.dynamic_triangles = count as usize;
        records.push(batch);
        Ok(phase)
    }

    pub fn run() -> Result<(), Box<dyn Error>> {
        let options = Options::parse()?;
        let triangle_count = options.grid * options.grid * 12 + 2;
        let mut records = Vec::new();
        eprintln!(
            "[perf] mode=host_gpu_only resolution=1920x1080 max_in_flight=2 label={} grid={} triangles={} cutout_every={} seed={} frames={} warmup={} validation={:?}; no pixel readback/checksum; GPU queries enabled; wall_ms is CPU enqueue including old-slot backpressure, isolated_completion_ms includes completion, *_batch_completion is drained throughput",
            options.label,
            options.grid,
            triangle_count,
            options.cutout_every,
            options.seed,
            options.frames,
            options.warmup,
            std::env::var_os("PRIME_VK_VALIDATION")
        );
        eprintln!(
            "[perf] source=direct_scene dynamic_counts={:?}; excludes MC/FFM/protocol decode; four-bounce shader budget unchanged; dynamic frames reset accumulation, fixture_update_ns is synthetic mutation only",
            options.dynamic_counts
        );
        let (mut scene, mut camera) = fixture(&options);
        let start = Instant::now();
        let mut renderer = HostBenchmark::new(1920, 1080)?;
        records.push(Record::cpu(
            renderer.device_name(),
            "device_init",
            start.elapsed().as_secs_f64() * 1000.0,
        ));
        isolated_frame(
            &mut renderer,
            &scene,
            &camera,
            "initial_build_and_frame",
            0,
            &mut records,
        )?;
        for i in 0..options.warmup {
            records.push(timed_frame(
                &mut renderer,
                &scene,
                &camera,
                "warmup",
                i,
                i + 1,
            )?);
        }
        complete(&mut renderer, &mut records)?;
        let steady_start = Instant::now();
        for i in 0..options.frames {
            records.push(timed_frame(
                &mut renderer,
                &scene,
                &camera,
                "steady",
                i,
                i + options.warmup + 1,
            )?);
        }
        complete(&mut renderer, &mut records)?;
        let mut batch = Record::cpu(
            renderer.device_name(),
            "steady_batch_completion",
            steady_start.elapsed().as_secs_f64() * 1000.0,
        );
        batch.batch_frames = options.frames;
        records.push(batch);
        let camera_start = Instant::now();
        for i in 0..options.frames {
            camera.position[0] += 0.125;
            records.push(timed_frame(
                &mut renderer,
                &scene,
                &camera,
                "camera_move",
                i,
                1,
            )?);
        }
        complete(&mut renderer, &mut records)?;
        let mut batch = Record::cpu(
            renderer.device_name(),
            "camera_move_batch_completion",
            camera_start.elapsed().as_secs_f64() * 1000.0,
        );
        batch.batch_frames = options.frames;
        records.push(batch);
        // The fixture mutation is kept outside native recording measurements.
        let patch_size = options.grid.min(16);
        let patch_start = (options.grid - patch_size) / 2;
        let (patch_key, _) = patch_identity(options.grid, patch_start, patch_start);
        let start = Instant::now();
        for layer in 0..=1 {
            if let Some(mesh) = scene.meshes.get_mut(&(patch_key, layer)) {
                let prime_scene::geometry::MeshGeometry::Triangles(data) = &mut mesh.triangles
                else {
                    panic!("expected triangle fixture");
                };
                for triangle in Arc::make_mut(data) {
                    for position in &mut triangle.positions {
                        if position[1] > 0.0 {
                            position[1] += 0.25;
                        }
                    }
                }
                mesh.revision += 1;
            }
        }
        scene.revision += 1;
        records.push(Record::cpu(
            renderer.device_name(),
            "section_change_scene_cpu",
            start.elapsed().as_secs_f64() * 1000.0,
        ));
        isolated_frame(
            &mut renderer,
            &scene,
            &camera,
            "section_change_build_and_frame",
            1,
            &mut records,
        )?;
        let start = Instant::now();
        scene.anchor[0] += 256.0;
        camera.position[0] -= 256.0;
        scene.revision += 1;
        records.push(Record::cpu(
            renderer.device_name(),
            "rebase_scene_cpu",
            start.elapsed().as_secs_f64() * 1000.0,
        ));
        isolated_frame(
            &mut renderer,
            &scene,
            &camera,
            "rebase_build_and_frame",
            1,
            &mut records,
        )?;
        let mut phases: Vec<String> = [
            "initial_build_and_frame",
            "steady",
            "camera_move",
            "section_change_build_and_frame",
            "rebase_build_and_frame",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect();
        for &count in &options.dynamic_counts {
            phases.push(dynamic_phase(
                &mut renderer,
                &mut scene,
                &camera,
                &options,
                count,
                &mut records,
            )?);
        }
        if !options.dynamic_counts.is_empty() {
            scene.dynamic.triangles = Arc::default();
            scene.dynamic.revision += 1;
            isolated_frame(
                &mut renderer,
                &scene,
                &camera,
                "dynamic_clear",
                options.seed,
                &mut records,
            )?;
        }
        for phase in phases {
            let rows: Vec<_> = records.iter().filter(|r| r.phase == phase).collect();
            let mut values: Vec<_> = rows.iter().map(|r| r.wall_ms).collect();
            values.sort_by(f64::total_cmp);
            let gpu_mean =
                rows.iter().filter_map(|r| r.gpu_ns).sum::<u64>() as f64 / rows.len() as f64 / 1e6;
            let record_mean =
                rows.iter().map(|r| r.record_ns).sum::<u64>() as f64 / rows.len() as f64 / 1e6;
            eprintln!(
                "[perf] 1920x1080 {phase}: n={} enqueue_median_ms={:.3} enqueue_p95_ms={:.3} record_mean_ms={record_mean:.3} completed_gpu_mean_ms={gpu_mean:.3} isolated_completion_ms={:?}",
                values.len(),
                values[values.len() / 2],
                values[(values.len() * 95).div_ceil(100).saturating_sub(1)],
                rows[0].isolated_completion_ms
            );
        }
        for record in records.iter().filter(|r| r.batch_frames > 0) {
            eprintln!(
                "[perf] {}: frames={} total_ms={:.3} completed_ms_per_frame={:.3}",
                record.phase,
                record.batch_frames,
                record.wall_ms,
                record.wall_ms / f64::from(record.batch_frames)
            );
        }
        let mut csv = String::from(
            "mode,label,device,grid,triangles,cutout_every,width,height,phase,iteration,seed,serial,cpu_enqueue_wall_ms,cpu_event_wall_ms,cpu_slot_wait_ns,cpu_record_ns,cpu_submit_ns,completed_gpu_ns,isolated_completion_ms,batch_frames,batch_completion_ms,output_bytes,checksum,allocations,allocation_ns,allocated_bytes,upload_ns,uploaded_bytes,readback_bytes,dynamic_triangles,dynamic_snapshots,fixture_update_ns,source_mode,protocol_decode_ns\n",
        );
        for record in records {
            if record.serial != 0 && record.gpu_ns.is_none() {
                return Err("A submitted frame has no completed GPU timing".into());
            }
            let mut cells = vec![
                "host_gpu_only".into(),
                csv_text(&options.label),
                csv_text(&record.device),
                options.grid.to_string(),
                triangle_count.to_string(),
                options.cutout_every.to_string(),
                "1920".into(),
                "1080".into(),
                record.phase,
                record.iteration.to_string(),
                options.seed.to_string(),
                record.serial.to_string(),
                if record.serial != 0 {
                    format!("{:.6}", record.wall_ms)
                } else {
                    String::new()
                },
                if record.serial == 0 && record.batch_frames == 0 {
                    format!("{:.6}", record.wall_ms)
                } else {
                    String::new()
                },
                record.slot_wait_ns.to_string(),
                record.record_ns.to_string(),
                record.submit_ns.to_string(),
                record.gpu_ns.map(|v| v.to_string()).unwrap_or_default(),
                record
                    .isolated_completion_ms
                    .map(|v| format!("{v:.6}"))
                    .unwrap_or_default(),
                record.batch_frames.to_string(),
                if record.batch_frames > 0 {
                    format!("{:.6}", record.wall_ms)
                } else {
                    String::new()
                },
                "0".into(),
                String::new(), // No readback: no image checksum is claimed.
            ];
            cells.extend(
                record
                    .profile
                    .map(|v| v.map(|n| n.to_string()))
                    .unwrap_or_else(|| std::array::from_fn(|_| String::new())),
            );
            cells.extend([
                record.dynamic_triangles.to_string(),
                record.dynamic_snapshots.to_string(),
                record
                    .fixture_update_ns
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
                "direct_scene".into(),
                String::new(), // The fixture does not execute protocol decoding.
            ]);
            csv.push_str(&cells.join(","));
            csv.push('\n');
        }
        print!("{csv}");
        if let Some(path) = options.csv {
            let path = std::path::Path::new(&path);
            if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(path, csv)?;
        }
        Ok(())
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn dynamic_fixture_is_prefix_deterministic_and_mutation_preserves_static_scene() {
            let small = dynamic_triangles(3, 123);
            let large = dynamic_triangles(10, 123);
            for (a, b) in small.iter().zip(large.iter()) {
                assert_eq!(a.positions, b.positions);
                assert_eq!(a.flags, b.flags);
            }
            assert_eq!(
                small
                    .iter()
                    .map(|triangle| triangle.flags)
                    .collect::<Vec<_>>(),
                [0, 1, 2]
            );
            let mut scene = Scene {
                revision: 77,
                ..Default::default()
            };
            scene.dynamic.triangles = large;
            let allocation = scene.dynamic.triangles.as_ptr();
            let before = scene.dynamic.triangles[0].positions;
            mutate_dynamic(&mut scene, 0);
            assert_eq!(scene.revision, 77);
            assert_eq!(scene.dynamic.revision, 1);
            assert_eq!(scene.dynamic.triangles.as_ptr(), allocation);
            assert_eq!(scene.dynamic.triangles[0].positions[0], before[0]);
            assert_ne!(scene.dynamic.triangles[0].positions[2], before[2]);
        }
    }
}

#[cfg(feature = "vulkan")]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    benchmark::run()
}

#[cfg(not(feature = "vulkan"))]
fn main() {
    eprintln!("perf requires the default vulkan feature");
    std::process::exit(1);
}
