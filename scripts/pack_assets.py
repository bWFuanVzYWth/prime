"""Losslessly pack immutable GPU textures as standard KTX2/Zstd, and verify them.

Only Python's standard library and the Zstandard CLI are required. Repacking after
legacy inputs have been removed uses --source-root pointing to an archived asset
directory. --check needs only the generated assets and packed-assets.json.
KTX2 layout: https://registry.khronos.org/KTX/specs/2.0/ktxspec.v2.html
DFD fields match Khronos KTX-Software v4.4.2 vk2dfd/createdfd (with stated primaries).
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from math import prod
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time


ASSETS = Path(__file__).resolve().parents[1] / "crates/prime-vulkan/assets"
IDENTIFIER = b"\xabKTX 20\xbb\r\n\x1a\n"
HEADER = struct.Struct("<12s13I2Q")
INDEX = struct.Struct("<3Q")
FORMATS = {143: (1, 16), 97: (2, 8), 109: (4, 16)}
ATMOSPHERE = {
    "ground_radiance": (109, (160, 1, 0), "F32"),
    "incident_mean": (109, (160, 40, 0), "F32"),
    "rayleigh_source": (109, (800, 21, 0), "F32"),
    "optical_depth": (97, (512, 128, 0), "F16"),
    "scattering_source": (97, (3200, 240, 0), "F16"),
}
NASA_SHA = "19a1351f00c386a6e5eec4d67af96d5fc71edf6a1189941579b9498b52e7589a"
ENERGY_SHA = "605c9160fb9348a1d033321c40cf9930226ce74c03f2624033f5b73aacfa67df"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_write(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def zstd_executable(requested: str | None = None) -> str:
    candidate = requested or os.environ.get("ZSTD") or shutil.which("zstd")
    if candidate is None and Path("C:/msys64/usr/bin/zstd.exe").is_file():
        candidate = "C:/msys64/usr/bin/zstd.exe"
    if candidate is None:
        raise ValueError("Zstandard CLI not found; pass --zstd or set ZSTD")
    return candidate


def compress(data: bytes, level: int, zstd: str) -> bytes:
    return subprocess.run(
        [zstd, "--ultra", f"-{level}", "--single-thread", "--no-progress", "--check", "-q",
         f"--stream-size={len(data)}", "-c"],
        input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    ).stdout


def decompress(data: bytes, zstd: str) -> bytes:
    return subprocess.run(
        [zstd, "-d", "-q", "-c"], input=data, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=True,
    ).stdout


def dfd(vk_format: int, primaries: int) -> bytes:
    if vk_format not in FORMATS or primaries not in (0, 4):
        raise ValueError("Unsupported asset format/primaries")
    if vk_format == 143:
        # BC6H: one FLOAT COLOR sample describes the entire 128-bit block.
        samples = [struct.pack("<HBB4BII", 0, 127, 0x80, 0, 0, 0, 0, 0, 0x3F800000)]
        model, dimensions, planes = 133, (3, 3, 0, 0), (16, 0, 0, 0, 0, 0, 0, 0)
    else:
        bits = FORMATS[vk_format][0] * 8
        samples = [struct.pack("<HBB4BII", i * bits, bits - 1, channel | 0xC0,
                               0, 0, 0, 0, 0xBF800000, 0x3F800000)
                   for i, channel in enumerate((0, 1, 2, 15))]
        model, dimensions = 1, (0, 0, 0, 0)
        planes = (FORMATS[vk_format][1], 0, 0, 0, 0, 0, 0, 0)
    block_size = 24 + 16 * len(samples)
    return (struct.pack("<IIHH4B4B8B", block_size + 4, 0, 2, block_size,
                        model, primaries, 1, 0, *dimensions, *planes) + b"".join(samples))


def kvd(metadata: dict[str, str]) -> bytes:
    result = bytearray()
    for key, value in sorted(metadata.items()):
        if not key or "\0" in key or "\0" in value:
            raise ValueError("Invalid KTX metadata key/value")
        entry = key.encode("utf-8") + b"\0" + value.encode("utf-8") + b"\0"
        result += struct.pack("<I", len(entry)) + entry
        result += b"\0" * (-len(entry) % 4)
    return bytes(result)


def level_size(vk_format: int, extent: tuple[int, int, int], level: int) -> int:
    x, y, z = (max(1, value >> level) for value in extent)
    if vk_format == 143:
        return ((x + 3) // 4) * ((y + 3) // 4) * z * 16
    return x * y * z * FORMATS[vk_format][1]


def parse_ktx(data: bytes) -> dict:
    if len(data) < HEADER.size:
        raise ValueError("Truncated KTX2 header")
    (identifier, fmt, type_size, width, height, depth, layers, faces, count, scheme,
     dfd_offset, dfd_size, kvd_offset, kvd_size, sgd_offset, sgd_size) = HEADER.unpack_from(data)
    if (identifier != IDENTIFIER or fmt not in FORMATS or type_size != FORMATS[fmt][0]
            or width == 0 or height == 0 or layers != 0 or faces != 1 or scheme != 2
            or not 1 <= count <= max(width, height, depth).bit_length()
            or sgd_offset != 0 or sgd_size != 0):
        raise ValueError("Unsupported or invalid KTX2 header")
    if dfd_offset != HEADER.size + count * INDEX.size or dfd_size not in (44, 92):
        raise ValueError("Invalid KTX2 index/DFD placement")
    if kvd_offset != dfd_offset + dfd_size or kvd_offset + kvd_size > len(data):
        raise ValueError("Invalid KTX2 key/value placement")
    descriptor = data[dfd_offset:dfd_offset + dfd_size]
    primaries = descriptor[13] if len(descriptor) >= 14 else -1
    if descriptor != dfd(fmt, primaries):
        raise ValueError("DFD does not match the original Vulkan format")
    metadata = {}
    at, end = kvd_offset, kvd_offset + kvd_size
    previous_key = ""
    while at < end:
        if at + 4 > end:
            raise ValueError("Truncated KTX2 key/value length")
        size = struct.unpack_from("<I", data, at)[0]
        at += 4
        if size == 0 or at + size > end:
            raise ValueError("Invalid KTX2 key/value size")
        key_bytes, separator, value_bytes = data[at:at + size].partition(b"\0")
        if not separator or not value_bytes.endswith(b"\0"):
            raise ValueError("Invalid KTX2 text metadata terminator")
        key, value = key_bytes.decode("utf-8"), value_bytes[:-1].decode("utf-8")
        if not key or key <= previous_key or "\0" in value:
            raise ValueError("Unsorted/duplicate/invalid KTX2 metadata")
        metadata[key] = value
        previous_key = key
        at += size
        padding = -size % 4
        if at + padding > end or any(data[at:at + padding]):
            raise ValueError("Invalid KTX2 metadata padding")
        at += padding
    orientation = "rdi" if depth else "rd"
    if metadata.get("KTXorientation") != orientation:
        raise ValueError("KTX2 orientation mismatch")
    levels = [INDEX.unpack_from(data, HEADER.size + i * INDEX.size) for i in range(count)]
    at = end
    for i in reversed(range(count)):
        offset, size, decoded_size = levels[i]
        # Scheme 2 requires alignment 1, and levels are physically smallest -> base.
        if offset != at or size == 0 or offset + size > len(data):
            raise ValueError("KTX2 levels overlap, are truncated or have wrong order")
        if decoded_size != level_size(fmt, (width, height, depth), i):
            raise ValueError("KTX2 uncompressed level size mismatch")
        at += size
    if at != len(data):
        raise ValueError("Unexpected KTX2 trailing bytes")
    return {"vkFormat": fmt, "typeSize": type_size, "extent": [width, height, depth],
            "levels": levels, "primaries": primaries, "metadata": metadata}


def pack_texture(path: Path, fmt: int, extent: tuple[int, int, int], primaries: int,
                 payloads, metadata: dict[str, str], level: int, compare: int, zstd: str) -> dict:
    compressed, records = [], []
    metadata = {**metadata, "KTXorientation": "rdi" if extent[2] else "rd",
                "KTXwriter": "Prime PT pack_assets.py v1",
                "KTXwriterScParams": f"zstd --ultra -{level} --single-thread --check"}
    for i, raw in enumerate(payloads):
        if len(raw) != level_size(fmt, extent, i):
            raise ValueError(f"Wrong source payload size for {path.name} level {i}")
        started = time.perf_counter()
        packed = compress(raw, level, zstd)
        elapsed = time.perf_counter() - started
        alternative = compress(raw, compare, zstd)
        if decompress(packed, zstd) != raw or decompress(alternative, zstd) != raw:
            raise ValueError("Zstandard did not reproduce the original payload")
        records.append({"level": i, "uncompressedBytes": len(raw), "uncompressedSha256": sha(raw),
                        "compressedBytes": len(packed), "compressedSha256": sha(packed),
                        f"zstd{compare}Bytes": len(alternative), "encodeSeconds": elapsed})
        compressed.append(packed)
        print(f"{path.name} level={i} raw={len(raw)} zstd{level}={len(packed)} "
              f"zstd{compare}={len(alternative)} seconds={elapsed:.3f}", flush=True)
    descriptor, keys = dfd(fmt, primaries), kvd(metadata)
    dfd_offset = HEADER.size + INDEX.size * len(records)
    kvd_offset = dfd_offset + len(descriptor)
    at = kvd_offset + len(keys)
    indices = [None] * len(records)
    for i in reversed(range(len(records))):
        indices[i] = (at, len(compressed[i]), records[i]["uncompressedBytes"])
        at += len(compressed[i])
    data = (HEADER.pack(IDENTIFIER, fmt, FORMATS[fmt][0], *extent, 0, 1, len(records), 2,
                        dfd_offset, len(descriptor), kvd_offset, len(keys), 0, 0)
            + b"".join(INDEX.pack(*entry) for entry in indices) + descriptor + keys
            + b"".join(reversed(compressed)))
    parse_ktx(data)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {"container": "KTX2", "bytes": len(data), "sha256": sha(data), "vkFormat": fmt,
            "extent": list(extent), "primaries": primaries, "metadata": metadata,
            "levels": records, "zstdLevel": level, "comparisonLevel": compare,
            f"zstd{compare}ContainerBytes": len(data) - sum(map(len, compressed))
            + sum(record[f"zstd{compare}Bytes"] for record in records)}


def verify_record(path: Path, record: dict, zstd: str) -> None:
    data = path.read_bytes()
    if len(data) != record["bytes"] or sha(data) != record["sha256"]:
        raise ValueError(f"Packed asset identity mismatch: {path}")
    if record["container"] == "KTX2":
        parsed = parse_ktx(data)
        for key in ("vkFormat", "extent", "primaries", "metadata"):
            if parsed[key] != record[key]:
                raise ValueError(f"Packed asset {key} mismatch: {path}")
        if len(parsed["levels"]) != len(record["levels"]):
            raise ValueError(f"Packed asset mip count mismatch: {path}")
        for i, ((offset, size, decoded_size), expected) in enumerate(zip(parsed["levels"], record["levels"])):
            frame = data[offset:offset + size]
            decoded = decompress(frame, zstd)
            if (len(decoded) != decoded_size or size != expected["compressedBytes"]
                    or sha(frame) != expected["compressedSha256"]
                    or sha(decoded) != expected["uncompressedSha256"] or expected["level"] != i):
                raise ValueError(f"Packed asset level identity mismatch: {path} mip {i}")
    elif record["container"] == "Zstd":
        decoded = decompress(data, zstd)
        if len(decoded) != record["uncompressedBytes"] or sha(decoded) != record["uncompressedSha256"]:
            raise ValueError(f"Packed safetensors identity mismatch: {path}")
        metadata, _ = read_safetensors(decoded)
        if metadata != record["safetensorsHeader"]:
            raise ValueError(f"Packed safetensors metadata mismatch: {path}")
    else:
        raise ValueError("Unknown packed asset container")
    print(f"verified {path.name}: {record['bytes']} bytes, sha256={record['sha256']}", flush=True)


def read_safetensors(data: bytes) -> tuple[dict, bytes]:
    if len(data) < 8:
        raise ValueError("Truncated safetensors")
    size = struct.unpack_from("<Q", data)[0]
    if size > len(data) - 8:
        raise ValueError("Truncated safetensors header")
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate safetensors JSON key: {key}")
            result[key] = value
        return result

    header = json.loads(data[8:8 + size], object_pairs_hook=unique_object)
    payload = data[8 + size:]
    if not isinstance(header, dict):
        raise ValueError("Safetensors header is not an object")
    metadata = header.get("__metadata__", {})
    if (not isinstance(metadata, dict)
            or any(not isinstance(value, str) for value in metadata.values())):
        raise ValueError("Safetensors metadata must contain strings")
    ranges = []
    for name, tensor in header.items():
        if name == "__metadata__":
            continue
        if not isinstance(tensor, dict):
            raise ValueError(f"Invalid safetensors tensor: {name}")
        dtype, shape, offsets = tensor.get("dtype"), tensor.get("shape"), tensor.get("data_offsets")
        if not isinstance(dtype, str) or dtype not in ("F16", "F32"):
            raise ValueError(f"Unsupported safetensors dtype: {name}")
        if (not isinstance(shape, list)
                or any(type(value) is not int or value < 0 for value in shape)):
            raise ValueError(f"Invalid safetensors shape: {name}")
        if (not isinstance(offsets, list) or len(offsets) != 2
                or any(type(value) is not int or value < 0 for value in offsets)):
            raise ValueError(f"Invalid safetensors offsets: {name}")
        begin, end = offsets
        if (end > len(payload) or end - begin != prod(shape) * (2 if dtype == "F16" else 4)):
            raise ValueError(f"Safetensors shape/offset length mismatch: {name}")
        ranges.append((begin, end, name))
    at = 0
    for begin, end, name in sorted(ranges):
        if begin != at:
            raise ValueError(f"Safetensors payload has a gap or overlap: {name}")
        at = end
    if at != len(payload):
        raise ValueError("Unexpected safetensors trailing payload")
    return header, payload


def stable_manifest(manifest: dict) -> dict:
    # Encoding duration belongs to the execution report, never a reproducible asset lock.
    records = {}
    for path, record in manifest["assets"].items():
        records[path] = dict(record)
        if "levels" in record:
            records[path]["levels"] = [
                {key: value for key, value in level.items() if key != "encodeSeconds"}
                for level in record["levels"]
            ]
    return {**manifest, "assets": records}


def pack_starmap(source: Path, target: Path, level: int, compare: int, zstd: str) -> dict:
    records = {}
    star = json.loads((source / "starmap/starmap_2020_16k.json").read_text(encoding="utf-8"))
    if star["version"] != 3 or star["source"]["sha256"] != NASA_SHA:
        raise ValueError("Packing requires the original v3 starmap manifest")
    image = star["image"]
    if (image["width"], image["height"], image["mipLevels"], image["format"]) != (
            16384, 8192, 15, "VK_FORMAT_BC6H_UFLOAT_BLOCK"):
        raise ValueError("Unexpected original starmap contract")
    parts = image["stripes"] + image["mips"]
    def star_part(part):
        packed = (source / "starmap" / part["name"]).read_bytes()
        if len(packed) != part["compressedBytes"] or sha(packed) != part["compressedSha256"]:
            raise ValueError(f"Original star gzip identity mismatch: {part['name']}")
        raw = gzip.decompress(packed)
        if len(raw) != part["uncompressedBytes"] or sha(raw) != part["uncompressedSha256"]:
            raise ValueError(f"Original star payload identity mismatch: {part['name']}")
        return raw
    def star_payloads():
        stripes = sorted(image["stripes"], key=lambda part: part["firstRow"])
        if [part["firstRow"] for part in stripes] != [0, 2048, 4096, 6144]:
            raise ValueError("Star base stripe order mismatch")
        yield b"".join(star_part(part) for part in stripes)
        mips = sorted(image["mips"], key=lambda part: part["level"])
        if [part["level"] for part in mips] != list(range(1, 15)):
            raise ValueError("Star mip order mismatch")
        yield from (star_part(part) for part in mips)
    star_record = pack_texture(target / "starmap/starmap_2020_16k.ktx2", 143,
                               (16384, 8192, 0), 4, star_payloads(),
                               {"source_sha256": NASA_SHA, "projection": star["source"]["projection"],
                                "working_color": image["colorSpace"]},
                               level, compare, zstd)
    star_record["sourceFiles"] = [{"name": part["name"], "bytes": part["compressedBytes"],
                                   "sha256": part["compressedSha256"]} for part in parts]
    records["starmap/starmap_2020_16k.ktx2"] = star_record
    # Preserve NASA provenance, encoder, original interpretation and original quality bounds.
    star["version"] = 4
    star["image"] = {key: value for key, value in image.items() if key not in ("stripes", "mips")}
    star["image"]["container"] = "starmap_2020_16k.ktx2"
    star["image"]["containerBytes"] = star_record["bytes"]
    star["image"]["containerSha256"] = star_record["sha256"]
    star["image"]["levels"] = [{key: record[key] for key in (
        "level", "uncompressedBytes", "uncompressedSha256")} for record in star_record["levels"]]
    json_write(target / "starmap/starmap_2020_16k.json", star)
    return records


def pack_openpbr(source: Path, target: Path, level: int, compare: int, zstd: str) -> dict:
    # Original author overlay is never rewritten. Check all independently locked source files.
    energy_root = ASSETS / "openpbr/author-bsdf-hotfix-2026-07-24"
    lock = json.loads((ASSETS / "openpbr/robocute.lock.json").read_text(encoding="utf-8"))
    for name, expected in lock["authorityOverlay"]["files"].items():
        if sha((energy_root / name).read_bytes()) != expected:
            raise ValueError(f"Locked author source changed: {name}")
    energy = (energy_root / "trans_ggx.bytes").read_bytes()
    if sha(energy) != ENERGY_SHA:
        raise ValueError("Unexpected OpenPBR table")
    record = pack_texture(target / "openpbr/trans_ggx.ktx2", 97, (44, 32, 159), 0, [energy],
                          {"source_sha256": ENERGY_SHA,
                           "source": "RoboCute author-bsdf-hotfix-2026-07-24"}, level, compare, zstd)
    record["sourceBytes"] = len(energy)
    record["sourceSha256"] = ENERGY_SHA
    return {"openpbr/trans_ggx.ktx2": record}


def pack_atmosphere(source: Path, target: Path, level: int, compare: int, zstd: str) -> dict:
    records = {}
    default = (source / "atmosphere/default.safetensors").read_bytes()
    header, tensor_bytes = read_safetensors(default)
    if header["__metadata__"]["schema"] != "prime.atmosphere.balanced.v1":
        raise ValueError("Unknown atmosphere source schema")
    if set(header) != {"__metadata__", *ATMOSPHERE}:
        raise ValueError("Unexpected atmosphere tensors")
    for name, (fmt, extent, dtype) in ATMOSPHERE.items():
        tensor = header[name]
        if tensor["dtype"] != dtype or tensor["shape"] != [extent[1], extent[0], 4]:
            raise ValueError(f"Wrong atmosphere dtype/shape: {name}")
        begin, end = tensor["data_offsets"]
        if not 0 <= begin <= end <= len(tensor_bytes):
            raise ValueError(f"Wrong atmosphere tensor offsets: {name}")
        record = pack_texture(target / f"atmosphere/{name}.ktx2", fmt, extent, 0,
                              [tensor_bytes[begin:end]], {**header["__metadata__"], "tensor": name},
                              level, compare, zstd)
        record["sourceBytes"] = len(default)
        record["sourceSha256"] = sha(default)
        record["sourceTensor"] = tensor
        records[f"atmosphere/{name}.ktx2"] = record

    medium = (source / "atmosphere/medium.safetensors").read_bytes()
    medium_header, _ = read_safetensors(medium)
    if medium_header["__metadata__"]["schema"] != "prime.atmosphere.medium.v1":
        raise ValueError("Unknown medium source schema")
    packed, alternative = compress(medium, level, zstd), compress(medium, compare, zstd)
    if decompress(packed, zstd) != medium or decompress(alternative, zstd) != medium:
        raise ValueError("Medium safetensors roundtrip mismatch")
    (target / "atmosphere/medium.safetensors.zst").write_bytes(packed)
    records["atmosphere/medium.safetensors.zst"] = {
        "container": "Zstd", "bytes": len(packed), "sha256": sha(packed),
        "uncompressedBytes": len(medium), "uncompressedSha256": sha(medium),
        "safetensorsHeader": medium_header, "zstdLevel": level, "comparisonLevel": compare,
        f"zstd{compare}Bytes": len(alternative),
    }
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ASSETS)
    parser.add_argument("--asset-root", type=Path, default=ASSETS)
    parser.add_argument("--only", action="append", choices=("starmap", "openpbr", "atmosphere"),
                        help="Update/check only this category (repeatable); retain other manifest entries")
    parser.add_argument("--level", type=int, default=22, choices=range(1, 23))
    parser.add_argument("--compare-level", type=int, default=19, choices=range(1, 23))
    parser.add_argument("--zstd")
    parser.add_argument("--ktx-validator", type=Path, help="Optional official ktx executable")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    zstd = zstd_executable(args.zstd)
    manifest_path = args.asset_root / "packed-assets.json"
    groups = set(args.only or ("starmap", "openpbr", "atmosphere"))
    if args.check:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest["version"] != 1:
            raise ValueError("Unknown packed-asset manifest")
    else:
        records = {}
        packers = {"starmap": pack_starmap, "openpbr": pack_openpbr, "atmosphere": pack_atmosphere}
        version = subprocess.check_output([zstd, "--version"], text=True).strip()
        for group in sorted(groups):
            added = packers[group](args.source_root, args.asset_root, args.level, args.compare_level, zstd)
            for record in added.values():
                record["zstdVersion"] = version
            records.update(added)
        manifest = (json.loads(manifest_path.read_text(encoding="utf-8"))
                    if manifest_path.is_file() else {"version": 1, "assets": {}})
        if manifest["version"] != 1:
            raise ValueError("Unknown packed-asset manifest")
        manifest["assets"] = {key: value for key, value in manifest["assets"].items()
                              if key.split("/", 1)[0] not in groups}
        manifest["assets"].update(records)
        json_write(manifest_path, stable_manifest(manifest))
    official = {}
    for relative, record in manifest["assets"].items():
        if relative.split("/", 1)[0] not in groups:
            continue
        path = args.asset_root / relative
        verify_record(path, record, zstd)
        if args.ktx_validator and record["container"] == "KTX2":
            result = subprocess.run([str(args.ktx_validator.resolve()), "validate", "--format", "json",
                                     str(path.resolve())], check=True, capture_output=True, text=True)
            validation = json.loads(result.stdout)
            official[relative] = validation
            # KTX2 3.11 permits custom metadata. Preserve the official 7010 warnings for it,
            # but reject every other warning as well as invalid files and errors.
            if (not validation["valid"] or any(message["type"] != "warning" or message["id"] != 7010
                                               for message in validation["messages"])):
                raise ValueError(f"Official KTX validation failed: {result.stdout}")
            print(f"official KTX valid=true errors=0 custom-key-warnings={len(validation['messages'])} "
                  f"asset={path.name}", flush=True)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        json_write(args.report, {**manifest, "officialValidation": official})
    selected = [record for path, record in manifest["assets"].items() if path.split("/", 1)[0] in groups]
    print(f"Verified {len(selected)} selected packed assets, "
          f"{sum(record['bytes'] for record in selected)} container bytes; "
          f"manifest retains {len(manifest['assets'])} assets")


if __name__ == "__main__":
    main()
