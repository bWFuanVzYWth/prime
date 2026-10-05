// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/math/delta.slang";

import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/spectrum.slang";

public float3 primeFullCompactGuardedThinWallSeriesReciprocal(float3 roundTrip) {
    return float3(1.0) / max(float3(PRIME_FULL_OPENPBR_DENOM_TOLERANCE),
                             float3(1.0) - primeFullOpenPbrSquare(roundTrip));
}

public PrimeFullOpenPbrVecPair primeFullThinWallRt(float cosineTheta,
                                                   PrimeFullOpenPbrSpecularFresnel specularFresnel,
                                                   float3 transmissionTint, float3 extinction) {
    PrimeFullOpenPbrVecPair fresnel =
            primeFullOpenPbrSpecularFresnelRt(specularFresnel, cosineTheta);
    float t0 = specularFresnel.ior == 1.0
                       ? cosineTheta * cosineTheta
                       : 1.0 - (1.0 - cosineTheta * cosineTheta) /
                                         primeFullOpenPbrSquare(specularFresnel.ior);
    if (t0 <= 0.0) {
        return fresnel;
    }
    float cosineThetaI = sqrt(t0);
    float3 absorption = transmissionTint * exp(-extinction / cosineThetaI);
    float3 inverseSeries =
            primeFullCompactGuardedThinWallSeriesReciprocal(fresnel.first * absorption);
    PrimeFullOpenPbrVecPair result;
    result.second = primeFullOpenPbrSquare(fresnel.second) * absorption * inverseSeries;
    result.first = fresnel.first * (float3(1.0) + result.second * absorption);
    return result;
}

public PrimeFullOpenPbrSample
primeFullRefractiveDeltaSample(float3 wi, float randomEvent,
                               PrimeFullOpenPbrSpecularFresnel specularFresnel,
                               uint samplingFlags) {
    float microIncident = wi.z;
    PrimeFullOpenPbrVecPair rt = primeFullOpenPbrSpecularFresnelRt(specularFresnel, microIncident);
    float3 reflection = rt.first;
    float3 transmission = rt.second;
    float reflectionProbability = primeFullOpenPbrSpectrumToWeight(reflection);
    float transmissionProbability = primeFullOpenPbrSpectrumToWeight(transmission);
    if (!primeFullOpenPbrIsReflective(samplingFlags)) {
        reflectionProbability = 0.0;
    }
    if (!primeFullOpenPbrIsTransmissive(samplingFlags)) {
        transmissionProbability = 0.0;
    }
    PrimeFullOpenPbrRefractResult refracted =
            primeFullOpenPbrSpecularFresnelRefract(specularFresnel, -wi, float3(0.0, 0.0, 1.0));
    if (refracted.valid == 0u) {
        transmissionProbability = 0.0;
        reflection = float3(1.0);
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return primeFullOpenPbrZeroSample();
    }
    PrimeFullOpenPbrSample result = primeFullOpenPbrZeroSample();
    if (randomEvent < transmissionProbability / probabilitySum) {
        float3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) {
            return primeFullOpenPbrZeroSample();
        }
        result.wo = wo;
        result.eta = refracted.relativeIor;
        result.throughput.value = transmission / primeFullOpenPbrSquare(refracted.relativeIor);
        result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionProbability / probabilitySum;
    } else {
        float3 wo = reflect(-wi, float3(0.0, 0.0, 1.0));
        if (wi.z * wo.z < 0.0) {
            return primeFullOpenPbrZeroSample();
        }
        result.wo = wo;
        result.throughput.value = reflection;
        result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION;
        result.pdf = reflectionProbability / probabilitySum;
    }
    return result;
}

public PrimeFullOpenPbrSample
primeFullThinWallDeltaSample(float3 wi, float randomEvent,
                             PrimeFullOpenPbrSpecularFresnel specularFresnel,
                             float3 transmissionTint, float3 extinction, uint samplingFlags) {
    PrimeFullOpenPbrSample result = primeFullOpenPbrZeroSample();
    if (wi.z <= 0.0 || !primeFullOpenPbrIsDelta(samplingFlags)) {
        return result;
    }
    PrimeFullOpenPbrVecPair rt =
            primeFullThinWallRt(wi.z, specularFresnel, transmissionTint, extinction);
    float reflectionProbability = primeFullOpenPbrSpectrumToWeight(rt.first);
    float transmissionProbability = primeFullOpenPbrSpectrumToWeight(rt.second);
    if (!primeFullOpenPbrIsReflective(samplingFlags)) {
        reflectionProbability = 0.0;
    }
    if (!primeFullOpenPbrIsTransmissive(samplingFlags)) {
        transmissionProbability = 0.0;
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return result;
    }
    result.wo = reflect(-wi, float3(0.0, 0.0, 1.0));
    result.pdf = 1.0;
    if (randomEvent < transmissionProbability / probabilitySum) {
        result.throughput.value = rt.second;
        result.pdf *= transmissionProbability / probabilitySum;
        result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.wo.z = -result.wo.z;
    } else {
        result.throughput.value = rt.first;
        result.pdf *= reflectionProbability / probabilitySum;
        result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION;
    }
    return result;
}
