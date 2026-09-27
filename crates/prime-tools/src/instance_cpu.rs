//! CPU-only op7 measurements. Packet construction, Minecraft/FFM and GPU work are excluded.
use prime_scene::{
    SourceScene,
    protocol::{ABI_VERSION, MAGIC},
};
use std::{
    alloc::{GlobalAlloc, Layout, System},
    error::Error,
    hint::black_box,
    sync::atomic::{AtomicU64, Ordering},
    time::Instant,
};

struct CountedSystem;
static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static REALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static REQUESTED: AtomicU64 = AtomicU64::new(0);

// SAFETY: The allocator delegates all operations and alignment requirements unchanged
// to System. Counters use allocation-free atomics and never access allocated memory.
unsafe impl GlobalAlloc for CountedSystem {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc(layout) };
        if !pointer.is_null() {
            ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
            REQUESTED.fetch_add(layout.size() as u64, Ordering::Relaxed);
        }
        pointer
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc_zeroed(layout) };
        if !pointer.is_null() {
            ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
            REQUESTED.fetch_add(layout.size() as u64, Ordering::Relaxed);
        }
        pointer
    }
    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let pointer = unsafe { System.realloc(pointer, layout, new_size) };
        if !pointer.is_null() {
            REALLOCATIONS.fetch_add(1, Ordering::Relaxed);
            REQUESTED.fetch_add(new_size as u64, Ordering::Relaxed);
        }
        pointer
    }
    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) };
    }
}

#[global_allocator]
static ALLOCATOR: CountedSystem = CountedSystem;

fn allocation_counts() -> [u64; 3] {
    [
        ALLOCATIONS.load(Ordering::Relaxed),
        REALLOCATIONS.load(Ordering::Relaxed),
        REQUESTED.load(Ordering::Relaxed),
    ]
}

struct Options {
    objects: Vec<u32>,
    samples: u32,
    warmup: u32,
    steady_iterations: u32,
    csv: String,
}

impl Options {
    fn parse() -> Result<Self, Box<dyn Error>> {
        let mut options = Self {
            objects: vec![1_000, 10_000, 20_000],
            samples: 21,
            warmup: 3,
            steady_iterations: 10_000,
            csv: "artifacts/instances/cpu.csv".into(),
        };
        let mut args = std::env::args().skip(1);
        while let Some(key) = args.next() {
            if key == "--help" {
                println!(
                    "instance-cpu [--objects 1000,10000,20000,56133] [--samples 21] [--warmup 3] [--steady-iterations 10000] [--csv artifacts/instances/cpu.csv]\nCPU-only actual op7 submit: one 48-triangle prototype; initial N instances, stationary borrowed scene with no packet, separately labelled 1% and 100% pose deltas, and 1% simultaneous removal/birth churn. Default counts remain 1000,10000,20000; explicitly add 56133 for the all-animated workload size (not a replay of its model/ID distribution). Packet generation, source/FFM/GPU work and final scene destruction are excluded. Stationary rows are one batch of repeated O(1) borrowed metadata reads; divide total_ns by operations. Allocator instrumentation records requested Rust allocations, not RSS. Use --no-default-features."
                );
                std::process::exit(0);
            }
            let value = args
                .next()
                .ok_or_else(|| format!("Missing value for {key}"))?;
            match key.as_str() {
                "--objects" => {
                    options.objects = value.split(',').map(str::parse).collect::<Result<_, _>>()?
                }
                "--samples" => options.samples = value.parse()?,
                "--warmup" => options.warmup = value.parse()?,
                "--steady-iterations" => options.steady_iterations = value.parse()?,
                "--csv" => options.csv = value,
                _ => return Err(format!("Unknown option {key}").into()),
            }
        }
        if options.objects.is_empty()
            || options.objects.contains(&0)
            || !(1..=1000).contains(&options.samples)
            || options.warmup > 100
            || !(1..=1_000_000).contains(&options.steady_iterations)
            || options.objects.iter().any(|&n| n > 262_144)
        {
            return Err("Require resident counts=1..262144, samples=1..1000, warmup<=100, steady iterations=1..1000000".into());
        }
        Ok(options)
    }
}

fn header(operation: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, ABI_VERSION, operation, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(1_u64.to_le_bytes());
    bytes
}

fn batch_header(sequence: u64, counts: [u32; 4]) -> Vec<u8> {
    let mut bytes = header(7);
    bytes.extend(sequence.to_le_bytes());
    for value in counts {
        bytes.extend(value.to_le_bytes());
    }
    bytes
}

