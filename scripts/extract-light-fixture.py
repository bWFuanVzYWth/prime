"""Read-only Minecraft world -> explicit rectangle/visibility sampling proxy.

Requires numpy and numba. This is a coarse algorithm fixture, not the game renderer:
source outline boxes replace baked geometry; transparent/cutout blocks are omitted;
emission is constant monochrome using the current Prime light-level calibration.
Visibility is binary, sampled once at each of 4x4 subrectangle centers. The reference
integrates those piecewise-constant visibility cells, not the original world integral.
"""

import argparse
import csv
import gzip
import hashlib
import io
import json
import math
import struct
import time
import zlib
from collections import Counter
from pathlib import Path

import numpy as np
from numba import njit, prange, set_num_threads


def nbt(raw):
    stream = io.BytesIO(raw)

    def read(size):
        if size < 0:
            raise ValueError("Negative NBT size")
        result = stream.read(size)
        if len(result) != size:
            raise ValueError("Truncated NBT")
        return result

    def number(fmt):
        return struct.unpack(">" + fmt, read(struct.calcsize(">" + fmt)))[0]

    def string():
        return read(number("H")).decode("utf-8", errors="surrogatepass")

    def payload(kind):
        if 1 <= kind <= 6:
            return number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 7:
            return read(number("i"))
        if kind == 8:
            return string()
        if kind == 9:
            child, count = number("B"), number("i")
            if count < 0:
                raise ValueError("Negative NBT list")
            return [payload(child) for _ in range(count)]
        if kind == 10:
            result = {}
            while child := number("B"):
                name = string()
                result[name] = payload(child)
            return result
        if kind in (11, 12):
            return np.frombuffer(read(number("i") * (4 if kind == 11 else 8)),
                                 dtype=">i4" if kind == 11 else ">u8").copy()
        raise ValueError(f"Unsupported NBT tag {kind}")

    kind = number("B")
    string()
    result = payload(kind)
    if stream.read(1):
        raise ValueError("Trailing NBT bytes")
    return result


def state_key(state, defaults):
    # 26.3 stores defaults as a bare id and non-defaults as id/properties;
    # retain the older Name/Properties form for migrated Anvil chunks.
    if isinstance(state, str):
        name, explicit = state, {}
    else:
        name = state.get("id", state.get("Name", state.get("")))
        explicit = state.get("properties", state.get("Properties", {}))
    if name not in defaults:
        raise ValueError(f"Registry does not describe saved block {name}")
    props = defaults[name] | explicit
    return name + "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"


def chunks(path, bounds):
    """Modern Anvil chunks; refuse unknown compression instead of dropping geometry."""
    before = path.stat()
    raw = path.read_bytes()
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError(f"World changed during read: {path}")
    rx, rz = map(int, path.stem.split(".")[1:])
    for slot in range(1024):
        x, z = rx * 32 + slot % 32, rz * 32 + slot // 32
        if not (bounds[0] <= x < bounds[1] and bounds[2] <= z < bounds[3]):
            continue
        record = int.from_bytes(raw[slot * 4:slot * 4 + 4], "big")
        if record == 0:
            continue
        offset = (record >> 8) * 4096
        length = int.from_bytes(raw[offset:offset + 4], "big")
        kind = raw[offset + 4]
        payload = raw[offset + 5:offset + 4 + length]
        if kind & 128:
            raise ValueError("External Anvil chunks need explicit support")
        if kind == 1:
            payload = gzip.decompress(payload)
        elif kind == 2:
            payload = zlib.decompress(payload)
        elif kind != 3:
            raise ValueError(f"Unknown Anvil compression {kind}")
        yield x, z, nbt(payload)


