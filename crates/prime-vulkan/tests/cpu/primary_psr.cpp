// Behavior checks execute the production Slang PSR transform, not a source-text surrogate.
#include "primary_psr.generated.cpp"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <limits>

using F3 = Vector<float, 3>;
using F4 = Vector<float, 4>;
static unsigned checks;
static void require(bool value, const char *label) {
    ++checks;
    if (!value) {
        std::fprintf(stderr, "FAIL %s (%u)\n", label, checks);
        std::exit(1);
    }
}
static bool near(float a, float b) { return std::isfinite(a) && std::abs(a - b) < 2e-5f; }
static void vector(F4 actual, F3 expected, const char *label) {
    require(near(actual.x, expected.x) && near(actual.y, expected.y) && near(actual.z, expected.z), label);
}
static F3 add(F3 a, F3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
static F3 sub(F3 a, F3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
static F3 scale(F3 a, float t) { return {a.x * t, a.y * t, a.z * t}; }
static float dot(F3 a, F3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
static F3 unit(F3 a) { return scale(a, 1.f / std::sqrt(dot(a, a))); }
static F3 reflect(F3 a, F3 normal) { return sub(a, scale(normal, 2.f * dot(a, normal))); }
static F3 reflectPoint(F3 point, F3 plane, F3 normal) {
    return sub(point, scale(normal, 2.f * dot(sub(point, plane), normal)));
}

int main() {
    const F3 zero{0, 0, 0}, n0{0, 0, -1}, n1{-1, 0, 0}, targetNormal{0, 1, 0};
    for (float x : {-0.8f, -0.2f, 0.f, 0.3f, 0.9f})
        for (float y : {-0.5f, 0.f, 0.7f})
            for (float distance : {0.2f, 1.f, 7.f}) {
                const F3 first{x, y, 1};
                const F3 direction = unit(first);
                const F3 target = add(first, scale(reflect(direction, n0), distance));
                const auto point = psrCpuProbe_0(zero, first, zero, target, n0, n1, targetNormal, 1, 31, 0);
                vector(point, reflectPoint(target, first, n0), "one mirror reconstructs unfolded point");
                require(point.w == 1, "one mirror resolved");
                const auto normal = psrCpuProbe_0(zero, first, zero, target, n0, n1, n0, 1, 31, 1);
                vector(normal, reflect(n0, n0), "one mirror transforms normal");
                require(normal.w == 1, "static planar mirror has known motion");
                const auto motion = psrCpuStaticMotion_0({point.x, point.y, point.z},
                                                          {(x + 1) * .5f, (1 - y) * .5f}, zero);
                require(near(motion.x, 0) && near(motion.y, 0), "static mirror excludes primary jitter");
            }
    const F3 first{1.2f, .4f, 2}, ray = unit(first);
    const F3 reflected = reflect(ray, n0);
    const F3 second = add(first, scale(reflected, (4.f - first.x) / reflected.x));
    const F3 target = add(second, scale(reflect(reflected, n1), 3.f));
    auto point = psrCpuProbe_0(zero, first, second, target, n0, n1, targetNormal, 2, 31, 0);
    vector(point, reflectPoint(reflectPoint(target, second, n1), first, n0),
           "two mirrors compose transformations in path order");
    auto normal = psrCpuProbe_0(zero, first, second, target, n0, n1, unit({1, 2, 3}), 2, 31, 1);
    vector(normal, reflect(reflect(unit({1, 2, 3}), n1), n0), "even reflection parity");
    require(normal.w == 1, "all static reflection chain has correspondence");
    for (unsigned dynamic : {4u, 8u, 16u}) {
        normal = psrCpuProbe_0(zero, first, second, target, n0, n1, targetNormal, 2, 31 ^ dynamic, 1);
        require(normal.w == 0, "any dynamic chain element invalidates motion");
    }
    for (unsigned refracted : {32u, 64u}) {
        normal = psrCpuProbe_0(zero, first, second, target, n0, n1, targetNormal, 2, 31 | refracted, 1);
        require(normal.w == 0, "refracted proxy never invents previous geometry");
    }
    point = psrCpuProbe_0(zero, {0, 0, 1}, {0, 0, 2}, {0, 0, 3}, n0, n1, targetNormal, 2, 28, 0);
    vector(point, {0, 0, 3}, "straight thin interfaces preserve physical target");
    require(point.w == 1, "straight thin interfaces resolve");
    point = psrCpuProbe_0(zero, zero, zero, {0, 0, 1}, n0, n1, targetNormal, 1, 31, 0);
    require(point.w == 0, "zero first segment cannot fabricate guide");
    const float nan = std::numeric_limits<float>::quiet_NaN();
    point = psrCpuProbe_0(zero, first, zero, {nan, 0, 1}, n0, n1, targetNormal, 1, 31, 0);
    require(point.w == 0, "nonfinite proxy is unresolved");
    std::printf("Primary PSR: %u checks passed\n", checks);
}