fn write_prototype(bytes: &mut Vec<u8>) {
    bytes.extend(1_u64.to_le_bytes()); // prototype id
    bytes.extend(1_u64.to_le_bytes()); // source revision
    bytes.extend(1_u32.to_le_bytes()); // one span
    bytes.extend(0_u32.to_le_bytes());
    for value in [0_u32, 0, 4, 96, 24, 0, 12, 16] {
        bytes.extend(value.to_le_bytes());
    }
    // Four closed cuboids (48 triangles), a complexity proxy, not a captured MC model.
    for (lo, hi) in [
        ([0.0, 0.0, 0.0], [1.0, 0.7, 1.0]),
        ([0.0, 0.7, 0.0], [1.0, 1.0, 1.0]),
        ([0.44, 0.5, -0.06], [0.56, 0.8, 0.0]),
        ([0.05, 0.05, 0.05], [0.95, 0.15, 0.95]),
    ] {
        let [x0, y0, z0] = lo;
        let [x1, y1, z1] = hi;
        for face in [
            [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]],
            [[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]],
            [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]],
            [[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]],
            [[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]],
            [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]],
        ] {
            for (position, uv) in
                face.into_iter()
                    .zip([[0.0_f32, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]])
            {
                for value in position {
                    bytes.extend(f32::to_le_bytes(value));
                }
                bytes.extend([255; 4]);
                for value in uv {
                    bytes.extend(value.to_le_bytes());
                }
            }
        }
    }
}

fn write_instance(bytes: &mut Vec<u8>, id: u64, revision: u64, position: u32, moved: bool) {
    for value in [id, revision, 1] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [
        29_999_744.0 + f64::from(position % 128) * 1.25,
        64.0,
        -16.0 + f64::from(position / 128) * 1.25,
    ] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [
        1.0_f32,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        0.0,
        if moved { 0.03125 } else { 0.0 },
        0.0,
        0.0,
        1.0,
        0.0,
    ] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(u32::MAX.to_le_bytes());
    bytes.extend(u32::MAX.to_le_bytes());
    bytes.extend([180, 128, 72, 255]);
    bytes.extend(0_u32.to_le_bytes());
    for value in [1.0_f32, 1.0, 0.0, 0.0] {
        bytes.extend(value.to_le_bytes());
    }
}

fn initial_packet(objects: u32) -> Vec<u8> {
    let mut bytes = batch_header(1, [1, 0, objects, 0]);
    bytes.reserve(24 + 32 + 96 * 24 + objects as usize * 128);
    write_prototype(&mut bytes);
    for position in 0..objects {
        write_instance(&mut bytes, u64::from(position) + 1, 1, position, false);
    }
    bytes
}

struct Sample {
    stage: &'static str,
    objects: u32,
    changed: u32,
    warmup: bool,
    iteration: u32,
    wire_bytes: usize,
    operations: u32,
    total_ns: u64,
    allocation: [u64; 3],
}

fn measure(
    source: &mut SourceScene,
    packet: &[u8],
    operations: u32,
) -> Result<(u64, [u64; 3]), String> {
    let before = allocation_counts();
    let start = Instant::now();
    if packet.is_empty() {
        for _ in 0..operations {
            let scene = black_box(&*source).instances();
            black_box((
                scene.epoch,
                scene.resource_revision,
                scene.instance_revision,
                scene.instances.len(),
            ));
        }
    } else {
        source.submit(black_box(packet))?;
        black_box(source.instances());
    }
    let elapsed = start.elapsed().as_nanos() as u64;
    let after = allocation_counts();
    Ok((elapsed, std::array::from_fn(|i| after[i] - before[i])))
}

