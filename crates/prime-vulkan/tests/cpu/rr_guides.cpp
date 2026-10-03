// Executes generated production Slang math on CPU; no Vulkan or window is created.
// Slang retains this SPIR-V contraction qualifier in its C++ output; explicit fma
// calls and -ffp-contract=off preserve the declared operation order on this target.
#define precise
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
using U2 = Vector<uint32_t, 2>;

static F2 motion(F4 target, F2 sample = {0.5f, 0.5f}, F4 position = {0, 0, 0, 1},
                 F4 forward = {0, 0, 1, 1}, F3 right = {1, 0, 0}, F3 up = {0, 1, 0},
                 bool history = true, bool known = true) {
    return rrCpuMotion_0(target, sample, position, forward, right, up, history, known);
}
static void invalid(F2 value, const char *label) {
    require(value.x == -65504 && value.y == -65504, label);
}

static F3 pixel_motion(F4 target, F2 sample = {0.5f, 0.5f}, F4 position = {0, 0, 0, 1},
                       F4 forward = {0, 0, 1, 1}, F3 right = {1, 0, 0}, F3 up = {0, 1, 0},
                       bool history = true, bool known = true, U2 extent = {960, 540}) {
    return rrCpuPixelMotion_0(target, sample, position, forward, right, up, history, known, extent);
}

static F3 distance_motion(float depth, float distance, F2 sample = {0.5f, 0.5f},
                          F4 previous = {0, 0, 0, 1}, F4 current = {0, 0, 0, 1}, float aspect = 1,
                          bool history = true, U2 extent = {960, 540}) {
    return rrCpuDistanceMotion_0(sample, depth, distance, current, {0, 0, 1, aspect}, {1, 0, 0},
                                 {0, 1, 0}, previous, {0, 0, 1, aspect}, {1, 0, 0}, {0, 1, 0},
                                 history, extent);
}

static F3 reflection_motion(float depth, float distance, F2 primary, F2 sample = {0.5f, 0.5f},
                            F4 previous = {0, 0, 0, 1}, F4 current = {0, 0, 0, 1}, float aspect = 1,
                            bool history = true, U2 extent = {960, 540}) {
    return rrCpuReflectionMotion_0(sample, depth, distance, primary, current, {0, 0, 1, aspect},
                                   {1, 0, 0}, {0, 1, 0}, previous, {0, 0, 1, aspect}, {1, 0, 0},
                                   {0, 1, 0}, history, extent);
}

static void sdk_motion(F3 value, double x, double y, const char *label) {
    require(value.z == 1 && close(value.x, x, 2e-4) && close(value.y, y, 2e-4), label);
    require(std::isfinite(value.x) && std::isfinite(value.y) && std::abs(value.x) < 65504 &&
                    std::abs(value.y) < 65504,
            "SDK motion contains finite pixel displacement, not an invalid sentinel");
}

static void unavailable_motion(F3 value, const char *label) {
    require(value.z == 0 && value.x == 0 && value.y == 0, label);
}

