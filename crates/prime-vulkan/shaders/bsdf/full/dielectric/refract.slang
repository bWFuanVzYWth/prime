// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/refract.slang";

import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/discrete.slang";
import "bsdf/full/dielectric/thin_wall.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/microfacet_distribution.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/spectrum.slang";

public float primeFullCompactMicrofacetG2r(PrimeFullOpenPbrMicrofacet microfacet, float3 wi,
                                           float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return 1.0 / (primeFullOpenPbrMicrofacetLambda(microfacet, -wi) +
                  primeFullOpenPbrMicrofacetLambda(microfacet, wo));
}

public PrimeFullOpenPbrThroughput
primeFullCompactRefractiveEval(float3 wi, float3 wo, PrimeFullCompactTransmissionState state,
                               uint samplingFlags) {
    PrimeFullOpenPbrThroughput result = primeFullOpenPbrZeroThroughput();
    PrimeFullOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeFullOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0) {
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
    if (halfVector.z <= 0.0) {
        halfVector = -halfVector;
    }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) {
        return result;
    }
    PrimeFullOpenPbrVecPair rt = primeFullOpenPbrSpecularFresnelRt(fresnel, microIncident);
    float distribution = primeFullOpenPbrMicrofacetD(microfacet, halfVector);
    if (reflected && (samplingFlags & PRIME_FULL_OPENPBR_FLAG_SPECULAR_REFLECTION) != 0u) {
        result.value = rt.first * distribution * primeFullCompactMicrofacetG2r(microfacet, wi, wo) /
                       (4.0 * abs(wi.z));
        result.flags = PRIME_FULL_OPENPBR_FLAG_SPECULAR_REFLECTION;
    } else if (!reflected &&
               (samplingFlags & PRIME_FULL_OPENPBR_FLAG_SPECULAR_TRANSMISSION) != 0u) {
        float denominator = primeFullOpenPbrSquare(microOutgoing + microIncident / etaPath) * wi.z;
        result.value = rt.second * distribution *
                       primeFullOpenPbrMicrofacetG2t(microfacet, wi, wo) *
                       abs(microIncident * microOutgoing / denominator);
        result.value *= 1.0 / primeFullOpenPbrSquare(etaPath);
        result.flags = PRIME_FULL_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
    }
    return result;
}

public PrimeFullOpenPbrSample
primeFullCompactRefractiveSample(float3 wi, float3 randomValue,
                                 PrimeFullCompactTransmissionState state, uint samplingFlags) {
    PrimeFullOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeFullOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0) {
        return primeFullCompactRefractiveDiscreteSample(wi, randomValue.z, state, samplingFlags);
    }
    float3 halfVector = float3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeFullOpenPbrIsSpecular(samplingFlags)) {
        return primeFullOpenPbrZeroSample();
    }
    float3 sampleDirection = wi.z > 0.0 ? wi : -wi;
    halfVector =
            primeFullOpenPbrMicrofacetSample(microfacet, sampleDirection, randomValue.xy, false);
    microfacetPdf = primeFullOpenPbrMicrofacetPdf(microfacet, sampleDirection, halfVector, false);
    distribution = primeFullOpenPbrMicrofacetD(microfacet, halfVector);
    float microIncident = dot(halfVector, wi);
    PrimeFullOpenPbrVecPair rt = primeFullOpenPbrSpecularFresnelRt(fresnel, microIncident);
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
            primeFullOpenPbrSpecularFresnelRefract(fresnel, -wi, halfVector);
    if (refracted.valid == 0u) {
        transmissionProbability = 0.0;
        reflection = float3(1.0);
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return primeFullOpenPbrZeroSample();
    }
    PrimeFullOpenPbrSample result = primeFullOpenPbrZeroSample();
    if (randomValue.z < transmissionProbability / probabilitySum) {
        float3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) {
            return primeFullOpenPbrZeroSample();
        }
        float etaPath = refracted.relativeIor;
        result.wo = wo;
        result.eta = etaPath;
        result.throughput.value = transmission;
        result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionProbability / probabilitySum;
        if (!primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            float microOutgoing = dot(wo, halfVector);
            float denominator = primeFullOpenPbrSquare(microOutgoing + microIncident / etaPath);
            result.pdf *= microfacetPdf * abs(microOutgoing) / denominator;
            result.throughput.value = transmission * distribution *
                                      primeFullOpenPbrMicrofacetG2t(microfacet, wi, wo) *
                                      abs(microIncident * microOutgoing / (wi.z * denominator));
            result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
        }
        result.throughput.value *= 1.0 / primeFullOpenPbrSquare(etaPath);
    } else {
        float3 wo = reflect(-wi, halfVector);
        if (wi.z * wo.z < 0.0) {
            return primeFullOpenPbrZeroSample();
        }
        result.wo = wo;
        result.throughput.value = reflection;
        result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION;
        result.pdf = reflectionProbability / probabilitySum;
        if (!primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            result.pdf *= microfacetPdf / (4.0 * abs(microIncident));
            result.throughput.value = reflection * distribution *
                                      primeFullCompactMicrofacetG2r(microfacet, wi, wo) /
                                      abs(4.0 * wi.z);
            result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_SPECULAR_REFLECTION;
        }
    }
    return result;
}