def palette_values(section):
    source = section.get("block_states")
    if source is None:
        return None
    palette = source["palette"]
    if len(palette) == 1:
        indices = np.zeros(4096, dtype=np.uint16)
    else:
        bits = max(4, (len(palette) - 1).bit_length())
        per_word = 64 // bits
        words = source["data"].astype(np.uint64)
        at = np.arange(4096, dtype=np.uint64)
        indices = ((words[at // per_word] >> ((at % per_word) * bits)) &
                   ((1 << bits) - 1)).astype(np.uint16)
    return palette, indices.reshape((16, 16, 16)).transpose(2, 0, 1)


def load_world(world, registry, center, radius):
    defaults = {}
    for key, entry in registry.items():
        if entry["default"]:
            name, props = key[:-1].split("[")
            defaults[name] = dict(p.split("=") for p in props.split(",") if p)
    xmin = math.floor((center[0] - radius) / 16) * 16
    zmin = math.floor((center[2] - radius) / 16) * 16
    xmax = math.ceil((center[0] + radius) / 16) * 16
    zmax = math.ceil((center[2] + radius) / 16) * 16
    ymin, ymax = -64, 320
    grid = np.zeros((xmax - xmin, ymax - ymin, zmax - zmin), dtype=np.uint16)
    state_ids, states, counts = {}, [None], Counter()
    region = world / "dimensions/minecraft/overworld/region"
    if not region.is_dir():
        region = world / "region"
    if not region.is_dir():
        raise ValueError("Overworld region directory missing")
    bounds = (xmin // 16, xmax // 16, zmin // 16, zmax // 16)
    sources, loaded = [], 0
    for path in sorted(region.glob("r.*.*.mca")):
        rx, rz = map(int, path.stem.split(".")[1:])
        if rx * 32 >= bounds[1] or (rx + 1) * 32 <= bounds[0]:
            continue
        if rz * 32 >= bounds[3] or (rz + 1) * 32 <= bounds[2]:
            continue
        sources.append({"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        for cx, cz, chunk in chunks(path, bounds):
            loaded += 1
            for section in chunk.get("sections", []):
                sy = int(section["Y"]) * 16
                if not ymin <= sy < ymax:
                    continue
                decoded = palette_values(section)
                if decoded is None:
                    continue
                palette, indices = decoded
                mapped = []
                for state in palette:
                    key = state_key(state, defaults)
                    if key not in registry:
                        raise ValueError(f"Registry does not describe saved state {key}")
                    if registry[key]["air"]:
                        mapped.append(0)
                        continue
                    if key not in state_ids:
                        state_ids[key] = len(states)
                        states.append((key, registry[key]))
                    mapped.append(state_ids[key])
                block = np.array(mapped, dtype=np.uint16)[indices]
                x, z = cx * 16 - xmin, cz * 16 - zmin
                grid[x:x + 16, sy - ymin:sy - ymin + 16, z:z + 16] = block
                ids, freq = np.unique(block, return_counts=True)
                counts.update({int(i): int(n) for i, n in zip(ids, freq) if i})
        print(f"loaded {path.name}: chunks={loaded}", flush=True)
    if loaded == 0:
        raise ValueError("No saved chunks intersect the selected crop")
    return grid, np.array([xmin, ymin, zmin], dtype=np.float64), states, counts, sources


def prepare_geometry(grid, states):
    boxes, offsets, emitters = [], [0], []
    excluded = []
    for sid, entry in enumerate(states):
        if entry is None:
            offsets.append(0)
            continue
        key, fact = entry
        if "shape_error" in fact:
            raise ValueError(f"Source outline unavailable: {key}: {fact['shape_error']}")
        name = key.split("[")[0]
        # Deliberately opaque-only proxy. Outlines of doors preserve open/closed state.
        opaque = fact["can_occlude"] or name.endswith(("_door", "_trapdoor"))
        opaque |= fact["emission"] > 0
        if any(s in name for s in ("glass", "leaves", "water", "ice", "portal")):
            opaque = False
        shape = np.asarray(fact["outline_boxes"], dtype=np.float64).reshape((-1, 6))
        if opaque:
            boxes.extend(np.clip(shape, 0, 1))
        else:
            excluded.append(key)
        offsets.append(len(boxes))
        if fact["emission"] == 0:
            continue
        if len(shape) == 0:
            excluded.append(key + ":emission_without_outline")
            continue
        radiance = 1.5 * (fact["emission"] / 15) ** 2
        for position in np.argwhere(grid == sid):
            for box in shape:
                lo, hi = box[:3] + position, box[3:] + position
                for axis in range(3):
                    a, b = (axis + 1) % 3, (axis + 2) % 3
                    for sign in (-1, 1):
                        center = (lo + hi) / 2
                        center[axis] = lo[axis] if sign == -1 else hi[axis]
                        u, v = np.zeros(3), np.zeros(3)
                        u[a], v[b] = (hi[a] - lo[a]) / 2, sign * (hi[b] - lo[b]) / 2
                        area = 4 * np.linalg.norm(np.cross(u, v))
                        if area == 0:
                            continue
                        page = tuple((position // 16).tolist())
                        emitters.append((page, center, u, v, radiance, radiance * area))
    emitters.sort(key=lambda e: e[0])
    values = np.array([np.r_[e[1], e[2], e[3], e[4:]] for e in emitters], dtype=np.float64)
    pages, last, index = [], None, -1
    for e in emitters:
        if e[0] != last:
            last, index = e[0], index + 1
        pages.append(index)
    return np.array(boxes, dtype=np.float64).reshape((-1, 6)), np.array(offsets, dtype=np.int32), values, pages, excluded


@njit(cache=True)
def trace(grid, boxes, offsets, start, direction, limit):
    cell = np.floor(start).astype(np.int64)
    step = np.where(direction > 0, 1, -1)
    delta = np.empty(3)
    crossing = np.empty(3)
    for a in range(3):
        if abs(direction[a]) < 1e-15:
            delta[a], crossing[a] = 1e30, 1e30
        else:
            delta[a] = abs(1 / direction[a])
            edge = cell[a] + (1 if step[a] > 0 else 0)
            crossing[a] = max(0., (edge - start[a]) / direction[a])
    while True:
        if (cell[0] < 0 or cell[1] < 0 or cell[2] < 0
                or cell[0] >= grid.shape[0] or cell[1] >= grid.shape[1] or cell[2] >= grid.shape[2]):
            return -1., 0, 0
        state = grid[cell[0], cell[1], cell[2]]
        best, best_axis, best_sign = 1e30, 0, 0
        for bi in range(offsets[state], offsets[state + 1]):
            near, far, normal_axis, normal_sign = -1e30, 1e30, 0, 0
            for a in range(3):
                lo, hi = boxes[bi, a] + cell[a], boxes[bi, a + 3] + cell[a]
                if abs(direction[a]) < 1e-15:
                    if start[a] < lo or start[a] > hi:
                        far = -1e30
                else:
                    ta, tb = (lo - start[a]) / direction[a], (hi - start[a]) / direction[a]
                    enter, leave = min(ta, tb), max(ta, tb)
                    if enter > near:
                        near, normal_axis, normal_sign = enter, a, -step[a]
                    far = min(far, leave)
            if near <= far and far > 1e-5 and near < limit and max(near, 0.) < best:
                best, best_axis, best_sign = max(near, 0.), normal_axis, normal_sign
        if best < 1e29:
            return best, best_axis, best_sign
        a = np.argmin(crossing)
        if crossing[a] >= limit:
            return -1., 0, 0
        cell[a] += step[a]
        crossing[a] += delta[a]


@njit(cache=True, parallel=True)
def view_samples(grid, boxes, offsets, camera, forward, right, up):
    points, normals = np.zeros((72, 128, 3)), np.zeros((72, 128, 3))
    depths = np.zeros((72, 128))
    for y in prange(72):
        for x in range(128):
            d = forward + right * ((x + .5) / 128 * 2 - 1) * math.tan(math.radians(35)) * 16 / 9
            d += up * (1 - (y + .5) / 72 * 2) * math.tan(math.radians(35))
            d /= np.linalg.norm(d)
            distance, axis, sign = trace(grid, boxes, offsets, camera, d, 800.)
            if distance >= 0:
                normals[y, x, axis] = sign
                points[y, x] = camera + d * distance + normals[y, x] * .001
                depths[y, x] = distance
    return points, normals, depths


@njit(cache=True)
def rectangle_integral(center, u, v, receiver, normal):
    light_normal = np.cross(u, v)
    if np.dot(light_normal, receiver - center) <= 0:
        return 0.
    corners = np.empty((4, 3))
    corners[0] = center - u - v - receiver
    corners[1] = center + u - v - receiver
    corners[2] = center + u + v - receiver
    corners[3] = center - u + v - receiver
    clipped = np.empty((8, 3))
    count = 0
    for i in range(4):
        a, b = corners[i], corners[(i + 1) % 4]
        da, db = np.dot(a, normal), np.dot(b, normal)
        if da >= 0:
            clipped[count] = a
            count += 1
        if (da >= 0) != (db >= 0):
            clipped[count] = a + (b - a) * da / (da - db)
            count += 1
    if count < 3:
        return 0.
    total = 0.
    for i in range(count):
        a, b = clipped[i], clipped[(i + 1) % count]
        a, b = a / np.linalg.norm(a), b / np.linalg.norm(b)
        cross = np.cross(a, b)
        length = np.linalg.norm(cross)
        if length > 1e-15:
            total += np.dot(normal, cross) * math.atan2(length, np.dot(a, b)) / length
    return abs(total) / (2 * math.pi)


@njit(cache=True, parallel=True)
def visibility_and_reference(grid, boxes, offsets, receivers, emitters):
    visibility = np.zeros((32, len(emitters), 16), dtype=np.uint8)
    reference = np.zeros(32)
    for r in prange(32):
        receiver, normal = receivers[r, :3], receivers[r, 3:]
        total = 0.
        for i in range(len(emitters)):
            light = emitters[i]
            center, u, v = light[:3], light[3:6], light[6:9]
            light_normal = np.cross(u, v)
            light_normal /= np.linalg.norm(light_normal)
            if np.dot(light_normal, receiver - center) <= 0:
                continue
            for y in range(4):
                for x in range(4):
                    subcenter = center + u * ((x + .5) / 2 - 1) + v * ((y + .5) / 2 - 1)
                    endpoint = subcenter + light_normal * .0001
                    delta = endpoint - receiver
                    distance = np.linalg.norm(delta)
                    blocked = trace(grid, boxes, offsets, receiver, delta / distance, distance - .0001)[0] >= 0
                    if not blocked:
                        visibility[r, i, y * 4 + x] = 1
                        total += light[9] * rectangle_integral(subcenter, u / 4, v / 4, receiver, normal)
        reference[r] = total
    return visibility, reference


def self_test():
    center, u, v = np.array([0., 2., 0.]), np.array([.5, 0., 0.]), np.array([0., 0., .5])
    receiver, normal = np.zeros(3), np.array([0., 1., 0.])
    exact = rectangle_integral(center, u, v, receiver, normal)
    nodes, weights = np.polynomial.legendre.leggauss(32)
    quadrature = sum(wx * wy * 4 / math.pi / (4 + (x * .5) ** 2 + (y * .5) ** 2) ** 2
                     for x, wx in zip(nodes, weights) for y, wy in zip(nodes, weights)) * .25
    assert abs(exact - quadrature) < 1e-12, (exact, quadrature)
    assert rectangle_integral(center, u, v, receiver, -normal) == 0
    # Hemisphere clipping and subdivision must conserve the same integral.
    clipped_normal = np.array([1., 0., 0.])
    whole = rectangle_integral(center, u, v, receiver, clipped_normal)
    split = sum(rectangle_integral(center + u * x + v * y, u / 2, v / 2,
                                   receiver, clipped_normal)
                for x in (-.5, .5) for y in (-.5, .5))
    assert whole > 0 and abs(whole - split) < 1e-12, (whole, split)
    grid = np.zeros((5, 5, 5), dtype=np.uint16)
    grid[2, 2, 2] = 1
    boxes, offsets = np.array([[0., 0., 0., 1., 1., 1.]]), np.array([0, 0, 1])
    hit = trace(grid, boxes, offsets, np.array([.5, 2.5, 2.5]), np.array([1., 0., 0.]), 4.)
    assert hit == (1.5, 0, -1), hit
    assert trace(grid, boxes, offsets, np.array([.5, 1.5, 2.5]), np.array([1., 0., 0.]), 4.)[0] < 0
    raw = b'\x0a\x00\x00\x03\x00\x01x\x00\x00\x00\x07\x00'
    assert nbt(raw) == {"x": 7}
    defaults = {"minecraft:test": {"a": "off", "b": "north"}}
    assert state_key("minecraft:test", defaults) == "minecraft:test[a=off,b=north]"
    assert state_key({"id": "minecraft:test", "properties": {"a": "on"}}, defaults) == "minecraft:test[a=on,b=north]"
    assert state_key({"Name": "minecraft:test", "Properties": {"a": "on"}}, defaults) == "minecraft:test[a=on,b=north]"
    # Verify the non-crossing packed-word layout and Minecraft's x/z/y order.
    expected = np.arange(4096, dtype=np.uint64) % 17
    words = np.zeros(math.ceil(4096 / 12), dtype=np.uint64)
    for i, value in enumerate(expected):
        words[i // 12] |= value << np.uint64((i % 12) * 5)
    _, decoded = palette_values({"block_states": {"palette": list(range(17)), "data": words}})
    assert np.array_equal(decoded, expected.reshape(16, 16, 16).transpose(2, 0, 1))
    print("self-test passed: NBT/state defaults, packed palette, AABB visibility, hemisphere integral")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path)
    parser.add_argument("--registry", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--camera", type=float, nargs=5, metavar=("X", "Y", "Z", "YAW", "PITCH"))
    parser.add_argument("--radius", type=int, default=192)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    set_num_threads(args.threads)
    if args.self_test:
        self_test()
        return
    if any(v is None for v in (args.world, args.registry, args.output, args.camera)):
        parser.error("world, registry, output and explicit camera are required")
    if args.output.exists():
        parser.error("Choose a new output directory to preserve evidence")
    began = time.perf_counter()
    registry = json.loads(args.registry.read_text(encoding="utf-8"))
    camera = np.array(args.camera[:3], dtype=np.float64)
    grid, origin, states, counts, sources = load_world(args.world, registry, camera, args.radius)
    boxes, offsets, emitters, pages, excluded = prepare_geometry(grid, states)
    if len(emitters) == 0:
        raise ValueError("No retained emitters in selected crop")
    print(f"emitters={len(emitters)} pages={max(pages)+1} states={len(states)-1}", flush=True)
    yaw, pitch = map(math.radians, args.camera[3:])
    forward = np.array([-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
    right = np.cross(forward, np.array([0., 1., 0.]))
    right /= np.linalg.norm(right)
    up = np.cross(right, forward)
    points, normals, depths = view_samples(grid, boxes, offsets, camera - origin, forward, right, up)
    all_hits = np.argwhere(depths > 0)
    if len(all_hits) < 32:
        raise ValueError("Selected camera does not see enough retained opaque geometry")
    receivers, locations = [], []
    for y in range(4):
        for x in range(8):
            target = np.array([y * 18 + 9, x * 16 + 8])
            py, px = all_hits[np.argmin(np.sum((all_hits - target) ** 2, axis=1))]
            receivers.append(np.r_[points[py, px], normals[py, px]])
            locations.append([int(px), int(py)])
    # Integrate the exact coordinates consumed by the GPU, avoiding an f64/f32
    # geometry mismatch near silhouettes in the independent reference.
    receivers = np.asarray(receivers, dtype=np.float32).astype(np.float64)
    emitters = emitters.astype(np.float32).astype(np.float64)
    print("building 4x4 visibility and independent boundary-integral reference", flush=True)
    visibility, reference = visibility_and_reference(grid, boxes, offsets, receivers, emitters)
    args.output.mkdir(parents=True)
    with (args.output / "emitters.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow("id,page,cx,cy,cz,ux,uy,uz,vx,vy,vz,radiance,power,two_sided".split(","))
        for i, light in enumerate(emitters):
            writer.writerow([i, pages[i], *light, 0])
    with (args.output / "receivers.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow("id,x,y,z,nx,ny,nz,reference".split(","))
        for i, receiver in enumerate(receivers):
            writer.writerow([i, *receiver, reference[i]])
    visibility.tofile(args.output / "visibility.bin")
    np.save(args.output / "depth.npy", depths)
    world = nbt(gzip.decompress((args.world / "level.dat").read_bytes()))["Data"]
    metadata = {
        "scope": "world_outline_box_monochrome_direct_light_proxy_not_game_render",
        "world": str(args.world.resolve()), "world_name": world.get("LevelName"),
        "world_version": world.get("Version"), "camera": args.camera, "fov": 70,
        "origin": origin.tolist(), "radius": args.radius, "grid": list(grid.shape),
        "emitters": len(emitters), "pages": max(pages)+1, "receivers": 32,
        "reference": "f64 hemisphere-clipped polygon boundary integral per visible 4x4 cell",
        "visibility": "binary center-ray AABB query per subrectangle; receiver-major light-major v*4+u",
        "lighting": "local emitters only; sun and sky zero; unit Lambert albedo",
        "receiver_pixels_128x72": locations,
        "nonzero_reference": int(np.count_nonzero(reference)),
        "reference_min_max": [float(reference.min()), float(reference.max())],
        "visible_cell_fraction": float(visibility.mean()), "prepare_seconds": time.perf_counter()-began,
        "registry_sha256": hashlib.sha256(args.registry.read_bytes()).hexdigest(), "regions": sources,
        "states": [{"key": states[i][0], "blocks": n} for i, n in counts.most_common()],
        "omitted_or_transparent_states": excluded,
        "limitations": ["source outline boxes, not baked model triangles", "no alpha textures, tint or material layers",
                        "transparent and cutout geometry omitted; no refraction/transmittance", "emission without outline omitted",
                        "32 fixed receiver tiles are not full-resolution G-buffer or motion vectors", "crop excludes distant world lights"],
    }
    (args.output / "scene.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: metadata[k] for k in ("world_name", "emitters", "pages", "nonzero_reference", "reference_min_max", "prepare_seconds")}))


if __name__ == "__main__":
    main()
