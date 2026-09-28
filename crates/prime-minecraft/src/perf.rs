//! CPU-only deterministic source workloads, not a game/GPU/FPS benchmark.
use super::*;
use crate::tests::{frame, header, requests, scene, string};
use std::io::Write;

fn definitions(out: &mut Vec<u8>) {
    for (id, flags, model, name) in [
        (0, 1, 0, "minecraft:air"),
        (1, 4, 1, "minecraft:stone"),
        (2, 0, 1, "minecraft:glass"),
        (3, 16, 0, "minecraft:water"),
        (4, 2, 2, "minecraft:fern"),
    ] {
        for v in [1, id, flags, model] {
            u32_to(out, v);
        }
        string(out, name);
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
    writeln!(file,"case,sample,warmup,threads,plan_ms,accept_ms,decode_ms,compile_ms,publish_ms,requested,changed,compiled,jobs,triangles,bytes,kernel_ms,finalize_ms,published_layers,retained_layers").unwrap();
    let mut sample = |name: &str, n: usize, ctx: &TerrainContext, plan: f64, accept: f64| {
        let s = &ctx.stats;
        writeln!(
            file,
            "{name},{n},{},8,{plan},{accept},{},{},{},{},{},{},{},{},{},{},{},{},{}",
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
            s.retained_layers
        )
        .unwrap();
    };
    for mode in ["dense", "terrain", "decorated"] {
        let events: Vec<_> = (0..8)
            .flat_map(|x| (0..8).map(move |z| (1, Section(x, 0, z))))
            .collect();
        for n in 0..15 {
            let mut ctx = TerrainContext {
                workers: Some(CpuWorkers::new(8).unwrap()),
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
                let accept = start.elapsed().as_secs_f64() * 1000.;
                sample(&format!("{mode}_{name}"), n, &ctx, plan, accept);
            }
        }
    }
    let mut ctx = TerrainContext {
        workers: Some(CpuWorkers::new(8).unwrap()),
        ..Default::default()
    };
    let mut scene = scene();
    let events: Vec<_> = (-32..=32)
        .flat_map(|x| (-32..=32).map(move |z| (1, Section(x, 0, z))))
        .collect();
    let req = requests(&mut ctx, &frame(1, 0., 30, [-4, 19], &events));
    ctx.accept(&[&packet(1, &req, "empty", false)], &mut scene)
        .unwrap();
    for n in 0..24 {
        let batch = n as u64 + 2;
        let input = frame(batch, if n % 2 == 0 { 16. } else { 0. }, 30, [-4, 19], &[]);
        let start = Instant::now();
        let req = requests(&mut ctx, &input);
        let plan = start.elapsed().as_secs_f64() * 1000.;
        let data = packet(batch, &req, "empty", false);
        let start = Instant::now();
        ctx.accept(&[&data], &mut scene).unwrap();
        let accept = start.elapsed().as_secs_f64() * 1000.;
        sample("window_89k", n, &ctx, plan, accept);
    }
}
