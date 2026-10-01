// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/sample.slang";

import "bsdf/full/contract/volume_sample.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/refract.slang";
import "bsdf/full/dielectric/state.slang";
import "bsdf/full/dielectric/volume.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/volume.slang";
import "bsdf/full/contract/sample.slang";

public PrimeFullOpenPbrSampleResult
primeFullCompactTransmissionSample(float3 wi, float3 randomValue,
                                   PrimeFullCompactTransmissionState state, uint samplingFlags,
                                   PrimeFullOpenPbrVolume medium) {
    PrimeFullOpenPbrSampleResult result;
    result.medium = medium;
    result.rayT = state.rayT;
    result.bsdfSample = primeFullCompactTransmissionSampleRaw(
            wi, randomValue, primeFullCompactTransmissionClosureState(state), samplingFlags);
    primeFullCompactApplyTransmissionVolumeTransition(state, result);
    return result;
}
