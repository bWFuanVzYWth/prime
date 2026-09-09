#!/usr/bin/env python3
# Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

"""Build Prime's lossless GPU starmap resources from NASA's 2020 EXR."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

import numpy as np
import OpenEXR
import Imath


SOURCE_SHA256 = "dc6c4f413e85707a29a25a9451148154554ecca2c996f84fa8f47b65ef9ff7c4"
WIDTH = 8192
HEIGHT = 4096
STRIPE_ROWS = 1024
IMPORTANCE_WIDTH = 1024
IMPORTANCE_HEIGHT = 512
SOURCE_URL = "https://svs.gsfc.nasa.gov/4851/"
CREDIT = (
    "NASA/Goddard Space Flight Center Scientific Visualization Studio. "
    "Gaia DR2: ESA/Gaia/DPAC. Constellation figures based on those developed "
    "for the IAU by Alan MacRobert of Sky and Telescope magazine "
    "(Roger Sinnott and Rick Fienberg)."
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_gzip(path: Path, data: memoryview) -> str:
    digest = hashlib.sha256()
    with path.open("wb") as raw:
        with gzip.GzipFile(
            filename="",
            mode="wb",
            fileobj=raw,
            compresslevel=9,
            mtime=0,
        ) as compressed:
            for offset in range(0, len(data), 1024 * 1024):
                chunk = data[offset : offset + 1024 * 1024]
                compressed.write(chunk)
                digest.update(chunk)
    return digest.hexdigest()


def build_alias_table(masses: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    flat = masses.ravel()
    total = float(flat.sum(dtype=np.float64))
    if not np.isfinite(total) or not total > 0.0:
        raise ValueError("Starmap importance mass must be finite and positive")
    probability = flat / total
    count = probability.size
    scaled = probability * count
    threshold = np.empty(count, dtype="<f4")
    alias = np.arange(count, dtype="<u4")
    small = [int(index) for index in np.flatnonzero(scaled < 1.0)]
    large = [int(index) for index in np.flatnonzero(scaled >= 1.0)]
    while small and large:
        low = small.pop()
        high = large.pop()
        threshold[low] = scaled[low]
        alias[low] = high
        scaled[high] = scaled[high] - (1.0 - scaled[low])
        (small if scaled[high] < 1.0 else large).append(high)
    for index in small + large:
        threshold[index] = 1.0
        alias[index] = index
    records = np.empty(
        count,
        dtype=np.dtype([
            ("threshold", "<f4"),
            ("alias", "<u4"),
            ("probability_mass", "<f4"),
        ]),
    )
    records["threshold"] = threshold
    records["alias"] = alias
    records["probability_mass"] = probability.astype("<f4")
    return records, probability


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    source = args.source.resolve()
    output = args.output.resolve()
    source_hash = sha256(source)
    if source_hash != SOURCE_SHA256:
        raise ValueError(
            f"Unexpected source SHA-256 {source_hash}; expected {SOURCE_SHA256}"
        )

    exr = OpenEXR.InputFile(str(source))
    try:
        header = exr.header()
        window = header["dataWindow"]
        width = window.max.x - window.min.x + 1
        height = window.max.y - window.min.y + 1
        if (width, height) != (WIDTH, HEIGHT):
            raise ValueError(f"Unexpected EXR extent {width}x{height}")
        channels = header["channels"]
        if set(channels) != {"R", "G", "B"}:
            raise ValueError(f"Unexpected EXR channels {sorted(channels)}")
        half = Imath.PixelType(Imath.PixelType.HALF)
        red = np.frombuffer(exr.channel("R", half), dtype="<f2").reshape(HEIGHT, WIDTH)
        green = np.frombuffer(exr.channel("G", half), dtype="<f2").reshape(HEIGHT, WIDTH)
        blue = np.frombuffer(exr.channel("B", half), dtype="<f2").reshape(HEIGHT, WIDTH)
    finally:
        exr.close()

    output.mkdir(parents=True, exist_ok=True)
    files: list[dict[str, object]] = []
    for stripe, first_row in enumerate(range(0, HEIGHT, STRIPE_ROWS)):
        rows = min(STRIPE_ROWS, HEIGHT - first_row)
        rgba = np.empty((rows, WIDTH, 4), dtype="<f2")
        rgba[:, :, 0] = red[first_row : first_row + rows]
        rgba[:, :, 1] = green[first_row : first_row + rows]
        rgba[:, :, 2] = blue[first_row : first_row + rows]
        rgba[:, :, 3] = np.float16(1.0)
        path = output / f"starmap_2020_8k_{stripe}.rgba16f.gz"
        raw_hash = write_gzip(path, memoryview(rgba).cast("B"))
        files.append({
            "name": path.name,
            "firstRow": first_row,
            "rows": rows,
            "uncompressedBytes": int(rgba.nbytes),
            "uncompressedSha256": raw_hash,
            "compressedBytes": path.stat().st_size,
            "compressedSha256": sha256(path),
        })
        print(f"wrote {path.name}: {path.stat().st_size / (1024 * 1024):.1f} MiB")

    cell_width = WIDTH // IMPORTANCE_WIDTH
    cell_height = HEIGHT // IMPORTANCE_HEIGHT
    masses = np.zeros((IMPORTANCE_HEIGHT, IMPORTANCE_WIDTH), dtype=np.float64)
    delta_ra = 2.0 * np.pi / WIDTH
    for cell_y in range(IMPORTANCE_HEIGHT):
        first_row = cell_y * cell_height
        rows = np.arange(first_row, first_row + cell_height, dtype=np.float64)
        declination_top = 0.5 * np.pi - np.pi * rows / HEIGHT
        declination_bottom = 0.5 * np.pi - np.pi * (rows + 1.0) / HEIGHT
        pixel_solid_angle = delta_ra * (
            np.sin(declination_top) - np.sin(declination_bottom)
        )
        luminance = (
            red[first_row : first_row + cell_height].astype(np.float32) * 0.2126
            + green[first_row : first_row + cell_height].astype(np.float32) * 0.7152
            + blue[first_row : first_row + cell_height].astype(np.float32) * 0.0722
        )
        weighted = luminance * pixel_solid_angle[:, None]
        masses[cell_y] = weighted.reshape(
            cell_height, IMPORTANCE_WIDTH, cell_width
        ).sum(axis=(0, 2), dtype=np.float64)

    alias, probability = build_alias_table(masses)
    alias_path = output / "starmap_2020_8k.alias.gz"
    alias_raw_hash = write_gzip(alias_path, memoryview(alias).cast("B"))
    print(f"wrote {alias_path.name}: {alias_path.stat().st_size / (1024 * 1024):.1f} MiB")
    manifest = {
        "version": 1,
        "source": {
            "name": source.name,
            "url": SOURCE_URL,
            "sha256": source_hash,
            "projection": (
                "plate carree celestial ICRF/J2000; centered at 0h right ascension; "
                "right ascension increases to the left"
            ),
            "encoding": "scene-linear RGB, primaries and white point unknown, OpenEXR HALF",
            "credit": CREDIT,
        },
        "image": {
            "width": WIDTH,
            "height": HEIGHT,
            "format": "little-endian RGBA16F; RGB preserves source HALF bits; A is 1",
            "stripes": files,
        },
        "importance": {
            "width": IMPORTANCE_WIDTH,
            "height": IMPORTANCE_HEIGHT,
            "cellWidth": cell_width,
            "cellHeight": cell_height,
            "format": "little-endian records: float threshold, uint alias, float probability mass",
            "uncompressedBytes": int(alias.nbytes),
            "uncompressedSha256": alias_raw_hash,
            "compressedBytes": alias_path.stat().st_size,
            "compressedSha256": sha256(alias_path),
            "probabilitySum": float(probability.sum(dtype=np.float64)),
        },
    }
    manifest_path = output / "starmap_2020_8k.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"wrote {manifest_path.name}")


if __name__ == "__main__":
    main()
