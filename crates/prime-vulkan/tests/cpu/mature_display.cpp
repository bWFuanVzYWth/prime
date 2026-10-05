#include "mature_display.generated.cpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <limits>

static unsigned checked = 0;
static void require(bool value, const char *contract) {
    ++checked;
    if (!value) {
        std::fprintf(stderr, "display contract failed: %s\n", contract);
        std::exit(1);
    }
}
static bool close(double a, double b, double relative = 3e-6, double absolute = 3e-6) {
    return std::isfinite(a) && std::isfinite(b) &&
           std::abs(a - b) <= absolute + relative * std::max(std::abs(a), std::abs(b));
}
static double eotf(double v) {
    const double x = std::abs(v);
    return std::copysign(x <= .04045 ? x / 12.92 : std::pow((x + .055) / 1.055, 2.4), v);
}
static bool sameBits(float a, float b) {
    std::uint32_t aBits, bBits;
    std::memcpy(&aBits, &a, sizeof(aBits));
    std::memcpy(&bBits, &b, sizeof(bBits));
    return aBits == bBits;
}
static double brightness(const Vector<float, 3> &v) {
    return std::max(double(v.x), 0.) * .2627 + std::max(double(v.y), 0.) * .6780 +
           std::max(double(v.z), 0.) * .0593;
}
int main() {
    // Actual production address/mask helpers, with independent raster/weight oracles.
    // FG stays in the final swapchain's top-left space; only the intermediate UI flips.
    for (unsigned height : {1u, 2u, 9u, 540u, 1080u})
        for (unsigned y = 0; y < height; ++y)
            for (bool bottomUp : {false, true}) {
                const unsigned ui = mdCpuUiRow_0(y, height, bottomUp);
                require(ui == (bottomUp ? height - y - 1 : y),
                        "present UI row matches SDR surface blit");
                require(mdCpuUiRow_0(ui, height, bottomUp) == y, "row conversion is an involution");
            }
    for (unsigned iw : {1u, 2u, 8u, 9u})
        for (unsigned ih : {1u, 3u, 8u})
            for (unsigned ow : {1u, 5u, 8u, 17u})
                for (unsigned oh : {1u, 3u, 8u, 19u})
                    for (unsigned y = 0; y < oh; ++y)
                        for (unsigned x = 0; x < ow; ++x) {
                            const float sx = (float(x) + .5f) * (float(iw) / float(ow)) - .5f;
                            const float sy = (float(y) + .5f) * (float(ih) / float(oh)) - .5f;
                            const float fx = sx - std::floor(sx), fy = sy - std::floor(sy);
                            const double weights[] = {(1. - fx) * (1. - fy), fx * (1. - fy),
                                                      (1. - fx) * fy, fx * fy};
                            const auto mask = mdCpuRrFootprint_0({sx, sy});
                            for (unsigned i = 0; i < 4; ++i)
                                require(bool(mask & (1u << i)) == (weights[i] > 0.),
                                        "RR fallback checks exactly positive raw interpolation support");
                        }
    require(mdCpuRrFootprint_0({4.f, 4.f}) == 1,
            "DLAA invalid neighbour cannot expand raw fallback");
    for (float previous : {-16.f, -1.f, 0.f, 8.f, 16.f})
        for (float target : {-16.f, -1.f, 0.f, 8.f, 16.f})
            for (float dt : {0.f, 0.001f, 0.1f, 0.5f, 2.f, 100.f}) {
                const double t90 = target < previous ? .5 : 2.;
                const double blend = 1. - std::exp(-dt * std::log(10.) / t90);
                require(close(mdCpuAdapt_0(previous, target, dt),
                              previous + (target - previous) * blend),
                        "old t90 adaptation equals independent double oracle");
            }
    require(close(mdCpuAdapt_0(0, -10, .5), -9), "dark adaptation reaches90percent at0.5s");
    require(close(mdCpuAdapt_0(0, 10, 2), 9), "bright adaptation reaches90percent at2s");
    for (float minimum : {-16.f, -8.f, 0.f, 8.f})
        for (float range : {0.f, 0.1f, 1.f, 2.f, 10.f, 36.f})
            for (float fraction : {0.f, .25f, .5f, .9f, 1.f})
                for (float strength : {0.f, .6f, 1.f}) {
                    const float measured = minimum + range * fraction, maximum = minimum + range;
                    const double actualRange = double(maximum) - minimum;
                    const double bias = actualRange <= 0
                                                ? 0
                                                : 2. * (2. * measured - minimum - maximum) /
                                                          std::max(actualRange, 2.);
                    const double expected =
                            std::clamp(std::log2(.16) - measured + bias, -16., 16.) * strength;
                    require(close(mdCpuTarget_0(measured, minimum, maximum, strength), expected),
                            "exposure strength and clipped scene-key target");
                }
    for (unsigned classification = 0; classification < 5; classification++)
        for (float distance : {-1.f, 0.f, 100.f})
            for (float albedo : {0.f, .001f, .02f, .18f, .5f, 1.f}) {
                Vector<float, 3> r{.1f, .2f, .3f}, a{albedo, albedo, albedo};
                const bool confidence =
                        (classification == 0 || classification == 3) && distance >= 0;
                const double expected =
                        brightness(r) * (confidence ? .18 / std::max(brightness(a), .02) : 1.);
                require(close(mdCpuMeter_0(r, a, classification, distance), expected),
                        "old material confidence and albedo-compensated metering");
            }
    for (float v :
         {-100.f, -4.f, -1.f, -.04045f, -.001f, 0.f, .001f, .04045f, .5f, 1.f, 2.f, 10.f, 100.f})
        require(close(mdCpuEotf_0(v), eotf(v), 5e-6), "extended sRGB EOTF positive and negative");
    for (float alpha : {0.f, .1f, .5f, 1.f})
        for (float scale : {1.f, 2.5f, 12.5f}) {
            Vector<float, 3> hdr{2.f, 1.f, .5f}, base{.25f, .5f, .75f}, ui{.8f, .3f, .6f};
            Vector<float, 4> composite{ui.x * alpha + base.x * (1 - alpha),
                                       ui.y * alpha + base.y * (1 - alpha),
                                       ui.z * alpha + base.z * (1 - alpha), alpha};
            const auto result = mdCpuHdr_0(hdr, base, composite, scale);
            for (unsigned c = 0; c < 3; c++) {
                const double expected =
                        (eotf((&ui.x)[c]) * alpha + eotf((&hdr.x)[c]) * (1 - alpha)) * scale;
                require(close((&result.x)[c], expected, 5e-6),
                        "linear UI coverage and W/80 HDR scRGB scale");
            }
        }
    // Compile both public paths from the actual Slang module. The linear input reuses exactly
    // the world decode used by the encoded wrapper, as the production HDR+FG pass now does.
    const Vector<float, 3> hdrCases[] = {{-4.f, -.04045f, -.001f},
                                         {0.f, .001f, .04045f},
                                         {.5f, 1.f, 2.f},
                                         {-2.f, .5f, 10.f},
                                         {100.f, -100.f, 4.f}};
    const Vector<float, 3> baselineCases[] = {{0.f, 0.f, 0.f}, {.25f, .5f, .75f}, {1.f, 1.f, 1.f}};
    const Vector<float, 3> uiCases[] = {{0.f, 0.f, 0.f}, {.8f, .3f, .6f}, {2.f, .04045f, 1.f}};
    for (const auto &hdr : hdrCases)
        for (const auto &baseline : baselineCases)
            for (const auto &ui : uiCases)
                for (float alpha : {-.5f, 0.f, .125f, .5f, 1.f, 1.5f})
                    for (float scale : {0.f, 1.f, 2.5f, 12.5f}) {
                        const float coverage = std::clamp(alpha, 0.f, 1.f);
                        Vector<float, 4> composite{ui.x * coverage + baseline.x * (1 - coverage),
                                                   ui.y * coverage + baseline.y * (1 - coverage),
                                                   ui.z * coverage + baseline.z * (1 - coverage),
                                                   alpha};
                        const auto decoded = mdCpuHdrDecode_0(hdr);
                        const auto wrapper = mdCpuHdr_0(hdr, baseline, composite, scale);
                        const auto linear = mdCpuHdrLinear_0(decoded, baseline, composite, scale);
                        for (unsigned c = 0; c < 3; c++) {
                            require(sameBits((&wrapper.x)[c], (&linear.x)[c]),
                                    "HDR encoded wrapper and decoded-world helper are bit exact");
                            // Independent double oracle evaluates the original source-over
                            // reconstruction, including zero/full and saturated UI coverage.
                            const double a = std::clamp(double(alpha), 0., 1.);
                            const double encodedUi = std::max(
                                    double((&composite.x)[c]) - double((&baseline.x)[c]) * (1 - a),
                                    0.);
                            const double recoveredUi = a > 0 ? eotf(encodedUi / a) * a : 0.;
                            const double expected =
                                    (recoveredUi + eotf((&hdr.x)[c]) * (1 - a)) * scale;
                            require(close((&linear.x)[c], expected, 6e-6, 8e-6),
                                    "HDR linear helper preserves signed extended-sRGB and UI math");
                        }
                    }
    for (float coverage : {-1.f, 0.f, .25f, 1.f, 2.f, std::numeric_limits<float>::quiet_NaN(),
                           std::numeric_limits<float>::infinity()}) {
        const auto v = mdCpuComposite_0({1, 2, 3}, {4, 8, 12}, coverage);
        const double visibility =
                std::isfinite(coverage) ? 1. - std::clamp(double(coverage), 0., 1.) : 0.;
        require(close(v.x, 1 + 4 * visibility) && close(v.y, 2 + 8 * visibility),
                "invalid external alpha does not reveal stars through geometry");
    }
    for (float size : {0.f, 1e-20f, 1.f, 2.f, 8.f, 100.f}) {
        const auto f = mdCpuFootprint_0({size / 16384.f, 0}, {0, size / 8192.f}, {16384, 8192});
        require(close(f.z, std::log2(std::max(double(size), 1.))), "isotropic mip footprint");
        require(f.w == 1 && std::isfinite(f.x) && std::isfinite(f.y),
                "zero and tiny Jacobian stays finite");
    }
    const auto anisotropic =
            mdCpuFootprint_0({128.f / 16384.f, 0}, {0, 1.f / 8192.f}, {16384, 8192});
    require(anisotropic.w == 8 && close(anisotropic.z, 4),
            "anisotropy cap widens minor footprint instead of underfiltering");
    for (float latitude : {-1.5f, -.5f, 0.f, .5f, 1.5f})
        for (float longitude : {0.f, 1.f, 2.f, 3.f, 5.f}) {
            Vector<float, 3> sun{0, 1, 0};
            const auto uv = mdCpuStarmapUv_0(sun, latitude, longitude, {1, 0, 0});
            const double solarRa = std::atan2(std::cos(.4090928042223289) * std::sin(longitude),
                                              std::cos(longitude));
            double expected = .5 - (solarRa + 3.141592653589793 / 2) / (2 * 3.141592653589793);
            expected -= std::floor(expected);
            require(close(uv.x, expected) && close(uv.y, .5),
                    "J2000 east direction and left-increasing RA projection");
        }
    std::printf("mature display CPU: %u contracts passed\n", checked);
}