public float primeFullCompactRefractivePdf(float3 wi, float3 wo,
                                           PrimeFullCompactTransmissionState state,
                                           uint samplingFlags) {
    PrimeFullOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeFullOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0 ||
        !primeFullOpenPbrIsSpecular(samplingFlags)) {
        return 0.0;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? fresnel.ior : 1.0 / fresnel.ior);
    float3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (wo.z == 0.0 || wi.z == 0.0 || lengthSquared == 0.0) {
        return 0.0;
    }
    halfVector *= rsqrt(lengthSquared);
    if (halfVector.z <= 0.0) {
        halfVector = -halfVector;
    }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) {
        return 0.0;
    }
    PrimeFullOpenPbrVecPair rt = primeFullOpenPbrSpecularFresnelRt(fresnel, microIncident);
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
        return 0.0;
    }
    float microfacetPdf =
            primeFullOpenPbrMicrofacetPdf(microfacet, wi.z > 0.0 ? wi : -wi, halfVector, false);
    if (reflected) {
        return microfacetPdf / (4.0 * abs(microIncident)) * reflectionProbability / probabilitySum;
    }
    float denominator = primeFullOpenPbrSquare(microOutgoing + microIncident / etaPath);
    return microfacetPdf * abs(microOutgoing) / denominator * transmissionProbability /
           probabilitySum;
}

