// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/evaluate.slang";

import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/refract.slang";
import "bsdf/compact/dielectric/state.slang";

public PrimeCompactTransmissionEvaluation primeCompactTransmissionEvaluate(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeCompactTransmissionState closure = primeCompactTransmissionClosureState(state);
    PrimeCompactTransmissionEvaluation result;
    result.throughput = primeCompactTransmissionEvalRaw(
            wi, wo, closure, samplingFlags);
    result.pdf = primeCompactTransmissionPdfRaw(
            wi, wo, closure, samplingFlags);
    return result;
}
