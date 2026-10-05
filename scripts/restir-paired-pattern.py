"""Decode Falcor's 16-bit PNG neighbor tables without third-party dependencies."""

import argparse
import hashlib
import math
import struct
import subprocess
import zlib
from pathlib import Path


def decode_png(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("invalid PNG signature")
    payload = bytearray()
    cursor = 8
    width = height = 0
    while cursor < len(data):
        length = struct.unpack_from(">I", data, cursor)[0]
        kind = data[cursor + 4 : cursor + 8]
        chunk = data[cursor + 8 : cursor + 8 + length]
        crc = struct.unpack_from(">I", data, cursor + 8 + length)[0]
        if zlib.crc32(kind + chunk) != crc:
            raise ValueError("PNG CRC mismatch")
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", chunk
            )
            if (depth, color, compression, filtering, interlace) != (16, 0, 0, 0, 0):
                raise ValueError("expected noninterlaced 16-bit grayscale PNG")
        elif kind == b"IDAT":
            payload.extend(chunk)
        elif kind == b"IEND":
            break
        cursor += length + 12
    raw = zlib.decompress(payload)
    stride = width * 2
    if len(raw) != height * (stride + 1):
        raise ValueError("invalid image payload size")
    previous = bytearray(stride)
    pixels = bytearray()
    for y in range(height):
        base = y * (stride + 1)
        filter_type = raw[base]
        row = bytearray(raw[base + 1 : base + 1 + stride])
        for x in range(stride):
            a = row[x - 2] if x >= 2 else 0
            b = previous[x]
            c = previous[x - 2] if x >= 2 else 0
            if filter_type == 1:
                prediction = a
            elif filter_type == 2:
                prediction = b
            elif filter_type == 3:
                prediction = (a + b) // 2
            elif filter_type == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                prediction = a if pa <= pb and pa <= pc else b if pb <= pc else c
            elif filter_type == 0:
                prediction = 0
            else:
                raise ValueError("unknown PNG filter")
            row[x] = (row[x] + prediction) & 255
        pixels.extend(row)
        previous = row
    # PNG rows are top-down, matching Bitmap::createFromFile(path, true, None).
    values = struct.unpack(f">{width * height}H", pixels)
    return width, height, values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("falcor", type=Path)
    parser.add_argument("--output", type=Path, default=Path("crates/prime-vulkan/assets/restir/paired-neighbors-3-16.bytes"))
    parser.add_argument("--all", action="store_true", help="Pack all 1..5-neighbor, 5..50px original LUTs")
    parser.add_argument("--zstd", default="zstd", help="Zstd CLI used only to reproduce the optional LUT bank")
    args = parser.parse_args()
    if args.all:
        pack_all(args)
        return
    source = args.falcor / "Source/Modules/ReSTIRPathTracing/PairedReusePattern/neighborCount3-stdev16.0"
    result = bytearray()
    for index, expected in enumerate((254, 232, 196)):
        path = source / f"neighbor{index}.png"
        width, height, values = decode_png(path)
        if width != expected or height != expected:
            raise ValueError(f"unexpected neighbor {index} dimensions")
        # Preserve Falcor's 256*256 segment pitch and actual-size row indexing.
        result.extend(struct.pack(f"<{len(values)}H", *values))
        result.extend(bytes(2 * (256 * 256 - len(values))))
        print(path.name, width, height, hashlib.sha256(path.read_bytes()).hexdigest())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(result)
    print(args.output, len(result), hashlib.sha256(result).hexdigest())


def pack_all(args):
    # Independently compressed frames allow loading only the selected original table.
    entries, payloads = [], []
    offset = 16 + 50 * 48
    root = args.falcor / "Source/Modules/ReSTIRPathTracing/PairedReusePattern"
    for count in range(1, 6):
        for radius in range(5, 51, 5):
            sigma = math.sqrt(8 / (9 * math.pi)) * radius
            source = root / f"neighborCount{count}-stdev{sigma:.1f}"
            raw, sizes = bytearray(), []
            for index in range(count):
                width, height, values = decode_png(source / f"neighbor{index}.png")
                if width != height or not 1 <= width <= 256:
                    raise ValueError("unexpected paired-neighbor extent")
                # Check the source table's mutual inverse, including periodic edges.
                for y in range(height):
                    for x in range(width):
                        value = values[y * width + x]
                        dx, dy = (value & 255) - 128, (value >> 8) - 128
                        reverse = values[((y + dy) % height) * width + ((x + dx) % width)]
                        if (reverse & 255) - 128 != -dx or (reverse >> 8) - 128 != -dy:
                            raise ValueError(f"non-reciprocal original table: {source} / {index}")
                sizes.append(width)
                raw.extend(struct.pack(f"<{len(values)}H", *values))
                raw.extend(bytes(2 * (256 * 256 - len(values))))
            packed = subprocess.run([args.zstd, "-q", "-3", "-c"], input=raw,
                                    stdout=subprocess.PIPE, check=True).stdout
            entries.append(struct.pack("<8I2Q", count, radius, *(sizes + [0] * (5 - count)),
                                       0, offset, len(packed)))
            payloads.append(packed)
            offset += len(packed)
    result = b"PRPN0001" + struct.pack("<2I", len(entries), 0) + b"".join(entries + payloads)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(result)
    print(args.output, len(result), hashlib.sha256(result).hexdigest(), "50 reciprocal configurations")


if __name__ == "__main__":
    main()
