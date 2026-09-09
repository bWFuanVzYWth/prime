// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/thin_wall.slang";

import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/fresnel.slang";
import "bsdf/compact/math/spectrum.slang";

public float3 primeCompactGuardedThinWallSeriesReciprocal(float3 roundTrip) {
    return float3(1.0) / max(
            float3(PRIME_OPENPBR_DENOM_TOLERANCE),
            float3(1.0) - primeOpenPbrSquare(roundTrip));
}

public PrimeOpenPbrVecPair primeCompactTransmissionThinWallRt(
        float cosineTheta,
        PrimeCompactTransmissionState state) {
    PrimeOpenPbrVecPair fresnel = primeOpenPbrSpecularFresnelRt(
            state.specularFresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            cosineTheta);
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta)
            / primeOpenPbrSquare(state.specularFresnel.ior);
    if (t0 <= 0.0) { return fresnel; }
    float cosineThetaI = sqrt(t0);
    float3 absorption = state.transmissionTint
            * exp(-state.transmissionVolume.extinction / cosineThetaI);
    float3 inverseSeries = primeCompactGuardedThinWallSeriesReciprocal(
            fresnel.first * absorption);
    PrimeOpenPbrVecPair result;
    result.first = fresnel.first
            * (float3(1.0) + primeOpenPbrSquare(fresnel.second * absorption) * inverseSeries);
    result.second = primeOpenPbrSquare(fresnel.second) * absorption * inverseSeries;
    return result;
}
