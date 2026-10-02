// Execute production Slang, comparing affine geometry with an independent double matrix oracle.
#include "primary_psr.generated.cpp"
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>

using F3 = Vector<float, 3>;
using F4 = Vector<float, 4>;
struct D3 {
    double x, y, z;
};
struct Psr {
    F4 rotation = psrCpuInitial_0(0);
    F4 translation = psrCpuInitial_0(1);
};
struct Affine {
    double a[3][3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    D3 b{0, 0, 0};
};
struct Camera {
    D3 position, forward, right, up;
    double tanFov, aspect;
};
static unsigned checks;
static void require(bool value, const char *label) {
    ++checks;
    if (!value) {
        std::fprintf(stderr, "FAIL %s (%u)\n", label, checks);
        std::exit(1);
    }
}
static D3 add(D3 a, D3 b) {
    return {a.x + b.x, a.y + b.y, a.z + b.z};
}
static D3 sub(D3 a, D3 b) {
    return {a.x - b.x, a.y - b.y, a.z - b.z};
}
static D3 scale(D3 a, double t) {
    return {a.x * t, a.y * t, a.z * t};
}
static double dot(D3 a, D3 b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}
static D3 unit(D3 a) {
    return scale(a, 1 / std::sqrt(dot(a, a)));
}
static F3 f3(D3 a) {
    return {float(a.x), float(a.y), float(a.z)};
}
static D3 d3(F3 a) {
    return {a.x, a.y, a.z};
}
static D3 d3(F4 a) {
    return {a.x, a.y, a.z};
}
static F4 f4(D3 a, double w) {
    return {float(a.x), float(a.y), float(a.z), float(w)};
}
static bool near(double a, double b, double tolerance = 3e-5) {
    return std::isfinite(a) && std::abs(a - b) <= tolerance * (1 + std::abs(b));
}
static void vector(D3 actual, D3 expected, const char *label, double tolerance = 3e-5) {
    require(near(actual.x, expected.x, tolerance) && near(actual.y, expected.y, tolerance) &&
                    near(actual.z, expected.z, tolerance),
            label);
}
static D3 direction(const Affine &state, D3 x) {
    const double p[3]{x.x, x.y, x.z};
    double result[3]{};
    for (unsigned i = 0; i < 3; ++i)
        for (unsigned j = 0; j < 3; ++j)
            result[i] += state.a[i][j] * p[j];
    return {result[0], result[1], result[2]};
}
static D3 point(const Affine &state, D3 x) {
    return add(direction(state, x), state.b);
}
static D3 reflection(D3 x, D3 n) {
    return sub(x, scale(n, 2 * dot(n, x)));
}
static D3 reflectionPoint(D3 x, D3 p, D3 n) {
    return sub(x, scale(n, 2 * dot(n, sub(x, p))));
}
static void append(Affine &state, D3 p, D3 n) {
    // Direct affine matrix composition, independent of production quaternion/parity storage.
    const double components[3]{n.x, n.y, n.z};
    Affine next;
    next.b = add(state.b, direction(state, scale(n, 2 * dot(n, p))));
    for (unsigned i = 0; i < 3; ++i)
        for (unsigned j = 0; j < 3; ++j) {
            next.a[i][j] = 0;
            for (unsigned k = 0; k < 3; ++k)
                next.a[i][j] +=
                        state.a[i][k] * ((k == j ? 1.0 : 0.0) - 2 * components[k] * components[j]);
        }
    state = next;
}
static void append(Psr &state, D3 p, D3 n, unsigned events = 5) {
    const auto rotation =
            psrCpuAppend_0(state.rotation, state.translation, f3(p), f3(n), events, 0);
    const auto translation =
            psrCpuAppend_0(state.rotation, state.translation, f3(p), f3(n), events, 1);
    state = {rotation, translation};
}
static D3 transformed(const Psr &state, D3 value, bool isPoint) {
    return d3(psrCpuTransform_0(state.rotation, state.translation, f3(value), isPoint));
}
static F4 surface(const Psr &state, D3 camera, D3 ray, D3 target, D3 geometric, D3 shading,
                  unsigned channel = 0, bool terminalStatic = true) {
    return psrCpuSurface_0(state.rotation, state.translation, f3(camera), f3(ray), f3(target),
                           f3(geometric), f3(shading), terminalStatic, channel);
}
static D3 anchor(D3 camera, D3 ray, D3 target, D3 normal) {
    return add(camera, scale(ray, dot(normal, sub(target, camera)) / dot(normal, ray)));
}
static D3 cameraRay(const Camera &camera, double u, double v) {
    return unit(add(camera.forward,
                    add(scale(camera.right, (2 * u - 1) * camera.tanFov * camera.aspect),
                        scale(camera.up, (1 - 2 * v) * camera.tanFov))));
}
static std::array<double, 2> project(const Camera &camera, D3 target) {
    const auto relative = sub(target, camera.position);
    const auto depth = dot(relative, camera.forward);
    return {.5 * (1 + dot(relative, camera.right) / (depth * camera.tanFov * camera.aspect)),
            .5 * (1 - dot(relative, camera.up) / (depth * camera.tanFov))};
}
static void motion(const Camera &previous, D3 target, double u, double v, double jitterX,
                   double jitterY) {
    const auto actual =
            psrCpuMotion_0(f3(target), {float(u), float(v)}, f4(previous.position, previous.tanFov),
                           f4(previous.forward, previous.aspect), f4(previous.right, 0),
                           f4(previous.up, 0), {float(jitterX), float(jitterY)});
    const auto expected = project(previous, target);
    require(near(actual.x, expected[0] - u) && near(actual.y, expected[1] - v),
            "production motion agrees with independent previous projection");
}

int main() {
    const D3 zero{0, 0, 0}, axisZ{0, 0, 1}, axisY{0, 1, 0};
    Psr state;
    require(sizeof(PrimePrimaryPsr_0) == 32, "PSR state has eight 32-bit lanes");
    vector(transformed(state, {3, -4, 7}, true), {3, -4, 7}, "identity point");
    vector(transformed(state, axisZ, false), axisZ, "identity direction");
    Affine oracle;
    Psr flipped;
    // Arbitrary planes and off-ray points detect omitted translation and reversed composition.
    for (unsigned i = 0; i < 64; ++i) {
        const double phase = double(i) * .71;
        const D3 normal = unit({std::cos(phase), std::sin(phase), .3 + .01 * i});
        const D3 plane{2 * std::sin(phase), .13 * i - 2, 1 + .04 * i};
        append(state, plane, normal);
        append(flipped, plane, scale(normal, -1));
        append(oracle, plane, normal);
        const D3 arbitrary{3.7 - .07 * i, -2.3 + .1 * i, 9.2};
        vector(transformed(state, arbitrary, true), point(oracle, arbitrary), "affine point chain");
        vector(transformed(state, arbitrary, false), direction(oracle, arbitrary),
               "orthogonal direction chain");
        vector(transformed(flipped, arbitrary, true), transformed(state, arbitrary, true),
               "plane normal sign does not change point");
        require((unsigned(state.translation.w) & 127u) == i + 1, "count preserves all 64 steps");
        require(near(dot(transformed(state, axisZ, false), transformed(state, axisZ, false)), 1),
                "reflection composition preserves unit length");
    }
    Psr parallel;
    append(parallel, {0, 0, 2}, axisZ);
    append(parallel, {0, 0, 5}, axisZ);
    vector(transformed(parallel, {2, 3, 8}, true), {2, 3, 2},
           "parallel displaced planes retain translation");
    vector(transformed(parallel, axisY, false), axisY,
           "two parallel reflections preserve direction");

    // Physical mirror paths unfold onto their originating camera ray.
    for (double x : {.1, .3, .8})
        for (double y : {-.4, .0, .7})
            for (double distance : {.2, 2., 7.}) {
                const D3 camera{.2, -.1, .3};
                const D3 first = add(camera, {x, y, 2});
                const D3 ray = unit(sub(first, camera));
                const D3 reflected = reflection(ray, axisZ);
                const D3 second = add(first, scale(reflected, 1.7));
                const D3 secondNormal = unit({.3, .8, .2});
                const D3 outgoing = reflection(reflected, secondNormal);
                const D3 target = add(second, scale(outgoing, distance));
                const D3 geometric = scale(outgoing, -1);
                const D3 shading = unit(add(geometric, scale(axisY, .1)));
                Psr chain;
                append(chain, first, axisZ);
                append(chain, second, secondNormal);
                const auto actual = surface(chain, camera, ray, target, geometric, shading);
                const auto expected = reflectionPoint(reflectionPoint(target, second, secondNormal),
                                                      first, axisZ);
                require(actual.w == 1, "two-mirror physical endpoint resolves");
                vector(d3(actual), expected, "exact unfolded endpoint equals camera-plane anchor");
                const auto normal = surface(chain, camera, ray, target, geometric, shading, 1);
                vector(d3(normal), reflection(reflection(shading, secondNormal), axisZ),
                       "shading normal follows same unfolding");
                require(normal.w == 1, "static mirrors have known correspondence");
                const D3 tangent = unit({-geometric.y, geometric.x, 0});
                const auto offset = surface(chain, camera, ray, add(target, scale(tangent, .002)),
                                            geometric, shading);
                vector(d3(offset), expected, "terminal plane removes tangent safe-origin drift");
            }

    Psr refracted;
    append(refracted, {0, 0, 1}, axisZ, 6);
    require((unsigned(refracted.translation.w) & 512u) != 0,
            "refraction approximation is recorded");
    vector(transformed(refracted, {2, 3, 8}, true), {2, 3, 8},
           "refraction does not fabricate an exact affine Snell transform");
    require(surface(refracted, zero, axisZ, {2, 3, 8}, axisZ, axisZ, 1).w == 1,
            "static refracted target-plane proxy has correspondence");
    Psr mixed = refracted;
    append(mixed, {0, 0, 2}, axisZ);
    const D3 target{.7, -.4, -4}, geo = unit({.1, .2, -1}), shade = unit({.15, .25, -1});
    const Camera current{{.2, -.1, .3}, axisZ, {1, 0, 0}, axisY, .8, 16. / 9.};
    const double turn = .08;
    const Camera previous{{-.15, .05, .1},
                          {std::sin(turn), 0, std::cos(turn)},
                          {std::cos(turn), 0, -std::sin(turn)},
                          axisY,
                          .8,
                          16. / 9.};
    const std::array<Psr, 2> proxies{refracted, mixed};
    for (unsigned mode = 0; mode < proxies.size(); ++mode) {
        const auto &proxy = proxies[mode];
        const D3 physicalTarget = mode == 0 ? D3{.7, -.4, 8} : target;
        const D3 physicalNormal = mode == 0 ? scale(geo, -1) : geo;
        const D3 virtualTarget = transformed(proxy, physicalTarget, true);
        const D3 virtualGeometric = transformed(proxy, physicalNormal, false);
        for (unsigned sample = 0; sample < 64; ++sample) {
            const double jitterX = (double((sample * 17) % 64) + .5) / 64 - .5;
            const double jitterY = (double((sample * 29) % 64) + .5) / 64 - .5;
            const double u = .57 + jitterX / 1920, v = .42 + jitterY / 1080;
            const auto ray = cameraRay(current, u, v);
            const auto actual =
                    surface(proxy, current.position, ray, physicalTarget, physicalNormal, shade);
            require(actual.w == 1, "refracted/reflected plane anchor resolves across jitter");
            vector(d3(actual), anchor(current.position, ray, virtualTarget, virtualGeometric),
                   "mixed proxy matches target-plane oracle");
            motion(current, d3(actual), u, v, jitterX, jitterY);
            const auto staticUv = project(current, d3(actual));
            require(near(staticUv[0], u, 2e-7) && near(staticUv[1], v, 2e-7),
                    "static camera jitter projects back to its actual sample");
            motion(previous, d3(actual), u, v, jitterX, jitterY);
            const auto previousUv = project(previous, d3(actual));
            vector(anchor(previous.position, cameraRay(previous, previousUv[0], previousUv[1]),
                          virtualTarget, virtualGeometric),
                   d3(actual), "moved camera reconstructs the same virtual plane point");
        }
    }

    const D3 shift{41, -13, 27};
    Psr shifted;
    append(shifted, add({0, 0, 1}, shift), axisZ, 6);
    append(shifted, add({0, 0, 2}, shift), axisZ);
    const auto ray = cameraRay(current, .57, .42);
    const auto originalPoint = surface(mixed, current.position, ray, target, geo, shade);
    const auto shiftedPoint =
            surface(shifted, add(current.position, shift), ray, add(target, shift), geo, shade);
    vector(d3(shiftedPoint), add(d3(originalPoint), shift), "scene anchor shift is equivariant");
    vector(d3(surface(shifted, add(current.position, shift), ray, add(target, shift), geo, shade,
                      1)),
           d3(surface(mixed, current.position, ray, target, geo, shade, 1)),
           "scene anchor shift leaves normals unchanged");

    for (unsigned events : {1u, 2u, 0u}) {
        Psr dynamic;
        append(dynamic, {0, 0, 2}, axisZ, events);
        require(surface(dynamic, zero, axisZ, {0, 0, 3}, axisZ, axisZ, 1).w == 0,
                "any dynamic interface rejects known previous geometry");
    }
    require(surface(refracted, zero, axisZ, {0, 0, 3}, axisZ, axisZ, 1, false).w == 0,
            "dynamic terminal rejects known previous geometry");
    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double inf = std::numeric_limits<double>::infinity();
    const Psr identity;
    const auto primaryGrazing = surface(identity, zero, axisZ, {0, 0, 3}, axisY, axisZ);
    require(primaryGrazing.w == 1,
            "ordinary primary hits do not require a proxy plane denominator");
    vector(d3(primaryGrazing), {0, 0, 3}, "ordinary primary endpoint remains the actual hit");
    require(surface(refracted, zero, axisZ, {0, 0, 3}, axisY, axisZ).w == 0,
            "parallel terminal plane is unresolved");
    require(surface(refracted, zero, axisZ, {0, 0, 3}, unit({1, 0, 1e-5}), axisZ).w == 0,
            "near parallel terminal plane is unresolved");
    require(surface(refracted, zero, axisZ, {0, 0, -3}, axisZ, axisZ).w == 0,
            "behind camera target plane is unresolved");
    require(surface(refracted, zero, axisZ, zero, axisZ, axisZ).w == 0,
            "zero depth cannot fabricate guide");
    require(surface(refracted, zero, zero, {0, 0, 3}, axisZ, axisZ).w == 0,
            "zero camera direction is unresolved");
    require(surface(refracted, zero, axisZ, {0, 0, 3}, zero, axisZ).w == 0,
            "zero geometric normal is unresolved");
    require(surface(identity, zero, axisZ, {0, 0, 3}, axisZ, zero).w == 0,
            "zero shading normal is unresolved");
    for (double invalid : {nan, inf}) {
        require(surface(refracted, zero, axisZ, {invalid, 0, 3}, axisZ, axisZ).w == 0,
                "nonfinite target is unresolved");
        require(surface(refracted, zero, {invalid, 0, 1}, {0, 0, 3}, axisZ, axisZ).w == 0,
                "nonfinite camera ray is unresolved");
        require(surface(refracted, zero, axisZ, {0, 0, 3}, {invalid, 0, 1}, axisZ).w == 0,
                "nonfinite target plane normal is unresolved");
        require(surface(identity, zero, axisZ, {0, 0, 3}, axisZ, {invalid, 0, 1}).w == 0,
                "nonfinite shading normal is unresolved");
    }
    std::printf("Primary PSR: %u checks passed\n", checks);
}
