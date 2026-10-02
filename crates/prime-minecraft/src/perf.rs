//! CPU-only deterministic source workloads, not a game/GPU/FPS benchmark.
use super::*;
use crate::tests::{frame, header, requests, scene, string};
use std::io::Write;

#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

#[test]
#[ignore = "large exact biome sampling costs; run release alone with PRIME_BIOME_CSV"]
fn biome_stream_cost() {
    use crate::biome::{Cache, Recipe, Resolver};
    let samples: usize = std::env::var("PRIME_BIOME_SAMPLES").map_or(33, |v| v.parse().unwrap());
    let mut file = std::fs::File::create(std::env::var("PRIME_BIOME_CSV").unwrap()).unwrap();
    writeln!(
        file,
        "case,radius,sample,warmup,requests,host_samples,plan_ms,filter_ms,total_ms,checksum"
    )
    .unwrap();
    for mode in ["flat", "surface", "sparse"] {
        let mut requests = Vec::new();
        for z in -128_i32..128 {
            for x in -128_i32..128 {
                if mode == "sparse" && !matches!((x & 15, z & 15), (0, 0) | (15, 15)) {
                    continue;
                }
                requests.push(tint::Request {
                    position: [
                        x,
                        64 + if mode == "surface" {
                            (x * 7 + z * 11) & 15
                        } else {
                            0
                        },
                        z,
                    ],
                    state: 0,
                    slot: 0,
                });
            }
        }
        let recipes = vec![
            Recipe::Biome {
                resolver: Resolver::Grass,
                below: false
            };
            requests.len()
        ];
        for radius in [0, 2, 7] {
            for sample in 0..samples {
                let mut cache = Cache::default();
                let start = std::time::Instant::now();
                let plan = cache.prepare(requests.iter().copied(), &recipes, radius);
                let plan_ms = start.elapsed().as_secs_f64() * 1000.;
                let host_samples = plan.samples.len();
                // Explicit deterministic source response; host evaluation is outside the timer.
                let colors: Vec<_> = plan
                    .samples
                    .iter()
                    .map(|q| {
                        let [x, y, z] = q.position;
                        0xff00_0000
                            | ((x.wrapping_mul(741103597)
                                ^ y.wrapping_mul(341873128)
                                ^ z.wrapping_mul(132897987)) as u32
                                & 0x00ff_ffff)
                    })
                    .collect();
                let start = std::time::Instant::now();
                let result = cache.finish(plan, &colors);
                let filter_ms = start.elapsed().as_secs_f64() * 1000.;
                let checksum = result
                    .iter()
                    .fold(0u64, |h, &c| h.wrapping_mul(31).wrapping_add(c as u64));
                writeln!(file, "{mode},{radius},{sample},{},{},{host_samples},{plan_ms},{filter_ms},{},{checksum}", sample < 3, requests.len(), plan_ms + filter_ms).unwrap();
            }
            file.flush().unwrap();
        }
    }
}

