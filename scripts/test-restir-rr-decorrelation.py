"""Execute Prime's production RR output math with Slang's CPU backend.

Independent Decimal/Fraction expectations check the actual shader consumer,
including inactive null pointers and deterministic Bernoulli selection. This
does not execute NGX or claim image quality, strict unbiasedness, or GPU speed.
"""

import argparse
from decimal import Decimal, localcontext
from fractions import Fraction
import itertools
import json
import math
import os
from pathlib import Path
import random
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "crates/prime-vulkan/shaders"
FIXTURE = ROOT / "crates/prime-vulkan/tests/shaders/restir_rr_decorrelation_math.slang"
DRIVER = r'''
#define _CRT_SECURE_NO_WARNINGS
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "rr-math.cpp"
int main(int argc, char** argv) {
    if (argc != 4) return 2;
    unsigned count = std::strtoul(argv[1], nullptr, 10);
    using U4 = Vector<uint32_t, 4>;
    std::vector<U4> input(count * 6), output(count * 4);
    FILE* file = std::fopen(argv[2], "rb");
    if (!file) return 3;
    if (std::fread(input.data(), sizeof(U4), input.size(), file) != input.size()) return 4;
    std::fclose(file);
    U4 config(0, count, 0, 0);
    GlobalParams_0 params{};
    params.inputs_0 = {input.data(), input.size()};
    params.outputs_0 = {output.data(), output.size()};
    params.dispatch_0 = &config;
    ComputeVaryingInput varying{};
    varying.endGroupID = {count, 1, 1};
    main_0(&varying, nullptr, &params);
    file = std::fopen(argv[3], "wb");
    if (!file) return 5;
    if (std::fwrite(output.data(), sizeof(U4), output.size(), file) != output.size()) return 6;
    std::fclose(file);
    return 0;
}
'''


def bits(value):
    return struct.unpack("<I", struct.pack("<f", value))[0]


def number(value):
    return struct.unpack("<f", struct.pack("<I", value))[0]


def quantize(value):
    return number(bits(value))


def random_reference(pixel, frame):
    # Independent scalar TEA/LCG and bit-by-bit Morton definition.
    v0 = sum(((pixel[axis] >> bit) & 1) << (2 * bit + axis)
             for bit in range(16) for axis in range(2))
    v1, total = frame ^ 0xA511E9B3, 0
    mask = 0xFFFFFFFF
    for _ in range(16):
        total = (total + 0x9E3779B9) & mask
        v0 = (v0 + (((v1 << 4) + 0xA341316C) ^ (v1 + total)
                    ^ ((v1 >> 5) + 0xC8013EA4))) & mask
        v1 = (v1 + (((v0 << 4) + 0xAD90777D) ^ (v0 + total)
                    ^ ((v0 >> 5) + 0x7E95761E))) & mask
    return (((v0 * 1664525 + 1013904223) & mask) >> 8) * 2 ** -24


def expected(row):
    curve = list(map(number, row[:4]))
    bound = number(row[4])
    enabled, mode, bias, detect = row[8:12]
    smoothed, reused = map(number, row[12:14])
    age, flags = row[14:16]
    initial = list(map(number, row[16:20]))
    active = bool(flags & 1 and enabled & 1 and mode != 0 and curve[0] > 0)
    raw = Fraction(min(age, 31), 40)
    ema = Fraction.from_float(min(1, max(0, curve[2])))
    smooth = raw if not flags & 4 else ema * raw + (1 - ema) * Fraction.from_float(smoothed)
    p = Decimal(0)
    if active:
        factor = Decimal.from_float(min(1, max(0, curve[0])))
        p = factor
        if mode == 2:
            s = Decimal.from_float(min(1, max(0, smoothed)))
            exponent = Decimal.from_float(max(0, curve[1]))
            p = min(Decimal(1), 4 * factor * (Decimal(1) if exponent == 0 else s ** exponent))
        if detect and flags & 2:
            p = Decimal(1)
    # Full-reservoir UCW limiting, independently of the narrow RGB scale helper.
    color = initial[:3]
    if bias and age == 0 and initial[3] > 0:
        integrand = [Fraction.from_float(v) / Fraction.from_float(initial[3]) for v in color]
        weight = min(Fraction.from_float(initial[3]),
                     Fraction.from_float(max(0, bound)) * Fraction.from_float(max(0, reused)))
        color = [float(v * weight) for v in integrand]
    random_value = random_reference(row[20:22], row[22])
    consumer = color if random_value < float(p) else [7, 11, 13]
    s = Decimal.from_float(min(1, max(1e-6, curve[3])))
    multiplier = Decimal(10) / s - 9
    return [float(raw), float(smooth), float(p), float(multiplier),
            *color, random_value, *consumer, float(active), *initial]


