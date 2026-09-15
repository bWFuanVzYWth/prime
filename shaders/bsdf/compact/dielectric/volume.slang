// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/volume.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/dielectric/contract.slang";

public void primeCompactApplyTransmissionVolumeTransition(
        PrimeCompactTransmissionState state,
        inout PrimeOpenPbrSampleResult result) {
    if (state.geometryThinWalled != 0u
            || !primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
        return;
    }
    result.medium = state.transmissionVolume;
    if (state.entering == 0u) result.rayT = 0.0;
}
