// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/discrete.slang";

import "bsdf/compact/contract/volume_sample.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/thin_wall.slang";
import "bsdf/compact/dielectric/volume.slang";
import "bsdf/compact/math/fresnel.slang";
import "bsdf/compact/math/spectrum.slang";

public PrimeOpenPbrSample primeCompactRefractiveDiscreteSample(
        float3 wi,
        float randomEvent,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    float microIncident = wi.z;
    PrimeOpenPbrVecPair rt = primeOpenPbrSpecularFresnelRt(
            state.specularFresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float3 reflection = rt.first;
    float3 transmission = rt.second;
    float reflectionProbability = primeOpenPbrSpectrumToWeight(reflection);
    float transmissionProbability = primeOpenPbrSpectrumToWeight(transmission);
    if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
    PrimeOpenPbrRefractResult refracted = primeOpenPbrSpecularFresnelRefract(
            state.specularFresnel, -wi, float3(0.0, 0.0, 1.0));
    if (refracted.valid == 0u) {
        transmissionProbability = 0.0;
        reflection = float3(1.0);
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return primeOpenPbrZeroSample();
    }
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (randomEvent < transmissionProbability / probabilitySum) {
        float3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) { return primeOpenPbrZeroSample(); }
        result.wo = wo;
        result.eta = refracted.relativeIor;
        result.throughput.value = transmission
                / primeOpenPbrSquare(refracted.relativeIor);
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionProbability / probabilitySum;
    } else {
        float3 wo = reflect(-wi, float3(0.0, 0.0, 1.0));
        if (wi.z * wo.z < 0.0) { return primeOpenPbrZeroSample(); }
        result.wo = wo;
        result.throughput.value = reflection;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
        result.pdf = reflectionProbability / probabilitySum;
    }
    return result;
}

public PrimeOpenPbrSample primeCompactThinWallDiscreteSampleRaw(
        float3 wi,
        float randomEvent,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (wi.z <= 0.0 || !primeOpenPbrIsDelta(samplingFlags)) {
        return result;
    }
    PrimeOpenPbrVecPair rt = primeCompactTransmissionThinWallRt(wi.z, state);
    float reflectionProbability = primeOpenPbrSpectrumToWeight(rt.first);
    float transmissionProbability = primeOpenPbrSpectrumToWeight(rt.second);
    if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return result;
    }
    result.wo = reflect(-wi, float3(0.0, 0.0, 1.0));
    result.pdf = 1.0;
    if (randomEvent < transmissionProbability / probabilitySum) {
        result.throughput.value = rt.second;
        result.pdf *= transmissionProbability / probabilitySum;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.wo.z = -result.wo.z;
    } else {
        result.throughput.value = rt.first;
        result.pdf *= reflectionProbability / probabilitySum;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
    }
    return result;
}

public PrimeOpenPbrSampleResult primeCompactTransmissionDiscreteSample(
        float3 wi,
        float randomEvent,
        PrimeCompactTransmissionState state,
        uint samplingFlags,
        PrimeOpenPbrVolume medium) {
    PrimeCompactTransmissionState closureState = state;
    if (closureState.geometryThinWalled == 0u) {
        closureState.entering = 1u;
    }
    PrimeOpenPbrSampleResult result;
    result.medium = medium;
    result.rayT = state.rayT;
    if (closureState.geometryThinWalled != 0u) {
        result.bsdfSample = primeCompactThinWallDiscreteSampleRaw(
                wi, randomEvent, closureState, samplingFlags);
    } else {
        result.bsdfSample = primeCompactRefractiveDiscreteSample(
                primeCompactTransmissionMaterialFrame(wi, closureState),
                randomEvent,
                closureState,
                samplingFlags);
        if (result.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
            result.bsdfSample.wo = primeCompactTransmissionMaterialFrame(
                    result.bsdfSample.wo, closureState);
            if (primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
                result.bsdfSample.throughput.value *= closureState.transmissionTint;
            }
        }
    }
    primeCompactApplyTransmissionVolumeTransition(state, result);
    return result;
}
