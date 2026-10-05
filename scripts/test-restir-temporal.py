"""Execute production ReSTIR math on independent CPU seed chains with an analytic target.

This fixture has three discrete paths, receiver-dependent integrands and unit shift
Jacobian. It tests correlated RIS normalization and retained lineages, not real
scene support, visibility, continuous path Jacobians or rendered-image correctness.
No Vulkan, window, Minecraft, shader catalog or persistent production state is used.
"""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "crates/prime-vulkan/shaders"
FIXTURE = ROOT / "crates/prime-vulkan/tests/cpu/restir_temporal.slang"
ASSET = ROOT / "crates/prime-vulkan/assets/restir/paired-neighbors-3-16.bytes"
WIDTH, HEIGHT = 32, 16

DRIVER = r'''
#define _CRT_SECURE_NO_WARNINGS
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "restir_temporal.cpp"
int main(int argc, char** argv) {
    if (argc != 7) return 2;
    unsigned mode = std::strtoul(argv[1], nullptr, 10);
    unsigned count = std::strtoul(argv[2], nullptr, 10);
    unsigned frames = std::strtoul(argv[3], nullptr, 10);
    unsigned seed = std::strtoul(argv[4], nullptr, 10);
    std::vector<uint16_t> packed(3 * 256 * 256);
    FILE* file = std::fopen(argv[5], "rb");
    if (!file) return 3;
    if (std::fread(packed.data(), sizeof(uint16_t), packed.size(), file) != packed.size()) return 4;
    std::fclose(file);
    std::vector<uint32_t> table(packed.begin(), packed.end());
    using F4 = Vector<float, 4>;
    using U4 = Vector<uint32_t, 4>;
    std::vector<F4> output(count * 5);
    U4 config(mode, count, frames, seed);
    GlobalParams_0 params{};
    params.pairedDeltas_0 = {table.data(), table.size()};
    params.results_0 = {output.data(), output.size()};
    params.config_0 = &config;
    ComputeVaryingInput varying{};
    varying.endGroupID = {count, 1, 1};
    main_0(&varying, nullptr, &params);
    file = std::fopen(argv[6], "wb");
    if (!file) return 5;
    if (std::fwrite(output.data(), sizeof(F4), output.size(), file) != output.size()) return 6;
    std::fclose(file);
    return 0;
}
'''


def run(command):
    subprocess.run([str(value) for value in command], check=True, cwd=ROOT)