fn run_case(options: &Options, objects: u32) -> Result<Vec<Sample>, String> {
    let initial = initial_packet(objects);
    let rounds = options.samples + options.warmup;
    let mut samples = Vec::with_capacity(rounds as usize * 5);
    for stage in [
        "initial",
        "steady_no_submit",
        "pose_1pct",
        "pose_100pct",
        "churn_1pct",
    ] {
        let changed = if stage == "pose_100pct" {
            objects
        } else {
            objects.div_ceil(100)
        };
        let mut source = SourceScene::default();
        source.submit(&header(1))?;
        if stage != "initial" {
            source.submit(&initial)?;
        }
        let mut live_ids: Vec<_> = (1..=u64::from(changed)).collect();
        let mut next_id = u64::from(objects) + 1;
        for frame in 0..rounds {
            let packet = match stage {
                "initial" => {
                    source = SourceScene::default();
                    source.submit(&header(1))?;
                    initial.clone()
                }
                "steady_no_submit" => Vec::new(),
                "pose_1pct" | "pose_100pct" => {
                    let mut bytes = batch_header(u64::from(frame) + 2, [0, 0, changed, 0]);
                    for position in 0..changed {
                        write_instance(
                            &mut bytes,
                            u64::from(position) + 1,
                            u64::from(frame) + 2,
                            position,
                            frame.is_multiple_of(2),
                        );
                    }
                    bytes
                }
                "churn_1pct" => {
                    let mut bytes = batch_header(u64::from(frame) + 2, [0, 0, changed, changed]);
                    for position in 0..changed {
                        write_instance(
                            &mut bytes,
                            next_id + u64::from(position),
                            u64::from(frame) + 2,
                            position,
                            false,
                        );
                    }
                    for (position, id) in live_ids.iter_mut().enumerate() {
                        bytes.extend(id.to_le_bytes());
                        bytes.extend((u64::from(frame) + 2).to_le_bytes());
                        *id = next_id + position as u64;
                    }
                    next_id += u64::from(changed);
                    bytes
                }
                _ => unreachable!(),
            };
            let operations = if stage == "steady_no_submit" {
                options.steady_iterations
            } else {
                1
            };
            let (total_ns, allocation) = measure(&mut source, &packet, operations)?;
            assert_eq!(source.instances().instances.len(), objects as usize);
            assert_eq!(source.instances().prototypes[&1].triangles.len(), 48);
            assert_eq!(source.instances().resource_revision, 1);
            assert_eq!(source.revision(), 1);
            samples.push(Sample {
                stage,
                objects,
                changed: match stage {
                    "initial" => objects,
                    "steady_no_submit" => 0,
                    _ => changed,
                },
                warmup: frame < options.warmup,
                iteration: if frame < options.warmup {
                    frame
                } else {
                    frame - options.warmup
                },
                wire_bytes: packet.len(),
                operations,
                total_ns,
                allocation,
            });
        }
    }
    Ok(samples)
}

fn percentile(values: &[f64], percent: usize) -> f64 {
    values[(values.len() * percent).div_ceil(100).saturating_sub(1)]
}

fn main() -> Result<(), Box<dyn Error>> {
    let options = Options::parse()?;
    eprintln!(
        "[instance-cpu] actual op7, one 48-triangle shared prototype, allocator instrumented; no packet construction/MC/FFM/GPU/final scene destruction in measured intervals"
    );
    let mut csv = String::from(
        "objects,prototype_count,unique_triangles,expanded_triangles,stage,changed_objects,warmup,iteration,wire_bytes,operations,total_ns,ns_per_operation,allocations,reallocations,requested_bytes\n",
    );
    for &objects in &options.objects {
        let samples = run_case(&options, objects)?;
        for stage in [
            "initial",
            "steady_no_submit",
            "pose_1pct",
            "pose_100pct",
            "churn_1pct",
        ] {
            let selected: Vec<_> = samples
                .iter()
                .filter(|s| s.stage == stage && !s.warmup)
                .collect();
            let mut times: Vec<_> = selected
                .iter()
                .map(|s| s.total_ns as f64 / f64::from(s.operations))
                .collect();
            times.sort_by(f64::total_cmp);
            eprintln!(
                "[instance-cpu] objects={objects} stage={stage} wire_bytes={} cpu_ms_per_op p50={:.6} p95={:.6} max={:.6} allocations={} n={}",
                selected[0].wire_bytes,
                percentile(&times, 50) / 1e6,
                percentile(&times, 95) / 1e6,
                times.last().unwrap() / 1e6,
                selected[0].allocation[0],
                times.len()
            );
        }
        for s in samples {
            let mut cells = vec![
                s.objects.to_string(),
                "1".into(),
                "48".into(),
                (u64::from(s.objects) * 48).to_string(),
                s.stage.into(),
                s.changed.to_string(),
                u32::from(s.warmup).to_string(),
                s.iteration.to_string(),
                s.wire_bytes.to_string(),
                s.operations.to_string(),
                s.total_ns.to_string(),
                format!("{:.4}", s.total_ns as f64 / f64::from(s.operations)),
            ];
            cells.extend(s.allocation.map(|value| value.to_string()));
            csv.push_str(&cells.join(","));
            csv.push('\n');
        }
    }
    let path = std::path::Path::new(&options.csv);
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, csv)?;
    eprintln!("[instance-cpu] wrote {}", path.display());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_five_stages_use_real_atomic_packets_and_preserve_instance_count() {
        let options = Options {
            objects: vec![200],
            samples: 2,
            warmup: 1,
            steady_iterations: 10,
            csv: String::new(),
        };
        let samples = run_case(&options, 200).unwrap();
        assert_eq!(samples.len(), 15);
        for sample in samples {
            let expected_bytes = match sample.stage {
                "initial" => 48 + 24 + 32 + 96 * 24 + 200 * 128,
                "steady_no_submit" => 0,
                "pose_1pct" => 48 + 2 * 128,
                "pose_100pct" => 48 + 200 * 128,
                "churn_1pct" => 48 + 2 * (128 + 16),
                _ => unreachable!(),
            };
            assert_eq!(sample.wire_bytes, expected_bytes);
        }
    }
}