static void moving_reflection_contracts() {
    // K1's actual previous-point projection includes object displacement. A zero-length
    // reflection segment owns that same endpoint, rather than the current-world position.
    auto primary = pixel_motion({-0.3f, 0.15f, 3, 0});
    sdk_motion(primary, -48, -13.5, "moving primary independent previous-point projection");
    auto reflection = reflection_motion(3, 0, {primary.x, primary.y});
    require(reflection.z == 1 && reflection.x == primary.x && reflection.y == primary.y,
            "zero-distance reflection exactly preserves moving primary pixel motion");

    for (const U2 extent : {U2{960, 540}, U2{853, 479}, U2{1920, 1080}})
        for (float aspect : {1.0f, 16.0f / 9.0f, 2.4f})
            for (float tangent : {0.25f, 0.7f, 1.5f})
                for (float jitterX : {-0.5f, 0.0f, 0.49f})
                    for (float jitterY : {-0.49f, 0.0f, 0.5f})
                        for (float depth : {0.1f, 10.0f, 4000.0f}) {
                            const F2 uv{(87.5f + jitterX) / extent.x,
                                        (140.5f + jitterY) / extent.y};
                            const F4 oldPoint{(2 * uv.x - 1) * depth * tangent * aspect -
                                                      0.03f * depth,
                                              -(2 * uv.y - 1) * depth * tangent + 0.02f * depth,
                                              depth * 0.97f, 0};
                            const F4 oldCamera{0.13f * depth, -0.04f * depth, -0.1f * depth,
                                               tangent * 1.1f};
                            auto main = pixel_motion(oldPoint, uv, oldCamera, {0, 0, 1, aspect},
                                                     {1, 0, 0}, {0, 1, 0}, true, true, extent);
                            require(main.z == 1, "moving primary test projection is valid");
                            auto actual =
                                    reflection_motion(depth, 0, {main.x, main.y}, uv, oldCamera,
                                                      {0, 0, 0, tangent}, aspect, true, extent);
                            require(actual.z == 1 && actual.x == main.x && actual.y == main.y,
                                    "object/camera/FOV/jitter/extent zero-distance identity");
                        }

    sdk_motion(reflection_motion(3, 0, {-48, -13.5f}, {0.5f, 0.5f}, {1, 2, 0, 1}, {0, 0, 0, 1}, 1,
                                 false),
               0, 0, "zero-distance reflection honors whole viewport history reset");
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    for (F2 bad : {F2{nan, 0}, F2{0, inf}, F2{65504, 0}, F2{0, -65504}})
        unavailable_motion(reflection_motion(3, 0, bad),
                           "zero-distance invalid primary motion stays finite and unavailable");
    for (float depth : {nan, inf, 0.0f, -1.0f})
        unavailable_motion(reflection_motion(depth, 0, {-48, -13.5f}),
                           "primary motion does not make invalid depth valid");
    for (float distance : {nan, inf, -1.0f})
        unavailable_motion(reflection_motion(3, distance, {-48, -13.5f}),
                           "primary motion does not make invalid distance valid");

    // Deliberately unrelated/invalid primary data must not change the nonzero camera-only
    // approximation or sky-directional proxy. They remain separately declared limitations.
    sdk_motion(reflection_motion(3, 5, {nan, inf}, {0.5f, 0.5f}, {1, 2, 0, 1}), -60, 67.5,
               "nonzero proxy does not infer secondary object motion from primary motion");
    sdk_motion(reflection_motion(std::numeric_limits<float>::max(), 0, {nan, inf}, {0.625f, 0.75f},
                                 {1000, 2000, 3000, 1}),
               0, 0, "sky direction does not consume a primary surface motion value");
}

