//! CPU-only measurements of actual op6 decoding and public dynamic translation.
//! Run with --no-default-features; no Minecraft, Engine/FFM, Vulkan or GPU calls.
use prime_scene::{
    protocol::{ABI_VERSION, MAGIC, MAX_PACKET_BYTES},
    scene::{DynamicScene, SourceScene, Triangle},
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
static FREED: AtomicU64 = AtomicU64::new(0);
static LIVE: AtomicU64 = AtomicU64::new(0);
static PEAK: AtomicU64 = AtomicU64::new(0);

fn allocated(bytes: usize) {
    ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
    REQUESTED.fetch_add(bytes as u64, Ordering::Relaxed);
    let live = LIVE.fetch_add(bytes as u64, Ordering::Relaxed) + bytes as u64;
    PEAK.fetch_max(live, Ordering::Relaxed);
}

// SAFETY: Every allocation operation delegates unchanged to System; bookkeeping
// uses allocation-free atomics and never examines or changes the returned memory.
unsafe impl GlobalAlloc for CountedSystem {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc(layout) };
        if !pointer.is_null() {
            allocated(layout.size());
        }
        pointer
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc_zeroed(layout) };
        if !pointer.is_null() {
            allocated(layout.size());
        }
        pointer
    }
    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) };
        LIVE.fetch_sub(layout.size() as u64, Ordering::Relaxed);
        FREED.fetch_add(layout.size() as u64, Ordering::Relaxed);
    }
    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let replacement = unsafe { System.realloc(pointer, layout, size) };
        if !replacement.is_null() {
            REALLOCATIONS.fetch_add(1, Ordering::Relaxed);
            REQUESTED.fetch_add(size as u64, Ordering::Relaxed);
            FREED.fetch_add(layout.size() as u64, Ordering::Relaxed);
            let live = if size >= layout.size() {
                LIVE.fetch_add((size - layout.size()) as u64, Ordering::Relaxed)
                    + (size - layout.size()) as u64
            } else {
                LIVE.fetch_sub((layout.size() - size) as u64, Ordering::Relaxed)
                    - (layout.size() - size) as u64
            };
            PEAK.fetch_max(live, Ordering::Relaxed);
        }
        replacement
    }
}

#[global_allocator]
static ALLOCATOR: CountedSystem = CountedSystem;

struct AllocationStart {
    allocations: u64,
    reallocations: u64,
    requested: u64,
    freed: u64,
    live: u64,
}
impl AllocationStart {
    fn begin() -> Self {
        let live = LIVE.load(Ordering::Relaxed);
        PEAK.store(live, Ordering::Relaxed);
        Self {
            allocations: ALLOCATIONS.load(Ordering::Relaxed),
            reallocations: REALLOCATIONS.load(Ordering::Relaxed),
            requested: REQUESTED.load(Ordering::Relaxed),
            freed: FREED.load(Ordering::Relaxed),
            live,
        }
    }
    fn finish(self) -> [u64; 8] {
        let peak = PEAK.load(Ordering::Relaxed);
        [
            ALLOCATIONS.load(Ordering::Relaxed) - self.allocations,
            REALLOCATIONS.load(Ordering::Relaxed) - self.reallocations,
            REQUESTED.load(Ordering::Relaxed) - self.requested,
            FREED.load(Ordering::Relaxed) - self.freed,
            self.live,
            peak,
            peak.saturating_sub(self.live),
            LIVE.load(Ordering::Relaxed),
        ]
    }
}

struct Options {
    objects: Vec<u32>,
    strides: Vec<u32>,
    samples: u32,
    warmup: u32,
    csv: String,
}
impl Options {
    fn parse() -> Result<Self, Box<dyn Error>> {
        let mut options = Self {
            objects: vec![1000, 10000, 20000],
            strides: vec![36],
            samples: 21,
            warmup: 3,
            csv: "artifacts/cpu-dynamic-protocol.csv".into(),
        };
        let mut args = std::env::args().skip(1);
        while let Some(arg) = args.next() {
            if arg == "--help" {
                println!(
                    "capture-cpu [--objects 1000,10000,20000] [--strides 36] [--samples 21] [--warmup 3] [--csv artifacts/cpu-dynamic-protocol.csv]\n24 quads / 96 vertices / 48 triangles per four-cuboid proxy. Actual op6 packet decoding; one span. Only sequence and one vertex change per sample. A prior DynamicScene Arc remains live until after translating its replacement. Measures SourceScene.submit, translate_dynamic and final old-snapshot retirement separately. No Engine/FFM/MC/source-generation/GPU work. Allocator counters cover requested Rust allocations, not RSS or allocator-internal transient memory. Use --no-default-features for a GPU-free dependency graph."
                );
                std::process::exit(0);
            }
            let value = args
                .next()
                .ok_or_else(|| format!("Missing value for {arg}"))?;
            match arg.as_str() {
                "--objects" => {
                    options.objects = value.split(',').map(str::parse).collect::<Result<_, _>>()?
                }
                "--strides" => {
                    options.strides = value.split(',').map(str::parse).collect::<Result<_, _>>()?
                }
                "--samples" => options.samples = value.parse()?,
                "--warmup" => options.warmup = value.parse()?,
                "--csv" => options.csv = value,
                _ => return Err(format!("Unknown option {arg}").into()),
            }
        }
        if options.objects.is_empty()
            || options.objects.contains(&0)
            || options.strides.is_empty()
            || options
                .strides
                .iter()
                .any(|stride| ![24, 36].contains(stride))
            || !(1..=1000).contains(&options.samples)
            || options.warmup > 100
        {
            return Err("Require nonzero object counts, strides=24 and/or36, samples=1..1000, warmup=0..100".into());
        }
        Ok(options)
    }
}

