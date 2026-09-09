#!/usr/bin/env python3
"""Offline NASA 16K EXR to linear Rec.2020 BC6H; no runtime transcoder."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import struct
import subprocess
from pathlib import Path

import Imath
import numpy as np
import OpenEXR

SOURCE_SHA256 = "19a1351f00c386a6e5eec4d67af96d5fc71edf6a1189941579b9498b52e7589a"
TEXCONV_SHA256 = "dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06"
WIDTH, HEIGHT, STRIPE_ROWS = 16384, 8192, 2048
NAME = "starmap_2020_16k"
MATRIX = np.array(((0.6274039, 0.3292830, 0.0433131),
                   (0.0690973, 0.9195404, 0.0113623),
                   (0.0163914, 0.0880133, 0.8955953)), dtype=np.float32)
LUMA = np.array((0.2627, 0.6780, 0.0593), dtype=np.float32)
# Relative to the uncompressed 16K working-space image, without exposure/tone mapping.
LIMITS = {"rgbNrmse": 0.08, "luminanceNrmse": 0.06,
          "sphericalEnergyRelativeError": 0.01, "brightLuminanceNrmse": 0.06}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def dds_header(width: int, height: int) -> bytes:
    words = [124, 0x100F, height, width, width * 16, 0, 1] + [0] * 11
    words += [32, 4, int.from_bytes(b"DX10", "little"), 0, 0, 0, 0, 0]
    words += [0x1000, 0, 0, 0, 0]
    return b"DDS " + struct.pack("<31I", *words) + struct.pack("<5I", 2, 3, 0, 1, 0)


def check_dds(path: Path, width: int, height: int, dxgi: int) -> None:
    with path.open("rb") as f:
        header = f.read(148)
    if len(header) != 148 or header[:4] != b"DDS ":
        raise ValueError(f"Invalid DDS: {path}")
    words = struct.unpack_from("<31I", header, 4)
    extension = struct.unpack_from("<5I", header, 128)
    if (words[0], words[2], words[3], words[6], words[18], words[20]) != (
            124, height, width, 1, 32, int.from_bytes(b"DX10", "little")):
        raise ValueError(f"DDS layout mismatch: {path}")
    if extension[:4] != (dxgi, 3, 0, 1):
        raise ValueError(f"DDS format/array mismatch: {path}")
    size = width * height if dxgi == 95 else width * height * 16
    if path.stat().st_size != 148 + size:
        raise ValueError(f"DDS data size mismatch: {path}")


def write_gzip(path: Path, data: bytes) -> dict:
    with path.open("wb") as stream:
        with gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0, compresslevel=9) as f:
            f.write(data)
    return {"name": path.name, "uncompressedBytes": len(data),
            "uncompressedSha256": hashlib.sha256(data).hexdigest(),
            "compressedBytes": path.stat().st_size, "compressedSha256": sha256(path)}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--texconv", required=True, type=Path,
                   help="DirectXTex may2026 texconv.exe (2026.5.8.1), verified by SHA-256")
    p.add_argument("--work", required=True, type=Path, help="Intermediate DDS/error report directory")
    p.add_argument("--fixture", type=Path, help="Independent decoder samples for Vulkan regression")
    args = p.parse_args()
    if sha256(args.source) != SOURCE_SHA256 or sha256(args.texconv) != TEXCONV_SHA256:
        raise ValueError("Source or texconv SHA-256 does not match the pinned asset/tool")
    args.output.mkdir(parents=True, exist_ok=True)
    work = args.work.resolve()
    for name in ("source", "bc6h", "decoded"):
        (work / name).mkdir(parents=True, exist_ok=True)
    exr = OpenEXR.InputFile(str(args.source))
    try:
        header = exr.header()
        w = header["dataWindow"]
        if (w.min.x, w.min.y, w.max.x, w.max.y) != (0, 0, WIDTH-1, HEIGHT-1):
            raise ValueError("Unexpected EXR extent/origin")
        if set(header["channels"]) != {"R", "G", "B"} or "chromaticities" in header:
            raise ValueError("EXR no longer matches the reviewed source colorimetry rule")
        channels = [np.frombuffer(exr.channel(c, Imath.PixelType(Imath.PixelType.HALF)),
                                  dtype="<f2").reshape(HEIGHT, WIDTH) for c in ("R", "G", "B")]
    finally:
        exr.close()
    for channel in channels:
        if not np.all(np.isfinite(channel)) or np.any(channel < 0):
            raise ValueError("BC6H_UFLOAT source must be finite and nonnegative")
    sums = np.zeros(8, dtype=np.float64)
    max_error = 0.0
    samples, files = [], []
    rng = np.random.default_rng(4851)
    for stripe, first in enumerate(range(0, HEIGHT, STRIPE_ROWS)):
        base = f"{NAME}_{stripe}"
        original = work / "source" / (base + ".dds")
        # Preserve the existing source-linear-sRGB interpretation once at asset build time.
        rgba = np.empty((STRIPE_ROWS, WIDTH, 4), dtype="<f4")
        for start in range(0, STRIPE_ROWS, 64):
            rgb = np.stack([c[first+start:first+start+64] for c in channels], axis=-1).astype(np.float32)
            rgba[start:start+64, :, :3] = rgb @ MATRIX.T
        rgba[:, :, 3] = 1
        with original.open("wb") as f:
            f.write(dds_header(WIDTH, STRIPE_ROWS))
            rgba.tofile(f)
        subprocess.run([str(args.texconv.resolve()), "-nologo", "-y", "-dx10", "-m", "1", "-f", "BC6H_UF16",
                        "-gpu", "0", "-o", str(work / "bc6h"), str(original)], check=True)
        compressed = work / "bc6h" / (base + ".dds")
        check_dds(compressed, WIDTH, STRIPE_ROWS, 95)
        subprocess.run([str(args.texconv.resolve()), "-nologo", "-y", "-dx10", "-m", "1", "-f", "R32G32B32A32_FLOAT",
                        "-o", str(work / "decoded"), str(compressed)], check=True)
        decoded_path = work / "decoded" / (base + ".dds")
        check_dds(decoded_path, WIDTH, STRIPE_ROWS, 2)
        decoded = np.memmap(decoded_path, dtype="<f4", mode="r", offset=148, shape=rgba.shape)
        for start in range(0, STRIPE_ROWS, 64):
            ref = rgba[start:start+64, :, :3]
            out = decoded[start:start+64, :, :3]
            if not np.all(np.isfinite(out)) or np.any(out < 0) or not np.all(decoded[start:start+64, :, 3] == 1):
                raise ValueError("Invalid BC6H decoded values")
            error = out - ref
            lum, actual = ref @ LUMA, out @ LUMA
            diff = actual - lum
            bright = lum >= 0.1
            rows = np.arange(first+start, first+start+ref.shape[0], dtype=np.float64)
            weight = np.cos(np.pi * rows / HEIGHT) - np.cos(np.pi * (rows+1) / HEIGHT)
            sums += [np.sum(error.astype(np.float64)**2), np.sum(ref.astype(np.float64)**2),
                     np.sum(diff.astype(np.float64)**2), np.sum(lum.astype(np.float64)**2),
                     np.sum(actual * weight[:, None]), np.sum(lum * weight[:, None]),
                     np.sum(diff[bright].astype(np.float64)**2), np.sum(lum[bright].astype(np.float64)**2)]
            max_error = max(max_error, float(np.max(np.abs(error))))
        coords = [(0, 0), (WIDTH-1, 0), (0, STRIPE_ROWS-1), (WIDTH-1, STRIPE_ROWS-1),
                  (WIDTH//2, STRIPE_ROWS//2)]
        coords += list(zip(rng.integers(1, WIDTH-1, 64), rng.integers(1, STRIPE_ROWS-1, 64)))
        for row in range(0, STRIPE_ROWS, 128):
            coords.append((int(np.argmax(rgba[row, :, :3] @ LUMA)), row))
        for x, y in coords:
            samples.append({"x": int(x), "y": int(first+y), "rgb": decoded[y, x, :3].tolist()})
        print(f"Encoded and measured stripe {stripe}", flush=True)
        del decoded, rgba
    metrics = {"rgbNrmse": float(np.sqrt(sums[0]/sums[1])),
               "luminanceNrmse": float(np.sqrt(sums[2]/sums[3])),
               "sphericalEnergyRelativeError": float(abs(sums[4]/sums[5]-1)),
               "brightLuminanceNrmse": float(np.sqrt(sums[6]/sums[7])),
               "maxAbsoluteChannelError": max_error}
    report = {"reference": "Uncompressed 16K source mapped to linear Rec.2020; no exposure/tone mapping",
              "metrics": metrics, "limits": LIMITS}
    (work / "quality.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)
    if any(metrics[k] > v for k, v in LIMITS.items()):
        raise ValueError("BC6H asset exceeds quality limits; manifest not published")
    decoded_stripes = [np.memmap(work / "decoded" / f"{NAME}_{i}.dds", dtype="<f4", mode="r",
                                offset=148, shape=(STRIPE_ROWS, WIDTH, 4)) for i in range(HEIGHT // STRIPE_ROWS)]
    def pixel(x: int, y: int) -> np.ndarray:
        return decoded_stripes[y // STRIPE_ROWS][y % STRIPE_ROWS, x, :3]
    for sample in samples:
        x, y = sample["x"], sample["y"]
        right, below = min(x+1, WIDTH-1), min(y+1, HEIGHT-1)
        sample["filteredRgb"] = ((pixel(x,y)+pixel(right,y)+pixel(x,below)+pixel(right,below))*0.25).tolist()
    for stripe, first in enumerate(range(0, HEIGHT, STRIPE_ROWS)):
        base = f"{NAME}_{stripe}"
        with (work / "bc6h" / (base + ".dds")).open("rb") as f:
            f.seek(148)
            data = f.read()
        record = write_gzip(args.output / (base + ".bc6h.gz"), data)
        files.append({**record, "firstRow": first, "rows": STRIPE_ROWS})
        print(f"Published stripe {stripe}: {record['compressedBytes']} packaged bytes", flush=True)
    manifest = {
        "version": 2,
        "source": {"name": "starmap_2020_16k.exr", "sha256": SOURCE_SHA256,
                   "url": "https://svs.gsfc.nasa.gov/4851/",
                   "projection": "plate carree ICRF/J2000; RA 0h at center, RA increases left",
                   "encoding": "scene-linear RGB HALF; source primaries/white point unspecified",
                   "interpretation": "Preserve Prime's source-linear-sRGB interpretation; convert once to D65 linear Rec.2020",
                   "toRec2020": MATRIX.tolist()},
        "encoder": {"name": "Microsoft DirectXTex texconv", "version": "2026.5.8.1 (may2026)",
                    "sha256": TEXCONV_SHA256, "mode": "BC6H_UF16 DirectCompute; one mip, no resize/gamma/tonemap"},
        "image": {"width": WIDTH, "height": HEIGHT, "mipLevels": 1,
                  "format": "VK_FORMAT_BC6H_UFLOAT_BLOCK", "colorSpace": "D65 linear Rec.2020",
                  "blockWidth": 4, "blockHeight": 4, "blockBytes": 16, "stripes": files},
        "quality": report}
    (args.output / (NAME + ".json")).write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    if args.fixture:
        args.fixture.parent.mkdir(parents=True, exist_ok=True)
        args.fixture.write_text(json.dumps({"sourceSha256": SOURCE_SHA256, "samples": samples}, indent=2)+"\n",
                                encoding="utf-8")


if __name__ == "__main__":
    main()
