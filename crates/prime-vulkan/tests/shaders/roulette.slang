#language slang 2026
module roulette_test;
import "math/transport.slang";
import "math/z_sobol.slang";
import "pbr/delta.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";

StructuredBuffer<float4> inputs;
RWStructuredBuffer<float4> outputs;

public float4 rrCpuMath(float4 betaEta) {
    float survival = primeRussianRouletteSurvival(betaEta.xyz, betaEta.w);
    return float4(survival,
                  survival > 0.0 ? primeRussianRouletteReweight(betaEta.xyz, survival) : float3(0));
}

public float4 rrCpuEta(float etaScale, float relativeEta, bool transmission) {
    float scale = primeEtaScaleAfterScatter(etaScale, relativeEta, transmission);
    return float4(scale, primeRussianRouletteMetric(float3(0.125, 0.25, 0.5), scale),
                  float(primeRussianRouletteApplies(0, PRIME_RUSSIAN_ROULETTE_START)),
                  float(primeRussianRouletteApplies(1, PRIME_RUSSIAN_ROULETTE_START)));
}

public PrimePbrSample rrCpuDeltaBsdf(float cosine, bool thin, float4 incident, float4 transmitted,
                                     float3 randomValue) {
    float3 view = float3(sqrt(max(0.0, 1.0 - cosine * cosine)), 0, cosine);
    PrimePbrVertex vertex =
            primePbrVertex(float3(1), float3(0, 0, 1), 0, float3(1.0, 4.0 / 255.0, 0), false, true,
                           thin, true, view);
    // Roulette's exact eta/medium seam needs only Full delta optics, which has no energy LUT.
    // Continuous Full BSDF/roughness behavior is exercised by the actual-LUT GPU fixtures.
    return primeSamplePbrDelta(vertex, view, float3(0, 0, 1), randomValue.z, incident, transmitted);
}

// Stable scalar/vector reports avoid depending on compiler-generated internal field suffixes.
public float4 rrCpuDeltaMeasure(float cosine, bool thin, float4 incident, float4 transmitted,
                                float3 randomValue) {
    BsdfSample sample = rrCpuDeltaBsdf(cosine, thin, incident, transmitted, randomValue).bsdfSample;
    return float4(sample.relativeEta, sample.pdf, sample.response.x, float(sample.eventFlags));
}
public float4 rrCpuDeltaMedium(float cosine, bool thin, float4 incident, float4 transmitted,
                               float3 randomValue) {
    return rrCpuDeltaBsdf(cosine, thin, incident, transmitted, randomValue).medium;
}

public BsdfSample rrCpuSanitize(float relativeEta, float pdf, float response) {
    BsdfSample sampleValue = {float3(0, 0, 1), float3(response), pdf, relativeEta,
                              PRIME_BSDF_EVENT_REFLECTION};
    return primeSanitizeBsdfSample(sampleValue);
}
public uint rrCpuSanitizeFlags(float relativeEta, float pdf, float response) {
    return rrCpuSanitize(relativeEta, pdf, response).eventFlags;
}

public float rrCpuSobol(uint sampleIndex, uint seed, uint domain) {
    return makeZSobolS8(uint2(0), sampleIndex, 0, seed).sample1D(domain);
}

public uint rrCpuBudget(uint vertex, uint budget) {
    return uint(primePathHasContinuation(vertex, budget));
}

public float rrCpuNee(uint vertex, uint budget, float pdf, float competingPdf) {
    return primePathHasContinuation(vertex, budget)
                   ? primePowerHeuristicOverPdfValue(pdf, competingPdf)
                   : 1.0 / pdf;
}

public float3 rrCpuZeroNee(float3 accumulated, float3 source, float pdf, float competingPdf,
                           float3 response, float3 throughput, bool shortcut, bool continuation) {
    BsdfEvaluation evaluated = primeSanitizeBsdfEvaluation({response, competingPdf});
    if (pdf > 0 && !(shortcut && primeCanSkipZeroNee(source, pdf, throughput, continuation))) {
        float inverse =
                continuation ? primePowerHeuristicOverPdfValue(pdf, evaluated.pdf) : 1.0 / pdf;
        accumulated += throughput * primeTripleProduct(source, evaluated.response, inverse);
    }
    // Match the production per-channel radiance cleanup after transport: a NaN
    // zero term must not silently retain an earlier finite accumulated value.
    return float3(primeBsdfFinite(accumulated.x) ? max(accumulated.x, 0.0) : 0.0,
                  primeBsdfFinite(accumulated.y) ? max(accumulated.y, 0.0) : 0.0,
                  primeBsdfFinite(accumulated.z) ? max(accumulated.z, 0.0) : 0.0);
}

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputs[2 * id.x], b = inputs[2 * id.x + 1];
    outputs[4 * id.x] = rrCpuMath(a);
    outputs[4 * id.x + 1] = rrCpuEta(a.w, b.x, b.y != 0);
    float4 measure = rrCpuDeltaMeasure(b.z, b.y != 0, float4(1, 0, 0, 0),
                                       float4(1.5, 0.01, 0.02, 0.03), a.xyz);
    float4 medium = rrCpuDeltaMedium(b.z, b.y != 0, float4(1, 0, 0, 0),
                                     float4(1.5, 0.01, 0.02, 0.03), a.xyz);
    outputs[4 * id.x + 2] = float4(measure.x, measure.w, medium.x, rrCpuBudget(id.x, 64));
    BsdfSample cleaned = rrCpuSanitize(a.w, b.x, b.y);
    outputs[4 * id.x + 3] = float4(
            rrCpuSobol(id.x & 255u, 0, 8), cleaned.pdf, cleaned.relativeEta,
            float(rrCpuSanitizeFlags(a.w, b.x, b.y)) + rrCpuNee(id.x, 64, 0.25, 0.5) +
                    rrCpuZeroNee(a.xyz, b.xyz, a.w, b.x, a.xyz, b.xyz, b.y != 0, b.z != 0).x);
}
