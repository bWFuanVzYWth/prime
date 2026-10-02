// Executes generated production Slang math on CPU; no Vulkan or window is created.
#include "rr_guides.generated.cpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>

static unsigned checks = 0;
static void require(bool condition, const char *label) {
    ++checks;
    if (!condition) {
        std::fprintf(stderr, "FAIL %s (%u)\n", label, checks);
        std::exit(1);
    }
}
static bool close(float actual, double expected, double tolerance = 2e-6) {
    return std::isfinite(actual) && std::abs(actual - expected) <= tolerance;
}
using F2 = Vector<float, 2>;
using F3 = Vector<float, 3>;
using F4 = Vector<float, 4>;

static F2 motion(F4 target, F2 sample = {0.5f, 0.5f}, F4 position = {0, 0, 0, 1},
                 F4 forward = {0, 0, 1, 1}, F3 right = {1, 0, 0}, F3 up = {0, 1, 0},
                 bool history = true, bool known = true) {
    return rrCpuMotion_0(target, sample, position, forward, right, up, history, known);
}
static void invalid(F2 value, const char *label) {
    require(value.x == -65504 && value.y == -65504, label);
}

int main() {
    for (float aspect : {1.0f, 16.0f / 9.0f, 2.4f})
        for (float fov : {0.25f, 0.7f, 1.5f})
            for (float jitterX : {-0.5f, 0.0f, 0.49f})
                for (float jitterY : {-0.49f, 0.0f, 0.5f})
                    for (float depth : {0.1f, 10.0f, 4000.0f}) {
                        F2 uv{(87.5f + jitterX) / 960, (140.5f + jitterY) / 540};
                        F4 target{(2 * uv.x - 1) * depth * fov * aspect,
                                  -(2 * uv.y - 1) * depth * fov, depth, 0};
                        auto value = motion(target, uv, {0, 0, 0, fov}, {0, 0, 1, aspect});
                        require(close(value.x, 0) && close(value.y, 0),
                                "static jitter-free motion");
                    }
    const auto translated = motion({0, 0, 10, 0}, {0.5f, 0.5f}, {1, 2, 0, 1});
    require(close(translated.x, -0.05) && close(translated.y, 0.1), "previous-minus-current UV");
    const auto sky = motion({0, 0, 10, 1}, {0.5f, 0.5f}, {1000, 2000, 3000, 1});
    require(close(sky.x, 0) && close(sky.y, 0), "sky excludes translation");
    const float angle = 0.3f, c = std::cos(angle), s = std::sin(angle);
    const auto rotated = motion({0, 0, 1, 1}, {0.5f, 0.5f}, {0, 0, 0, 1}, {s, 0, c, 1}, {c, 0, -s});
    require(close(rotated.x, -0.5 * std::tan(angle)) && close(rotated.y, 0), "sky camera rotation");
    const auto rebased = motion({-255, 2, 10, 0}, {0.5f, 0.5f}, {-256, 0, 0, 1});
    require(close(rebased.x, 0.05) && close(rebased.y, -0.1), "anchor-adjusted previous camera");
    invalid(motion({0, 0, -1, 0}), "previous camera behind point");
    invalid(motion({0, 0, 0, 0}), "zero projective depth");
    invalid(motion({0, 0, 1, 0}, {0.5f, 0.5f}, {0, 0, 0, 1}, {0, 0, 1, 1}, {1, 0, 0}, {0, 1, 0},
                   false),
            "no global history");
    invalid(motion({0, 0, 1, 0}, {0.5f, 0.5f}, {0, 0, 0, 1}, {0, 0, 1, 1}, {1, 0, 0}, {0, 1, 0},
                   true, false),
            "unknown object previous pose");
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    invalid(motion({nan, 0, 1, 0}), "nonfinite motion");
    auto finite = rrCpuColor_0({nan, inf, -1}, false);
    require(finite.x == 0 && finite.y == 0 && finite.z == 0, "half input finite cleaning");
    auto clamped = rrCpuColor_0({65505, 5.5f, 1e30f}, false);
    require(clamped.x == 65504 && clamped.y == 5.5f && clamped.z == 65504, "half range");
    for (const F3 color : {F3{1, 0, 0}, F3{0, 1, 0}, F3{0, 0, 1}, F3{0.2f, 0.5f, 2}}) {
        auto result = rrCpuColor_0(color, true);
        require(close(result.x, color.x) && close(result.y, color.y) && close(result.z, color.z),
                "scene-linear color roundtrip");
    }
    for (bool dielectric : {false, true})
        for (bool thin : {false, true})
            for (float roughness : {0.0f, 0.25f, 0.8f})
                for (float cosine : {0.05f, 0.5f, 1.0f})
                    for (unsigned code : {0u, 4u, 230u, 231u, 239u, 255u}) {
                        auto result =
                                rrCpuAlbedos_0({0.2f, 0.4f, 0.8f}, {0.5f, float(code) / 255, 0},
                                               roughness, dielectric, thin, cosine);
                        for (float value :
                             {result.diffuse_0.x, result.diffuse_0.y, result.diffuse_0.z,
                              result.specular_0.x, result.specular_0.y, result.specular_0.z})
                            require(std::isfinite(value) && value >= 0 && value <= 1,
                                    "material energy guide finite range");
                        if (!dielectric && (code == 231 || code == 239))
                            require(result.diffuse_0.x == 0 && result.diffuse_0.y == 0 &&
                                            result.diffuse_0.z == 0,
                                    "conductor has no diffuse albedo");
                    }
    std::printf("RR production motion/color/material CPU contracts: %u checks passed.\n", checks);
}
