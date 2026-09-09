#!/usr/bin/env python3
# Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

"""Build Prime's immutable realtime direct-light STBN table with EA FAST.

The initializer is a two-dimensional Sobol digital sequence with the Laine-Karras
approximate Owen scramble used by pbrt-v4.  Each 128x128 frame is an aligned
(0,14,2) net.  FAST may only swap samples inside that frame, so the exact net and
the full-bank low-discrepancy point set survive the spectral optimization.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys

import numpy as np


WIDTH = 128
HEIGHT = 128
DEPTH = 64
BANK_COUNT = 3
PIXELS_PER_FRAME = WIDTH * HEIGHT
POINT_COUNT = PIXELS_PER_FRAME * DEPTH
UINT16_SCALE = 1.0 / 65536.0
BANK_SEEDS = (0x2D3A_EC5B, 0xA511_E9B3, 0x63D8_3595)
FAST_SEEDS = (0x175B_7A93, 0xC2B2_AE35, 0x85EB_CA77)


def reverse_bits32(values: np.ndarray) -> np.ndarray:
    result = values.astype(np.uint32, copy=True)
    result = ((result >> np.uint32(1)) & np.uint32(0x55555555)) \
        | ((result & np.uint32(0x55555555)) << np.uint32(1))
    result = ((result >> np.uint32(2)) & np.uint32(0x33333333)) \
        | ((result & np.uint32(0x33333333)) << np.uint32(2))
    result = ((result >> np.uint32(4)) & np.uint32(0x0F0F0F0F)) \
        | ((result & np.uint32(0x0F0F0F0F)) << np.uint32(4))
    result = ((result >> np.uint32(8)) & np.uint32(0x00FF00FF)) \
        | ((result & np.uint32(0x00FF00FF)) << np.uint32(8))
    return (result >> np.uint32(16)) | (result << np.uint32(16))


def fast_owen(values: np.ndarray, seed: int) -> np.ndarray:
    """pbrt-v4's Laine-Karras approximate Owen permutation."""
    result = reverse_bits32(values)
    with np.errstate(over="ignore"):
        result ^= result * np.uint32(0x3D20ADEA)
        result += np.uint32(seed)
        result *= np.uint32((seed >> 16) | 1)
        result ^= result * np.uint32(0x05526C56)
        result ^= result * np.uint32(0x53A22864)
    return reverse_bits32(result)


def sobol_uint32(count: int) -> tuple[np.ndarray, np.ndarray]:
    if count <= 0 or count & (count - 1):
        raise ValueError("Sobol point count must be a positive power of two")
    bits = count.bit_length() - 1
    indices = np.arange(count, dtype=np.uint32)
    gray = indices ^ (indices >> np.uint32(1))
    first = np.zeros(count, dtype=np.uint32)
    second = np.zeros(count, dtype=np.uint32)
    second_direction = np.uint32(0x80000000)
    for bit in range(bits):
        selected = (gray & np.uint32(1 << bit)) != 0
        first[selected] ^= np.uint32(1 << (31 - bit))
        second[selected] ^= second_direction
        second_direction ^= second_direction >> np.uint32(1)
    return first, second


def make_initial_codes(bank: int) -> np.ndarray:
    first, second = sobol_uint32(POINT_COUNT)
    seed = BANK_SEEDS[bank]
    first = fast_owen(first, seed ^ 0xF8ADE99A)
    second = fast_owen(second, seed ^ 0xE0AAAF76)
    codes = np.stack((first >> np.uint32(16), second >> np.uint32(16)), axis=1) \
        .astype(np.uint16).reshape(DEPTH, PIXELS_PER_FRAME, 2)

    # The digital sequence fixes each frame's point set.  A deterministic spatial
    # permutation avoids presenting FAST with a structured pixel assignment.
    random = np.random.Generator(np.random.PCG64(seed))
    for frame in range(DEPTH):
        codes[frame] = codes[frame, random.permutation(PIXELS_PER_FRAME)]
    return codes.reshape(DEPTH, HEIGHT, WIDTH, 2)


def verify_net(codes: np.ndarray, total_bits: int, label: str) -> None:
    points = codes.reshape(-1, 2).astype(np.uint32)
    minimum_x = max(0, total_bits - 16)
    maximum_x = min(16, total_bits)
    expected = np.arange(1 << total_bits, dtype=np.uint32)
    for x_bits in range(minimum_x, maximum_x + 1):
        y_bits = total_bits - x_bits
        x_bin = points[:, 0] >> np.uint32(16 - x_bits) if x_bits else 0
        y_bin = points[:, 1] >> np.uint32(16 - y_bits) if y_bits else 0
        packed = (np.asarray(x_bin, dtype=np.uint32) << np.uint32(y_bits)) \
            | np.asarray(y_bin, dtype=np.uint32)
        if not np.array_equal(np.sort(packed), expected):
            raise RuntimeError(
                f"{label} is not a (0,{total_bits},2) net at "
                f"partition {x_bits}+{y_bits}")


