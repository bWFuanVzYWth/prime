"""Validate actual production ReSTIR SPIR-V and its host uniform/BDA layout.

Run after cargo build with the generated restir_*.spv files as arguments.
The packed BDA layout requires scalarBlockLayout on the logical Vulkan device.
"""

import argparse
import json
import shutil
import struct
import subprocess
from pathlib import Path


def inspect(path):
    data = path.read_bytes()
    words = struct.unpack(f"<{len(data) // 4}I", data)
    assert words[0] == 0x07230203, path
    names, offsets, strides, pointers, capabilities = {}, {}, {}, {}, set()
    cursor = 5
    while cursor < len(words):
        count, opcode = words[cursor] >> 16, words[cursor] & 0xFFFF
        args = words[cursor + 1 : cursor + count]
        if opcode == 17:  # OpCapability
            capabilities.add(args[0])
        elif opcode == 5:
            names[args[0]] = struct.pack(f"<{len(args) - 1}I", *args[1:]).split(b"\0")[0].decode()
        elif opcode == 71 and args[1] == 6:
            strides[args[0]] = args[2]
        elif opcode == 72 and args[2] == 35:
            offsets.setdefault(args[0], {})[args[1]] = args[3]
        elif opcode == 32 and args[1] == 5349:
            pointers[args[0]] = args[2]
        cursor += count
    uniform = [0, 128, 256, *range(272, 392, 8), 400, 416, 424, 432, 448]
    # Ray-query renderer never needs RayTracingKHR pipelines or shaderInt64.
    assert not ({4479, 11} & capabilities), (path, capabilities)
    uniforms = [key for key, name in names.items() if name == "RestirParameters_std140"]
    assert len(uniforms) == 1, (path, uniforms)
    assert [offsets[uniforms[0]][i] for i in range(len(uniform))] == uniform, path
    expected = {
        "PathReservoir": (80, [0, 4, 8, 20, 24, 28, 32, 36, 52, 56, 68]),
        "RestirPrimary": (20, [0, 16]),
        "RestirReplayData": (60, [0, 44]),
        "PrecomputedShiftData": (20, [0, 12, 16]),
        "NeighborValidMask": (8, [0, 4]),
    }
    found = {}
    for pointer, element in pointers.items():
        name = names.get(element, "").removesuffix("_natural")
        if name in expected and pointer in strides:
            stride, members = expected[name]
            assert strides[pointer] == stride, (path, name, strides[pointer])
            assert [offsets[element][i] for i in range(len(members))] == members, (path, name)
            found[name] = stride
    assert found.keys() == expected.keys(), (path, found)
    return {"uniform_offsets": uniform, "bda_strides": found}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("spirv", nargs="+", type=Path)
    parser.add_argument("--spirv-val", default=shutil.which("spirv-val"), required=False)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.spirv_val:
        parser.error("spirv-val is required")
    results = {}
    for path in args.spirv:
        subprocess.run([args.spirv_val, "--target-env", "vulkan1.3", "--scalar-block-layout", str(path)], check=True)
        results[path.name] = inspect(path)
    result = json.dumps(results, indent=2)
    if args.output:
        args.output.write_text(result + "\n", encoding="utf8")
    print(f"ReSTIR production SPIR-V layout/validation: PASS ({len(results)} modules)")


if __name__ == "__main__":
    main()
