// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/opaque/lobes.slang";

import "bsdf/compact/math/fresnel.slang";
import "bsdf/compact/math/microfacet_distribution.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "bsdf/compact/opaque/discrete.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";

public PrimeCompactLayerState primeCompactMakeLayerState(
        float3 baseEnergy, float3 transmittanceIn, float3 specularEnergy) {
    PrimeCompactLayerState result;
    result.transmittanceIn = transmittanceIn;
    result.baseSampleWeight = primeOpenPbrSpectrumToWeight(transmittanceIn * baseEnergy);
    result.specularSampleWeight = primeOpenPbrSpectrumToWeight(specularEnergy);
    float sum = result.baseSampleWeight + result.specularSampleWeight;
    if (sum > 0.0) {
        result.baseSampleWeight /= sum;
        result.specularSampleWeight /= sum;
    }
    return result;
}

public PrimeCompactMixState primeCompactMakeMixState(
        float weight, float3 firstEnergy, float3 secondEnergy) {
    PrimeCompactMixState result;
    if (weight == 0.0) {
        result.firstSampleWeight = 1.0;
        result.secondSampleWeight = 0.0;
        return result;
    }
    if (weight == 1.0) {
        result.firstSampleWeight = 0.0;
        result.secondSampleWeight = 1.0;
        return result;
    }
    result.firstSampleWeight = (1.0 - weight) * primeOpenPbrSpectrumToWeight(firstEnergy);
    result.secondSampleWeight = weight * primeOpenPbrSpectrumToWeight(secondEnergy);
    float sum = result.firstSampleWeight + result.secondSampleWeight;
    if (sum > 0.0) {
        result.firstSampleWeight /= sum;
        result.secondSampleWeight /= sum;
    }
    return result;
}

public PrimeOpenPbrThroughput primeCompactMixEval(
        PrimeOpenPbrThroughput first, PrimeOpenPbrThroughput second, float weight) {
    PrimeOpenPbrThroughput result = primeOpenPbrZeroThroughput();
    if (weight < 1.0) { result = first; }
    if (weight > 0.0) {
        result = primeOpenPbrThroughputScale(result, 1.0 - weight);
        result = primeOpenPbrThroughputAdd(
                result, primeOpenPbrThroughputScale(second, weight));
    }
    return result;
}

public PrimeOpenPbrThroughput primeCompactLayerEval(
        PrimeOpenPbrThroughput base,
        PrimeOpenPbrThroughput specular,
        PrimeCompactLayerState layer) {
    PrimeOpenPbrThroughput result = primeOpenPbrThroughputScale(base, layer.transmittanceIn);
    return primeOpenPbrThroughputAdd(result, specular);
}

public float3x3 primeCompactEonBasis(float3 wi) {
    float lengthSquared = dot(wi.xy, wi.xy);
    float3 x = lengthSquared > 0.0
            ? float3(wi.x, wi.y, 0.0) * rsqrt(lengthSquared)
            : float3(1.0, 0.0, 0.0);
    float3 y = float3(-x.y, x.x, 0.0);
    return float3x3(x, y, float3(0.0, 0.0, 1.0));
}

public PrimeOpenPbrThroughput primeCompactLambertEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (wi.z < 0.0 || wo.z < 0.0
            || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value = state.baseColor * (PRIME_OPENPBR_INV_PI * wo.z);
    result.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    return result;
}

public float primeCompactLambertPdf(float3 wi, float3 wo) {
    return wi.z >= 0.0 && wo.z > 0.0 ? PRIME_OPENPBR_INV_PI * wo.z : 0.0;
}

public float3 primeCompactLambertEnergy(float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    return wi.z >= 0.0 && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
            ? state.baseColor : float3(0.0);
}

public PrimeOpenPbrSample primeCompactLambertSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (wi.z < 0.0 || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    result.wo = primeOpenPbrCosineSampleHemisphere(randomValue.xy);
    result.pdf = PRIME_OPENPBR_INV_PI * result.wo.z;
    result.throughput.value = state.baseColor * result.pdf;
    result.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    return result;
}