fn header(operation: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, ABI_VERSION, operation, 0] {
        bytes.extend_from_slice(&value.to_le_bytes());
    }
    bytes.extend_from_slice(&1u64.to_le_bytes());
    bytes
}

fn packet(objects: u32, stride: u32) -> Result<Vec<u8>, String> {
    let count = objects.checked_mul(96).ok_or("Vertex count overflow")?;
    let length = 96u64 + u64::from(count) * u64::from(stride);
    if length > MAX_PACKET_BYTES as u64 || u64::from(objects) * 48 > 8_000_000 {
        return Err("Fixture exceeds protocol packet/triangle budget".into());
    }
    let mut bytes = header(6);
    bytes.reserve(length as usize - bytes.len());
    bytes.extend_from_slice(&1u64.to_le_bytes());
    for value in [29_999_984.0_f64, 64.0, -16.0] {
        bytes.extend_from_slice(&value.to_le_bytes());
    }
    for value in [1u32, 0, 1, 0, 4, count, stride, 0, 12, 16] {
        bytes.extend_from_slice(&value.to_le_bytes());
    }
    // Four closed cuboids, a geometric complexity proxy rather than any captured
    // Minecraft chest/model. The final 12 bytes in stride36 are ignored source fields.
    let boxes = [
        ([0.0, 0.0, 0.0], [1.0, 0.7, 1.0]),
        ([0.0, 0.7, 0.0], [1.0, 1.0, 1.0]),
        ([0.44, 0.5, -0.06], [0.56, 0.8, 0.0]),
        ([0.05, 0.05, 0.05], [0.95, 0.15, 0.95]),
    ];
    for object in 0..objects {
        let offset = [
            (object % 128) as f32 * 1.25,
            0.0,
            (object / 128) as f32 * 1.25,
        ];
        for (lo, hi) in boxes {
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
                    for axis in 0..3 {
                        bytes.extend_from_slice(&(position[axis] + offset[axis]).to_le_bytes());
                    }
                    bytes.extend_from_slice(&[180, 128, 72, 255]);
                    for value in uv {
                        bytes.extend_from_slice(&value.to_le_bytes());
                    }
                    if stride == 36 {
                        bytes.extend_from_slice(&[0, 0, 0, 0, 240, 0, 240, 0, 0, 127, 0, 0]);
                    }
                }
            }
        }
    }
    assert_eq!(bytes.len(), length as usize);
    Ok(bytes)
}

fn nanos(start: Instant) -> u64 {
    start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
}
struct Sample {
    objects: u32,
    stride: u32,
    warmup: bool,
    iteration: u32,
    wire_bytes: usize,
    decode_ns: u64,
    translate_ns: u64,
    retire_ns: u64,
    total_ns: u64,
    allocation: [u64; 8],
}