def make_row(*, age=0, mode=2, enabled=1, temporal=True, previous=True,
             factor=.4, exponent=.5, ema=.2, strength=.7, bound=15,
             bias=True, detect=True, firefly=False, smoothed=.1, reused=.1,
             initial=(2, 3, -.5, 1), pixel=(13, 21), frame=57):
    return (*map(bits, (factor, exponent, ema, strength)), bits(bound), 0, 0, 0,
            enabled, mode, int(bias), int(detect), bits(smoothed), bits(reused), age,
            int(temporal) | (int(firefly) << 1) | (int(previous) << 2),
            *map(bits, initial), *pixel, frame, 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangc", default=os.environ.get("SLANGC") or shutil.which("slangc"))
    parser.add_argument("--cxx", default=shutil.which("clang++"))
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/restir-settings-2026-10-05/rr-cpu")
    args = parser.parse_args()
    if not args.slangc or not args.cxx:
        parser.error("Slang and clang++ are required")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def run(command):
        subprocess.run([str(part) for part in command], check=True, cwd=ROOT)

    run([args.slangc, FIXTURE, "-I", SHADERS, "-entry", "main", "-stage", "compute",
         "-target", "cpp", "-O2", "-o", output / "rr-math.cpp"])
    driver = output / "driver.cpp"
    driver.write_text(DRIVER, encoding="utf8", newline="\n")
    executable = output / ("rr-math.exe" if os.name == "nt" else "rr-math")
    run([args.cxx, "-std=c++17", "-O2", driver, "-o", executable])

    rows = [make_row(age=age, mode=mode, enabled=enabled, temporal=temporal,
                     exponent=exponent, ema=ema, strength=strength, firefly=firefly,
                     detect=detect, smoothed=smoothed)
            for age, mode, enabled, temporal, exponent, ema, strength, firefly, detect, smoothed
            in itertools.product((0, 1, 8, 31, 40, 300), (0, 1, 2), (0, 1, 2, 3),
                                 (False, True), (0, .5, 2), (0, .2, 1), (.02, .7, 1),
                                 (False, True), (False, True), (0, .025, .775, 1))]
    for factor, exponent, ema, strength, bound, bias, previous, weight, reused in itertools.product(
            (-.4, 0, .4, 1, 2), (-1, 0, .5), (-.2, 1.2), (0, 1),
            (0, 15), (False, True), (False, True), (0, 1, 4, 100), (-1, 0, .1)):
        rows.append(make_row(factor=factor, exponent=exponent, ema=ema, strength=strength,
                             bound=bound, bias=bias, previous=previous, reused=reused,
                             initial=(2 * weight, 3 * weight, -.5 * weight, weight)))
    rng = random.Random(0xA511E9B3)
    for i in range(8192):
        rows.append(make_row(mode=1, detect=False, pixel=(rng.randrange(1 << 32),
                                                          rng.randrange(1 << 32)),
                             frame=rng.randrange(1 << 32), age=i % 32))
    (output / "input.bin").write_bytes(b"".join(struct.pack("<24I", *row) for row in rows))
    run([executable, len(rows), output / "input.bin", output / "output.bin"])
    results = list(struct.iter_unpack("<16I", (output / "output.bin").read_bytes()))
    maximum_error = [0.] * 16
    selected = 0
    with localcontext() as context:
        context.prec = 48
        for index, (row, words) in enumerate(zip(rows, results, strict=True)):
            targets = expected(row)
            for column, (word, target) in enumerate(zip(words, targets, strict=True)):
                actual = number(word)
                maximum_error[column] = max(maximum_error[column], abs(actual - target))
                assert math.isclose(actual, target, abs_tol=2e-6, rel_tol=3e-6), (
                    index, column, row, actual, target)
            assert words[7] == bits(targets[7]), (index, "final RNG differs")
            assert words[12:] == row[16:20], (index, "initial/history was mutated")
            if index >= len(rows) - 8192:
                selected += int(number(words[8]) != 7)
    # Statistical bound supplements exact per-seed Bernoulli outcome checks.
    p = quantize(.4)
    assert abs(selected - 8192 * p) < 6 * math.sqrt(8192 * p * (1 - p))
    report = dict(cases=len(rows), independent_random_seeds=8192, selected=selected,
                  maximum_absolute_error=maximum_error,
                  boundary="Actual production Slang CPU math/consumer; no GPU, NGX or image correlation claim.")
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf8")
    print(f"ReSTIR RR CPU math: PASS ({len(rows)} cases, 8192 exact RNG/Bernoulli seeds, inactive null pointers)")


if __name__ == "__main__":
    main()
