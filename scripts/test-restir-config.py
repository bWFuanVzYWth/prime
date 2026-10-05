"""Run production ReSTIR config/RIS math through Slang's CPU backend, without GPU."""

import argparse
import json
import math
import os
import random
import shutil
import struct
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "crates/prime-vulkan/shaders"
FIXTURE = ROOT / "crates/prime-vulkan/tests/shaders/restir_config_math.slang"
DRIVER = r'''
#define _CRT_SECURE_NO_WARNINGS
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "config-math.cpp"
int main(int argc, char** argv) {
    if (argc != 8) return 2;
    unsigned mode = std::strtoul(argv[1], nullptr, 10);
    unsigned count = std::strtoul(argv[2], nullptr, 10);
    unsigned seed = std::strtoul(argv[3], nullptr, 10);
    unsigned age = std::strtoul(argv[4], nullptr, 10);
    using U4 = Vector<uint32_t, 4>;
    std::vector<U4> input(count * 2), output(count * 2);
    FILE* file = std::fopen(argv[5], "rb");
    if (!file) return 3;
    if (std::fread(input.data(), sizeof(U4), input.size(), file) != input.size()) return 4;
    std::fclose(file);
    U4 config(mode, count, seed, age);
    GlobalParams_0 params{};
    params.inputs_0 = {input.data(), input.size()};
    params.outputs_0 = {output.data(), output.size()};
    params.dispatch_0 = &config;
    ComputeVaryingInput varying{};
    varying.endGroupID = {count, 1, 1};
    main_0(&varying, nullptr, &params);
    file = std::fopen(argv[6], "wb");
    if (!file) return 5;
    std::fwrite(output.data(), sizeof(U4), output.size(), file);
    std::fclose(file);
    return 0;
}
'''


def u(value):
    return struct.unpack("<I", struct.pack("<f", value))[0]


def f(value):
    return struct.unpack("<f", struct.pack("<I", value))[0]


