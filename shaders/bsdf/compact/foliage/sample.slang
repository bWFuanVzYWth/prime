// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/foliage/sample.slang";

import "bsdf/compact/dielectric/refract.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/foliage/lobes.slang";
import "bsdf/compact/opaque/sample.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";

public PrimeOpenPbrSample primeCompactFoliageSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactFoliageState state) {
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        return primeCompactOpaqueSample(wi, randomValue, state.base);
    }
    if (state.dielectricMix.firstSampleWeight
            + state.dielectricMix.secondSampleWeight <= 0.0) {
        return primeOpenPbrZeroSample();
    }
    if (randomValue.z <= state.dielectricMix.secondSampleWeight
            && state.dielectricMix.secondSampleWeight > 0.0) {
        PrimeOpenPbrSample result = primeCompactTransmissionSampleRaw(
                wi,
                float3(
                        randomValue.xy,
                        randomValue.z / state.dielectricMix.secondSampleWeight),
                primeCompactFoliageTransmissionState(state),
                PRIME_OPENPBR_FLAG_ALL);
        result.throughput = primeOpenPbrThroughputScale(
                result.throughput, state.transmissionWeight);
        result.pdf *= state.dielectricMix.secondSampleWeight;
        return result;
    }
    PrimeOpenPbrSample result = primeCompactOpaqueSampleWithEonDiffuse(
            wi,
            float3(
                    randomValue.xy,
                    (randomValue.z - state.dielectricMix.secondSampleWeight)
                    / state.dielectricMix.firstSampleWeight),
            state.base);
    result.throughput = primeOpenPbrThroughputScale(
            result.throughput, 1.0 - state.transmissionWeight);
    result.pdf *= state.dielectricMix.firstSampleWeight;
    return result;
}
