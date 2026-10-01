// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/thin_wall.slang";

import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/spectrum.slang";

public float3 primeFullCompactGuardedThinWallSeriesReciprocal(float3 roundTrip) {
    return float3(1.0) / max(float3(PRIME_FULL_OPENPBR_DENOM_TOLERANCE),
                             float3(1.0) - primeFullOpenPbrSquare(roundTrip));
}

public PrimeFullOpenPbrVecPair
primeFullCompactTransmissionThinWallRt(float cosineTheta, PrimeFullCompactTransmissionState state) {
    PrimeFullOpenPbrVecPair fresnel =
            primeFullOpenPbrSpecularFresnelRt(state.specularFresnel, cosineTheta);
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) /
                             primeFullOpenPbrSquare(state.specularFresnel.ior);
    if (t0 <= 0.0) {
        return fresnel;
    }
    float cosineThetaI = sqrt(t0);
    float3 absorption =
            state.transmissionTint * exp(-state.transmissionVolume.extinction / cosineThetaI);
    float3 inverseSeries =
            primeFullCompactGuardedThinWallSeriesReciprocal(fresnel.first * absorption);
    PrimeFullOpenPbrVecPair result;
    result.second = primeFullOpenPbrSquare(fresnel.second) * absorption * inverseSeries;
    result.first = fresnel.first * (float3(1.0) + result.second * absorption);
    return result;
}
