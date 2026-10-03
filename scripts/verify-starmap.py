"""Verify the unchanged legacy NASA BC6H asset, including gzip CRC and both hashes."""
from pathlib import Path
import gzip
import hashlib
import json
import sys


def verify(directory: Path) -> None:
    manifest = json.loads((directory / "starmap_2020_16k.json").read_text(encoding="utf-8"))
    if manifest["version"] != 3 or manifest["source"]["sha256"] != "19a1351f00c386a6e5eec4d67af96d5fc71edf6a1189941579b9498b52e7589a":
        raise ValueError("Unknown NASA source or manifest contract")
    image = manifest["image"]
    if (image["width"], image["height"], image["mipLevels"], image["format"]) != (16384, 8192, 15, "VK_FORMAT_BC6H_UFLOAT_BLOCK"):
        raise ValueError("Unexpected star image extent, mip chain or format")
    parts = image["stripes"] + image["mips"]
    expected_names = {f"starmap_2020_16k_{i}.bc6h.gz" for i in range(4)} | {f"starmap_2020_16k_mip{i}.bc6h.gz" for i in range(1, 15)}
    if {p["name"] for p in parts} != expected_names or len(parts) != 18 or {p.name for p in directory.glob("*.gz")} != expected_names:
        raise ValueError("Incomplete or duplicated starmap parts")
    total = 0
    for part in parts:
        compressed = (directory / part["name"]).read_bytes()
        if len(compressed) != part["compressedBytes"] or hashlib.sha256(compressed).hexdigest() != part["compressedSha256"]:
            raise ValueError(f"Compressed identity mismatch: {part['name']}")
        decoded = gzip.decompress(compressed)
        if len(decoded) != part["uncompressedBytes"] or hashlib.sha256(decoded).hexdigest() != part["uncompressedSha256"]:
            raise ValueError(f"Decoded identity mismatch: {part['name']}")
        level = part.get("level", 0)
        width, height = max(1, 16384 >> level), part.get("rows", max(1, 8192 >> level))
        if len(decoded) != ((width + 3) // 4) * ((height + 3) // 4) * 16:
            raise ValueError(f"BC6H block length mismatch: {part['name']}")
        total += len(decoded)
        print(f"verified {part['name']} level={level} bytes={len(decoded)} sha256={part['uncompressedSha256']}")
    if total != 178957008:
        raise ValueError("Unexpected total mip bytes")
    print(f"NASA starmap asset: 18 parts, 15 mips, {total} BC6H bytes verified")


if __name__ == "__main__":
    verify(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "crates/prime-vulkan/assets/starmap")
