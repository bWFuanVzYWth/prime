// Runs actual Slang-generated production math/BSDF code on the CPU; no Vulkan API.
#include "roulette.generated.cpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>

static unsigned checks = 0;
static void require(bool condition, const char *label) {
    ++checks;
    if (!condition) {
        std::fprintf(stderr, "FAIL %s check=%u\n", label, checks);
        std::exit(1);
    }
}
static bool close(double actual, double expected, double tolerance = 4e-6) {
    return std::isfinite(actual) &&
           std::abs(actual - expected) <= tolerance * std::max(std::abs(expected), 1e-35);
}

int main() {
    const float infinity = std::numeric_limits<float>::infinity();
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float cases[][4] = {
            {0, 0, 0, 1},          {0.04f, 0.08f, 0, 1},  {0.25f, 0.125f, 0, 0.5f},
            {0.01f, 0.02f, 0, 4},  {4, 2, 0, 1},          {0.001f, 0.4f, 0, 1},
            {1e-30f, 0, 0, 1},     {1e20f, 0, 0, 1e-20f}, {1e-30f, 0, 0, 1e20f},
            {1e20f, 0, 0, 1e-30f}, {1, 0, 0, 0},          {0, 1, 0, 1},
            {0, 0, 1, 1},
    };
    for (const auto &row : cases) {
        const auto result = rrCpuMath_0({row[0], row[1], row[2], row[3]});
        const double expected = std::min(
                1.0, std::max(0.0, std::max({double(row[0]), double(row[1]), double(row[2])}) *
                                           double(row[3])));
        require(close(result.x, expected), "maxRGB eta survival");
        const float weights[] = {result.y, result.z, result.w};
        for (unsigned channel = 0; channel < 3; ++channel) {
            require(std::isfinite(weights[channel]), "finite weight");
            if (expected > 0)
                require(close(double(weights[channel]) * result.x, row[channel]),
                        "conditional unbiased expectation p * beta/p");
        }
    }
    require(rrCpuMath_0({0.01f, 0, 0, 1}).x < 0.05f, "no survival floor");
    require(rrCpuMath_0({4, 0, 0, 1}).x == 1, "no upper survival cap");
    const auto overflow = rrCpuEta_0(1e30f, 1e20f, true);
    const auto underflow = rrCpuEta_0(1e-30f, 1e-20f, true);
    require(std::isinf(overflow.x) && overflow.x > 0, "old eta overflow domain");
    require(underflow.x == 0, "old eta underflow domain");
    require(rrCpuMath_0({0.01f, 0, 0, overflow.x}).x == 1,
            "positive beta times eta overflow clamps to unit survival");
    require(rrCpuMath_0({0.01f, 0, 0, underflow.x}).x == 0,
            "eta underflow clamps to zero survival");
    // Zero beta is rejected in transport before calling the metric. Raw 0*Inf
    // remains NaN, retaining the old helper's result domain rather than adding a clamp.
    require(std::isnan(primeRussianRouletteMetric_0({0, 0, 0}, infinity)), "raw zero times Inf");
    require(!primeBsdfFinite_0({nan, 1, 1}) && !primeBsdfFinite_0({infinity, 1, 1}),
            "invalid throughput boundary");
    require(overflow.z == 0 && overflow.w == 1, "RR starts at second scatter");
    for (float eta : {0.5f, 1.0f, 1.33f, 1.5f, 2.0f}) {
        const auto enter = rrCpuEta_0(1, eta, true);
        const auto leave = rrCpuEta_0(enter.x, 1.0f / eta, true);
        require(close(enter.x, double(eta) * eta), "transmission eta square");
        require(close(leave.x, 1), "enter exit restoration");
        require(rrCpuEta_0(enter.x, eta, false).x == enter.x, "reflection eta unchanged");
        require(rrCpuEta_0(enter.x, 1, true).x == enter.x, "thin wall eta unchanged");
    }
    float scale = 1;
    const float media[] = {1, 1.5f, 1.33f, 2, 1.33f, 1.5f, 1};
    for (unsigned repeat = 0; repeat < 8; ++repeat)
        for (unsigned i = 1; i < sizeof(media) / sizeof(media[0]); ++i) {
            scale = rrCpuEta_0(scale, media[i] / media[i - 1], true).x;
            require(close(scale, double(media[i]) * media[i], 2e-5), "multiple medium crossings");
        }
    for (float invalid : {0.0f, -1.0f, infinity, nan}) {
        require(rrCpuSanitize_0(invalid, 1, 1).eventFlags_0 == 0, "invalid relative eta");
        require(rrCpuSanitize_0(1, invalid, 1).eventFlags_0 == 0, "invalid pdf");
    }
    for (float invalid : {-1.0f, infinity, nan})
        require(rrCpuSanitize_0(1, 1, invalid).eventFlags_0 == 0, "invalid response");
    require(rrCpuSanitize_0(1, 1, 0).eventFlags_0 != 0, "legal zero response is distinct");

    unsigned reflected = 0, transmittedCount = 0;
    for (bool thin : {false, true})
        for (float roughness : {0.0f, 0.1f, 0.35f})
            for (float cosine : {0.2f, 0.8f, 1.0f})
                for (bool exiting : {false, true})
                    for (unsigned i = 0; i < 256; ++i) {
                        const Vector<float, 4> incident{exiting ? 1.5f : 1.0f, 0.01f, 0.02f, 0.03f};
                        const Vector<float, 4> target{exiting ? 1.0f : 1.5f, 0.04f, 0.05f, 0.06f};
                        const Vector<float, 3> random{rrCpuSobol_0(i, 17, 7),
                                                      rrCpuSobol_0(i, 18, 7),
                                                      rrCpuSobol_0(i, 17, 1281)};
                        const auto sample =
                                rrCpuBsdf_0(cosine, roughness, thin, incident, target, random);
                        const auto bsdf = sample.bsdfSample_0;
                        if (bsdf.eventFlags_0 == 0)
                            continue; // finite geometric-support rejection is permitted.
                        const bool transmission = (bsdf.eventFlags_0 & 2u) != 0;
                        require(std::isfinite(bsdf.relativeEta_0) && bsdf.relativeEta_0 > 0,
                                "sample eta finite");
                        require(std::isfinite(bsdf.pdf_2) && bsdf.pdf_2 > 0, "sample pdf finite");
                        const auto eta = rrCpuEta_0(1, bsdf.relativeEta_0, transmission);
                        if (transmission) {
                            ++transmittedCount;
                            require(close(bsdf.relativeEta_0, thin ? 1.0 : target.x / incident.x),
                                    "actual BSDF relative eta direction");
                            require(sample.medium_0.x == (thin ? incident.x : target.x),
                                    "transmission medium");
                            if (!thin && roughness == 0) {
                                const double beta = bsdf.response_0.x / bsdf.pdf_2;
                                require(close(beta * eta.x, 1),
                                        "smooth transmission RR metric compensates eta");
                            }
                        } else {
                            ++reflected;
                            require(eta.x == 1 && sample.medium_0.x == incident.x,
                                    "reflection or TIR medium/eta");
                        }
                        if (!thin && roughness == 0 && exiting && cosine == 0.2f)
                            require(!transmission, "smooth TIR never changes eta/medium");
                    }
    require(reflected > 0 && transmittedCount > 0, "BSDF covers both event types");

    for (unsigned budget = 1; budget <= 64; ++budget) {
        unsigned continuations = 0;
        for (unsigned vertex = 0; vertex < budget; ++vertex) {
            const auto next = rrCpuBudget_0(vertex, budget);
            continuations += next;
            require(next == (vertex + 1 < budget), "budget terminal continuation");
            if (vertex + 1 == budget) {
                const float pdf = 0.25f, competingPdf = 0.5f;
                const float inversePdf = rrCpuNee_0(vertex, budget, pdf, competingPdf);
                require(inversePdf == 4, "terminal NEE no discarded competing BSDF technique");
            }
        }
        require(continuations == budget - 1, "N vertices allow N-1 continuations");
    }
    // A complete S=8 net makes grid-aligned survival probabilities exact here.
    // This is a finite-net property, not an arbitrary-prefix convergence claim.
    for (unsigned seed : {0u, 17u, 0x13572468u})
        for (unsigned numerator : {1u, 2u, 16u, 64u, 128u, 255u, 256u}) {
            const float beta = numerator / 256.0f;
            const auto weighted = rrCpuMath_0({beta, 0, 0, 1});
            double sum = 0;
            for (unsigned i = 0; i < 256; ++i)
                if (rrCpuSobol_0(i, seed, 8) < weighted.x)
                    sum += weighted.y;
            require(close(sum / 256, beta), "actual Sobol RR finite-net expectation");
        }
    std::printf("PASS checks=%u BSDF reflection=%u transmission=%u budget=1..64\n", checks,
                reflected, transmittedCount);
}
