// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/opaque/evaluate.slang";

import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/contract/flags.slang";

public PrimeCompactOpaqueEvaluation primeCompactOpaqueEvaluate(
        float3 wi, float3 wo, PrimeCompactOpaqueState state) {
    PrimeCompactOpaqueEvaluation result;
    result.throughput = state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorEval(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
            : primeCompactOpaqueLayerEval(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
    result.pdf = state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorPdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
            : primeCompactOpaqueLayerPdf(wi, wo, state);
    return result;
}

public PrimeCompactOpaqueComponents primeCompactOpaqueEvaluateComponents(
        float3 wi, float3 wo, PrimeCompactOpaqueState state) {
    PrimeCompactOpaqueComponents result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.specular = primeCompactConductorEval(
                wi, wo, state, PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA).value;
        result.pdf = primeCompactConductorPdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
        return result;
    }
    result.diffuse = primeCompactOpaqueLayerEval(
            wi, wo, state, PRIME_OPENPBR_FLAG_DIFFUSE).value;
    result.specular = primeCompactOpaqueLayerEval(
            wi, wo, state, PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA).value;
    result.pdf = primeCompactOpaqueLayerPdf(wi, wo, state);
    return result;
}