fn run_case(options: &Options, objects: u32, stride: u32) -> Result<Vec<Sample>, String> {
    let mut bytes = packet(objects, stride)?;
    let mut source = SourceScene::default();
    source.submit(&header(1))?;
    let mut texture = header(4);
    for value in [1u32, 1, 1, 0] {
        texture.extend_from_slice(&value.to_le_bytes());
    }
    texture.extend_from_slice(&[255; 4]);
    source.submit(&texture)?;
    let mut previous = DynamicScene::default();
    let mut samples = Vec::with_capacity((options.samples + options.warmup) as usize);
    for frame in 0..options.samples + options.warmup {
        bytes[24..32].copy_from_slice(&(u64::from(frame) + 1).to_le_bytes());
        bytes[96..100]
            .copy_from_slice(&(if frame % 2 == 0 { 0.125_f32 } else { 0.126_f32 }).to_le_bytes());
        let allocation = AllocationStart::begin();
        let total = Instant::now();
        let decode = Instant::now();
        source.submit(black_box(&bytes))?;
        let decode_ns = nanos(decode);
        let translate = Instant::now();
        let replacement = source.translate_dynamic([29_999_872.0, 64.0, -16.0])?;
        let translate_ns = nanos(translate);
        let retire = Instant::now();
        drop(std::mem::replace(&mut previous, replacement));
        let retire_ns = nanos(retire);
        let total_ns = nanos(total);
        let allocation = allocation.finish();
        assert_eq!(previous.triangles.len(), objects as usize * 48);
        assert_eq!(
            source.revision(),
            2,
            "dynamic updates must leave static revision unchanged"
        );
        black_box(&previous);
        samples.push(Sample {
            objects,
            stride,
            warmup: frame < options.warmup,
            iteration: if frame < options.warmup {
                frame
            } else {
                frame - options.warmup
            },
            wire_bytes: bytes.len(),
            decode_ns,
            translate_ns,
            retire_ns,
            total_ns,
            allocation,
        });
    }
    Ok(samples)
}

fn percentile(values: &[u64], percent: usize) -> u64 {
    values[(values.len() * percent).div_ceil(100).saturating_sub(1)]
}

fn main() -> Result<(), Box<dyn Error>> {
    let options = Options::parse()?;
    eprintln!(
        "[capture-cpu] actual op6 bytes, 24quads/object, strides={:?}, no Engine/FFM/MC/GPU; retain one old translated Arc until replacement; allocator instrumented; concurrent game interference is not controlled",
        options.strides
    );
    let mut csv = String::from(
        "objects,quads,vertices,triangles,stride,wire_bytes,decoded_triangle_bytes,gpu_upload_bytes_estimate,warmup,iteration,submit_ns,translate_dynamic_ns,old_snapshot_retire_ns,decode_and_retire_ns,total_ns,allocations,reallocations,requested_bytes,freed_bytes,live_before_bytes,peak_live_bytes,extra_peak_bytes,live_after_bytes\n",
    );
    for &stride in &options.strides {
        for &objects in &options.objects {
            let samples = run_case(&options, objects, stride)?;
            let measured: Vec<_> = samples.iter().filter(|sample| !sample.warmup).collect();
            let mut times: Vec<_> = measured
                .iter()
                .map(|sample| sample.decode_ns + sample.retire_ns)
                .collect();
            times.sort_unstable();
            let translate_max = measured
                .iter()
                .map(|sample| sample.translate_ns)
                .max()
                .unwrap();
            eprintln!(
                "[capture-cpu] objects={objects} stride={stride} wire_MB={:.3} triangles={} decode+retire_ms p50={:.3} p95={:.3} max={:.3} translate_max_us={:.3} n={}",
                samples[0].wire_bytes as f64 / 1e6,
                objects * 48,
                percentile(&times, 50) as f64 / 1e6,
                percentile(&times, 95) as f64 / 1e6,
                times.last().unwrap().to_owned() as f64 / 1e6,
                translate_max as f64 / 1000.0,
                times.len()
            );
            for sample in samples {
                let triangles = u64::from(sample.objects) * 48;
                let mut cells = vec![
                    sample.objects.to_string(),
                    (sample.objects * 24).to_string(),
                    (sample.objects * 96).to_string(),
                    triangles.to_string(),
                    sample.stride.to_string(),
                    sample.wire_bytes.to_string(),
                    (triangles * std::mem::size_of::<Triangle>() as u64).to_string(),
                    (triangles * 140).to_string(),
                    u32::from(sample.warmup).to_string(),
                    sample.iteration.to_string(),
                    sample.decode_ns.to_string(),
                    sample.translate_ns.to_string(),
                    sample.retire_ns.to_string(),
                    (sample.decode_ns + sample.retire_ns).to_string(),
                    sample.total_ns.to_string(),
                ];
                cells.extend(sample.allocation.map(|value| value.to_string()));
                csv.push_str(&cells.join(","));
                csv.push('\n');
            }
        }
    }
    let path = std::path::Path::new(&options.csv);
    if let Some(parent) = path.parent().filter(|path| !path.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, csv)?;
    eprintln!("[capture-cpu] samples written to {}", path.display());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn both_source_layouts_decode_the_full_proxy_and_keep_one_prior_snapshot_alive() {
        for stride in [24, 36] {
            let options = Options {
                objects: vec![2],
                strides: vec![stride],
                samples: 2,
                warmup: 1,
                csv: String::new(),
            };
            let samples = run_case(&options, 2, stride).unwrap();
            assert_eq!(samples.len(), 3);
            assert!(samples[0].warmup);
            assert!(!samples[1].warmup);
            assert_eq!(samples[2].wire_bytes, 96 + 2 * 96 * stride as usize);
        }
    }
}
