"""Execute production visibility/medium/filter helpers through Slang CPU; no GPU."""
import argparse
import itertools
import json
import math
import os
from pathlib import Path
import random
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "crates/prime-vulkan/shaders"
FIXTURE = ROOT / "crates/prime-vulkan/tests/shaders/transport_physics_math.slang"
DRIVER = r'''
#define _CRT_SECURE_NO_WARNINGS
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "physics-math.cpp"
int main(int argc, char** argv) {
    if (argc != 7) return 2;
    unsigned mode = std::strtoul(argv[1], nullptr, 10);
    unsigned count = std::strtoul(argv[2], nullptr, 10);
    unsigned front = std::strtoul(argv[3], nullptr, 10);
    using U4 = Vector<uint32_t, 4>;
    std::vector<U4> input(count * 2), output(count * 2), textures(4);
    std::vector<uint32_t> texels(256);
    FILE* file = std::fopen(argv[4], "rb");
    if (!file) return 3;
    if (std::fread(input.data(), sizeof(U4), input.size(), file) != input.size()) return 4;
    if (std::fread(textures.data(), sizeof(U4), textures.size(), file) != textures.size()) return 5;
    if (std::fread(texels.data(), sizeof(uint32_t), texels.size(), file) != texels.size()) return 6;
    std::fclose(file);
    U4 config(mode, count, front, 0);
    GlobalParams_0 params{};
    params.inputs_0 = {input.data(), input.size()};
    params.outputs_0 = {output.data(), output.size()};
    params.textures_0 = {textures.data(), textures.size()};
    params.texels_0 = {texels.data(), texels.size()};
    params.dispatch_0 = &config;
    params.surfaceFeatures_0 = 2;
    ComputeVaryingInput varying{};
    varying.endGroupID = {count, 1, 1};
    main_0(&varying, nullptr, &params);
    file = std::fopen(argv[5], "wb");
    if (!file) return 7;
    std::fwrite(output.data(), sizeof(U4), output.size(), file);
    std::fclose(file);
    return 0;
}
'''


def u(value):
    return struct.unpack("<I", struct.pack("<f", value))[0]


def f(value):
    return struct.unpack("<f", struct.pack("<I", value))[0]


