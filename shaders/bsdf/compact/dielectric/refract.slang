// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/refract.slang";

import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/discrete.slang";
import "bsdf/compact/dielectric/thin_wall.slang";
import "bsdf/compact/math/fresnel.slang";
import "bsdf/compact/math/microfacet_distribution.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";

public PrimeOpenPbrSampleResult primeCompactTransmissionZeroResult(
        PrimeCompactTransmissionState state,
        PrimeOpenPbrVolume medium) {
    PrimeOpenPbrSampleResult result;
    result.bsdfSample = primeOpenPbrZeroSample();
    result.medium = medium;
    result.rayT = state.rayT;
    return result;
}

public float primeCompactMicrofacetG2r(
        PrimeOpenPbrMicrofacet microfacet,
        float3 wi,
        float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return 1.0 / (primeOpenPbrMicrofacetLambda(microfacet, -wi)
            + primeOpenPbrMicrofacetLambda(microfacet, wo));
}

public PrimeOpenPbrThroughput primeCompactRefractiveEval(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeOpenPbrThroughput result = primeOpenPbrZeroThroughput();
    PrimeOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0) {
        return result;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? fresnel.ior : 1.0 / fresnel.ior);
    float3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (wo.z == 0.0 || wi.z == 0.0 || lengthSquared == 0.0) {
        return result;
    }
    halfVector *= rsqrt(lengthSquared);
    if (halfVector.z <= 0.0) { halfVector = -halfVector; }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) {
        return result;
    }
    PrimeOpenPbrVecPair rt = primeOpenPbrSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float distribution = primeOpenPbrMicrofacetD(microfacet, halfVector);
    if (reflected && (samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION) != 0u) {
        result.value = rt.first * distribution
                * primeCompactMicrofacetG2r(microfacet, wi, wo) / (4.0 * abs(wi.z));
        result.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    } else if (!reflected
            && (samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION) != 0u) {
        float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath) * wi.z;
        result.value = rt.second * distribution
                * primeOpenPbrMicrofacetG2t(microfacet, wi, wo)
                * abs(microIncident * microOutgoing / denominator);
        result.value *= 1.0 / primeOpenPbrSquare(etaPath);
        result.flags = PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
    }
    return result;
}

public PrimeOpenPbrSample primeCompactRefractiveSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0) {
        return primeCompactRefractiveDiscreteSample(
                wi, randomValue.z, state, samplingFlags);
    }
    float3 halfVector = float3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeOpenPbrIsSpecular(samplingFlags)) {
        return primeOpenPbrZeroSample();
    }
    float3 sampleDirection = wi.z > 0.0 ? wi : -wi;
    halfVector = primeOpenPbrMicrofacetSample(microfacet, sampleDirection, randomValue.xy, false);
    microfacetPdf = primeOpenPbrMicrofacetPdf(
            microfacet, sampleDirection, halfVector, false);
    distribution = primeOpenPbrMicrofacetD(microfacet, halfVector);
    float microIncident = dot(halfVector, wi);
    PrimeOpenPbrVecPair rt = primeOpenPbrSpecularFresnelRt(
            fresnel,
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
            fresnel, -wi, halfVector);
    if (refracted.valid == 0u) {
        transmissionProbability = 0.0;
        reflection = float3(1.0);
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return primeOpenPbrZeroSample();
    }
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (randomValue.z < transmissionProbability / probabilitySum) {
        float3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) { return primeOpenPbrZeroSample(); }
        float etaPath = refracted.relativeIor;
        result.wo = wo;
        result.eta = etaPath;
        result.throughput.value = transmission;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionProbability / probabilitySum;
        if (!primeOpenPbrMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            float microOutgoing = dot(wo, halfVector);
            float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath);
            result.pdf *= microfacetPdf * abs(microOutgoing) / denominator;
            result.throughput.value = transmission * distribution
                    * primeOpenPbrMicrofacetG2t(microfacet, wi, wo)
                    * abs(microIncident * microOutgoing / (wi.z * denominator));
            result.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
        }
        result.throughput.value *= 1.0 / primeOpenPbrSquare(etaPath);
    } else {
        float3 wo = reflect(-wi, halfVector);
        if (wi.z * wo.z < 0.0) { return primeOpenPbrZeroSample(); }
        result.wo = wo;
        result.throughput.value = reflection;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
        result.pdf = reflectionProbability / probabilitySum;
        if (!primeOpenPbrMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            result.pdf *= microfacetPdf / (4.0 * abs(microIncident));
            result.throughput.value = reflection * distribution
                    * primeCompactMicrofacetG2r(microfacet, wi, wo) / abs(4.0 * wi.z);
            result.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
        }
    }
    return result;
}

