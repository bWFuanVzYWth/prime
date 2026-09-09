// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/foliage/lobes.slang";

import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";

public PrimeCompactMixState primeCompactFoliageMakeMixState(
        float weight, float3 firstEnergy, float3 secondEnergy) {
    PrimeCompactMixState result;
    if (weight == 0.0) {
        result.firstSampleWeight = 1.0;
        result.secondSampleWeight = 0.0;
        return result;
    }
    if (weight == 1.0) {
        result.firstSampleWeight = 0.0;
        result.secondSampleWeight = 1.0;
        return result;
    }
    result.firstSampleWeight = (1.0 - weight) * primeOpenPbrSpectrumToWeight(firstEnergy);
    result.secondSampleWeight = weight * primeOpenPbrSpectrumToWeight(secondEnergy);
    float sum = result.firstSampleWeight + result.secondSampleWeight;
    if (sum > 0.0) {
        result.firstSampleWeight /= sum;
        result.secondSampleWeight /= sum;
    }
    return result;
}

public PrimeCompactTransmissionState primeCompactFoliageTransmissionState(
        PrimeCompactFoliageState state) {
    PrimeCompactTransmissionState result;
    result.onb = state.base.onb;
    result.geometryThinWalled = 1u;
    result.entering = 1u;
    result.rayT = state.base.rayT;
    result.originalIor = state.base.dielectricFresnel.ior;
    result.transmissionColor = state.transmissionTint;
    result.specularMicrofacet = state.base.microfacet;
    result.specularFresnel = state.base.dielectricFresnel;
    result.transmissionMicrofacet = state.transmissionMicrofacet;
    result.transmissionTint = state.transmissionTint;
    result.transmissionVolume.extinction = float3(0.0);
    result.transmissionVolume.albedo = float3(0.0);
    result.transmissionVolume.anisotropy = 0.0;
    result.transmissionVolume.ior = 1.0;
    result.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    return result;
}

public PrimeOpenPbrThroughput primeCompactFoliageMixEval(
        PrimeOpenPbrThroughput first,
        PrimeOpenPbrThroughput second,
        float weight) {
    PrimeOpenPbrThroughput result = primeOpenPbrZeroThroughput();
    if (weight < 1.0) { result = first; }
    if (weight > 0.0) {
        result = primeOpenPbrThroughputScale(result, 1.0 - weight);
        result = primeOpenPbrThroughputAdd(
                result, primeOpenPbrThroughputScale(second, weight));
    }
    return result;
}