def verify_initializer(codes: np.ndarray, bank: int) -> None:
    for frame in range(DEPTH):
        verify_net(codes[frame], 14, f"bank {bank} frame {frame}")
    verify_net(codes, 20, f"bank {bank}")


def write_fast_initializer(path: Path, codes: np.ndarray) -> None:
    values = np.zeros((POINT_COUNT, 4), dtype="<f4")
    values[:, :2] = (codes.reshape(POINT_COUNT, 2).astype(np.float64) + 0.5) \
        * UINT16_SCALE
    values.tofile(path)


def read_fast_exr(path: Path) -> np.ndarray:
    try:
        import OpenEXR
        import Imath
    except ImportError as exception:
        raise RuntimeError("OpenEXR and Imath Python packages are required") from exception
    image = OpenEXR.InputFile(str(path))
    try:
        window = image.header()["dataWindow"]
        width = window.max.x - window.min.x + 1
        height = window.max.y - window.min.y + 1
        if (width, height) != (WIDTH, HEIGHT * DEPTH):
            raise RuntimeError(f"unexpected FAST EXR dimensions {width}x{height}")
        pixel_type = Imath.PixelType(Imath.PixelType.FLOAT)
        red = np.frombuffer(image.channel("R", pixel_type), dtype="<f4")
        green = np.frombuffer(image.channel("G", pixel_type), dtype="<f4")
        return np.stack((red, green), axis=1).reshape(DEPTH, HEIGHT, WIDTH, 2)
    finally:
        image.close()


def quantize_fast_output(samples: np.ndarray) -> np.ndarray:
    if not np.all(np.isfinite(samples)) or np.any(samples <= 0.0) \
            or np.any(samples >= 1.0):
        raise RuntimeError("FAST produced a non-finite or endpoint sample")
    scaled = samples.astype(np.float64) * 65536.0 - 0.5
    rounded = np.rint(scaled)
    if np.max(np.abs(scaled - rounded)) > 1.0e-3:
        raise RuntimeError("FAST changed sample values instead of only permuting them")
    return rounded.astype(np.uint16)


def verify_frame_sets(initial: np.ndarray, optimized: np.ndarray, bank: int) -> None:
    for frame in range(DEPTH):
        before = initial[frame].reshape(-1, 2).astype(np.uint32)
        after = optimized[frame].reshape(-1, 2).astype(np.uint32)
        before = np.sort(before[:, 0] | (before[:, 1] << np.uint32(16)))
        after = np.sort(after[:, 0] | (after[:, 1] << np.uint32(16)))
        if not np.array_equal(before, after):
            raise RuntimeError(f"FAST changed bank {bank} frame {frame}'s point set")


def verify_quality(codes: np.ndarray) -> None:
    """Validate the distribution properties that justify the immutable artifact."""
    vectors = codes.transpose(0, 4, 1, 2, 3).reshape(BANK_COUNT * 2, -1)
    correlations = np.corrcoef(vectors)
    for first_bank in range(BANK_COUNT):
        for second_bank in range(first_bank + 1, BANK_COUNT):
            block = correlations[
                first_bank * 2:(first_bank + 1) * 2,
                second_bank * 2:(second_bank + 1) * 2]
            maximum = np.max(np.abs(block))
            if maximum >= 0.01:
                raise RuntimeError(
                    f"bank {first_bank}/{second_bank} correlation is {maximum}")

    y_frequency = np.minimum(np.arange(HEIGHT), HEIGHT - np.arange(HEIGHT))
    x_frequency = np.minimum(np.arange(WIDTH), WIDTH - np.arange(WIDTH))
    radius_squared = y_frequency[:, None] ** 2 + x_frequency[None, :] ** 2
    spatial_low = (radius_squared >= 1) & (radius_squared < 8 ** 2)
    spatial_high = (radius_squared >= 24 ** 2) & (radius_squared < 48 ** 2)
    spatial_low_power = []
    spatial_high_power = []
    temporal_low_power = []
    temporal_high_power = []
    for threshold in (16_384, 32_768, 49_152):
        probability = threshold / 65_536.0
        spatial_error = (codes[:, ::8] < threshold).astype(np.float64) - probability
        spatial_power = np.abs(np.fft.fft2(spatial_error, axes=(2, 3))) ** 2
        spatial_low_power.append(spatial_power[:, :, spatial_low, :])
        spatial_high_power.append(spatial_power[:, :, spatial_high, :])

        temporal_error = (codes[:, :, ::4, ::4] < threshold).astype(np.float64) \
            - probability
        temporal_power = np.abs(np.fft.fft(temporal_error, axis=1)) ** 2
        temporal_low_power.append(temporal_power[:, 1:5])
        temporal_high_power.append(temporal_power[:, 16:32])

    spatial_ratio = np.mean(spatial_low_power) / np.mean(spatial_high_power)
    temporal_ratio = np.mean(temporal_low_power) / np.mean(temporal_high_power)
    if spatial_ratio >= 0.30:
        raise RuntimeError(f"spatial low/high power ratio is {spatial_ratio}")
    if temporal_ratio >= 0.50:
        raise RuntimeError(f"temporal low/high power ratio is {temporal_ratio}")


