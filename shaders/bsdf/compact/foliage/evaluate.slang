// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/foliage/evaluate.slang";

import "bsdf/compact/dielectric/evaluate.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/foliage/lobes.slang";
import "bsdf/compact/opaque/evaluate.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/contract/flags.slang";

public PrimeCompactFoliageEvaluation primeCompactFoliageEvaluate(
        float3 wi, float3 wo, PrimeCompactFoliageState state) {
    PrimeCompactOpaqueEvaluation base = primeCompactOpaqueEvaluate(wi, wo, state.base);
    PrimeCompactFoliageEvaluation result;
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.throughput = base.throughput;
        result.pdf = base.pdf;
        return result;
    }
    PrimeCompactTransmissionEvaluation transmission = primeCompactTransmissionEvaluate(
            wi,
            wo,
            primeCompactFoliageTransmissionState(state),
            PRIME_OPENPBR_FLAG_ALL);
    result.throughput = primeCompactFoliageMixEval(
            base.throughput, transmission.throughput, state.transmissionWeight);
    if (state.dielectricMix.firstSampleWeight
            + state.dielectricMix.secondSampleWeight <= 0.0) {
        result.pdf = 0.0;
    } else {
        result.pdf = lerp(
                base.pdf,
                transmission.pdf,
                state.dielectricMix.secondSampleWeight);
    }
    return result;
}

public PrimeCompactFoliageComponents primeCompactFoliageEvaluateComponents(
        float3 wi, float3 wo, PrimeCompactFoliageState state) {
    PrimeCompactOpaqueComponents base = primeCompactOpaqueEvaluateComponents(
            wi, wo, state.base);
    PrimeCompactFoliageComponents result;
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.diffuse = base.diffuse;
        result.specular = base.specular;
        result.pdf = base.pdf;
        return result;
    }
    PrimeCompactTransmissionEvaluation transmission = primeCompactTransmissionEvaluate(
            wi,
            wo,
            primeCompactFoliageTransmissionState(state),
            PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA);
    result.diffuse = base.diffuse * (1.0 - state.transmissionWeight);
    result.specular = base.specular * (1.0 - state.transmissionWeight)
            + transmission.throughput.value * state.transmissionWeight;
    if (state.dielectricMix.firstSampleWeight
            + state.dielectricMix.secondSampleWeight <= 0.0) {
        result.pdf = 0.0;
    } else {
        PrimeCompactTransmissionEvaluation fullTransmission =
                primeCompactTransmissionEvaluate(
                        wi,
                        wo,
                        primeCompactFoliageTransmissionState(state),
                        PRIME_OPENPBR_FLAG_ALL);
        result.pdf = lerp(
                base.pdf,
                fullTransmission.pdf,
                state.dielectricMix.secondSampleWeight);
    }
    return result;
}