def integrand(pixel, path):
    x = pixel % WIDTH / (WIDTH - 1)
    y = pixel // WIDTH / (HEIGHT - 1)
    base = ((.25, .5, 1), (1.5, .75, .3), (32, 16, 8))[path]
    gain = (.5 + x, .75 + .5 * y, 1.25 - .5 * x)[path]
    return tuple(value * gain for value in base)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangc", default=os.environ.get("SLANGC") or shutil.which("slangc"))
    parser.add_argument("--cxx", default=shutil.which("clang++") or shutil.which("clang"))
    parser.add_argument("--chains", type=int, default=128)
    parser.add_argument("--frames", type=int, default=64)
    parser.add_argument("--seed", type=lambda value: int(value, 0), default=0x13572468)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/restir-followup-2026-10-05/temporal-math")
    args = parser.parse_args()
    if not args.slangc or not args.cxx:
        parser.error("Slang and clang++ are required")
    if args.chains < 16 or args.frames < 16 or not 0 <= args.seed <= 0xFFFFFFFF:
        parser.error("Need at least 16 independent chains/frames and a uint32 seed")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    run([args.slangc, FIXTURE, "-I", SHADERS, "-entry", "main", "-stage", "compute", "-target", "cpp", "-O2", "-o", output / "restir_temporal.cpp"])
    driver = output / "driver.cpp"
    driver.write_text(DRIVER, encoding="utf-8", newline="\n")
    exe = output / ("restir-temporal.exe" if os.name == "nt" else "restir-temporal")
    run([args.cxx, "-std=c++17", "-O2", driver, "-o", exe])
    target = [sum(integrand(pixel, path)[channel] for pixel in range(WIDTH * HEIGHT) for path in range(3)) / (WIDTH * HEIGHT) for channel in range(3)]
    report = {
        "status": "pending", "execution": "production Slang compiled to C++; no GPU/game",
        "domain": "3 discrete paths; receiver-dependent full-support RGB integrands; J=1",
        "chains": args.chains, "frames": args.frames, "warm_frames": args.frames // 2,
        "extent": [WIDTH, HEIGHT], "seed": args.seed, "sequence_stride": 1000003,
        "target": target, "path_probabilities": [.8, .19, .01],
        "independence": "seed histories have separate state; TEA/LCG statistical independence is not assumed proven",
        "hashes": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in [FIXTURE, ASSET, *[SHADERS / f"restir/{name}.slang" for name in ("rng", "reservoir", "pairwise", "paired_neighbors")]]},
        "profiles": {},
    }
    for mode, label in enumerate(("initial", "temporal", "temporal_spatial")):
        destination = output / f"{label}.bin"
        started = time.perf_counter()
        run([exe, mode, args.chains, args.frames, args.seed, ASSET, destination])
        rows = list(struct.iter_unpack("<20f", destination.read_bytes()))
        assert len(rows) == args.chains
        assert all(math.isfinite(value) for row in rows for value in row), label
        for row in rows:
            assert all(abs(row[4 + channel] - expected) < 1e-4 for channel, expected in enumerate(target)), (label, row, target)
            assert row[11] == args.frames - args.frames // 2
        means = [statistics.mean(row[channel] for row in rows) for channel in range(3)]
        # One observation is one complete seed-chain mean, not one correlated frame or pixel.
        errors = [statistics.stdev(row[channel] for row in rows) / math.sqrt(args.chains) for channel in range(3)]
        passed = [abs(mean - expected) <= max(7 * error, 0.002 * expected) for mean, error, expected in zip(means, errors, target)]
        lineage = statistics.mean(row[3] for row in rows)
        covariance = statistics.mean(row[12] for row in rows)
        variance_left = statistics.mean(row[13] for row in rows)
        variance_right = statistics.mean(row[14] for row in rows)
        correlation = covariance / math.sqrt(variance_left * variance_right)
        temporal_covariance = statistics.mean(row[16] for row in rows)
        current_variance = statistics.mean(row[17] for row in rows)
        previous_variance = statistics.mean(row[18] for row in rows)
        temporal_correlation = temporal_covariance / math.sqrt(current_variance * previous_variance)
        temporal_lineage = statistics.mean(row[19] for row in rows)
        report["profiles"][label] = {
            "passed": all(passed), "mean": means, "chain_standard_error": errors,
            "adjacent_lineage_equal_fraction": lineage,
            "adjacent_red_covariance_about_analytic_mean": covariance,
            "red_variance_left_right": [variance_left, variance_right],
            "adjacent_red_correlation": correlation,
            "lag1_red_covariance_about_analytic_mean": temporal_covariance,
            "lag1_red_variance_current_previous": [current_variance, previous_variance],
            "lag1_red_correlation": temporal_correlation,
            "lag1_lineage_equal_fraction": temporal_lineage,
            "cpu_execution_seconds": time.perf_counter() - started,
            "chain_rows": rows,
        }
        print(label, "mean", means, "target", target, "chainSE", errors,
              "lineage_equal", lineage, "adjacent_correlation", correlation,
              "lag1_lineage_equal", temporal_lineage, "lag1_correlation", temporal_correlation, flush=True)
        if mode != 2:
            assert lineage == 0, (label, lineage)
        if mode == 0:
            assert temporal_lineage == 0, (label, temporal_lineage)
    report["status"] = "passed" if all(profile["passed"] for profile in report["profiles"].values()) else "failed"
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    assert report["status"] == "passed", "Analytic mean mismatch; inspect complete per-chain evidence"
    print("PASS correlated reservoir means across initial/temporal/spatial profiles; covariance is measured, not asserted absent")


if __name__ == "__main__":
    main()