fn definitions(out: &mut Vec<u8>) {
    for v in [5, 1, 2, 0] {
        u32_to(out, v);
    }
    for _ in 0..3 {
        u32_to(out, 0);
        for v in [0f32, 0., 1., 1.] {
            u32_to(out, v.to_bits());
        }
    }
    for (id, flags, model, name) in [
        (0, 1, 0, "minecraft:air"),
        (1, 36, 1, "minecraft:stone"),
        (2, 0, 1, "minecraft:glass"),
        (3, 16, 0, "minecraft:water"),
        (4, 2, 2, "minecraft:fern"),
    ] {
        for v in [1, id, flags, model] {
            u32_to(out, v);
        }
        string(out, name);
        if id == 3 {
            for _ in 0..6 {
                u32_to(out, 0);
            }
            u32_to(out, 0);
            string(out, "minecraft:water");
            for v in [0, 0, 1, 0] {
                u32_to(out, v);
            }
            for _ in 0..4 {
                u32_to(out, 0);
            }
        } else {
            crate::tests::state_source(out, flags);
        }
    }
    let faces = [
        [[0., 0., 0.], [1., 0., 0.], [1., 0., 1.], [0., 0., 1.]],
        [[0., 1., 1.], [1., 1., 1.], [1., 1., 0.], [0., 1., 0.]],
        [[1., 0., 0.], [0., 0., 0.], [0., 1., 0.], [1., 1., 0.]],
        [[0., 0., 1.], [1., 0., 1.], [1., 1., 1.], [0., 1., 1.]],
        [[0., 0., 0.], [0., 0., 1.], [0., 1., 1.], [0., 1., 0.]],
        [[1., 0., 1.], [1., 0., 0.], [1., 1., 0.], [1., 1., 1.]],
    ];
    for model in [1, 2] {
        for v in [2, model, 1, 6] {
            u32_to(out, v);
        }
        for (face, positions) in faces.iter().enumerate() {
            for v in [
                if model == 2 { 6 } else { face as u32 },
                if model == 2 { 0 } else { u32::MAX },
                if model == 2 { 1 } else { 0 },
                0,
                0,
            ] {
                u32_to(out, v);
            }
            for (i, position) in positions.iter().enumerate() {
                for &v in position {
                    u32_to(out, f32::to_bits(v));
                }
                let u = (i & 1) as f32;
                let v = (i >> 1) as f32;
                u64_to(out, (u64::from(u.to_bits()) << 32) | u64::from(v.to_bits()));
            }
        }
    }
}

pub(super) fn packet(batch: u64, sections: &[Section], mode: &str, mutation: bool) -> Vec<u8> {
    let mut out = header(2, batch);
    if batch == 1 {
        definitions(&mut out);
    }
    for &s in sections {
        for v in [3, s.0 as u32, s.1 as u32, s.2 as u32, 1] {
            u32_to(&mut out, v);
        }
        if mode == "empty" || mode == "dense" {
            for v in [0, 1, 0, u32::from(mode == "dense")] {
                u32_to(&mut out, v);
            }
            continue;
        }
        for v in [4, 5, 256, 0, 1, 2, 3, 4] {
            u32_to(&mut out, v);
        }
        for word in 0..256 {
            let mut bits = 0u64;
            for lane in 0..16 {
                let i = word * 16 + lane;
                let y = i / 256;
                let z = (i / 16) % 16;
                let x = i % 16;
                let h = 4 + (x * 7 + z * 3 + (s.0 as usize & 7)) % 9;
                let mut state = if mode == "decorated" {
                    if (x + y + z) % 3 == 0 {
                        4
                    } else if (x + y + z) % 3 == 1 {
                        1
                    } else {
                        0
                    }
                } else if y < h {
                    1
                } else if y < 7 {
                    3
                } else if y == h && (x + z) % 7 == 0 {
                    4
                } else {
                    0
                };
                if mutation && x == 8 && y == 8 && z == 8 {
                    state = 2;
                }
                bits |= (state as u64) << (lane * 4);
            }
            u64_to(&mut out, bits);
        }
    }
    u32_to(&mut out, 0);
    out
}

