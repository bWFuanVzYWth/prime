"""Verify the lossless NASA BC6H KTX2, optionally against archived legacy gzip parts."""

import argparse
import gzip
import json
from pathlib import Path

from pack_assets import NASA_SHA, decompress, parse_ktx, sha, verify_record, zstd_executable


def verify(directory: Path, legacy: Path | None = None, zstd: str | None = None) -> None:
    zstd = zstd_executable(zstd)
    manifest = json.loads((directory / "starmap_2020_16k.json").read_text(encoding="utf-8"))
    if manifest["version"] != 4 or manifest["source"]["sha256"] != NASA_SHA:
        raise ValueError("Unknown NASA source or KTX2 manifest contract")
    image = manifest["image"]
    if (image["width"], image["height"], image["mipLevels"], image["format"], image["colorSpace"]) != (
            16384, 8192, 15, "VK_FORMAT_BC6H_UFLOAT_BLOCK", "D65 linear Rec.2020"):
        raise ValueError("Unexpected star extent, mip chain, format or interpretation")
    packed = json.loads((directory.parent / "packed-assets.json").read_text(encoding="utf-8"))
    record = packed["assets"]["starmap/starmap_2020_16k.ktx2"]
    path = directory / image["container"]
    if (image["containerBytes"], image["containerSha256"]) != (record["bytes"], record["sha256"]):
        raise ValueError("Star metadata/packed manifest disagree")
    verify_record(path, record, zstd)
    data = path.read_bytes()
    parsed = parse_ktx(data)
    if (parsed["vkFormat"], parsed["extent"], parsed["primaries"]) != (143, [16384, 8192, 0], 4):
        raise ValueError("KTX2 star format/color contract mismatch")
    if parsed["metadata"]["source_sha256"] != NASA_SHA:
        raise ValueError("KTX2 star provenance mismatch")
    if parsed["metadata"]["working_color"] != image["colorSpace"]:
        raise ValueError("KTX2 star working color mismatch")
    for i, expected in enumerate(image["levels"]):
        actual = record["levels"][i]
        if any(expected[key] != actual[key] for key in (
                "level", "uncompressedBytes", "uncompressedSha256")):
            raise ValueError("KTX2 star mip manifests disagree")
        print(f"verified mip={i} bytes={actual['uncompressedBytes']} sha256={actual['uncompressedSha256']}")
    if legacy is not None:
        original = json.loads((legacy / "starmap_2020_16k.json").read_text(encoding="utf-8"))
        if original["version"] != 3 or original["source"]["sha256"] != NASA_SHA:
            raise ValueError("Unknown legacy NASA source")
        def part_bytes(part):
            raw = (legacy / part["name"]).read_bytes()
            if len(raw) != part["compressedBytes"] or sha(raw) != part["compressedSha256"]:
                raise ValueError(f"Legacy compressed identity mismatch: {part['name']}")
            decoded = gzip.decompress(raw)  # Includes gzip CRC validation.
            if len(decoded) != part["uncompressedBytes"] or sha(decoded) != part["uncompressedSha256"]:
                raise ValueError(f"Legacy payload identity mismatch: {part['name']}")
            return decoded
        def payloads():
            yield b"".join(part_bytes(part) for part in sorted(
                original["image"]["stripes"], key=lambda part: part["firstRow"]))
            yield from (part_bytes(part) for part in sorted(
                original["image"]["mips"], key=lambda part: part["level"]))
        for raw, (offset, size, _) in zip(payloads(), parsed["levels"], strict=True):
            if raw != decompress(data[offset:offset + size], zstd):
                raise ValueError("KTX2 is not byte-identical to legacy BC6H payload")
        print("Legacy 18 gzip parts -> 15 KTX2 mip payloads: exact byte identity verified")
    total = sum(level["uncompressedBytes"] for level in record["levels"])
    if total != 178957008 or len(record["levels"]) != 15:
        raise ValueError("Unexpected total star mip bytes")
    print(f"NASA starmap KTX2: 15 mips, {total} unchanged BC6H bytes verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path,
                        default=Path(__file__).resolve().parents[1] / "crates/prime-vulkan/assets/starmap")
    parser.add_argument("--legacy-root", type=Path, help="Archived legacy starmap directory")
    parser.add_argument("--zstd")
    args = parser.parse_args()
    verify(args.directory, args.legacy_root, args.zstd)