def run_fast(
        executable: Path,
        init: Path,
        output: Path,
        bank: int,
        steps: int,
        combine: str) -> Path:
    combine_arguments = ["product"] if combine == "product" else ["separate", "0.5"]
    command = [
        str(executable), "vector2", "uniform",
        "gauss", "1.0", "exponential", "0.1", "0.1", *combine_arguments,
        str(WIDTH), str(HEIGHT), str(DEPTH), str(output),
        "-numsteps", str(steps), "-output", "exr",
        "-seed", str(FAST_SEEDS[bank]), "-init", str(init),
    ]
    # The distributed executable loads generated shaders relative to its own directory.
    subprocess.run(command, cwd=executable.parent, check=True)
    result = output.with_suffix(".exr")
    if not result.is_file():
        raise RuntimeError(f"FAST did not produce {result}")
    return result


def build(arguments: argparse.Namespace) -> None:
    executable = arguments.fastnoise_dir / "FastNoise.exe"
    if not executable.is_file():
        raise FileNotFoundError(executable)
    work = arguments.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = arguments.output.with_name(arguments.output.name + ".tmp")

    banks = []
    with temporary_output.open("wb") as packed:
        for bank in range(BANK_COUNT):
            print(f"Building realtime STBN bank {bank + 1}/{BANK_COUNT}", flush=True)
            initial = make_initial_codes(bank)
            verify_initializer(initial, bank)
            init_path = work / f"bank_{bank}.init"
            output_path = work / f"bank_{bank}"
            write_fast_initializer(init_path, initial)
            exr_path = run_fast(
                executable,
                init_path,
                output_path,
                bank,
                arguments.steps,
                arguments.combine)
            optimized = quantize_fast_output(read_fast_exr(exr_path))
            verify_frame_sets(initial, optimized, bank)
            verify_initializer(optimized, bank)
            banks.append(optimized)
            packed.write(optimized.astype("<u2", copy=False).tobytes(order="C"))
            if not arguments.keep_work:
                init_path.unlink()
                exr_path.unlink()

    verify_quality(np.stack(banks))
    expected_size = BANK_COUNT * POINT_COUNT * 2 * np.dtype("<u2").itemsize
    actual_size = temporary_output.stat().st_size
    if actual_size != expected_size:
        raise RuntimeError(f"packed table is {actual_size} bytes, expected {expected_size}")
    temporary_output.replace(arguments.output)
    print(f"Wrote {arguments.output} ({actual_size} bytes)", flush=True)
    if not arguments.keep_work:
        try:
            work.rmdir()
        except OSError:
            pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fastnoise-dir", type=Path, default=Path(r"C:\WorkSpace\_ref\fastnoise"))
    parser.add_argument("--work-dir", type=Path, default=Path("tmp/realtime-stbn"))
    parser.add_argument(
        "--output", type=Path,
        default=Path("src/client/resources/prime/stbn/realtime_128x128x64x3.rg16ui"))
    parser.add_argument("--steps", type=int, default=10_000)
    parser.add_argument("--combine", choices=("product", "separate"), default="separate")
    parser.add_argument("--keep-work", action="store_true")
    arguments = parser.parse_args()
    if arguments.steps <= 0:
        parser.error("--steps must be positive")
    return arguments


if __name__ == "__main__":
    try:
        build(parse_args())
    except (OSError, RuntimeError, subprocess.CalledProcessError, ValueError) as exception:
        print(f"error: {exception}", file=sys.stderr)
        raise SystemExit(1) from exception
