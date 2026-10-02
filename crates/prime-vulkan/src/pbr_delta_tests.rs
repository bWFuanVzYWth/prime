//! Actual production Slang checks against the retained Full entry and full energy states.
use super::{Context, shader_tests::run};

const CODE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/pbr_delta.spv"));
const CASES: u32 = 74_088;
// Signed zero has identical ray geometry. Bit 8 still rejects any numeric difference.
const DIRECTION_BITS: u32 = 128;

#[test]
#[ignore = "requires Vulkan; compares narrow delta/guide energy with the production Full closure"]
fn gpu_pbr_delta_and_guide_contracts() {
    let context = Context::new().unwrap();
    let input: Vec<_> = (0..CASES).collect();
    let output = run(&context, CODE, &input, CASES as usize * 4, [0, CASES], None);
    let mut delta = 0;
    let mut guide = 0;
    let mut tir = 0;
    let mut failures = Vec::new();
    let mut masks = [0_u32; 9];
    for (case, result) in output.as_chunks::<4>().0.iter().enumerate() {
        if result[0] & !DIRECTION_BITS != 0 {
            failures.push((case as u32, result[0]));
        }
        for (bit, count) in masks.iter_mut().enumerate() {
            *count += u32::from(result[0] & (1 << bit) != 0);
        }
        delta += result[1];
        guide += result[2];
        tir += result[3];
    }
    if !failures.is_empty() {
        println!(
            "PBR failure masks 1..256: {masks:?}; failures={}",
            failures.len()
        );
        let diagnostic: Vec<_> = failures.iter().take(32).map(|&(case, _)| case).collect();
        for mode in 2..=5 {
            let values = run(
                &context,
                CODE,
                &diagnostic,
                diagnostic.len() * 4,
                [mode, diagnostic.len() as u32],
                None,
            );
            for (index, result) in values.as_chunks::<4>().0.iter().enumerate() {
                println!(
                    "case={} mask={} mode={mode} xyz/flags={result:08x?}",
                    diagnostic[index], failures[index].1
                );
            }
        }
        panic!(
            "PBR contract failures: bit 0 class, 1 Full, 2 albedo, 3 pair, 4 expectation, \
                5 guide independence, 6 validity, 7 informational bit mismatch, \
                8 exact numeric mismatch; {masks:?}"
        );
    }
    assert!(delta > 10_000 && guide > 10_000 && tir > 500);
    let edges: Vec<_> = (0..9).collect();
    let output = run(&context, CODE, &edges, 9 * 4, [1, 9], None);
    for (case, result) in output.as_chunks::<4>().0.iter().enumerate() {
        assert_eq!(result[0], 0, "delta/guide edge contract {case}");
    }
    println!(
        "PBR narrow contracts: {CASES} cases; {delta} delta, {guide} valid guides, {tir} TIR; \
         9 edges; signed-zero direction differences={}; exact numeric mismatches={}",
        masks[7], masks[8]
    );
}
