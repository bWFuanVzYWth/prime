"""Package sparse, linear-light regression samples from the pinned WGSL solver."""
import base64
import gzip
import json
import math
import struct


def f32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def pack_reference(work, destination, params):
    destination.mkdir(parents=True, exist_ok=True)
    cases = json.loads((work / "cases.json").read_text())
    payload = bytearray(struct.pack("<I", len(cases) * 192))
    directions = []
    for case, (height, elevation, yaw, pitch) in enumerate(cases):
        light = (work / f"light-{case}.bin").read_bytes()
        trans = (work / f"trans-{case}.bin").read_bytes()
        assert len(light) == len(trans) == 192 * 16
        yaw, pitch, elevation = [f32(f32(a) * f32(math.pi / 180)) for a in (yaw, pitch, elevation)]
        forward = [math.sin(yaw) * math.cos(pitch), math.sin(pitch), math.cos(yaw) * math.cos(pitch)]
        right = [math.cos(yaw), 0, -math.sin(yaw)]
        up = [-math.sin(yaw) * math.sin(pitch), math.cos(pitch), -math.cos(yaw) * math.sin(pitch)]
        tangent = math.tan(f32(f32(85) * f32(math.pi / 180)) * 0.5)
        for y in range(12):
            for x in range(16):
                u, v = (x + 0.5) / 16 * 2 - 1, (y + 0.5) / 12 * 2 - 1
                ray = [forward[k] + right[k] * u * tangent * 16 / 12 - up[k] * v * tangent for k in range(3)]
                length = math.sqrt(sum(t * t for t in ray))
                direction = struct.pack("<8f", *(t / length for t in ray), height,
                                        0, math.sin(elevation), math.cos(elevation), 0)
                directions.append(direction)
                payload += direction
                offset = (y * 16 + x) * 16
                payload += light[offset:offset + 16] + trans[offset:offset + 16]
    write(destination / "transport-reference.bin.gz.b64", payload)

    scale = [12.5 / sum(params["solar"][i] * params["rgb"][4 * i + k] for i in range(4)) for k in range(3)]
    coordinates = [(x, y) for y in range(0, 256, 4) for x in range(0, 256, 8)]
    # Duplicate chart endpoints and their neighbors expose horizon branch errors.
    coordinates += [(x, y) for y in (94, 95, 96, 97, 190, 191, 192, 193, 254, 255)
                    for x in (0, 1, 2, 32, 128, 254, 255)]
    payload = bytearray(struct.pack("<II", len(cases), len(coordinates)))
    for case, (height, elevation, _, _) in enumerate(cases):
        payload += struct.pack("<ff", height, elevation)
        raw = (work / f"sky-{case}.bin").read_bytes()
        for x, y in coordinates:
            rgb = struct.unpack_from("<3f", raw, (y * 256 + x) * 16)
            payload += struct.pack("<II3f", x, y, *(math.exp(v) * scale[k] for k, v in enumerate(rgb)))
    write(destination / "sky-reference.bin.gz.b64", payload)
    payload = bytearray()
    for case in range(len(cases)):
        projection = (work / f"projection-{case}.bin").read_bytes()
        for pixel in range(192):
            payload += directions[case * 192 + pixel]
            rgb = struct.unpack_from("<3f", projection, pixel * 16)
            payload += struct.pack("<3f", *(rgb[k] * scale[k] for k in range(3)))
    write(destination / "projection-reference.bin.gz.b64", payload)


def write(path, payload):
    path.write_bytes(base64.encodebytes(gzip.compress(payload, mtime=0)))