static void pixel_motion_contracts() {
    for (const U2 extent : {U2{960, 540}, U2{853, 479}, U2{1920, 1080}})
        for (float aspect : {1.0f, 16.0f / 9.0f, 2.4f})
            for (float tangent : {0.25f, 0.7f, 1.5f})
                for (float jitterX : {-0.5f, 0.0f, 0.49f})
                    for (float jitterY : {-0.49f, 0.0f, 0.5f})
                        for (float depth : {0.1f, 10.0f, 4000.0f}) {
                            const F2 uv{(87.5f + jitterX) / extent.x,
                                        (140.5f + jitterY) / extent.y};
                            const F4 point{(2 * uv.x - 1) * depth * tangent * aspect,
                                           -(2 * uv.y - 1) * depth * tangent, depth, 0};
                            sdk_motion(pixel_motion(point, uv, {0, 0, 0, tangent},
                                                    {0, 0, 1, aspect}, {1, 0, 0}, {0, 1, 0}, true,
                                                    true, extent),
                                       0, 0, "static primary pixel motion excludes sample jitter");
                            for (float distance : {0.0f, 5.0f, 65504.0f})
                                sdk_motion(distance_motion(depth, distance, uv, {0, 0, 0, tangent},
                                                           {0, 0, 0, tangent}, aspect, true,
                                                           extent),
                                           0, 0, "static distance motion excludes sample jitter");
                        }

    // Mirror plane z=3 reflects the physical endpoint z=-2 to virtual z=8.
    // Its camera displacement must use z=8, not the primary depth 3 or endpoint -2.
    sdk_motion(distance_motion(3, 5, {0.5f, 0.5f}, {1, 2, 0, 1}), -60, 67.5,
               "planar reflected virtual point with camera XY translation");
    sdk_motion(distance_motion(3, 5, {0.5f, 0.5f}, {1, 2, -4, 1}), -40, 45,
               "planar reflected virtual point with camera XYZ translation");
    sdk_motion(distance_motion(3, 0, {0.5f, 0.5f}, {1, 2, 0, 1}), -160, 180,
               "zero hit distance gives primary-surface motion");

    // Off-axis oracle: intersect z=10, reflect a ray towards -z, then geometrically
    // reflect its endpoint through that plane before projecting into the previous camera.
    for (float distance : {0.0f, 2.0f, 9.0f, 100.0f}) {
        const F2 uv{0.625f, 0.75f};
        const double dx = 0.25, dy = -0.5, length = std::sqrt(dx * dx + dy * dy + 1);
        const double hit_x = 5 + dx * 3, hit_y = 2 + dy * 3;
        const double reflected_x = hit_x + dx / length * distance;
        const double reflected_y = hit_y + dy / length * distance;
        const double reflected_z = 10 - distance / length;
        const double virtual_z = 20 - reflected_z;
        const double expected_x = ((reflected_x - 6) / (virtual_z - 8) * 0.5 + 0.5 - uv.x) * 853;
        const double expected_y = (-(reflected_y - 1) / (virtual_z - 8) * 0.5 + 0.5 - uv.y) * 479;
        auto value =
                distance_motion(3, distance, uv, {6, 1, 8, 1}, {5, 2, 7, 1}, 1, true, {853, 479});
        sdk_motion(value, expected_x, expected_y, "off-axis planar virtual endpoint projection");
        if (distance == 0) {
            auto primary = pixel_motion({float(hit_x), float(hit_y), 10, 0}, uv, {6, 1, 8, 1},
                                        {0, 0, 1, 1}, {1, 0, 0}, {0, 1, 0}, true, true, {853, 479});
            require(close(value.x, primary.x, 2e-4) && close(value.y, primary.y, 2e-4),
                    "zero distance agrees with actual primary point projection");
        }
    }

    const float sky_depth = std::numeric_limits<float>::max();
    sdk_motion(
            distance_motion(sky_depth, 65504, {0.625f, 0.75f}, {1000, 2000, 3000, 1}, {5, 2, 7, 1}),
            0, 0, "sky distance motion has no translation parallax");
    const float angle = 0.3f, c = std::cos(angle), s = std::sin(angle);
    sdk_motion(rrCpuDistanceMotion_0({0.5f, 0.5f}, sky_depth, 0, {0, 0, 0, 1}, {0, 0, 1, 1},
                                     {1, 0, 0}, {0, 1, 0}, {100, 200, 300, 1}, {s, 0, c, 1},
                                     {c, 0, -s}, {0, 1, 0}, true, {960, 540}),
               -480 * std::tan(angle), 0, "sky distance motion retains camera rotation");
    sdk_motion(pixel_motion({0, 0, 10, 0}, {0.5f, 0.5f}, {1, 2, 0, 1}), -48, 54,
               "primary motion converts UV to input pixels once");
    sdk_motion(pixel_motion({0, 0, 10, 0}, {0.5f, 0.5f}, {1, 2, 0, 1}, {0, 0, 1, 1}, {1, 0, 0},
                            {0, 1, 0}, false),
               0, 0, "viewport history reset emits finite zero motion");
    sdk_motion(distance_motion(3, 5, {0.5f, 0.5f}, {1, 2, 0, 1}, {0, 0, 0, 1}, 1, false), 0, 0,
               "distance motion honors whole viewport reset");
    for (bool history : {false, true})
        unavailable_motion(pixel_motion({0, 0, 10, 0}, {0.5f, 0.5f}, {0, 0, 0, 1}, {0, 0, 1, 1},
                                        {1, 0, 0}, {0, 1, 0}, history, false),
                           "unknown dynamic correspondence stays explicit with finite zero output");

    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    for (F4 target : {F4{nan, 0, 1, 0}, F4{inf, 0, 1, 0}, F4{0, 0, -1, 0}, F4{0, 0, 0, 0},
                      F4{1000, 0, 1, 0}, F4{-1000, 0, 1, 0}})
        unavailable_motion(pixel_motion(target), "failed pixel projection never exports sentinel");
    for (float depth : {nan, inf, -1.0f, 0.0f})
        unavailable_motion(distance_motion(depth, 5), "invalid distance depth is finite zero");
    for (float distance : {nan, inf, -1.0f})
        unavailable_motion(distance_motion(3, distance), "invalid hit distance is finite zero");
    unavailable_motion(distance_motion(3, 5, {0.5f, 0.5f}, {0, 0, 20, 1}),
                       "virtual endpoint behind previous camera is unavailable");
}

