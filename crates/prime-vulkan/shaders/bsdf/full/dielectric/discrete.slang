// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/discrete.slang";

import "bsdf/full/contract/volume_sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/volume.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/thin_wall.slang";
import "bsdf/full/dielectric/volume.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/spectrum.slang";

public PrimeFullOpenPbrSample primeFullCompactRefractiveDiscreteSample(
        float3 wi, float randomEvent, PrimeFullCompactTransmissionState state, uint samplingFlags) {
    float microIncident = wi.z;
    PrimeFullOpenPbrVecPair rt =
            primeFullOpenPbrSpecularFresnelRt(state.specularFresnel, microIncident);
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
    PrimeFullOpenPbrRefractResult refracted = primeFullOpenPbrSpecularFresnelRefract(
            state.specularFresnel, -wi, float3(0.0, 0.0, 1.0));
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

public PrimeFullOpenPbrSample primeFullCompactThinWallDiscreteSampleRaw(
        float3 wi, float randomEvent, PrimeFullCompactTransmissionState state, uint samplingFlags) {
    PrimeFullOpenPbrSample result = primeFullOpenPbrZeroSample();
    if (wi.z <= 0.0 || !primeFullOpenPbrIsDelta(samplingFlags)) {
        return result;
    }
    PrimeFullOpenPbrVecPair rt = primeFullCompactTransmissionThinWallRt(wi.z, state);
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

public PrimeFullOpenPbrSampleResult
primeFullCompactTransmissionDiscreteSample(float3 wi, float randomEvent,
                                           PrimeFullCompactTransmissionState state,
                                           uint samplingFlags, PrimeFullOpenPbrVolume medium) {
    PrimeFullCompactTransmissionState closureState = state;
    if (closureState.geometryThinWalled == 0u) {
        closureState.entering = 1u;
    }
    PrimeFullOpenPbrSampleResult result;
    result.medium = medium;
    result.rayT = state.rayT;
    if (closureState.geometryThinWalled != 0u) {
        result.bsdfSample = primeFullCompactThinWallDiscreteSampleRaw(wi, randomEvent, closureState,
                                                                      samplingFlags);
    } else {
        result.bsdfSample = primeFullCompactRefractiveDiscreteSample(
                primeFullCompactTransmissionMaterialFrame(wi, closureState), randomEvent,
                closureState, samplingFlags);
        if (result.bsdfSample.throughput.flags != PRIME_FULL_OPENPBR_FLAG_NONE) {
            result.bsdfSample.wo =
                    primeFullCompactTransmissionMaterialFrame(result.bsdfSample.wo, closureState);
            if (primeFullOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
                result.bsdfSample.throughput.value *= closureState.transmissionTint;
            }
        }
    }
    primeFullCompactApplyTransmissionVolumeTransition(state, result);
    return result;
}