public float primeCompactRefractivePdf(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet)
            || fresnel.ior == 1.0 || !primeOpenPbrIsSpecular(samplingFlags)) {
        return 0.0;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? fresnel.ior : 1.0 / fresnel.ior);
    float3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (wo.z == 0.0 || wi.z == 0.0 || lengthSquared == 0.0) { return 0.0; }
    halfVector *= rsqrt(lengthSquared);
    if (halfVector.z <= 0.0) { halfVector = -halfVector; }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) { return 0.0; }
    PrimeOpenPbrVecPair rt = primeOpenPbrSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float reflectionProbability = primeOpenPbrSpectrumToWeight(rt.first);
    float transmissionProbability = primeOpenPbrSpectrumToWeight(rt.second);
    if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) { return 0.0; }
    float microfacetPdf = primeOpenPbrMicrofacetPdf(
            microfacet, wi.z > 0.0 ? wi : -wi, halfVector, false);
    if (reflected) {
        return microfacetPdf / (4.0 * abs(microIncident))
                * reflectionProbability / probabilitySum;
    }
    float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath);
    return microfacetPdf * abs(microOutgoing) / denominator
            * transmissionProbability / probabilitySum;
}

public PrimeOpenPbrThroughput primeCompactTransmissionEvalRaw(
        float3 wi,
        float3 inputWo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
            if (!primeOpenPbrIsTransmissive(samplingFlags)) {
                return primeOpenPbrZeroThroughput();
            }
        } else if (!primeOpenPbrIsReflective(samplingFlags)) {
            return primeOpenPbrZeroThroughput();
        }
        PrimeOpenPbrReflectiveEvalBase base = primeOpenPbrReflectiveEvalBase(
                wi, wo, state.transmissionMicrofacet,
                samplingFlags, PRIME_OPENPBR_FLAG_ALL);
        PrimeOpenPbrThroughput result;
        result.value = float3(base.factor);
        result.flags = base.flags;
        if (result.flags != PRIME_OPENPBR_FLAG_NONE) {
            PrimeOpenPbrVecPair rt = primeCompactTransmissionThinWallRt(
                    base.microCosine, state);
            if (transmitted) {
                result.value *= rt.second;
                result.flags = primeOpenPbrIsDelta(result.flags)
                        ? PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION
                        : PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
            } else {
                result.value *= rt.first;
            }
            if (primeOpenPbrIsNonDelta(result.flags)) {
                result.value /= primeOpenPbrMicrofacetDirectionalAlbedoUnity(
                        state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }
    float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
    float3 materialWo = primeCompactTransmissionMaterialFrame(inputWo, state);
    PrimeOpenPbrThroughput result = primeCompactRefractiveEval(
            materialWi, materialWo, state, samplingFlags);
    if (result.flags != PRIME_OPENPBR_FLAG_NONE) {
        if (primeOpenPbrIsTransmissive(result.flags)) {
            result.value *= state.transmissionTint;
        }
        if (primeOpenPbrIsNonDelta(result.flags)) {
            result.value *= primeOpenPbrIsTransmissive(result.flags)
                    ? state.transmissionMultipleScattering.y
                    : state.transmissionMultipleScattering.x;
        }
    }
    return result;
}

public PrimeOpenPbrSampleResult primeCompactTransmissionSampleRaw(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags,
        PrimeOpenPbrVolume medium) {
    PrimeOpenPbrSampleResult result = primeCompactTransmissionZeroResult(state, medium);
    if (state.geometryThinWalled != 0u) {
        if (primeOpenPbrMicrofacetEffectivelySmooth(state.transmissionMicrofacet)) {
            return primeCompactThinWallDiscreteSampleRaw(
                    wi, randomValue.z, state, samplingFlags, medium);
        }
        PrimeOpenPbrReflectiveSampleBase base = primeOpenPbrReflectiveSampleBase(
                wi, randomValue, state.transmissionMicrofacet,
                samplingFlags, PRIME_OPENPBR_FLAG_ALL);
        result.bsdfSample = base.bsdfSample;
        if (result.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
            PrimeOpenPbrVecPair rt = primeCompactTransmissionThinWallRt(
                    base.microCosine, state);
            float reflectionProbability = primeOpenPbrSpectrumToWeight(rt.first);
            float transmissionProbability = primeOpenPbrSpectrumToWeight(rt.second);
            if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
            if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
            float probabilitySum = reflectionProbability + transmissionProbability;
            if (probabilitySum == 0.0) {
                result.bsdfSample = primeOpenPbrZeroSample();
                return result;
            }
            if (randomValue.z < transmissionProbability / probabilitySum) {
                result.bsdfSample.throughput.value *= rt.second;
                result.bsdfSample.pdf *= transmissionProbability / probabilitySum;
                result.bsdfSample.throughput.flags =
                        primeOpenPbrIsDelta(result.bsdfSample.throughput.flags)
                        ? PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION
                        : PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
                result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
            } else {
                result.bsdfSample.throughput.value *= rt.first;
                result.bsdfSample.pdf *= reflectionProbability / probabilitySum;
            }
            if (primeOpenPbrIsNonDelta(result.bsdfSample.throughput.flags)) {
                result.bsdfSample.throughput.value /=
                        primeOpenPbrMicrofacetDirectionalAlbedoUnity(
                                state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }

    float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
    result.bsdfSample = primeCompactRefractiveSample(
            materialWi, randomValue, state, samplingFlags);
    if (result.bsdfSample.throughput.flags == PRIME_OPENPBR_FLAG_NONE) { return result; }
    result.bsdfSample.wo = primeCompactTransmissionMaterialFrame(
            result.bsdfSample.wo, state);
    if (primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.transmissionTint;
    }
    if (primeOpenPbrIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *=
                primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)
                ? state.transmissionMultipleScattering.y
                : state.transmissionMultipleScattering.x;
    }
    return result;
}

public float primeCompactTransmissionPdfRaw(
        float3 wi,
        float3 inputWo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
        }
        float3 halfVector = normalize(wi + wo);
        PrimeOpenPbrVecPair rt = primeCompactTransmissionThinWallRt(
                dot(wi, halfVector), state);
        float reflectionProbability = primeOpenPbrSpectrumToWeight(rt.first);
        float transmissionProbability = primeOpenPbrSpectrumToWeight(rt.second);
        if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
        if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
        float probabilitySum = reflectionProbability + transmissionProbability;
        if (probabilitySum == 0.0) { return 0.0; }
        float microfacetPdf = primeOpenPbrReflectivePdf(
                wi, wo, state.transmissionMicrofacet,
                samplingFlags, PRIME_OPENPBR_FLAG_ALL);
        return transmitted
                ? microfacetPdf * transmissionProbability / probabilitySum
                : microfacetPdf * reflectionProbability / probabilitySum;
    }
    return primeCompactRefractivePdf(
            primeCompactTransmissionMaterialFrame(wi, state),
            primeCompactTransmissionMaterialFrame(inputWo, state),
            state,
            samplingFlags);
}
