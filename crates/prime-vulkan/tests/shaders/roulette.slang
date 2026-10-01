#language slang 2026
module roulette_test;
import "math/transport.slang";
import "math/z_sobol.slang";
import "../../shaders/pbr.slang";
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

public PrimePbrSample rrCpuBsdf(float cosine, float roughness, bool thin, float4 incident,
                                float4 transmitted, float3 randomValue) {
    float3 view = float3(sqrt(max(0.0, 1.0 - cosine * cosine)), 0, cosine);
    PrimePbrVertex vertex =
            primePbrVertex(float3(1), float3(0, 0, 1), 0, float3(1.0 - roughness, 4.0 / 255.0, 0),
                           false, true, thin, true, view);
    // This CPU fixture validates the shared roulette/eta seam through the retained Lite backend.
    // Full OpenPBR uses its independent, actual-LUT old-source equivalence oracle.
    return primeSampleLitePbrVertex(vertex, view, randomValue, incident, transmitted);
}

public BsdfSample rrCpuSanitize(float relativeEta, float pdf, float response) {
    BsdfSample sampleValue = {float3(0, 0, 1), float3(response), pdf, relativeEta,
                              PRIME_BSDF_EVENT_REFLECTION};
    return primeSanitizeBsdfSample(sampleValue);
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

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputs[2 * id.x], b = inputs[2 * id.x + 1];
    outputs[4 * id.x] = rrCpuMath(a);
    outputs[4 * id.x + 1] = rrCpuEta(a.w, b.x, b.y != 0);
    PrimePbrSample sampled =
            rrCpuBsdf(b.z, b.w, b.y != 0, float4(1, 0, 0, 0), float4(1.5, 0.01, 0.02, 0.03), a.xyz);
    outputs[4 * id.x + 2] = float4(sampled.bsdfSample.relativeEta, sampled.bsdfSample.eventFlags,
                                   sampled.medium.x, rrCpuBudget(id.x, 64));
    BsdfSample cleaned = rrCpuSanitize(a.w, b.x, b.y);
    outputs[4 * id.x + 3] = float4(rrCpuSobol(id.x & 255u, 0, 8), cleaned.pdf, cleaned.relativeEta,
                                   float(cleaned.eventFlags) + rrCpuNee(id.x, 64, 0.25, 0.5));
}
