// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/evaluate.slang";

import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/refract.slang";
import "bsdf/full/dielectric/state.slang";

public PrimeFullCompactTransmissionEvaluation
primeFullCompactTransmissionEvaluate(float3 wi, float3 wo, PrimeFullCompactTransmissionState state,
                                     uint samplingFlags) {
    PrimeFullCompactTransmissionState closure = primeFullCompactTransmissionClosureState(state);
    PrimeFullCompactTransmissionEvaluation result;
    result.throughput = primeFullCompactTransmissionEvalRaw(wi, wo, closure, samplingFlags);
    result.pdf = primeFullCompactTransmissionPdfRaw(wi, wo, closure, samplingFlags);
    return result;
}