#[test]
#[ignore = "CPU cost matrix; run explicitly in release and preserve raw samples"]
fn source_cost_matrix() {
    let path = std::env::var("PRIME_SOURCE_BENCH_CSV").expect("set PRIME_SOURCE_BENCH_CSV");
    let mut file = std::fs::File::create(path).unwrap();
    writeln!(file,"case,sample,warmup,threads,plan_ms,accept_ms,decode_ms,compile_ms,publish_ms,requested,changed,compiled,jobs,triangles,bytes,kernel_ms,finalize_ms,published_layers,retained_layers,retire_ms").unwrap();
    let mut sample = |name: &str, n: usize, ctx: &TerrainContext, plan: f64, accept: f64| {
        let s = &ctx.stats;
        writeln!(
            file,
            "{name},{n},{},8,{plan},{accept},{},{},{},{},{},{},{},{},{},{},{},{},{},{}",
            n < 3,
            s.decode_ms,
            s.compile_ms,
            s.publish_ms,
            s.requested,
            s.changed,
            s.compiled,
            s.jobs,
            s.triangles,
            s.bytes,
            s.kernel_ms,
            s.finalize_ms,
            s.published_layers,
            s.retained_layers,
            s.retire_ms
        )
        .unwrap();
    };
    for mode in ["dense", "terrain", "decorated"] {
        let events: Vec<_> = (0..8)
            .flat_map(|x| (0..8).map(move |z| (1, Section(x, 0, z))))
            .collect();
        for n in 0..15 {
            let mut ctx = TerrainContext {
                workers: Some(CpuWorkers::new(8).unwrap().into()),
                ..Default::default()
            };
            let mut scene = scene();
            let input = frame(1, 48., 8, [0, 3], &events);
            let start = Instant::now();
            let req = requests(&mut ctx, &input);
            let plan = start.elapsed().as_secs_f64() * 1000.;
            let data = packet(1, &req, mode, false);
            let start = Instant::now();
            ctx.accept(&[&data], &mut scene).unwrap();
            crate::tests::test_tints(&mut ctx, &mut scene);
            let accept = start.elapsed().as_secs_f64() * 1000.;
            sample(mode, n, &ctx, plan, accept);
            let events: Vec<_> = req.iter().map(|&s| (3, s)).collect();
            for (batch, changed, name) in [(2, false, "unchanged"), (3, true, "interior_edit")] {
                let input = frame(batch, 48., 8, [0, 3], &events);
                let start = Instant::now();
                let req = requests(&mut ctx, &input);
                let plan = start.elapsed().as_secs_f64() * 1000.;
                let data = packet(batch, &req, mode, changed);
                let start = Instant::now();
                ctx.accept(&[&data], &mut scene).unwrap();
                crate::tests::test_tints(&mut ctx, &mut scene);
                let accept = start.elapsed().as_secs_f64() * 1000.;
                sample(&format!("{mode}_{name}"), n, &ctx, plan, accept);
            }
        }
    }
    let mut ctx = TerrainContext {
        workers: Some(CpuWorkers::new(8).unwrap().into()),
        ..Default::default()
    };
    let mut scene = scene();
    let events: Vec<_> = (-32..=32)
        .flat_map(|x| (-32..=32).map(move |z| (1, Section(x, 0, z))))
        .collect();
    let req = requests(&mut ctx, &frame(1, 0., 30, [-4, 19], &events));
    ctx.accept(&[&packet(1, &req, "empty", false)], &mut scene)
        .unwrap();
    crate::tests::test_tints(&mut ctx, &mut scene);
    for n in 0..24 {
        let batch = n as u64 + 2;
        let input = frame(batch, if n % 2 == 0 { 16. } else { 0. }, 30, [-4, 19], &[]);
        let start = Instant::now();
        let req = requests(&mut ctx, &input);
        let plan = start.elapsed().as_secs_f64() * 1000.;
        let data = packet(batch, &req, "empty", false);
        let start = Instant::now();
        ctx.accept(&[&data], &mut scene).unwrap();
        crate::tests::test_tints(&mut ctx, &mut scene);
        let accept = start.elapsed().as_secs_f64() * 1000.;
        sample("window_89k", n, &ctx, plan, accept);
    }
}

