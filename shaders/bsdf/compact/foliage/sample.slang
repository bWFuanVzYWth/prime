// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/foliage/sample.slang";

import "bsdf/compact/dielectric/sample.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/foliage/lobes.slang";
import "bsdf/compact/opaque/sample.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";

public PrimeOpenPbrSampleResult primeCompactFoliageSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactFoliageState state,
        PrimeOpenPbrVolume medium) {
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        return primeCompactOpaqueSample(wi, randomValue, state.base, medium);
    }
    if (state.dielectricMix.firstSampleWeight
            + state.dielectricMix.secondSampleWeight <= 0.0) {
        PrimeOpenPbrSampleResult result;
        result.bsdfSample = primeOpenPbrZeroSample();
        result.medium = medium;
        result.rayT = state.base.rayT;
        return result;
    }
    if (randomValue.z <= state.dielectricMix.secondSampleWeight
            && state.dielectricMix.secondSampleWeight > 0.0) {
        PrimeOpenPbrSampleResult result = primeCompactTransmissionSample(
                wi,
                float3(
                        randomValue.xy,
                        randomValue.z / state.dielectricMix.secondSampleWeight),
                primeCompactFoliageTransmissionState(state),
                PRIME_OPENPBR_FLAG_ALL,
                medium);
        result.bsdfSample.throughput = primeOpenPbrThroughputScale(
                result.bsdfSample.throughput, state.transmissionWeight);
        result.bsdfSample.pdf *= state.dielectricMix.secondSampleWeight;
        return result;
    }
    PrimeOpenPbrSampleResult result = primeCompactOpaqueSampleWithEonDiffuse(
            wi,
            float3(
                    randomValue.xy,
                    (randomValue.z - state.dielectricMix.secondSampleWeight)
                    / state.dielectricMix.firstSampleWeight),
            state.base,
            medium);
    result.bsdfSample.throughput = primeOpenPbrThroughputScale(
            result.bsdfSample.throughput, 1.0 - state.transmissionWeight);
    result.bsdfSample.pdf *= state.dielectricMix.firstSampleWeight;
    return result;
}