// The generic OpenPBR diffuse closure uses EON even at diffuse roughness zero.
// Its value, PDF and energy reduce to Lambert, but its cLTC sampler rotates the
// cosine sample into the incident-direction basis. Preserve that exact mapping
// for fractional diffuse/subsurface mixtures without retaining general EON state.
public PrimeOpenPbrSample primeCompactZeroRoughnessEonSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (wi.z < 0.0 || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    if (randomValue.z <= 0.0) {
        // EON uses <= for its zero-probability uniform branch. Although this has
        // zero measure, preserving it keeps boundary random inputs deterministic.
        result.wo = primeOpenPbrUniformSampleHemisphere(randomValue.xy);
        result.pdf = result.wo.z * PRIME_OPENPBR_INV_PI;
        result.throughput = primeCompactLambertEval(
                wi, result.wo, state, samplingFlags);
        return result;
    }
    float radius = sqrt(randomValue.x);
    float phi = 2.0 * PRIME_OPENPBR_PI * randomValue.y;
    float x = -radius * cos(phi);
    float y = radius * sin(phi);
    float3 wo = float3(x, y, sqrt(max(1.0 - (x * x + y * y), 0.0)));
    float woLength = length(wo);
    result.pdf = wo.z * PRIME_OPENPBR_INV_PI
            * woLength * woLength * woLength;
    result.wo = normalize(mul(wo, primeCompactEonBasis(wi)));
    result.throughput = primeCompactLambertEval(
            wi, result.wo, state, samplingFlags);
    return result;
}

public PrimeOpenPbrThroughput primeCompactSubsurfaceEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    bool reflected = wo.z * wi.z > 0.0;
    if ((reflected && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u)
            || (!reflected && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION) == 0u)) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value = float3(PRIME_OPENPBR_INV_PI * abs(wo.z))
            * state.subsurfaceColor * 0.5
            * (reflected
                    ? 1.0 - state.subsurfaceAnisotropy
                    : 1.0 + state.subsurfaceAnisotropy);
    result.flags = reflected
            ? PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION
            : PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
    return result;
}

public float primeCompactSubsurfaceReflectionProbability(
        PrimeCompactOpaqueState state, uint samplingFlags) {
    float probability = (1.0 - state.subsurfaceAnisotropy) * 0.5;
    return primeOpenPbrIsReflective(samplingFlags) ? probability : 0.0;
}

public float primeCompactSubsurfaceTransmissionProbability(
        PrimeCompactOpaqueState state, uint samplingFlags) {
    float probability = (1.0 + state.subsurfaceAnisotropy) * 0.5;
    return primeOpenPbrIsTransmissive(samplingFlags) ? probability : 0.0;
}

public float primeCompactSubsurfacePdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    float reflectionProbability = primeCompactSubsurfaceReflectionProbability(
            state, samplingFlags);
    float transmissionProbability = primeCompactSubsurfaceTransmissionProbability(
            state, samplingFlags);
    if (reflectionProbability == 0.0 && transmissionProbability == 0.0) {
        return 0.0;
    }
    return PRIME_OPENPBR_INV_PI * abs(wo.z)
            * (wi.z * wo.z > 0.0 ? reflectionProbability : transmissionProbability);
}

public float3 primeCompactSubsurfaceEnergy(
        PrimeCompactOpaqueState state, uint samplingFlags) {
    if ((samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u) {
        return state.subsurfaceColor;
    }
    return float3(0.0);
}

public PrimeOpenPbrSample primeCompactSubsurfaceSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if ((samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE) == 0u) { return result; }
    float reflectionProbability = primeCompactSubsurfaceReflectionProbability(
            state, samplingFlags);
    float transmissionProbability = primeCompactSubsurfaceTransmissionProbability(
            state, samplingFlags);
    if (reflectionProbability == 0.0 && transmissionProbability == 0.0) {
        return result;
    }
    result.wo = primeOpenPbrCosineSampleHemisphere(randomValue.xy);
    result.pdf = PRIME_OPENPBR_INV_PI * result.wo.z;
    result.throughput.value = float3(result.pdf);
    if (wi.z < 0.0) { result.wo.z = -result.wo.z; }
    if (randomValue.z <= transmissionProbability) {
        result.wo.z = -result.wo.z;
        result.pdf *= transmissionProbability;
        result.throughput.value *= transmissionProbability;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
        result.throughput.value *= state.subsurfaceColor;
    } else {
        result.pdf *= reflectionProbability;
        result.throughput.value *= reflectionProbability * state.subsurfaceColor;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    }
    return result;
}

public PrimeOpenPbrThroughput primeCompactMixedDiffuseEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    return primeCompactMixEval(
            state.subsurfaceWeight < 1.0
                    ? primeCompactLambertEval(wi, wo, state, samplingFlags)
                    : primeOpenPbrZeroThroughput(),
            state.subsurfaceWeight > 0.0
                    ? primeCompactSubsurfaceEval(wi, wo, state, samplingFlags)
                    : primeOpenPbrZeroThroughput(),
            state.subsurfaceWeight);
}

public float primeCompactMixedDiffusePdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (state.diffuseMix.firstSampleWeight + state.diffuseMix.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    float first = state.subsurfaceWeight < 1.0
            && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
            ? primeCompactLambertPdf(wi, wo) : 0.0;
    float second = state.subsurfaceWeight > 0.0
            ? primeCompactSubsurfacePdf(wi, wo, state, samplingFlags) : 0.0;
    return lerp(first, second, state.diffuseMix.secondSampleWeight);
}

public float3 primeCompactMixedDiffuseEnergy(
        float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    float3 first = state.subsurfaceWeight < 1.0
            && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
            ? state.baseColor : float3(0.0);
    float3 second = state.subsurfaceWeight > 0.0
            ? primeCompactSubsurfaceEnergy(state, samplingFlags) : float3(0.0);
    float3 result = float3(0.0);
    if (state.subsurfaceWeight < 1.0) { result = first; }
    if (state.subsurfaceWeight > 0.0) {
        result = result * (1.0 - state.subsurfaceWeight)
                + second * state.subsurfaceWeight;
    }
    return result;
}

public PrimeOpenPbrSample primeCompactMixedDiffuseSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags) {
    if (state.diffuseMix.firstSampleWeight + state.diffuseMix.secondSampleWeight <= 0.0) {
        return primeOpenPbrZeroSample();
    }
    if (randomValue.z <= state.diffuseMix.secondSampleWeight
            && state.diffuseMix.secondSampleWeight > 0.0) {
        PrimeOpenPbrSample result = primeCompactSubsurfaceSample(
                wi,
                float3(randomValue.xy,
                        randomValue.z / state.diffuseMix.secondSampleWeight),
                state,
                samplingFlags);
        result.throughput = primeOpenPbrThroughputScale(
                result.throughput, state.subsurfaceWeight);
        result.pdf *= state.diffuseMix.secondSampleWeight;
        return result;
    }
    PrimeOpenPbrSample result = primeCompactZeroRoughnessEonSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - state.diffuseMix.secondSampleWeight)
                    / state.diffuseMix.firstSampleWeight),
            state,
            samplingFlags);
    result.throughput = primeOpenPbrThroughputScale(
            result.throughput, 1.0 - state.subsurfaceWeight);
    result.pdf *= state.diffuseMix.firstSampleWeight;
    return result;
}

public PrimeOpenPbrThroughput primeCompactDielectricEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    PrimeOpenPbrReflectiveEvalBase base = primeOpenPbrReflectiveEvalBase(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    if (base.flags == PRIME_OPENPBR_FLAG_NONE) { return primeOpenPbrZeroThroughput(); }
    PrimeOpenPbrThroughput result;
    result.value = primeOpenPbrSpecularFresnelUnpolarized(
            state.dielectricFresnel, float3(0.0), 0u, base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeOpenPbrIsNonDelta(result.flags)) {
        result.value *= state.dielectricMultipleScattering.x;
    }
    return result;
}

public float primeCompactDielectricPdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    return primeOpenPbrReflectivePdf(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
}

public float3 primeCompactDielectricEnergy(
        float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (wi.z < 0.0) { return float3(0.0); }
    if (!primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet)
            && state.dielectricFresnel.ior != 1.0) {
        if (!primeOpenPbrIsSpecular(samplingFlags)) { return float3(0.0); }
    } else if (!primeOpenPbrIsDelta(samplingFlags)) {
        return float3(0.0);
    }
    if (!primeOpenPbrIsReflective(samplingFlags)) { return float3(0.0); }
    return state.dielectricMultipleScattering.z * state.dielectricFresnel.color;
}

public PrimeOpenPbrSample primeCompactDielectricSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    PrimeOpenPbrReflectiveSampleBase base = primeOpenPbrReflectiveSampleBase(
            wi, randomValue, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    result = base.bsdfSample;
    result.throughput.value *= primeOpenPbrSpecularFresnelUnpolarized(
            state.dielectricFresnel, float3(0.0), 0u, base.microCosine);
    if (primeOpenPbrIsNonDelta(result.throughput.flags)) {
        result.throughput.value *= state.dielectricMultipleScattering.x;
    }
    return result;
}

