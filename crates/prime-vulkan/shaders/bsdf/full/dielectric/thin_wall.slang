// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/thin_wall.slang";

import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/spectrum.slang";

__exported import "bsdf/full/math/delta.slang";

public PrimeFullOpenPbrVecPair
primeFullCompactTransmissionThinWallRt(float cosineTheta, PrimeFullCompactTransmissionState state) {
    return primeFullThinWallRt(cosineTheta, state.specularFresnel, state.transmissionTint,
                               state.transmissionVolume.extinction);
}