int main() {
    // An independent double affine oracle covers translation, reflection, shear and nonuniform scale.
    const F3 vertices[] = {{0.25f, -1, 2}, {3, 0.5f, -4}, {-2, 5, 0.125f}};
    const F4 transforms[][3] = {{{1, 0, 0, 1.25f}, {0, 1, 0, -2}, {0, 0, 1, 10}},
                                {{0, -2, 0.25f, 3}, {1, 0, 0, 4}, {0, 0, -1, 5}}};
    for (const auto &rows : transforms)
        for (F2 bary : {F2{0, 0}, F2{1, 0}, F2{0, 1}, F2{0.2f, 0.3f}}) {
            const auto actual = rrCpuRigid_0(vertices[0], vertices[1], vertices[2], bary, rows[0],
                                             rows[1], rows[2]);
            double local[3];
            const float *v0 = &vertices[0].x, *v1 = &vertices[1].x, *v2 = &vertices[2].x;
            for (unsigned axis = 0; axis < 3; ++axis)
                local[axis] = v0[axis] + double(bary.x) * (v1[axis] - v0[axis]) +
                              double(bary.y) * (v2[axis] - v0[axis]);
            for (unsigned axis = 0; axis < 3; ++axis) {
                const float *row = &rows[axis].x;
                const double expected =
                        row[0] * local[0] + row[1] * local[1] + row[2] * local[2] + row[3];
                require(close((&actual.x)[axis], expected, 3e-6),
                        "previous local barycentric affine point");
            }
        }
    for (unsigned status = 0; status < 16; ++status)
        require(rrCpuForeground_0(float(status) / 255) == ((status & 8) != 0),
                "foreground is independent of guide completion and reflection status");
    for (float viewZ : {0.01f, 0.1f, 1.0f, 100.0f, 1e6f}) {
        const float a = 1e6f / (1e6f - 0.01f);
        const double projected = (double(a) * viewZ - double(0.01f * a)) / viewZ;
        require(close(rrCpuDeviceDepth_0(viewZ), projected),
                "visible device depth matches clip Z/W");
    }
    require(rrCpuDeviceDepth_0(0) == 1 && rrCpuDeviceDepth_0(-1) == 1,
            "unobserved invalid depth uses finite far plane");
    pixel_motion_contracts();
    moving_reflection_contracts();
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
