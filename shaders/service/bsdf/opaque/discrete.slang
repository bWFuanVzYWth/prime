// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/discrete.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/frame.slang";
import "bsdf/compact/opaque/discrete.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/opaque/material.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/sanitization.slang";

public PrimeCompactOpaqueDiscreteState primeOpaqueDiscreteState(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float rayT) {
    return primeCompactOpaqueDiscreteStateInit(
            primeOpaqueMaterial(baseColor, normal, roughness, opticalControl, flags),
            rayT);
}

public BsdfSample primeSampleOpaqueDiscreteFromState(
        PrimeCompactOpaqueDiscreteState state,
        float3 normal,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    BsdfSample result = primeInvalidBsdfSample();
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactConductorDiscreteSample(
            localView, state, PRIME_OPENPBR_FLAG_ALL, volumeStack);
    if (!primeOpenPbrHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.direction = normalize(reflect(-viewDirection, normal));
    result.response = sampled.bsdfSample.throughput.value;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeOpenPbrToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    return primeSanitizeBsdfSample(result);
}

public PrimeDenoiseAlbedos primeOpaqueDiscreteAlbedosFromState(
        PrimeCompactOpaqueDiscreteState state,
        float3 viewDirection) {
    PrimeDenoiseAlbedos result;
    result.diffuse = float3(0.0);
    result.specular = primeSanitizeDenoiseAlbedo(
            primeCompactConductorDiscreteEnergy(
                    primeOpenPbrOnbToLocal(state.onb, viewDirection),
                    state));
    return result;
}
