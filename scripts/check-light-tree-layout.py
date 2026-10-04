"""Verify the emitted production distance-tree BDA strides and member offsets."""

import struct
import sys
from pathlib import Path


def verify(path):
    data = Path(path).read_bytes()
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
        "TreeNode": (24, [0, 12, 16, 20]),
        "TreePage": (48, [0, 8, 16, 28, 32, 36, 40, 44]),
        "TreeEmitter": (16, [0, 4, 8, 12]),
        "LightTree": (32, [0, 8, 16, 24, 28]),
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
    print("PASS distance-tree SPIR-V BDA ABI:", found)


if __name__ == "__main__":
    verify(sys.argv[1])
