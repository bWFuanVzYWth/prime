"""Execute the production ReSTIR Slang math on CPU and verify its Vulkan BDA ABI.

Requires Slang, clang++, and spirv-val (or their path options). Does not create a
window, start Minecraft, or substitute source-text matching for mathematical behavior.
The dense BDA layout requires scalarBlockLayout enabled on the logical device.
"""

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
TEST_SHADERS = ROOT / "crates/prime-vulkan/tests/shaders"
ASSET = ROOT / "crates/prime-vulkan/assets/restir/paired-neighbors-3-16.bytes"
SIZES = (254, 232, 196)


def run(command):
    subprocess.run([str(part) for part in command], check=True, cwd=ROOT)


def tea(v0, v1):
    total = 0
    for _ in range(16):
        total = (total + 0x9E3779B9) & 0xFFFFFFFF
        v0 = (v0 + (((v1 << 4) + 0xA341316C) ^ (v1 + total) ^ ((v1 >> 5) + 0xC8013EA4))) & 0xFFFFFFFF
        v1 = (v1 + (((v0 << 4) + 0xAD90777D) ^ (v0 + total) ^ ((v0 >> 5) + 0x7E95761E))) & 0xFFFFFFFF
    return v0


def morton(x, y):
    return sum(((x >> bit) & 1) << (2 * bit) | ((y >> bit) & 1) << (2 * bit + 1) for bit in range(16))


def verify_bda(path):
    data = path.read_bytes()
    words = struct.unpack(f"<{len(data) // 4}I", data)
    names, strides, pointers, offsets = {}, {}, {}, {}
    cursor = 5
    while cursor < len(words):
        length, opcode = words[cursor] >> 16, words[cursor] & 0xFFFF
        args = words[cursor + 1 : cursor + length]
        if opcode == 5:  # OpName
            names[args[0]] = struct.pack(f"<{len(args) - 1}I", *args[1:]).split(b"\0")[0].decode()
        elif opcode == 71 and args[1] == 6:  # OpDecorate ArrayStride
            strides[args[0]] = args[2]
        elif opcode == 72 and args[2] == 35:  # OpMemberDecorate Offset
            offsets.setdefault(args[0], {})[args[1]] = args[3]
        elif opcode == 32 and args[1] == 5349:  # OpTypePointer PhysicalStorageBuffer
            pointers[args[0]] = args[2]
        cursor += length
    expected = {
        # These scalar-aligned vec3 fields need Vulkan scalarBlockLayout. The
        # absence of an extra OpCapability does not prove that feature is enabled.
        "PathReservoir": (80, [0, 4, 8, 20, 24, 28, 32, 36, 52, 56, 68]),
        "ReconnectionData": (44, [0, 16, 28, 40]),
        "PrecomputedShiftData": (20, [0, 12, 16]),
        "HitInfo": (16, [0]),
    }
    found = {}
    for pointer, element in pointers.items():
        name = names.get(element, "").removesuffix("_natural")
        if name in expected and pointer in strides:
            stride, members = expected[name]
            assert strides[pointer] == stride, (name, strides[pointer], stride)
            actual = [offsets[element][index] for index in range(len(members))]
            assert actual == members, (name, actual, members)
            found[name] = stride
    assert found.keys() == expected.keys(), found
    return found