public PrimeOpenPbrThroughput primeCompactConductorEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    PrimeOpenPbrReflectiveEvalBase base = primeOpenPbrReflectiveEvalBase(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    if (base.flags == PRIME_OPENPBR_FLAG_NONE) { return primeOpenPbrZeroThroughput(); }
    PrimeOpenPbrThroughput result;
    result.value = primeOpenPbrConductorFresnelUnpolarized(
            state.conductorFresnel, float3(0.0), 0u, base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeOpenPbrIsNonDelta(result.flags)) {
        result.value *= float3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

public float primeCompactConductorPdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    return primeOpenPbrReflectivePdf(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
}

public float3 primeCompactConductorEnergy(
        float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (wi.z < 0.0) { return float3(0.0); }
    if (primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet)) {
        if ((samplingFlags & PRIME_OPENPBR_FLAG_DELTA_REFLECTION) == 0u) {
            return float3(0.0);
        }
    } else if ((samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION) == 0u) {
        return float3(0.0);
    }
    float3 f0 = state.conductorFresnel.energyF0;
    return primeOpenPbrMicrofacetDirectionalAlbedoReflection(
            state.microfacet,
            wi.z,
            f0,
            clamp(float3(state.conductorFresnel.weight), float3(0.0), float3(1.0)))
            * (float3(1.0) + f0 * (1.0 - state.conductorEss) / state.conductorEss);
}

public PrimeOpenPbrSample primeCompactConductorSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags) {
    if (primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet)) {
        return primeCompactConductorDiscreteSample(
                wi,
                primeCompactOpaqueDiscreteStateFromFull(state),
                samplingFlags);
    }
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    PrimeOpenPbrReflectiveSampleBase base = primeOpenPbrReflectiveSampleBase(
            wi, randomValue, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    result = base.bsdfSample;
    result.throughput.value *= primeOpenPbrConductorFresnelUnpolarized(
            state.conductorFresnel, float3(0.0), 0u, base.microCosine);
    if (primeOpenPbrIsNonDelta(result.throughput.flags)) {
        result.throughput.value *= float3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

public PrimeOpenPbrThroughput primeCompactOpaqueLayerEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    PrimeOpenPbrThroughput base = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffuseEval(wi, wo, state, samplingFlags)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfaceEval(wi, wo, state, samplingFlags)
                    : primeCompactLambertEval(wi, wo, state, samplingFlags);
    PrimeOpenPbrThroughput specular = primeCompactDielectricEval(wi, wo, state, samplingFlags);
    return primeCompactLayerEval(base, specular, state.layer);
}

public float primeCompactOpaqueLayerPdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state) {
    float basePdf = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffusePdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfacePdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
                    : primeCompactLambertPdf(wi, wo);
    if (state.layer.baseSampleWeight + state.layer.specularSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(
            basePdf,
            primeCompactDielectricPdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL),
            state.layer.specularSampleWeight);
}

public PrimeOpenPbrSample primeCompactOpaqueLayerSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        bool useEonDiffuseMapping) {
    if (state.layer.baseSampleWeight + state.layer.specularSampleWeight <= 0.0) {
        return primeOpenPbrZeroSample();
    }
    if (randomValue.z <= state.layer.specularSampleWeight
            && state.layer.specularSampleWeight > 0.0) {
        PrimeOpenPbrSample result = primeCompactDielectricSample(
                wi,
                float3(randomValue.xy, randomValue.z / state.layer.specularSampleWeight),
                state,
                PRIME_OPENPBR_FLAG_ALL);
        result.pdf *= state.layer.specularSampleWeight;
        return result;
    }
    PrimeOpenPbrSample result = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffuseSample(
                    wi,
                    float3(randomValue.xy,
                            (randomValue.z - state.layer.specularSampleWeight)
                            / state.layer.baseSampleWeight),
                    state,
                    PRIME_OPENPBR_FLAG_ALL)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfaceSample(
                            wi,
                            float3(randomValue.xy,
                                    (randomValue.z - state.layer.specularSampleWeight)
                                    / state.layer.baseSampleWeight),
                            state,
                            PRIME_OPENPBR_FLAG_ALL)
                    : useEonDiffuseMapping
                            ? primeCompactZeroRoughnessEonSample(
                                    wi,
                                    float3(randomValue.xy,
                                            (randomValue.z
                                                    - state.layer.specularSampleWeight)
                                            / state.layer.baseSampleWeight),
                                    state,
                                    PRIME_OPENPBR_FLAG_ALL)
                            : primeCompactLambertSample(
                                    wi,
                                    float3(randomValue.xy,
                                            (randomValue.z - state.layer.specularSampleWeight)
                                            / state.layer.baseSampleWeight),
                                    state,
                                    PRIME_OPENPBR_FLAG_ALL);
    result.throughput = primeOpenPbrThroughputScale(
            result.throughput, state.layer.transmittanceIn);
    result.pdf *= state.layer.baseSampleWeight;
    return result;
}
