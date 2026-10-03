#include "starmap_poles.generated.cpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static unsigned checked = 0;
static constexpr double pi = 3.14159265358979323846;
using V3 = Vector<float, 3>;
using V2 = Vector<float, 2>;
static void require(bool condition, const char *message) {
    ++checked;
    if (!condition) {
        std::fprintf(stderr, "starmap contract failed: %s\n", message);
        std::exit(1);
    }
}
static bool close(double actual, double expected, double tolerance = 2e-7) {
    return std::isfinite(actual) && std::abs(actual - expected) <= tolerance;
}
static double dot(V3 a, V3 b) {
    return double(a.x) * b.x + double(a.y) * b.y + double(a.z) * b.z;
}
static V3 add(V3 a, V3 b, double scale = 1) {
    return {float(a.x + b.x * scale), float(a.y + b.y * scale), float(a.z + b.z * scale)};
}
static double wrap(double value) {
    return value - std::floor(value);
}
static bool closeU(double actual, double expected) {
    const double difference = actual - expected;
    return close(difference - std::floor(difference + .5), 0, 4e-7);
}
// Independent double oracle projects the actual input frame/ray, normalizes it, then
// recovers spherical coordinates. This is not the production colatitude expression.
static std::array<double, 2> oracle(const PrimeCelestialFrame_0 &frame, V3 ray) {
    const double e = dot(ray, frame.east_0), m = dot(ray, frame.meridian_0);
    const double p = dot(ray, frame.pole_0);
    const double length = std::sqrt(e * e + m * m + p * p);
    const double declination = std::asin(std::clamp(p / length, -1., 1.));
    return {wrap(.5 - (frame.siderealPhase_0 - std::atan2(-e, m)) / (2 * pi)),
            .5 - declination / pi};
}
static float ordinaryLod(PrimeCelestialFrame_0 &frame, V3 ray, V3 dx, V3 dy) {
    const auto difference = [](V2 a, V2 b) {
        double x = double(a.x) - b.x;
        return std::array<double, 2>{x - std::floor(x + .5), double(a.y) - b.y};
    };
    const auto x =
            difference(smCpuUv_0(&frame, add(ray, dx, .5)), smCpuUv_0(&frame, add(ray, dx, -.5)));
    const auto y =
            difference(smCpuUv_0(&frame, add(ray, dy, .5)), smCpuUv_0(&frame, add(ray, dy, -.5)));
    const double a = (x[0] * x[0] + y[0] * y[0]) * 16384 * 16384;
    const double b = (x[0] * x[1] + y[0] * y[1]) * 16384 * 8192;
    const double c = (x[1] * x[1] + y[1] * y[1]) * 8192 * 8192;
    const double discriminant = std::hypot(a - c, 2 * b);
    const double major = std::sqrt(std::max(.5 * (a + c + discriminant), 0.));
    const double minor = std::sqrt(std::max(.5 * (a + c - discriminant), 0.));
    return float(std::log2(std::max({1., minor, major / 8})));
}
struct Texture final : ITexture {
    struct Sample {
        float u, v, lod;
    };
    std::vector<Sample> samples;
    bool radial = false, south = false;
    TextureDimensions GetDimensions(int = -1) override {
        TextureDimensions dimensions{};
        dimensions.width = 16384;
        dimensions.height = 8192;
        dimensions.numberOfLevels = 15;
        return dimensions;
    }
    void Load(const int32_t *, void *, size_t) override {
        std::abort();
    }
    void Sample(SamplerState, const float *, void *, size_t) override {
        std::abort();
    }
    void SampleLevel(SamplerState, const float *uv, float lod, void *out, size_t size) override {
        require(size == sizeof(Vector<float, 4>), "actual sampler ABI is float4");
        samples.push_back({uv[0], uv[1], lod});
        const double theta = (south ? 1. - uv[1] : uv[1]) * pi;
        const float value = radial ? float(theta * theta) : 1.25f;
        Vector<float, 4> result{value, radial ? value : .5f, radial ? value : 8.f, .123f};
        std::memcpy(out, &result, size);
    }
};
int main() {
    auto frame = smCpuFrame_0({0, 1, 0}, 0, 0);
    for (float sign : {-1.f, 1.f})
        for (float radius : {1e-6f, 1e-5f, 1e-4f, .0002f, .0003f, .001f, .01f, .1f})
            for (unsigned angle = 0; angle < 64; ++angle) {
                const double azimuth = angle * 2 * pi / 64;
                V3 ray{float(radius * std::cos(azimuth)), float(radius * std::sin(azimuth)), -sign};
                const auto uv = smCpuUv_0(&frame, ray);
                const double colatitude = std::atan(double(radius));
                const double expectedV = sign > 0 ? colatitude / pi : 1 - colatitude / pi;
                require(close(uv.y, expectedV, 1.3e-7), "thin polar ring retains correct latitude");
                if (sign > 0)
                    require(uv.y > 0, "north polar rings do not collapse to pole row");
            }
    for (float latitude : {-1.5f, -.5f, 0.f, .5f, 1.5f})
        for (float longitude : {0.f, 1.f, 3.f, 5.f}) {
            auto celestial = smCpuFrame_0({.3f, .8f, -.2f}, latitude, longitude);
            for (int x = -7; x <= 7; ++x)
                for (int y = -7; y <= 7; ++y) {
                    V3 ray{float(x) / 7, float(y) / 7, 1};
                    const auto expected = oracle(celestial, ray);
                    const auto uv = smCpuUv_0(&celestial, ray);
                    require(closeU(uv.x, expected[0]) && close(uv.y, expected[1]),
                            "actual mapped coordinates equal independent double sphere oracle");
                    const auto scaled = smCpuUv_0(&celestial, {ray.x * 8, ray.y * 8, ray.z * 8});
                    require(closeU(uv.x, scaled.x) && close(uv.y, scaled.y),
                            "non-unit perspective ray gives the same direction");
                }
        }
    // Eight equal weights reproduce the square pixel's constant, first, and second moments.
    double x = 0, y = 0, xx = 0, yy = 0, xy = 0;
    for (unsigned tap = 0; tap < 8; ++tap) {
        const auto offset = smCpuOffset_0(tap);
        require(std::abs(offset.x) <= .5 && std::abs(offset.y) <= .5,
                "all sample positions stay within the pixel");
        x += offset.x / 8;
        y += offset.y / 8;
        xx += double(offset.x) * offset.x / 8;
        yy += double(offset.y) * offset.y / 8;
        xy += double(offset.x) * offset.y / 8;
        const auto opposite = smCpuOffset_0((tap + 4) % 8);
        require(offset.x == -opposite.x && offset.y == -opposite.y, "opposite tap symmetry");
    }
    require(close(x, 0) && close(y, 0) && close(xy, 0) && close(xx, 1. / 12) && close(yy, 1. / 12),
            "constant and quadratic box-pixel moments");
    Texture texture;
    Sampler2D_0 sampler{{&texture}, {nullptr}};
    double worstLatitudeError = 0, worstPolarLod = 0;
    for (float sign : {-1.f, 1.f})
        for (float width : {.0001f, .001f, .01f})
            for (float radius : {0.f, .0003f, .001f, .003f, .01f, .03f, .08f})
                for (unsigned rotation = 0; rotation < 16; ++rotation) {
                    const double phi = rotation * 2 * pi / 16;
                    V3 dx{float(width * std::cos(phi)), float(width * std::sin(phi)), 0};
                    V3 dy{-dx.y, dx.x, 0};
                    V3 ray{float(radius * std::cos(phi)), float(radius * std::sin(phi)), -sign};
                    float lod = smCpuLod_0(&frame, ray, dx, dy, ordinaryLod(frame, ray, dx, dy));
                    if (lod < 0)
                        continue;
                    worstPolarLod = std::max(worstPolarLod, double(lod));
                    require(std::exp2(lod) <= std::max(1., width * 8192 / pi),
                            "polar mip never spreads latitude beyond pixel angular support");
                    texture.samples.clear();
                    texture.radial = false;
                    const auto constant = smCpuSample_0(&sampler, &frame, ray, dx, dy, lod);
                    require(constant.x == 1.25f && constant.y == .5f && constant.z == 8.f,
                            "actual production sampler preserves constant RGB radiance");
                    // The C++ backend expands one float4 SampleLevel into three component calls.
                    // SPIR-V validation separately checks a single instruction per loop iteration.
                    require(texture.samples.size() == 24, "actual C++ sampling backend contract");
                    for (unsigned tap = 0; tap < 8; ++tap) {
                        const double angle = tap * pi / 4;
                        const double ox = std::cos(angle) / std::sqrt(6.);
                        const double oy = std::sin(angle) / std::sqrt(6.);
                        const double actualX =
                                double(ray.x) + double(dx.x) * ox + double(dy.x) * oy;
                        const double actualY =
                                double(ray.y) + double(dx.y) * ox + double(dy.y) * oy;
                        const double theta = std::atan2(std::hypot(actualX, actualY), 1.);
                        const double expectedV = sign > 0 ? theta / pi : 1 - theta / pi;
                        const double expectedU =
                                wrap(.5 - (frame.siderealPhase_0 - std::atan2(-actualX, actualY)) /
                                                  (2 * pi));
                        for (unsigned component = 0; component < 3; ++component) {
                            const auto sample = texture.samples[tap * 3 + component];
                            worstLatitudeError = std::max(worstLatitudeError,
                                                          std::abs(sample.v - expectedV) * 8192);
                            require(close(sample.v, expectedV, 1.3e-7) &&
                                            (std::hypot(actualX, actualY) < 1e-8 ||
                                             closeU(sample.u, expectedU)) &&
                                            sample.lod == lod,
                                    "actual texture requests use independent subpixel sphere points");
                        }
                    }
                    // Radially symmetric radiance must have equal ring means at all rotations.
                    texture.radial = true;
                    texture.south = sign < 0;
                    const auto value = smCpuSample_0(&sampler, &frame, ray, dx, dy, lod);
                    double expected = 0;
                    for (unsigned tap = 0; tap < 8; ++tap) {
                        const double angle = tap * pi / 4;
                        const double ox = std::cos(angle) / std::sqrt(6.);
                        const double oy = std::sin(angle) / std::sqrt(6.);
                        const double theta = std::atan(std::hypot(radius + width * ox, width * oy));
                        expected += theta * theta / 8;
                    }
                    require(close(value.x, expected, 1e-7),
                            "polar radial field preserves rotation-invariant independent mean");
                }
    for (float sign : {-1.f, 1.f}) {
        V3 ray{0, 0, -sign}, dx{.001f, 0, 0}, dy{0, .001f, 0};
        const float oldLod = ordinaryLod(frame, ray, dx, dy);
        const float lod = smCpuLod_0(&frame, ray, dx, dy, oldLod);
        require(close(oldLod, 10.5), "baseline cross-pole UV footprint regression trigger");
        require(lod >= 0 && lod < .5, "cross-pole footprint no longer selects thousand-row mip");
    }
    for (V3 ray : {V3{1, 0, 0}, V3{1, 1, -1}, V3{.2f, 0, -1}})
        for (float lod : {0.f, 4.f, 10.f})
            require(smCpuLod_0(&frame, ray, {.001f, 0, 0}, {0, .001f, 0}, lod) < 0,
                    "ordinary chart always retains the original ellipse sampler");
    // Rotate the whole celestial pole and pixel basis, not just an axis-aligned camera.
    // Longitude is ill-conditioned at a pole; compare its physical angular displacement.
    for (float latitude : {-1.5f, -.5f, .5f, 1.5f})
        for (float sign : {-1.f, 1.f})
            for (float radius : {.0003f, .003f, .01f})
                for (unsigned rotation = 0; rotation < 16; ++rotation) {
                    auto tilted = smCpuFrame_0({.3f, .8f, -.2f}, latitude, 1.f);
                    const double phi = rotation * 2 * pi / 16;
                    V3 ray = add({tilted.pole_0.x * sign, tilted.pole_0.y * sign,
                                  tilted.pole_0.z * sign},
                                 tilted.east_0, radius);
                    V3 dx = add({tilted.east_0.x * float(.001 * std::cos(phi)),
                                 tilted.east_0.y * float(.001 * std::cos(phi)),
                                 tilted.east_0.z * float(.001 * std::cos(phi))},
                                tilted.meridian_0, .001 * std::sin(phi));
                    V3 dy = add({-tilted.east_0.x * float(.001 * std::sin(phi)),
                                 -tilted.east_0.y * float(.001 * std::sin(phi)),
                                 -tilted.east_0.z * float(.001 * std::sin(phi))},
                                tilted.meridian_0, .001 * std::cos(phi));
                    const float lod =
                            smCpuLod_0(&tilted, ray, dx, dy, ordinaryLod(tilted, ray, dx, dy));
                    require(lod >= 0 && lod < .5, "tilted pole keeps a pixel-sized radial mip");
                    for (unsigned tap = 0; tap < 8; ++tap) {
                        const double angle = tap * pi / 4;
                        const double ox = std::cos(angle) / std::sqrt(6.);
                        const double oy = std::sin(angle) / std::sqrt(6.);
                        const std::array<double, 3> point{
                                ray.x + double(dx.x) * ox + double(dy.x) * oy,
                                ray.y + double(dx.y) * ox + double(dy.y) * oy,
                                ray.z + double(dx.z) * ox + double(dy.z) * oy};
                        const double e = point[0];
                        const double m = point[1] * std::cos(double(latitude)) +
                                         point[2] * std::sin(double(latitude));
                        const double p = point[1] * std::sin(double(latitude)) -
                                         point[2] * std::cos(double(latitude));
                        const double length = std::sqrt(e * e + m * m + p * p);
                        const double expectedU =
                                wrap(.5 - (tilted.siderealPhase_0 - std::atan2(-e, m)) / (2 * pi));
                        const double expectedV = .5 - std::asin(p / length) / pi;
                        const auto uv = smCpuTap_0(&tilted, ray, dx, dy, tap);
                        double du = uv.x - expectedU;
                        du -= std::floor(du + .5);
                        require(std::abs(du) * 2 * pi * std::hypot(e, m) / length < 3e-7 &&
                                        std::abs(uv.y - expectedV) * pi < 4e-7,
                                "tilted-frame taps follow independent physical rotated points");
                    }
                }
    require(smCpuLod_0(&frame, {0, 0, -1}, {.001f, 0, 0}, {0, .001f, 0}, 0) < 0,
            "well-sized existing polar footprint keeps original sampler");
    std::printf(
            "starmap CPU: %u contracts passed; max tap latitude error %.9g texels; max polar LOD %.9g\n",
            checked, worstLatitudeError, worstPolarLod);
}
