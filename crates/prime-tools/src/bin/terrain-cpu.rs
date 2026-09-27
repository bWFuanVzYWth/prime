//! Section publication + conditional scene translation, without Java, FFM or GPU work.
use prime_scene::{SourceScene, protocol::MAGIC};
use std::{
    alloc::{GlobalAlloc, Layout, System},
    error::Error,
    hint::black_box,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Instant,
};

struct CountedSystem;
static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static REALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static REQUESTED: AtomicU64 = AtomicU64::new(0);
// SAFETY: All operations delegate unchanged to System; counters never access allocations.
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
    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let pointer = unsafe { System.realloc(pointer, layout, size) };
        if !pointer.is_null() {
            REALLOCATIONS.fetch_add(1, Ordering::Relaxed);
            REQUESTED.fetch_add(size as u64, Ordering::Relaxed);
        }
        pointer
    }
    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) };
    }
}
#[global_allocator]
static ALLOCATOR: CountedSystem = CountedSystem;
fn allocations() -> [u64; 3] {
    [
        ALLOCATIONS.load(Ordering::Relaxed),
        REALLOCATIONS.load(Ordering::Relaxed),
        REQUESTED.load(Ordering::Relaxed),
    ]
}

struct Options {
    sections: u32,
    updates: u32,
    quads: u32,
    samples: u32,
    warmup: u32,
    csv: String,
}
impl Options {
    fn parse() -> Result<Self, Box<dyn Error>> {
        let mut options = Self {
            sections: 1024,
            updates: 36,
            quads: 64,
            samples: 60,
            warmup: 10,
            csv: "artifacts/exclusive-renderer/terrain/cpu.csv".into(),
        };
        let mut args = std::env::args().skip(1);
        while let Some(key) = args.next() {
            if key == "--help" {
                println!(
                    "terrain-cpu [--sections 1024 --updates 36 --quads 64 --samples 60 --warmup 10 --csv output.csv]\nThree layers per section, deterministic fixture; same content, one layer tint change, all layers geometry change. Measures SourceScene.submit plus conditional translate when source revision changes, including prior snapshot retirement. Packet construction, retained-Arc checks, CSV, Java/FFM/GPU excluded; never interpreted as FPS. Use --no-default-features."
                );
                std::process::exit(0);
            }
            let value = args.next().ok_or("missing argument value")?;
            match key.as_str() {
                "--sections" => options.sections = value.parse()?,
                "--updates" => options.updates = value.parse()?,
                "--quads" => options.quads = value.parse()?,
                "--samples" => options.samples = value.parse()?,
                "--warmup" => options.warmup = value.parse()?,
                "--csv" => options.csv = value,
                _ => return Err(format!("unknown option {key}").into()),
            }
        }
        if options.sections == 0
            || options.updates == 0
            || options.updates > options.sections
            || options.quads == 0
            || options.sections as u64 * options.quads as u64 * 6 > 8_000_000
            || !(1..=1000).contains(&options.samples)
            || options.warmup > 100
        {
            return Err("invalid fixture extent or sample count".into());
        }
        Ok(options)
    }
}
fn header(op: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, prime_scene::protocol::ABI_VERSION, op, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(1_u64.to_le_bytes());
    bytes
}
fn vertices(quads: u32, tint: u8, moved: bool) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(quads as usize * 96);
    for q in 0..quads {
        let x = (q % 16) as f32;
        let z = ((q / 16) % 16) as f32;
        let y = (q / 256) as f32 + if moved { 0.125 } else { 0.0 };
        for [u, v] in [[0_f32, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]] {
            for value in [x + u, y, z + v] {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend([tint, 128, 64, 255]);
            bytes.extend(u.to_le_bytes());
            bytes.extend(v.to_le_bytes());
        }
    }
    bytes
}
fn packets(options: &Options, key: u64, sequence: u64, stage: &str, variant: bool) -> Vec<Vec<u8>> {
    let origin = [
        ((key - 1) % 32) as f64 * 16.0,
        64.0,
        ((key - 1) / 32) as f64 * 16.0,
    ];
    let mut packets = Vec::new();
    let mut whole = header(8);
    whole.extend(key.to_le_bytes());
    whole.extend(sequence.to_le_bytes());
    for value in origin {
        whole.extend(value.to_le_bytes());
    }
    whole.extend(3_u32.to_le_bytes());
    whole.extend(0_u32.to_le_bytes());
    for layer in 0..3_u32 {
        let raw = vertices(
            options.quads,
            if stage == "tint_one_layer" && layer == 1 && variant {
                120
            } else {
                255
            },
            stage == "geometry_all_layers" && variant,
        );
        for value in [layer, 0, layer, 4, options.quads * 4, 24, 0, 12, 16, 0] {
            whole.extend(value.to_le_bytes());
        }
        whole.extend(raw);
    }
    packets.push(whole);
    packets
}
fn main() -> Result<(), Box<dyn Error>> {
    let options = Options::parse()?;
    let mode = "atomic";
    let mut csv = String::from(
        "mode,stage,sections,updated_sections,quads_per_layer,sample,warmup,packets,wire_bytes,submit_ns,translate_ns,total_ns,allocations,reallocations,requested_bytes,source_revision_delta,retained_updated_layers\n",
    );
    for stage in ["identical", "tint_one_layer", "geometry_all_layers"] {
        let mut source = SourceScene::default();
        source.submit(&header(1))?;
        for key in 1..=u64::from(options.sections) {
            for packet in packets(&options, key, 2, "identical", false) {
                source.submit(&packet)?;
            }
        }
        let mut translated = source.translate([0.0; 3])?;
        let mut times = Vec::new();
        for sample in 0..options.warmup + options.samples {
            let packets: Vec<_> = (1..=u64::from(options.updates))
                .flat_map(|key| {
                    packets(
                        &options,
                        key,
                        4 + u64::from(sample) * 2,
                        stage,
                        sample.is_multiple_of(2),
                    )
                })
                .collect();
            let leases: Vec<_> = (1..=u64::from(options.updates))
                .flat_map(|key| (0..3).map(move |layer| (key, layer)))
                .map(|key| (key, translated.meshes[&key].triangles.clone()))
                .collect();
            let previous_revision = source.revision();
            let before = allocations();
            let start = Instant::now();
            for packet in &packets {
                source.submit(black_box(packet))?;
            }
            let submitted = Instant::now();
            if previous_revision != source.revision() {
                translated = source.translate([0.0; 3])?;
            }
            black_box(&translated);
            let finished = Instant::now();
            let after = allocations();
            let submit_ns = (submitted - start).as_nanos();
            let translate_ns = (finished - submitted).as_nanos();
            let retained = leases
                .iter()
                .filter(|(key, lease)| Arc::ptr_eq(lease, &translated.meshes[key].triangles))
                .count();
            let expected_retained = {
                match stage {
                    "identical" => options.updates * 3,
                    "tint_one_layer" => options.updates * 2,
                    _ => 0,
                }
            };
            assert_eq!(retained, expected_retained as usize);
            assert_eq!(
                translated.triangle_count(),
                options.sections as usize * options.quads as usize * 6
            );
            let wire: usize = packets.iter().map(Vec::len).sum();
            csv.push_str(&format!("{mode},{stage},{},{},{},{sample},{},{},{wire},{submit_ns},{translate_ns},{},{},{},{},{},{retained}\n",options.sections,options.updates,options.quads,u32::from(sample<options.warmup),packets.len(),submit_ns+translate_ns,after[0]-before[0],after[1]-before[1],after[2]-before[2],source.revision()-previous_revision));
            if sample >= options.warmup {
                times.push(submit_ns + translate_ns);
            }
        }
        times.sort_unstable();
        eprintln!(
            "terrain-cpu mode={mode} stage={stage} p50_ms={:.6} p95_ms={:.6} max_ms={:.6} n={}",
            times[(times.len() * 50).div_ceil(100) - 1] as f64 / 1e6,
            times[(times.len() * 95).div_ceil(100) - 1] as f64 / 1e6,
            times[times.len() - 1] as f64 / 1e6,
            times.len()
        );
    }
    let path = std::path::Path::new(&options.csv);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, csv)?;
    Ok(())
}
