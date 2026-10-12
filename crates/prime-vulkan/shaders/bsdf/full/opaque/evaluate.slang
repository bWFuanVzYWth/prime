// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/opaque/evaluate.slang";

import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/contract/flags.slang";

public PrimeFullCompactOpaqueEvaluation
primeFullCompactOpaqueEvaluate(float3 wi, float3 wo, PrimeFullCompactOpaqueState state) {
    PrimeFullCompactOpaqueEvaluation result;
    if (state.metalness > 0.0 && state.metalness < 1.0) {
        result.throughput = primeFullCompactMixEval(
                primeFullCompactOpaqueLayerEval(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL),
                primeFullCompactConductorEval(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL),
                state.metalness);
        result.pdf = lerp(primeFullCompactOpaqueLayerPdf(wi, wo, state),
                          primeFullCompactConductorPdf(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL),
                          state.metalness);
        return result;
    }
    result.throughput =
            state.kind == PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR
                    ? primeFullCompactConductorEval(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL)
                    : primeFullCompactOpaqueLayerEval(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL);
    result.pdf = state.kind == PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR
                         ? primeFullCompactConductorPdf(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL)
                         : primeFullCompactOpaqueLayerPdf(wi, wo, state);
    return result;
}

public PrimeFullCompactOpaqueComponents
primeFullCompactOpaqueEvaluateComponents(float3 wi, float3 wo, PrimeFullCompactOpaqueState state) {
    PrimeFullCompactOpaqueComponents result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (state.metalness > 0.0 && state.metalness < 1.0) {
        result.diffuse =
                (1.0 - state.metalness) *
                primeFullCompactOpaqueLayerEval(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_DIFFUSE)
                        .value;
        result.specular = lerp(
                primeFullCompactOpaqueLayerEval(wi, wo, state,
                                                PRIME_FULL_OPENPBR_FLAG_SPECULAR |
                                                        PRIME_FULL_OPENPBR_FLAG_DELTA)
                        .value,
                primeFullCompactConductorEval(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL).value,
                state.metalness);
        result.pdf = primeFullCompactOpaqueEvaluate(wi, wo, state).pdf;
        return result;
    }
    if (state.kind == PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR) {
        result.specular = primeFullCompactConductorEval(wi, wo, state,
                                                        PRIME_FULL_OPENPBR_FLAG_SPECULAR |
                                                                PRIME_FULL_OPENPBR_FLAG_DELTA)
                                  .value;
        result.pdf = primeFullCompactConductorPdf(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL);
        return result;
    }
    result.diffuse =
            primeFullCompactOpaqueLayerEval(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_DIFFUSE).value;
    result.specular =
            primeFullCompactOpaqueLayerEval(
                    wi, wo, state, PRIME_FULL_OPENPBR_FLAG_SPECULAR | PRIME_FULL_OPENPBR_FLAG_DELTA)
                    .value;
    result.pdf = primeFullCompactOpaqueLayerPdf(wi, wo, state);
    return result;
}