def lcg(seed):
    return (seed * 1664525 + 1013904223) & 0xFFFFFFFF


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangc", default=os.environ.get("SLANGC") or shutil.which("slangc"))
    parser.add_argument("--cxx", default=shutil.which("clang++"))
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/restir-config-math")
    args = parser.parse_args()
    if not args.slangc or not args.cxx:
        parser.error("Slang and clang++ are required")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def run(command):
        subprocess.run([str(part) for part in command], check=True, cwd=ROOT)

    run([args.slangc, FIXTURE, "-I", SHADERS, "-entry", "main", "-stage", "compute",
         "-target", "cpp", "-O2", "-o", output / "config-math.cpp"])
    driver = output / "driver.cpp"
    driver.write_text(DRIVER, encoding="utf8", newline="\n")
    executable = output / "config-math.exe"
    run([args.cxx, "-std=c++17", "-O2", driver, "-o", executable])

    def execute(mode, rows, seed=0, age=0):
        data = b"".join(struct.pack("<8I", *row) for row in rows)
        (output / "input.bin").write_bytes(data)
        run([executable, mode, len(rows), seed, age, output / "input.bin", output / "output.bin", "unused"])
        return list(struct.iter_unpack("<8I", (output / "output.bin").read_bytes()))

    rng = random.Random(0xC0715)
    defaults = execute(4, [(0,) * 8])[0]
    assert defaults == (20, 3, 1, 1, u(0.0002), u(0.2), u(0.2), u(0.0)), defaults
    rows = []
    for history in (1, 20, 100):
        for fresh in (1, 2, 16):
            for rounds in (0, 1, 4):
                for requested, ready in ((0, 0), (1, 0), (1, 4), (1, 7)):
                    for power in (0.0, 0.1, 0.5, 1.0):
                        for duplicate in (0, 1, 144, 288):
                            rows.append((history, 3, rounds, fresh, requested, ready, u(power), duplicate))
    for row, result in zip(rows, execute(0, rows, seed=3, age=27), strict=True):
        h, _, rounds, fresh, requested, ready, power, duplicate = row
        assert result[:3] == (fresh + rounds + 2, fresh, fresh + 5), (row, result)
        expected = h if not requested or not ready & 4 else h + (1 - h) * (duplicate / 288) ** f(power)
        assert math.isclose(f(result[3]), expected, abs_tol=2e-5, rel_tol=2e-6), (row, result, expected)
        assert result[4:6] == ((28 if ready & 2 else 1), 0), (row, result)

    threshold_rows = []
    for _ in range(4096):
        for sigma in (0.0, 0.2):
            threshold_rows.append((u(0.0002), u(sigma), u(0.2), u(sigma), rng.randrange(1 << 32), 0, 0, 0))
    maximum_error = 0.0
    for row, result in zip(threshold_rows, execute(1, threshold_rows), strict=True):
        seed = row[4]
        expected = []
        for mean, sigma in ((f(row[0]), f(row[1])), (f(row[2]), f(row[3]))):
            if sigma == 0:
                expected.append(mean)
            else:
                seed = lcg(seed)
                x = (seed >> 8) * 2 ** -24
                seed = lcg(seed)
                y = (seed >> 8) * 2 ** -24
                expected.append(mean + math.sqrt(-2 * math.log(x)) * math.cos(2 * math.pi * y) * mean * sigma)
        assert result[2] == seed, (row, result)
        for actual, target in zip(map(f, result[:2]), expected, strict=True):
            maximum_error = max(maximum_error, abs(actual - target))
            assert math.isclose(actual, target, abs_tol=2e-7, rel_tol=3e-6), (row, actual, target)

    stochastic_rows = []
    for _ in range(8192):
        location = (rng.uniform(-2, 1922), rng.uniform(-2, 1082))
        seed = rng.randrange(1 << 32)
        for enabled in (0, 1):
            stochastic_rows.append((u(location[0]), u(location[1]), enabled, 0, seed, 0, 0, 0))
    for row, result in zip(stochastic_rows, execute(2, stochastic_rows), strict=True):
        seed = row[4]
        expected = [f(row[0]), f(row[1])]
        if row[2]:
            for axis in range(2):
                seed = lcg(seed)
                expected[axis] = f(u(expected[axis] - 0.5)) + (seed >> 8) * 2 ** -24
        assert result[2] == seed
        assert result[:2] == tuple(u(value) for value in expected), (row, result, expected)

    fresh_results = []
    for count in (1, 2, 4, 16):
        results = execute(3, [(0, 0, 0, count, 0, 0, 0, 0)] * 32768, seed=31)
        colors = [tuple(map(f, row[:3])) for row in results]
        expected = tuple(map(f, results[0][4:7]))
        means = []
        for axis in range(3):
            mean = sum(color[axis] for color in colors) / len(colors)
            variance = sum((color[axis] - mean) ** 2 for color in colors) / (len(colors) - 1)
            standard_error = math.sqrt(variance / len(colors))
            assert abs(mean - expected[axis]) <= 6 * standard_error + 3e-6, (count, axis, mean, expected, standard_error)
            means.append({"mean": mean, "expected": expected[axis], "standard_error": standard_error})
        assert all(f(row[3]) == 1.0 for row in results)
        fresh_results.append({"count": count, "independent_seed_chains": len(results), "channels": means})
    report = {"configuration_cases": len(rows), "threshold_cases": len(threshold_rows),
              "threshold_max_absolute_error": maximum_error, "stochastic_cases": len(stochastic_rows),
              "fresh_ris": fresh_results,
              "boundary": "Pure production math on CPU; no visibility, GPU timeline, or image correlation claim."}
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf8")
    print(f"ReSTIR config CPU math: PASS ({len(rows)} configs, {len(threshold_rows)} thresholds, {len(stochastic_rows)} donors, 4 fresh RIS means)")


if __name__ == "__main__":
    main()
