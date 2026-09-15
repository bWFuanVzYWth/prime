// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/foliage/sample.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/foliage/sample.slang";
import "bsdf/compact/foliage/contract.slang";
import "service/bsdf/foliage/state.slang";
import "service/bsdf/support/conversion.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/frame.slang";

public BsdfSample primeSampleMinecraftFoliageFromState(
        PrimeCompactFoliageState state,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolume medium) {
    BsdfSample result = primeInvalidBsdfSample();
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactFoliageSample(
            localView, sampleValue, state, medium);
    if (!primeOpenPbrHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.direction = primeOpenPbrOnbToWorld(
            state.base.onb, sampled.bsdfSample.wo);
    result.response = sampled.bsdfSample.throughput.value;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeOpenPbrToBsdfEventFlags(sampled.bsdfSample.throughput.flags);
    return primeSanitizeBsdfSample(result);
}

public BsdfSample primeSampleMinecraftFoliage(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        float rayT,
        PrimeOpenPbrVolume medium) {
    PrimeCompactFoliageState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            medium);
    return primeSampleMinecraftFoliageFromState(
            state, viewDirection, sampleValue, medium);
}
