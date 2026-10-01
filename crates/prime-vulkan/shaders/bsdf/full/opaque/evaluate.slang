// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/opaque/evaluate.slang";

import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/contract/flags.slang";

public PrimeFullCompactOpaqueEvaluation
primeFullCompactOpaqueEvaluate(float3 wi, float3 wo, PrimeFullCompactOpaqueState state) {
    PrimeFullCompactOpaqueEvaluation result;
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
