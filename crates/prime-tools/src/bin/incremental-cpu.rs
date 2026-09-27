//! Same-build snapshot/incremental CPU comparison. Real op-8 readiness; no Java/FFM/GPU.
use prime_scene::{
    SourceScene,
    incremental::TranslatedScene,
    protocol::MAGIC,
    translation::{TerrainLimits, TerrainPlanner},
};
use std::{
    alloc::{GlobalAlloc, Layout, System},
    error::Error,
    hint::black_box,
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
    time::Instant,
};

struct Allocator;
static COUNT: AtomicBool = AtomicBool::new(false);
static CALLS: AtomicU64 = AtomicU64::new(0);
static BYTES: AtomicU64 = AtomicU64::new(0);
fn allocation(size: usize) {
    if COUNT.load(Ordering::Relaxed) {
        CALLS.fetch_add(1, Ordering::Relaxed);
        BYTES.fetch_add(size as u64, Ordering::Relaxed);
    }
}
// SAFETY: The instrumentation never accesses allocations; all operations delegate to System.
unsafe impl GlobalAlloc for Allocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        allocation(layout.size());
        unsafe { System.alloc(layout) }
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        allocation(layout.size());
        unsafe { System.alloc_zeroed(layout) }
    }
    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        allocation(size);
        unsafe { System.realloc(pointer, layout, size) }
    }
    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) };
    }
}
#[global_allocator]
static ALLOCATOR: Allocator = Allocator;

fn header(op: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, 1, op, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(1_u64.to_le_bytes());
    bytes
}

fn section(cell: u64, slot: u64, sequence: u64, red: Option<u8>) -> Vec<u8> {
    let mut bytes = header(8);
    bytes.extend((cell * 64 + slot).to_le_bytes());
    bytes.extend(sequence.to_le_bytes());
    for value in [
        ((cell % 128) * 64 + (slot / 16) * 16) as f64,
        ((slot / 4 % 4) * 16) as f64,
        ((cell / 128) * 64 + (slot % 4) * 16) as f64,
    ] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(u32::from(red.is_some()).to_le_bytes());
    bytes.extend(0_u32.to_le_bytes());
    if let Some(red) = red {
        for value in [0_u32, 0, 0, 4, 4, 24, 0, 12, 16, 0] {
            bytes.extend(value.to_le_bytes());
        }
        for position in [[0_f32, 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]] {
            for value in position {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend([red, 255, 255, 255]);
            bytes.extend([0_u8; 8]);
        }
    }
    bytes
}

fn run(
    cells: u64,
    density: u64,
    samples: usize,
    incremental: bool,
    count: bool,
    csv: &mut String,
) -> Result<(), Box<dyn Error>> {
    let mode = if incremental {
        "incremental"
    } else {
        "snapshot"
    };
    let mut source = SourceScene::default();
    source.submit(&header(1))?;
    for cell in 0..cells {
        for slot in 0..64 {
            source.submit(&section(cell, slot, 1, (slot < density).then_some(255)))?;
        }
    }
    let mut translated = TranslatedScene::default();
    let mut snapshot = source.translate([0.; 3])?;
    let mut planner = TerrainPlanner::new(TerrainLimits {
        triangles_per_geometry: 1 << 25,
        geometry_records: 1 << 23,
    })?;
    translated.update(&mut source, [0.; 3])?;
    let plan = if incremental {
        planner.plan_input(translated.input())?
    } else {
        planner.plan(&snapshot)?
    };
    assert_eq!(plan.geometry.len(), cells as usize);
    planner.recycle(plan);
    for sample in 0..samples + 10 {
        let packet = section(
            0,
            0,
            sample as u64 + 2,
            Some(if sample % 2 == 0 { 127 } else { 255 }),
        );
        CALLS.store(0, Ordering::Relaxed);
        BYTES.store(0, Ordering::Relaxed);
        COUNT.store(count, Ordering::Relaxed);
        let start = Instant::now();
        source.submit(black_box(&packet))?;
        let submitted = Instant::now();
        let meshes_published = if incremental {
            translated.update(&mut source, [0.; 3])?.meshes_published
        } else {
            snapshot = source.translate([0.; 3])?;
            snapshot.meshes.len()
        };
        let translated_at = Instant::now();
        let plan = if incremental {
            planner.plan_input(translated.input())?
        } else {
            planner.plan(&snapshot)?
        };
        let work = (plan.geometry.len(), plan.meshes_visited, plan.cells_visited);
        black_box(&plan);
        planner.recycle(plan);
        let finished = Instant::now();
        COUNT.store(false, Ordering::Relaxed);
        let calls = CALLS.load(Ordering::Relaxed);
        let bytes = BYTES.load(Ordering::Relaxed);
        assert_eq!(work.0, 1);
        if incremental {
            assert_eq!(work, (1, density as usize, 1));
        }
        csv.push_str(&format!("{mode},{cells},{density},{sample},{},{count},{},{},{},{},{calls},{bytes},{meshes_published},{},{}\n",
            sample < 10, (submitted-start).as_nanos(), (translated_at-submitted).as_nanos(),
            (finished-translated_at).as_nanos(), (finished-start).as_nanos(), work.1, work.2));
    }
    Ok(())
}

fn main() -> Result<(), Box<dyn Error>> {
    let mut csv_path = "artifacts/incremental-cpu.csv".to_string();
    let mut samples = 100_usize;
    let mut args = std::env::args().skip(1);
    while let Some(key) = args.next() {
        match key.as_str() {
            "--help" => {
                println!(
                    "incremental-cpu [--samples 100 --csv artifacts/incremental-cpu.csv]\nCompares complete snapshot and production incremental paths in the same build; fixed one-mesh edits at 1/16/512 cells, density 1/64. Every cell has 64 real source positions. Separate timed and allocation-counted passes; 10 warmups, raw samples retained. Setup/packets/CSV excluded; submit+translation+terrain planning+plan recycling included. This is a CPU microbenchmark, not game FPS or GPU rendering performance."
                );
                return Ok(());
            }
            "--samples" => samples = args.next().ok_or("missing samples")?.parse()?,
            "--csv" => csv_path = args.next().ok_or("missing CSV path")?,
            _ => return Err(format!("unknown option {key}").into()),
        }
    }
    if !(1..=10_000).contains(&samples) {
        return Err("samples must be in 1..=10000".into());
    }
    let mut csv = String::from(
        "mode,cells,density,sample,warmup,allocation_instrumentation,submit_ns,translate_ns,plan_ns,total_ns,allocation_requests,requested_bytes,meshes_published,meshes_grouped,cells_grouped\n",
    );
    for count in [false, true] {
        for cells in [1, 16, 512] {
            for density in [1, 64] {
                for incremental in [false, true] {
                    run(cells, density, samples, incremental, count, &mut csv)?;
                }
            }
        }
    }
    let path = std::path::Path::new(&csv_path);
    if let Some(parent) = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, csv)?;
    eprintln!("CPU samples saved to {csv_path}; no game or graphics workload was started.");
    Ok(())
}
