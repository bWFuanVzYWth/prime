use std::hint::black_box;
use std::num::NonZeroU16;
use std::time::{Duration, Instant};

use rectangle_decomposition::{QuadLeaf64, SparseOptimalScratch64, SparseQuadError};

const EDGE: u8 = 64;
const EDGE_USIZE: usize = 64;
const PIXELS: usize = EDGE_USIZE * EDGE_USIZE;
const HOLES_7_8: usize = PIXELS / 8;
const CASES: usize = 1000;
const CASES_F64: f64 = 1000.0;

fn main() -> Result<(), SparseQuadError> {
    let cases = low_discrepancy_cases();
    let unique_cases = unique_case_count(&cases);
    let mut scratch = SparseOptimalScratch64::new();

    for leaves in &cases {
        let rectangles = scratch.decompose_borrowed(black_box(leaves.as_slice()))?;
        black_box(rectangles.len());
    }

    let mut total = Duration::ZERO;
    let mut worst = Duration::ZERO;
    let mut worst_case = 0usize;
    let mut rects_at_worst = 0usize;

    for (case, leaves) in cases.iter().enumerate() {
        let start = Instant::now();
        let rectangles = scratch.decompose_borrowed(black_box(leaves.as_slice()))?;
        let elapsed = start.elapsed();
        total += elapsed;
        if elapsed > worst {
            worst = elapsed;
            worst_case = case;
            rects_at_worst = rectangles.len();
        }
        black_box(rectangles.len());
    }

    println!("cases {CASES}");
    println!("unique_cases {unique_cases}");
    println!("worst_case {worst_case}");
    println!("worst_us {:.3}", duration_us(worst));
    println!("avg_us {:.3}", duration_us(total) / CASES_F64);
    println!("rects_at_worst {rects_at_worst}");

    Ok(())
}

fn unique_case_count(cases: &[Vec<QuadLeaf64>]) -> usize {
    let mut fingerprints = Vec::with_capacity(cases.len());
    for leaves in cases {
        fingerprints.push(case_fingerprint(leaves));
    }
    fingerprints.sort_unstable();
    fingerprints.dedup();
    fingerprints.len()
}

fn case_fingerprint(leaves: &[QuadLeaf64]) -> u64 {
    let mut hash = 0x6a09_e667_f3bc_c909u64;
    for leaf in leaves {
        let packed = u64::from(leaf.u)
            | (u64::from(leaf.v) << 8)
            | (u64::from(leaf.lod) << 16)
            | (u64::from(leaf.value.get()) << 24);
        hash ^= splitmix64_value(packed.wrapping_add(hash));
    }
    hash
}

fn low_discrepancy_cases() -> Vec<Vec<QuadLeaf64>> {
    let mut cases = Vec::with_capacity(CASES);
    let mut seed = 0x9e37_79b9_7f4a_7c15u64;
    for case in 0..CASES {
        let scramble_u = u8::try_from(splitmix64(&mut seed) & 63).unwrap_or(0);
        let shift_v = u8::try_from(splitmix64(&mut seed) & 63).unwrap_or(0);
        let sample_offset = u32::try_from(splitmix64(&mut seed)).unwrap_or(0);
        let stride = (u32::try_from(splitmix64(&mut seed)).unwrap_or(0) | 1).max(1);
        cases.push(low_discrepancy_holes_7_8_leaves(
            case,
            scramble_u,
            shift_v,
            sample_offset,
            stride,
        ));
    }
    cases
}

fn low_discrepancy_holes_7_8_leaves(
    case: usize,
    scramble_u: u8,
    shift_v: u8,
    sample_offset: u32,
    stride: u32,
) -> Vec<QuadLeaf64> {
    let Some(value) = NonZeroU16::new(1) else {
        return Vec::new();
    };

    let holes = low_discrepancy_holes(case, scramble_u, shift_v, sample_offset, stride);
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

fn low_discrepancy_holes(
    case: usize,
    scramble_u: u8,
    shift_v: u8,
    sample_offset: u32,
    stride: u32,
) -> [bool; PIXELS] {
    let mut holes = [false; PIXELS];
    let mut hole_count = 0usize;
    let mut sample = sample_offset.wrapping_add(u32::try_from(case).unwrap_or(0));

    while hole_count < HOLES_7_8 {
        let u = radical_inverse_base2_64(sample) ^ scramble_u;
        let v = radical_inverse_base3_64(sample).wrapping_add(shift_v) & 63;
        let index = usize::from(v) * EDGE_USIZE + usize::from(u);
        if let Some(hole) = holes.get_mut(index)
            && !*hole
        {
            *hole = true;
            hole_count += 1;
        }
        sample = sample.wrapping_add(stride);
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

const fn splitmix64(seed: &mut u64) -> u64 {
    *seed = seed.wrapping_add(0x9e37_79b9_7f4a_7c15);
    splitmix64_value(*seed)
}

const fn splitmix64_value(mut value: u64) -> u64 {
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    value ^ (value >> 31)
}

fn duration_us(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000_000.0
}