public PrimeFullOpenPbrThroughput
primeFullCompactTransmissionEvalRaw(float3 wi, float3 inputWo,
                                    PrimeFullCompactTransmissionState state, uint samplingFlags) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
            if (!primeFullOpenPbrIsTransmissive(samplingFlags)) {
                return primeFullOpenPbrZeroThroughput();
            }
        } else if (!primeFullOpenPbrIsReflective(samplingFlags)) {
            return primeFullOpenPbrZeroThroughput();
        }
        PrimeFullOpenPbrReflectiveEvalBase base = primeFullOpenPbrReflectiveEvalBase(
                wi, wo, state.transmissionMicrofacet, samplingFlags, PRIME_FULL_OPENPBR_FLAG_ALL);
        PrimeFullOpenPbrThroughput result;
        result.value = float3(base.factor);
        result.flags = base.flags;
        if (result.flags != PRIME_FULL_OPENPBR_FLAG_NONE) {
            PrimeFullOpenPbrVecPair rt =
                    primeFullCompactTransmissionThinWallRt(base.microCosine, state);
            if (transmitted) {
                result.value *= rt.second;
                result.flags = primeFullOpenPbrIsDelta(result.flags)
                                       ? PRIME_FULL_OPENPBR_FLAG_DELTA_TRANSMISSION
                                       : PRIME_FULL_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
            } else {
                result.value *= rt.first;
            }
            if (primeFullOpenPbrIsNonDelta(result.flags)) {
                result.value /= primeFullOpenPbrMicrofacetDirectionalAlbedoUnity(
                        state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }
    float3 materialWi = primeFullCompactTransmissionMaterialFrame(wi, state);
    float3 materialWo = primeFullCompactTransmissionMaterialFrame(inputWo, state);
    PrimeFullOpenPbrThroughput result =
            primeFullCompactRefractiveEval(materialWi, materialWo, state, samplingFlags);
    if (result.flags != PRIME_FULL_OPENPBR_FLAG_NONE) {
        if (primeFullOpenPbrIsTransmissive(result.flags)) {
            result.value *= state.transmissionTint;
        }
        if (primeFullOpenPbrIsNonDelta(result.flags)) {
            result.value *= primeFullOpenPbrIsTransmissive(result.flags)
                                    ? state.transmissionMultipleScattering.y
                                    : state.transmissionMultipleScattering.x;
        }
    }
    return result;
}

public PrimeFullOpenPbrSample
primeFullCompactTransmissionSampleRaw(float3 wi, float3 randomValue,
                                      PrimeFullCompactTransmissionState state, uint samplingFlags) {
    PrimeFullOpenPbrSample result = primeFullOpenPbrZeroSample();
    if (state.geometryThinWalled != 0u) {
        if (primeFullOpenPbrMicrofacetEffectivelySmooth(state.transmissionMicrofacet)) {
            return primeFullCompactThinWallDiscreteSampleRaw(wi, randomValue.z, state,
                                                             samplingFlags);
        }
        PrimeFullOpenPbrReflectiveSampleBase base =
                primeFullOpenPbrReflectiveSampleBase(wi, randomValue, state.transmissionMicrofacet,
                                                     samplingFlags, PRIME_FULL_OPENPBR_FLAG_ALL);
        result = base.bsdfSample;
        if (result.throughput.flags != PRIME_FULL_OPENPBR_FLAG_NONE) {
            PrimeFullOpenPbrVecPair rt =
                    primeFullCompactTransmissionThinWallRt(base.microCosine, state);
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
                result = primeFullOpenPbrZeroSample();
                return result;
            }
            if (randomValue.z < transmissionProbability / probabilitySum) {
                result.throughput.value *= rt.second;
                result.pdf *= transmissionProbability / probabilitySum;
                result.throughput.flags = primeFullOpenPbrIsDelta(result.throughput.flags)
                                                  ? PRIME_FULL_OPENPBR_FLAG_DELTA_TRANSMISSION
                                                  : PRIME_FULL_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
                result.wo.z = -result.wo.z;
            } else {
                result.throughput.value *= rt.first;
                result.pdf *= reflectionProbability / probabilitySum;
            }
            if (primeFullOpenPbrIsNonDelta(result.throughput.flags)) {
                result.throughput.value /= primeFullOpenPbrMicrofacetDirectionalAlbedoUnity(
                        state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }

    float3 materialWi = primeFullCompactTransmissionMaterialFrame(wi, state);
    result = primeFullCompactRefractiveSample(materialWi, randomValue, state, samplingFlags);
    if (result.throughput.flags == PRIME_FULL_OPENPBR_FLAG_NONE) {
        return result;
    }
    result.wo = primeFullCompactTransmissionMaterialFrame(result.wo, state);
    if (primeFullOpenPbrIsTransmissive(result.throughput.flags)) {
        result.throughput.value *= state.transmissionTint;
    }
    if (primeFullOpenPbrIsNonDelta(result.throughput.flags)) {
        result.throughput.value *= primeFullOpenPbrIsTransmissive(result.throughput.flags)
                                           ? state.transmissionMultipleScattering.y
                                           : state.transmissionMultipleScattering.x;
    }
    return result;
}

public float primeFullCompactTransmissionPdfRaw(float3 wi, float3 inputWo,
                                                PrimeFullCompactTransmissionState state,
                                                uint samplingFlags) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
        }
        float3 halfVector = normalize(wi + wo);
        PrimeFullOpenPbrVecPair rt =
                primeFullCompactTransmissionThinWallRt(dot(wi, halfVector), state);
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
            return 0.0;
        }
        float microfacetPdf = primeFullOpenPbrReflectivePdf(
                wi, wo, state.transmissionMicrofacet, samplingFlags, PRIME_FULL_OPENPBR_FLAG_ALL);
        return transmitted ? microfacetPdf * transmissionProbability / probabilitySum
                           : microfacetPdf * reflectionProbability / probabilitySum;
    }
    return primeFullCompactRefractivePdf(primeFullCompactTransmissionMaterialFrame(wi, state),
                                         primeFullCompactTransmissionMaterialFrame(inputWo, state),
                                         state, samplingFlags);
}
