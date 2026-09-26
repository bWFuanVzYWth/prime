use std::hint::black_box;
use std::num::NonZeroU16;
use std::time::{Duration, Instant};

use rectangle_decomposition::{
    QuadLeaf64, SparseDecomposeCounts, SparseDecomposeProfile, SparseDecomposeTimings,
    SparseOptimalScratch64, SparseQuadError,
};

const EDGE: u8 = 64;
const EDGE_USIZE: usize = 64;
const PIXELS: usize = EDGE_USIZE * EDGE_USIZE;
const HOLES_7_8: usize = PIXELS / 8;
const WARMUP_ITERATIONS: u32 = 32;
const ITERATIONS: u32 = 256;

fn main() -> Result<(), SparseQuadError> {
    let leaves = low_discrepancy_holes_7_8_leaves();
    let mut scratch = SparseOptimalScratch64::new();

    for _ in 0..WARMUP_ITERATIONS {
        let profile = scratch.decompose_profile(black_box(leaves.as_slice()))?;
        black_box(profile.counts.rectangles);
    }

    let mut timings = SparseDecomposeTimings::default();
    let mut counts = SparseDecomposeCounts::default();
    let wall_start = Instant::now();

    for _ in 0..ITERATIONS {
        let profile = scratch.decompose_profile(black_box(leaves.as_slice()))?;
        add_profile(&mut timings, &profile);
        counts = profile.counts;
        black_box(profile.counts.rectangles);
    }

    let wall = wall_start.elapsed();
    print_counts(counts);
    println!("iterations {ITERATIONS}");
    println!(
        "wall_us_per_iter {:.3}",
        duration_per_iter_us(wall, ITERATIONS)
    );
    println!(
        "total_us_per_iter {:.3}",
        duration_per_iter_us(timings.total, ITERATIONS)
    );

    print_stage("build_axis", timings.build_axis, timings.total);
    print_stage("extract_chords", timings.extract_chords, timings.total);
    print_stage("select_cuts", timings.select_cuts, timings.total);
    print_stage("partition", timings.partition, timings.total);
    print_select_stage("matching", timings.matching, timings.select_cuts);
    print_select_stage("emit_cuts", timings.emit_cuts, timings.select_cuts);
    print_select_stage("sort_cuts", timings.sort_cuts, timings.select_cuts);
    print_matching_stage("matching_greedy", timings.matching_greedy, timings.matching);
    print_matching_stage("matching_bfs", timings.matching_bfs, timings.matching);
    print_matching_stage(
        "matching_dfs_build",
        timings.matching_dfs_build,
        timings.matching,
    );
    print_matching_stage(
        "matching_dfs_search",
        timings.matching_dfs_search,
        timings.matching,
    );
    print_matching_stage("matching_cover", timings.matching_cover, timings.matching);
    print_matching_stage(
        "matching_collect",
        timings.matching_collect,
        timings.matching,
    );

    Ok(())
}

fn add_profile(timings: &mut SparseDecomposeTimings, profile: &SparseDecomposeProfile) {
    timings.total += profile.timings.total;
    timings.build_axis += profile.timings.build_axis;
    timings.extract_chords += profile.timings.extract_chords;
    timings.select_cuts += profile.timings.select_cuts;
    timings.matching += profile.timings.matching;
    timings.matching_greedy += profile.timings.matching_greedy;
    timings.matching_bfs += profile.timings.matching_bfs;
    timings.matching_dfs_build += profile.timings.matching_dfs_build;
    timings.matching_dfs_search += profile.timings.matching_dfs_search;
    timings.matching_cover += profile.timings.matching_cover;
    timings.matching_collect += profile.timings.matching_collect;
    timings.emit_cuts += profile.timings.emit_cuts;
    timings.sort_cuts += profile.timings.sort_cuts;
    timings.partition += profile.timings.partition;
}

fn print_counts(counts: SparseDecomposeCounts) {
    println!("leaves {}", counts.leaves);
    println!("row_intervals {}", counts.row_intervals);
    println!("column_intervals {}", counts.column_intervals);
    println!("chord_groups {}", counts.chord_groups);
    println!("horizontal_chords {}", counts.horizontal_chords);
    println!("vertical_chords {}", counts.vertical_chords);
    println!("horizontal_cuts {}", counts.horizontal_cuts);
    println!("vertical_cuts {}", counts.vertical_cuts);
    println!("rectangles {}", counts.rectangles);
    println!("greedy_matches {}", counts.greedy_matches);
    println!("matching_phases {}", counts.matching_phases);
    println!("matching_augmentations {}", counts.matching_augmentations);
}

fn print_stage(name: &str, duration: Duration, total: Duration) {
    println!(
        "{}_us_per_iter {:.3} pct_total {:.2}",
        name,
        duration_per_iter_us(duration, ITERATIONS),
        duration_pct(duration, total)
    );
}

fn print_select_stage(name: &str, duration: Duration, select: Duration) {
    println!(
        "{}_us_per_iter {:.3} pct_select {:.2}",
        name,
        duration_per_iter_us(duration, ITERATIONS),
        duration_pct(duration, select)
    );
}

fn print_matching_stage(name: &str, duration: Duration, matching: Duration) {
    println!(
        "{}_us_per_iter {:.3} pct_matching {:.2}",
        name,
        duration_per_iter_us(duration, ITERATIONS),
        duration_pct(duration, matching)
    );
}

fn duration_per_iter_us(duration: Duration, iterations: u32) -> f64 {
    duration.as_secs_f64() * 1_000_000.0 / f64::from(iterations)
}

fn duration_pct(duration: Duration, total: Duration) -> f64 {
    let total = total.as_secs_f64();
    if total == 0.0 {
        return 0.0;
    }
    duration.as_secs_f64() * 100.0 / total
}

fn low_discrepancy_holes_7_8_leaves() -> Vec<QuadLeaf64> {
    let Some(value) = NonZeroU16::new(1) else {
        return Vec::new();
    };

    let holes = low_discrepancy_holes();
    let mut leaves = Vec::with_capacity(PIXELS - HOLES_7_8);
    for v in 0..EDGE {
        for u in 0..EDGE {
            let index = usize::from(v) * EDGE_USIZE + usize::from(u);
            if holes.get(index).copied().is_some_and(|hole| !hole) {
                leaves.push(QuadLeaf64 {
                    u,
                    v,
                    lod: 0,
                    value,
                });
            }
        }
    }
    leaves
}

fn low_discrepancy_holes() -> [bool; PIXELS] {
    let mut holes = [false; PIXELS];
    let mut hole_count = 0usize;
    let mut sample = 0u32;

    while hole_count < HOLES_7_8 {
        let u = radical_inverse_base2_64(sample);
        let v = radical_inverse_base3_64(sample);
        let index = usize::from(v) * EDGE_USIZE + usize::from(u);
        if let Some(hole) = holes.get_mut(index)
            && !*hole
        {
            *hole = true;
            hole_count += 1;
        }
        sample += 1;
    }

    holes
}

fn radical_inverse_base2_64(mut value: u32) -> u8 {
    let mut reversed = 0u8;
    for _ in 0..6 {
        reversed = (reversed << 1) | u8::from((value & 1) != 0);
        value >>= 1;
    }
    reversed
}

fn radical_inverse_base3_64(mut value: u32) -> u8 {
    let mut numerator = 0u32;
    let mut denominator = 1u32;
    while denominator < 729 {
        numerator = numerator * 3 + value % 3;
        denominator *= 3;
        value /= 3;
    }
    let coord = (numerator * u32::from(EDGE)) / denominator;
    u8::try_from(coord).unwrap_or(0)
}