def fresnel(cosine, outside, inside):
    if outside == inside:
        return 0.0
    sin2 = (outside / inside) ** 2 * max(0.0, 1 - cosine * cosine)
    if sin2 >= 1:
        return 1.0
    ct = math.sqrt(1 - sin2)
    return .5 * (((outside * cosine - inside * ct) / (outside * cosine + inside * ct)) ** 2 +
                 ((inside * cosine - outside * ct) / (inside * cosine + outside * ct)) ** 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangc", default=os.environ.get("SLANGC") or shutil.which("slangc"))
    parser.add_argument("--cxx", default=shutil.which("clang++"))
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/p12-implementation-2026-10-05/physics-cpu")
    args = parser.parse_args()
    if not args.slangc or not args.cxx:
        parser.error("Slang and clang++ are required")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def run(command):
        subprocess.run([str(part) for part in command], check=True, cwd=ROOT)

    run([args.slangc, FIXTURE, "-I", SHADERS, "-DPRIME_RESTIR_MATERIAL_ONLY=1", "-entry", "main",
         "-stage", "compute", "-target", "cpp", "-O2", "-o", output / "physics-math.cpp"])
    driver = output / "driver.cpp"
    driver.write_text(DRIVER, encoding="utf8", newline="\n")
    executable = output / "physics-math.exe"
    run([args.cxx, "-std=c++17", "-O2", "-ffp-contract=off", driver, "-o", executable])
    rng = random.Random(0xA11F12)
    texels = [rng.randrange(1 << 32) for _ in range(256)]

    def execute(mode, rows, front=0, sprite=False, blend=0.0):
        metadata = [13, 7, 5, (0x80000000 | 11) if sprite else 7, 127, 0, 0, u(blend)] + [0] * 8
        data = b"".join(struct.pack("<8I", *row) for row in rows)
        data += struct.pack("<16I", *metadata) + struct.pack("<256I", *texels)
        (output / "input.bin").write_bytes(data)
        run([executable, mode, len(rows), front, output / "input.bin", output / "output.bin", "unused"])
        return list(struct.iter_unpack("<8I", (output / "output.bin").read_bytes()))

    counts = {}
    matrix = ((.627403896, .329283038, .043313066),
              (.069097289, .919540395, .011362316),
              (.016391439, .088013308, .895595253))
    color_rows = []
    for _ in range(4096):
        color_rows.append(tuple(u(x) for x in [*(rng.random() * 100 for _ in range(3)), 0,
                                                *(rng.random() for _ in range(3)), 0]))
    for row, actual in zip(color_rows, execute(0, color_rows), strict=True):
        source, visibility = [f(x) for x in row[:3]], [f(x) for x in row[4:7]]
        expected = [sum(a * b for a, b in zip(m, source)) * v for m, v in zip(matrix, visibility)]
        assert all(math.isclose(f(a), e, abs_tol=2e-5, rel_tol=2e-6) for a, e in zip(actual, expected))
    counts["working_color"] = len(color_rows)

    low = (1 + math.sqrt(.02)) / (1 - math.sqrt(.02))
    thin_rows = []
    for outside, inside in itertools.product((1., 1.333, 1.5), (1., low, 1.333, 1.5, 2.5)):
        for cosine in (0., 1e-8, 1e-7, 1e-6, 1e-5, 1e-4, .001, .05, .1, .5, 1.):
            for sigma in (0., .01, 1., 100.):
                thin_rows.append(tuple(u(x) for x in (cosine, outside, inside, 0, sigma, sigma * .5, sigma * 2, 0)))
    for _ in range(4096):
        thin_rows.append(tuple(u(x) for x in (rng.uniform(.001, 1), rng.uniform(1, 1.7),
                                             rng.uniform(1, 2.5), 0, *(rng.uniform(0, 50) for _ in range(3)), 0)))
    for row, actual in zip(thin_rows, execute(1, thin_rows), strict=True):
        cosine, outside, inside = (f(x) for x in row[:3])
        internal = (cosine * cosine if inside == outside else
                    1 - (1 - cosine * cosine) / (inside / outside) ** 2)
        expected = [0.] * 3
        if internal > 0:
            reflect = fresnel(cosine, outside, inside)
            for i, sigma in enumerate(f(x) for x in row[4:7]):
                absorption = math.exp(-sigma * .0625 / math.sqrt(internal))
                expected[i] = (1 - reflect) ** 2 * absorption / max(1e-7, 1 - (reflect * absorption) ** 2)
        assert all(math.isfinite(f(x)) and f(x) >= 0 for x in actual[:3]), (row, actual)
        if cosine > 0 and inside == outside and all(f(x) == 0 for x in row[4:7]):
            # Exercise primeThinVisibility's own preguard, before the Full RT consumer.
            assert tuple(f(x) for x in actual[:3]) == (1., 1., 1.), (row, actual)
        assert all(math.isclose(f(a), e, abs_tol=8e-6, rel_tol=2e-4) for a, e in zip(actual, expected)), (row, actual, expected)
    counts["thin_series"] = len(thin_rows)

    media = [(low, .29, 1., 1.1), (1.333, .2916, .04444, .010182), (1, 0, 0, 0)]
    identities = [tuple(u(x) for x in (*a, *b)) for a, b in itertools.product(media, repeat=2)]
    for front in (0, 1):
        for row, actual in zip(identities, execute(2, identities, front), strict=True):
            assert actual == ((*row[4:], *row[:4]) if front else row)
    counts["endpoint_identity"] = len(identities) * 2

    orders = [sum(j << (2 * i) for i, j in enumerate(order)) for order in itertools.permutations(range(3))]
    boundary_rows = [(order, u(limit), 0, 0, *(u(x) for x in media[0]))
                     for order, limit in itertools.product(orders, (7.5, 10., 100.))]
    for row, actual in zip(boundary_rows, execute(3, boundary_rows), strict=True):
        expected = [math.exp(-(w * 3 + g * (f(row[1]) - 7)))
                    for w, g in zip(media[1][1:], (f(x) for x in row[5:8]))]
        assert actual[3] == u(7.) and actual[4:] == row[4:]
        assert all(math.isclose(f(a), e, abs_tol=2e-6, rel_tol=1e-5) for a, e in zip(actual, expected))
    counts["unordered_terminal"] = len(boundary_rows)

    classify_rows = []
    for rough, control, incident, transmitted in itertools.product((0., .005, .1, 1.),
            (0, (1 << 19) | 231, 1 << 17, (1 << 17) | (1 << 16)), (1., 1.333), (1., low, 1.5)):
        classify_rows.append((u(rough), control, u(incident), u(transmitted), 0, 0, u(1), u(1)))
    for row, actual in zip(classify_rows, execute(4, classify_rows), strict=True):
        rough, control, incident, transmitted = f(row[0]), row[1], f(row[2]), f(row[3])
        dielectric, thin = bool(control & (1 << 17)), bool(control & (1 << 16))
        eta = transmitted / incident
        if dielectric and thin:
            eta = max(eta, 1 / eta)
            alpha = min(1., max(0., rough * rough * 3.7 * (eta - 1) * (eta - .5) ** 2 / eta ** 3))
            expected = alpha < 1e-4
        elif dielectric:
            expected = rough * rough < 1e-4 or transmitted == incident
        else:
            expected = bool(control & (1 << 19)) and rough * rough < 1e-4
        assert actual[:2] == (int(expected), 1), (row, actual, expected)
    counts["whole_closure_delta"] = len(classify_rows)
    support_rows = [(u(.1), 1 << 17, u(1.), u(1.5), u(nx), 0, u(nz), u(scatter_z))
                    for nx, nz, scatter_z in ((-1., 1., .2), (1., 1., -.2), (0., 1., .2),
                                             (0., 1., -.2), (0., 1., 0.))]
    for row, actual in zip(support_rows, execute(4, support_rows), strict=True):
        nx, nz, scatter_z = f(row[4]), f(row[6]), f(row[7])
        shading_side = nz * (nx + nz * scatter_z)
        expected = scatter_z != 0 and shading_side != 0 and (shading_side >= 0) == (scatter_z > 0)
        assert actual[1] == int(expected), (row, actual, expected)
    counts["geometric_support"] = len(support_rows)
    continuous_rows = [(*row[:7], u(sign)) for row, sign in itertools.product(classify_rows, (-1., 1.))]
    discrete_checks = 0
    for row, actual in zip(continuous_rows, execute(6, continuous_rows), strict=True):
        if actual[4] != 0:
            assert actual[:4] == (0, 0, 0, 0), (row, actual)
            discrete_checks += 1
    assert discrete_checks >= 30
    counts["discrete_zero_continuous_response_pdf"] = discrete_checks

    uv_rows = [(u(x), u(y), 0, 0, 0, 0, 0, 0) for x, y in itertools.product(
        (-2., -1., -.001, 0., .5 / 7, .5, 1., 1.001, 2.), (-2., -.001, 0., .5 / 5, .5, 1., 2.))]
    uv_rows += [(u(rng.uniform(-4, 4)), u(rng.uniform(-4, 4)), 0, 0, 0, 0, 0, 0) for _ in range(4096)]
    for sprite, blend in ((False, 0.), (True, 0.), (True, .1), (True, .5), (True, .999), (True, 1.)):
        for row, actual in zip(uv_rows, execute(5, uv_rows, sprite=sprite, blend=blend), strict=True):
            assert actual[:4] == actual[4:], (row, sprite, blend, actual)
    counts["bilinear_bit_exact"] = len(uv_rows) * 6
    report = {"status": "PASS", "consumer": "production Slang CPU", "gpu": False, "cases": counts}
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