DRIVER = r'''
#define _CRT_SECURE_NO_WARNINGS
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "restir_math.cpp"
static_assert(sizeof(PathReservoir_0) == 80);
static_assert(sizeof(ReconnectionData_0) == 44);
static_assert(sizeof(PrecomputedShiftData_0) == 20);
int main(int argc, char** argv) {
    if (argc != 7) return 2;
    unsigned mode = std::strtoul(argv[1], nullptr, 10);
    unsigned count = std::strtoul(argv[2], nullptr, 10);
    unsigned seed = std::strtoul(argv[3], nullptr, 10);
    using U4 = Vector<uint32_t, 4>;
    std::vector<U4> input(count * 2), output(count * 2);
    FILE* file = std::fopen(argv[4], "rb");
    if (!file) return 3;
    std::fread(input.data(), sizeof(U4), input.size(), file);
    std::fclose(file);
    std::vector<uint16_t> packed(3 * 256 * 256);
    file = std::fopen(argv[6], "rb");
    if (!file) return 4;
    if (std::fread(packed.data(), sizeof(uint16_t), packed.size(), file) != packed.size()) return 5;
    std::fclose(file);
    std::vector<uint32_t> table(packed.begin(), packed.end());
    std::vector<PathReservoir_0> reservoirs(count);
    std::vector<ReconnectionData_0> reconnections(count);
    std::vector<PrecomputedShiftData_0> shifts(count);
    U4 config(mode, count, seed, 0);
    GlobalParams_0 params{};
    params.inputs_0 = {input.data(), input.size()};
    params.outputs_0 = {output.data(), output.size()};
    params.pairingDeltaTextures_0 = {table.data(), table.size()};
    params.reservoirLayout_0 = {reservoirs.data(), reservoirs.size()};
    params.reconnectionLayout_0 = {reconnections.data(), reconnections.size()};
    params.shiftLayout_0 = {shifts.data(), shifts.size()};
    params.config_0 = &config;
    ComputeVaryingInput varying{};
    varying.endGroupID = {count, 1, 1};
    main_0(&varying, nullptr, &params);
    file = std::fopen(argv[5], "wb");
    if (!file) return 6;
    std::fwrite(output.data(), sizeof(U4), output.size(), file);
    std::fclose(file);
    return 0;
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangc", default=os.environ.get("SLANGC") or shutil.which("slangc"))
    parser.add_argument("--cxx", default=shutil.which("clang++") or shutil.which("clang"))
    parser.add_argument("--spirv-val", help="SPIR-V validator; defaults to Slang's directory or PATH")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/restir-pt/math-tests")
    args = parser.parse_args()
    if not args.slangc or not args.cxx:
        parser.error("Slang and clang++ are required")
    if not args.spirv_val:
        sibling_validator = Path(args.slangc).resolve().with_name("spirv-val.exe" if os.name == "nt" else "spirv-val")
        args.spirv_val = str(sibling_validator) if sibling_validator.is_file() else shutil.which("spirv-val")
    if not args.spirv_val:
        parser.error("spirv-val is required")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    common = [args.slangc, TEST_SHADERS / "restir_math.slang", "-I", SHADERS, "-entry", "main", "-stage", "compute"]
    run(common + ["-target", "cpp", "-O2", "-o", output / "restir_math.cpp"])
    run(common + ["-target", "spirv", "-profile", "sm_6_6", "-emit-spirv-directly", "-O3", "-o", output / "restir_math.spv"])
    run([args.slangc, TEST_SHADERS / "restir_bda_layout.slang", "-I", SHADERS, "-entry", "main", "-stage", "compute", "-target", "spirv", "-profile", "sm_6_6", "-emit-spirv-directly", "-O3", "-g3", "-o", output / "bda_layout.spv"])
    strides = verify_bda(output / "bda_layout.spv")
    run([args.spirv_val, "--target-env", "vulkan1.3", output / "restir_math.spv"])
    run([args.spirv_val, "--target-env", "vulkan1.3", "--scalar-block-layout", output / "bda_layout.spv"])
    driver = output / "driver.cpp"
    driver.write_text(DRIVER, encoding="utf-8", newline="\n")
    exe = output / ("restir-math.exe" if os.name == "nt" else "restir-math")
    run([args.cxx, "-std=c++17", "-O2", driver, "-o", exe])

    def execute(mode, count, data=b"", seed=0):
        (output / "input.bin").write_bytes(data)
        run([exe, mode, count, seed, output / "input.bin", output / "output.bin", ASSET])
        return (output / "output.bin").read_bytes()

    rng = random.Random(0x5EED)
    cases = [(rng.randrange(1 << 20), rng.randrange(1 << 20), rng.randrange(1 << 32)) for _ in range(4096)]
    result = execute(0, len(cases), b"".join(struct.pack("<4I", x, y, sample, 0) for x, y, sample in cases))
    for index, (x, y, sample) in enumerate(cases):
        state = tea(morton(x, y), sample)
        expected = []
        for _ in range(8):
            state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
            expected.append(state)
        assert struct.unpack_from("<8I", result, index * 32) == tuple(expected)
    print("PASS TinyUniformSampleGenerator: 4096 seeds, 8 exact uint draws each")

    count = 1048576
    result = execute(1, count)
    values = list(struct.iter_unpack("<4f", result[: count * 16]))
    target = (1.5, 2.3, 3.4)
    means = [sum(value[channel] for value in values) / count for channel in range(3)]
    for channel, expected in enumerate(target):
        variance = sum((value[channel] - means[channel]) ** 2 for value in values) / count
        assert abs(means[channel] - expected) <= max(0.002, 7 * math.sqrt(variance / count)), (means, target)
    assert all(value[3] == 1 for value in values)
    print("PASS RIS mean:", means, "target", target)
    result = execute(6, count)
    merge_values = list(struct.iter_unpack("<4f", result[: count * 16]))
    merge_target = (2.24, 2.56, 2.88)
    merge_means = [sum(value[channel] for value in merge_values) / count for channel in range(3)]
    for channel, expected in enumerate(merge_target):
        variance = sum((value[channel] - merge_means[channel]) ** 2 for value in merge_values) / count
        assert abs(merge_means[channel] - expected) <= max(0.002, 7 * math.sqrt(variance / count)), (merge_means, merge_target)
    assert all(value[3] == 2 for value in merge_values)
    print("PASS RIS reservoir merge mean with Jacobian and MIS:", merge_means, "target", merge_target)
    assert struct.unpack_from("<4I", execute(2, 1)) == (0, 0, 0, 0)
    print("PASS zero contribution, empty finalize and zero merge")

    cases = [[2 ** rng.uniform(-12, 12) for _ in range(8)] for _ in range(4096)]
    result = execute(3, len(cases), b"".join(struct.pack("<8f", *case) for case in cases))
    for index, case in enumerate(cases):
        spatial, neighbor, temporal, temporal_neighbor, jacobian, inverse, _, _ = struct.unpack_from("<8f", result, index * 32)
        assert abs(spatial + neighbor - 1) < 2e-6
        assert abs(temporal + temporal_neighbor - 1) < 2e-6
        expected = (case[6] / case[7]) / (case[4] / case[5])
        assert abs(jacobian / expected - 1) < 5e-6
        assert abs(jacobian * inverse - 1) < 5e-6
    print("PASS pairwise spatial/temporal partition and reciprocal geometry Jacobian: 4096 cases")

    cases = [(x, y, index) for index, size in enumerate(SIZES) for y in range(size) for x in range(size)]
    data = b"".join(struct.pack("<4I", x, y, index, 0) for x, y, index in cases)
    for seed in (0, 1, 3, 11, 0xFFFFFFFF):
        result = execute(4, len(cases), data, seed)
        for index, (dx, dy, ix, iy) in enumerate(struct.iter_unpack("<4i", result[: len(cases) * 16])):
            assert dx == -ix and dy == -iy, (seed, cases[index], dx, dy, ix, iy)
    print("PASS original paired-neighbor reciprocity:", len(cases), "entries x 5 transforms")
    result = execute(7, 65536)
    for index, (packed, before, after, lengths) in enumerate(struct.iter_unpack("<4I", result[: 65536 * 16])):
        expected = ((index & 63) << 20) | (((index >> 6) & 31) << 26) | (index & 65535) | ((index & 1) << 16) | (((index >> 1) & 1) << 17) | (((index >> 2) & 3) << 18)
        assert (packed, before, after, lengths) == (expected, index & 63, (index >> 6) & 31, index)
    print("PASS reservoir flag ABI: 65536 packed combinations")
    identity_cases = [(epoch, live_count, primitive, accepted)
                      for epoch in (0, 1, 7, 0xFFFFFFFE, 0xFFFFFFFF)
                      for live_count in (0, 1, 2, 100, 0xFFFFFFFF)
                      for primitive in (0, 1, 2, 99, 100, 0xFFFFFFFE, 0xFFFFFFFF)
                      for accepted in (0, 1, 6, 7, 0xFFFFFFFE, 0xFFFFFFFF)]
    identity_cases += [tuple(rng.randrange(1 << 32) for _ in range(4)) for _ in range(10000)]
    result = execute(8, len(identity_cases), b"".join(struct.pack("<4I", *case) for case in identity_cases))
    for index, (epoch, live_count, primitive, accepted) in enumerate(identity_cases):
        expected = int(epoch != 0 and epoch <= accepted and primitive < live_count)
        assert struct.unpack_from("<4I", result, index * 16) == (expected, 0, 0, 0)
    print("PASS scene identity watermark/liveness/primitive bounds:", len(identity_cases), "cases")
    report = {"status": "passed", "rng_cases": 4096, "ris_paths": count, "ris_mean": means, "ris_merge_mean": merge_means, "pairwise_cases": 4096, "paired_entries": len(cases), "paired_transforms": 5, "flag_cases": 65536, "identity_cases": len(identity_cases), "bda_strides": strides, "scalar_block_layout_required": True, "execution": "Slang-generated C++ CPU; SPIR-V validation with scalarBlockLayout and binary ABI; no GPU/game execution"}
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS Vulkan BDA ABI:", strides)


if __name__ == "__main__":
    main()