/// Includes the live renderer snapshot: the source publication is usually not the last owner
/// of replaced geometry. Dropping that final reference belongs in update latency measurements.
#[test]
#[ignore = "large CPU burst matrix; explicit release run with preserved raw samples"]
fn source_burst_cost() {
    use prime_scene::{
        incremental::TranslatedScene,
        translation::{TerrainLimits, TerrainPlanner},
    };
    use std::{
        collections::hash_map::DefaultHasher,
        hash::{Hash, Hasher},
    };
    let number =
        |key: &str, default: usize| std::env::var(key).map_or(default, |v| v.parse().unwrap());
    let side = number("PRIME_BURST_SIDE", 16) as i32;
    let samples = number("PRIME_BURST_SAMPLES", 23);
    let warmup = number("PRIME_BURST_WARMUP", 3);
    let mode = std::env::var("PRIME_BURST_MODE").unwrap_or("terrain".into());
    assert!(matches!(side, 4 | 8 | 16 | 32));
    assert!(matches!(mode.as_str(), "terrain" | "decorated"));
    assert!(samples > warmup);
    let mut file =
        std::fs::File::create(std::env::var("PRIME_BURST_CSV").expect("set PRIME_BURST_CSV"))
            .unwrap();
    writeln!(file,"case,sample,warmup,threads,sections,input_hash,total_ms,plan_ms,accept_ms,translate_ms,group_ms,decode_ms,kernel_ms,finalize_ms,publish_ms,retire_ms,compiled,published_layers,retained_layers,resident_triangles,rebuilt_triangles,rebuilt_cells,biome_plan_ms,tint_requests,resident_geometry_bytes").unwrap();
    let low = -(side / 8) * 4; // Whole 4x4 columns, including the smallest single-cell fixture.
    let loaded: Vec<_> = (low..low + side)
        .flat_map(|x| (low..low + side).map(move |z| (1, Section(x, 0, z))))
        .collect();
    for sample in 0..samples {
        let mut ctx = TerrainContext {
            workers: Some(CpuWorkers::new(8).unwrap().into()),
            ..Default::default()
        };
        let mut source = scene();
        let mut translated = TranslatedScene::default();
        let mut planner = TerrainPlanner::new(TerrainLimits {
            triangles_per_geometry: 524_288,
            geometry_records: 8_388_607,
        })
        .unwrap();
        let mut dirty = Vec::new();
        for (phase, mutation) in [
            ("cold", false),
            ("edit_on", true),
            ("edit_off", false),
            ("unchanged", false),
        ] {
            let batch = match phase {
                "cold" => 1,
                "edit_on" => 2,
                "edit_off" => 3,
                _ => 4,
            };
            let input = frame(
                batch,
                f64::from((low + side / 2) * 16),
                side,
                [0, 3],
                if batch == 1 { &loaded } else { &dirty },
            );
            let start = Instant::now();
            let req = requests(&mut ctx, &input);
            let plan_ms = start.elapsed().as_secs_f64() * 1000.;
            assert_eq!(req.len(), (side * side * 4) as usize);
            let data = packet(batch, &req, &mode, mutation);
            let mut hasher = DefaultHasher::new();
            data.hash(&mut hasher);
            let input_hash = hasher.finish();
            let start = Instant::now();
            ctx.accept(&[&data], &mut source).unwrap();
            crate::tests::test_tints(&mut ctx, &mut source);
            let accept_ms = start.elapsed().as_secs_f64() * 1000.;
            let start = Instant::now();
            translated.update(&mut source, [0.; 3]).unwrap();
            let translate_ms = start.elapsed().as_secs_f64() * 1000.;
            let start = Instant::now();
            let plan = planner.plan_input(translated.input()).unwrap();
            let cells = plan.geometry.len();
            let rebuilt: u64 = plan
                .geometry
                .iter()
                .flat_map(|b| &b.geometries)
                .map(|g| u64::from(g.triangle_count))
                .sum();
            planner.recycle(plan);
            let group_ms = start.elapsed().as_secs_f64() * 1000.;
            let total_ms = plan_ms + accept_ms + translate_ms + group_ms;
            let resident = translated.input().triangle_count();
            let resident_bytes: usize = translated
                .input()
                .meshes
                .values()
                .map(|mesh| mesh.triangles.byte_len())
                .sum();
            if phase == "unchanged" {
                assert_eq!(rebuilt, 0);
            } else {
                assert!(rebuilt > 0);
            }
            let s = &ctx.stats;
            writeln!(file,"{mode}_{phase},{sample},{},8,{},{input_hash},{total_ms},{plan_ms},{accept_ms},{translate_ms},{group_ms},{},{},{},{},{},{},{},{},{resident},{rebuilt},{cells},{},{},{resident_bytes}",
                sample < warmup, side * side * 4, s.decode_ms, s.kernel_ms, s.finalize_ms,
                s.publish_ms, s.retire_ms, s.compiled, s.published_layers, s.retained_layers,
                s.biome_plan_ms, s.tint_requests).unwrap();
            if batch == 1 {
                dirty = req.into_iter().map(|s| (3, s)).collect();
            }
        }
        file.flush().unwrap();
    }
    println!(
        "CPU source bursts saved; 8 workers, resident translated snapshots, synchronous grouping/retirement; fixture construction, Java/FFM, GPU and final scene teardown excluded"
    );
}
