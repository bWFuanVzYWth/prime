// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "trace/visibility.slang";
import "math/color.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/delta.slang";
import "bsdf/full/math/spectrum.slang";

// The source emitter is linear709; extinction and visibility are working Rec.2020.
public float3 primeVisibleEmitterRadiance(float3 emitter709, float3 visibilityWorking) {
    return linear709ToWorking(emitter709) * visibilityWorking;
}

// A physical thin sheet keeps negative=material and positive=authored outside.
// Straight NEE omits bent caustics, but uses the same two-interface RGB series as Full.
public float3 primeThinVisibility(float cosine, float4 outside, float4 inside) {
    float ior = inside.x / outside.x;
    // Match Full's equal-media limit; subtracting two values near one erases grazing c^2.
    float internalCosineSquared =
            ior == 1.0 ? cosine * cosine : 1.0 - (1.0 - cosine * cosine) / (ior * ior);
    if (internalCosineSquared <= 0.0)
        return float3(0.0);
    PrimeFullOpenPbrSpecularFresnel fresnel;
    fresnel.ior = ior;
    fresnel.energyIor = primeFullOpenPbrIorAdjustment(ior, 1.0);
    fresnel.color = float3(1.0);
    return primeFullThinWallRt(cosine, fresnel, float3(1.0), inside.yzw * 0.0625).second;
}

// Candidate traversal is unordered. Only the farthest thick boundary defines the end medium.
public void primeVisibilityBoundaryMoment(float distance, float4 from, float4 to,
                                          inout float3 moment, inout float last,
                                          inout float3 terminalSigma, inout float terminalIor) {
    moment += distance * (from.yzw - to.yzw);
    if (distance > last) {
        last = distance;
        terminalSigma = to.yzw;
        terminalIor = to.x;
    }
}
public void primeVisibilityBoundaryMoment(float distance, float4 from, float4 to,
                                          inout float3 moment, inout float last,
                                          inout float4 terminalMedium) {
    float3 sigma = terminalMedium.yzw;
    float ior = terminalMedium.x;
    primeVisibilityBoundaryMoment(distance, from, to, moment, last, sigma, ior);
    terminalMedium = float4(ior, sigma);
}
