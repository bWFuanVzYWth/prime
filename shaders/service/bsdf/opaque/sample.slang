// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/sample.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/opaque/sample.slang";
import "bsdf/compact/opaque/state.slang";
import "service/bsdf/opaque/state.slang";
import "service/bsdf/support/conversion.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/frame.slang";

public BsdfSample primeSampleOpaqueFromState(
        PrimeCompactOpaqueState state,
        float3 normal,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolume medium) {
    BsdfSample result = primeInvalidBsdfSample();
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactOpaqueSample(
            localView, sampleValue, state, medium);
    if (!primeOpenPbrHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    if ((sampled.bsdfSample.throughput.flags & PRIME_OPENPBR_FLAG_DELTA_REFLECTION) != 0u) {
        // Avoid a local-to-world round trip exactly where the reflected direction can be only a
        // few ulps above the tangent plane. This is algebraically the same discrete direction, but
        // preserves the world-space hemisphere at extreme grazing angles.
        result.direction = normalize(reflect(-viewDirection, normal));
    }
    result.response = sampled.bsdfSample.throughput.value;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeOpenPbrToBsdfEventFlags(sampled.bsdfSample.throughput.flags);
    return primeSanitizeBsdfSample(result);
}

public BsdfSample primeSampleOpaque(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float3 sampleValue,
        float rayT,
        PrimeOpenPbrVolume medium) {
    PrimeCompactOpaqueState state = primeOpaqueState(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            rayT,
            medium);
    return primeSampleOpaqueFromState(
            state, normal, viewDirection, sampleValue, medium);
}
