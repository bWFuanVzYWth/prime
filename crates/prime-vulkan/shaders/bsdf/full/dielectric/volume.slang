// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/volume.slang";

import "bsdf/full/contract/volume_sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/volume.slang";
import "bsdf/full/dielectric/contract.slang";

public void
primeFullCompactApplyTransmissionVolumeTransition(PrimeFullCompactTransmissionState state,
                                                  inout PrimeFullOpenPbrSampleResult result) {
    if (state.geometryThinWalled != 0u ||
        !primeFullOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
        return;
    }
    result.medium = state.transmissionVolume;
    if (state.entering == 0u)
        result.rayT = 0.0;
}
