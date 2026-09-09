// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/sample.slang";

import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/refract.slang";
import "bsdf/compact/dielectric/state.slang";
import "bsdf/compact/dielectric/volume.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";

public PrimeOpenPbrSampleResult primeCompactTransmissionSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactTransmissionSampleRaw(
            wi,
            randomValue,
            primeCompactTransmissionClosureState(state),
            samplingFlags,
            stack);
    primeCompactApplyTransmissionVolumeTransition(state, result);
    return result;
}
