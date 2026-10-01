// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/lite/bsdf.slang";

import "bsdf/common/common.slang";

// Lightweight renderer model. It consumes Prime's existing material/transport ABI, but it is
// deliberately not an OpenPBR implementation: unresolved microfacet bounces are collapsed to a
// scalar directional-energy closure instead of reconstructed with transmission-energy tables.

public static const uint PRIME_LITE_OPAQUE_DIELECTRIC = 0u;
public static const uint PRIME_LITE_OPAQUE_CONDUCTOR = 1u;
public static const uint PRIME_LITE_OPAQUE_SUBSURFACE = 2u;
public static const uint PRIME_LITE_OPAQUE_MIXED_SUBSURFACE = 3u;

public struct PrimeLiteEnergy {};

public PrimeLiteEnergy primeLiteEnergy() {
    return PrimeLiteEnergy();
}

public struct PrimeLiteMixState {
    public float firstSampleWeight;
    public float secondSampleWeight;
};

public struct PrimeLiteLayerState {
    public float transmittanceIn;
    public float specularScale;
    public float baseSampleWeight;
    public float specularSampleWeight;
};

public struct PrimeLiteOpaqueState {
    public PrimeOpenPbrOnb onb;
    public uint kind;
    public uint thinWalled;
    public float rayT;
    public float3 baseColor;
    public float subsurfaceWeight;
    public float3 subsurfaceColor;
    public float subsurfaceAnisotropy;
    public PrimeOpenPbrMicrofacet microfacet;
    public PrimeOpenPbrSpecularFresnel dielectricFresnel;
    public PrimeOpenPbrConductorFresnel conductorFresnel;
    public PrimeLiteMixState diffuseMix;
    public PrimeLiteLayerState layer;
};

public struct PrimeLiteOpaqueEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeLiteOpaqueComponents {
    public float3 diffuse;
    public float3 specular;
    public float pdf;
};

public struct PrimeLiteOpaqueEnergy {
    public float3 diffuse;
    public float3 specular;
};

public struct PrimeLiteTransmissionState {
    public PrimeOpenPbrOnb onb;
    public uint geometryThinWalled;
    public uint entering;
    public float rayT;
    public float originalIor;
    public float3 transmissionColor;
    public PrimeOpenPbrMicrofacet specularMicrofacet;
    public PrimeOpenPbrSpecularFresnel specularFresnel;
    public PrimeOpenPbrMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeOpenPbrVolume transmissionVolume;
};

public struct PrimeLiteTransmissionEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeLiteFoliageState {
    public PrimeLiteOpaqueState base;
    public float3 transmissionTint;
    public PrimeLiteMixState mix;
    public float transmissionWeight;
};

public struct PrimeLiteFoliageEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeLiteFoliageComponents {
    public float3 diffuse;
    public float3 specular;
    public float pdf;
};

public struct PrimeLiteFoliageEnergy {
    public float3 diffuse;
    public float3 specular;
};

public float primeLiteWeight(float3 value) {
    return max(0.0, primeOpenPbrSpectrumToWeight(value));
}

public PrimeLiteMixState primeLiteMixState(float firstWeight, float secondWeight) {
    PrimeLiteMixState result;
    float sum = firstWeight + secondWeight;
    result.firstSampleWeight = sum > 0.0 ? firstWeight / sum : 0.0;
    result.secondSampleWeight = sum > 0.0 ? secondWeight / sum : 0.0;
    return result;
}

public PrimeOpenPbrSampleResult primeLiteZeroSampleResult(float rayT,
                                                          PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result;
    result.bsdfSample = primeOpenPbrZeroSample();
    result.volumeStack = stack;
    result.rayT = rayT;
    return result;
}

public bool primeLiteMicrofacetEffectivelySmooth(PrimeOpenPbrMicrofacet microfacet) {
    return primeOpenPbrReduceMax(microfacet.alpha) < 1.0e-4;
}

float primeLiteMicrofacetD(PrimeOpenPbrMicrofacet microfacet, float3 halfVector) {
    if (halfVector.z <= 0.0) {
        return 0.0;
    }
    float3 stretched = float3(halfVector.x / microfacet.alpha.x, halfVector.y / microfacet.alpha.y,
                              halfVector.z);
    float denominator = dot(stretched, stretched);
    return 1.0 /
           (PRIME_OPENPBR_PI * microfacet.alpha.x * microfacet.alpha.y * denominator * denominator);
}

float primeLiteMicrofacetLambda(PrimeOpenPbrMicrofacet microfacet, float3 direction) {
    float cosine = abs(direction.z);
    if (cosine == 0.0) {
        return 1.0e20;
    }
    float projected = dot(microfacet.alpha * direction.xy, microfacet.alpha * direction.xy);
    return 0.5 * (sqrt(1.0 + projected / (cosine * cosine)) - 1.0);
}

float primeLiteMicrofacetG1(PrimeOpenPbrMicrofacet microfacet, float3 direction) {
    return 1.0 / (1.0 + primeLiteMicrofacetLambda(microfacet, direction));
}

float primeLiteMicrofacetG2(PrimeOpenPbrMicrofacet microfacet, float3 wi, float3 wo) {
    return 1.0 / (1.0 + primeLiteMicrofacetLambda(microfacet, wi) +
                  primeLiteMicrofacetLambda(microfacet, wo));
}

float3 primeLiteMicrofacetSample(PrimeOpenPbrMicrofacet microfacet, float3 wi, float2 sampleValue) {
    // Heitz visible-normal sampling. Unlike the exact production sampler, this is the standard
    // first-order GGX proposal and has no reflection-only spherical-cap correction.
    float3 visible = normalize(float3(microfacet.alpha * wi.xy, wi.z));
    float lensq = dot(visible.xy, visible.xy);
    float3 tangent =
            lensq > 0.0 ? float3(-visible.y, visible.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 bitangent = cross(visible, tangent);
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_OPENPBR_PI * sampleValue.y;
    float x = radius * cos(phi);
    float y = radius * sin(phi);
    float blend = 0.5 * (1.0 + visible.z);
    y = lerp(sqrt(max(0.0, 1.0 - x * x)), y, blend);
    float z = sqrt(max(0.0, 1.0 - x * x - y * y));
    float3 normal = x * tangent + y * bitangent + z * visible;
    return normalize(float3(microfacet.alpha * normal.xy, max(0.0, normal.z)));
}

float primeLiteMicrofacetPdf(PrimeOpenPbrMicrofacet microfacet, float3 wi, float3 halfVector) {
    if (wi.z <= 0.0 || halfVector.z <= 0.0) {
        return 0.0;
    }
    float projected = dot(wi, halfVector);
    return projected > 0.0 ? primeLiteMicrofacetD(microfacet, halfVector) *
                                     primeLiteMicrofacetG1(microfacet, wi) * projected / wi.z
                           : 0.0;
}

float primeLiteSchlick(float f0, float cosineTheta) {
    float oneMinusCosine = 1.0 - clamp(abs(cosineTheta), 0.0, 1.0);
    return f0 + (1.0 - f0) * primeOpenPbrPow5(oneMinusCosine);
}

public float primeLiteDielectricFresnel(float ior, float cosineTheta) {
    float cosine = cosineTheta;
    if (cosine < 0.0) {
        float transmittedCosineSquared = 1.0 - (1.0 - cosine * cosine) * ior * ior;
        if (transmittedCosineSquared <= 0.0) {
            return 1.0;
        }
        cosine = sqrt(transmittedCosineSquared);
    }
    float ratio = (ior - 1.0) / (ior + 1.0);
    return primeLiteSchlick(ratio * ratio, cosine);
}

float2 primeLiteReflectiveDirectionalAlbedoBasis(PrimeOpenPbrMicrofacet microfacet,
                                                 float cosineTheta) {
    // Rational fit of the single-scatter GGX directional integral. Keeping only its two Schlick
    // basis coefficients avoids Lite's transmission-energy texture and all of its coordinates.
    float x = clamp(abs(cosineTheta), 0.0, 1.0);
    float y2 = 0.5 * dot(microfacet.alpha, microfacet.alpha);
    float x2 = x * x;
    float y = sqrt(y2);
    float4 r =
            float4(0.1003, 0.9345, 1.0, 1.0) + float4(-0.6303, -2.323, -1.765, 0.2281) * x +
            float4(9.748, 2.229, 8.263, 15.94) * y + float4(-2.038, -3.748, 11.53, -55.83) * x * y +
            float4(29.34, 1.424, 28.96, 13.08) * x2 + float4(-8.245, -0.7684, -7.507, 41.26) * y2 +
            float4(-26.44, 1.436, -36.11, 54.9) * x2 * y +
            float4(19.99, 0.2913, 15.86, 300.2) * x * y2 +
            float4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
    return clamp(r.xy / r.zw, float2(0.0), float2(1.0));
}

public float primeLiteDielectricDirectionalClosure(PrimeOpenPbrMicrofacet microfacet,
                                                   float cosineTheta, float energyIor,
                                                   out float specularScale) {
    float f0 = primeOpenPbrIorToF0(energyIor);
    float2 basis = primeLiteReflectiveDirectionalAlbedoBasis(microfacet, cosineTheta);
    float singleScatterUnity = clamp(basis.x + basis.y, 1.0e-5, 1.0);
    float singleScatter = clamp(f0 * basis.x + basis.y, 0.0, 1.0);
    specularScale = 1.0 + f0 * (1.0 - singleScatterUnity) / singleScatterUnity;
    return clamp(singleScatter * specularScale, 0.0, 1.0);
}

public float3 primeLiteConductorFresnelCore(float3 f0, float3 f82, float3 f90, float weight,
                                            float cosineTheta) {
    // Generalized Schlick retains the authored F82 anchor without a conductor-IOR solve.
    const float anchorCosine = 1.0 / 7.0;
    float anchorTail = primeOpenPbrPow6(1.0 - anchorCosine);
    float3 baseAtAnchor = lerp(f0, f90, primeOpenPbrPow5(1.0 - anchorCosine));
    float3 correction = baseAtAnchor * (float3(1.0) - f82) / (anchorCosine * anchorTail);
    float cosine = clamp(abs(cosineTheta), 0.0, 1.0);
    float oneMinusCosine = 1.0 - cosine;
    return clamp((lerp(f0, f90, primeOpenPbrPow5(oneMinusCosine)) -
                  correction * cosine * primeOpenPbrPow6(oneMinusCosine)) *
                         weight,
                 float3(0.0), float3(1.0));
}

float3 primeLiteConductorFresnel(PrimeOpenPbrConductorFresnel fresnel, float cosineTheta) {
    return primeLiteConductorFresnelCore(fresnel.f0, fresnel.f82, fresnel.f90, fresnel.weight,
                                         cosineTheta);
}

public PrimeOpenPbrRefractResult primeLiteDielectricRefract(float ior, float3 incident,
                                                            float3 normal) {
    float cosine = dot(normal, incident);
    float inverseEta;
    if (cosine < 0.0) {
        inverseEta = 1.0 / ior;
    } else {
        cosine = -cosine;
        normal = -normal;
        inverseEta = ior;
    }
    float transmittedCosineSquared = 1.0 - (1.0 - cosine * cosine) * inverseEta * inverseEta;
    PrimeOpenPbrRefractResult result;
    result.valid = transmittedCosineSquared > 0.0 ? 1u : 0u;
    result.relativeIor = 1.0 / inverseEta;
    result.wo = inverseEta * incident -
                (inverseEta * cosine + sqrt(max(0.0, transmittedCosineSquared))) * normal;
    return result;
}

public PrimeOpenPbrVecPair primeLiteFresnelRtCore(float ior, float3 color, float cosineTheta) {
    float reflection = primeLiteDielectricFresnel(ior, cosineTheta);
    PrimeOpenPbrVecPair result;
    result.first = float3(reflection) * (cosineTheta >= 0.0 ? color : float3(1.0));
    result.second = float3(1.0 - reflection);
    return result;
}

PrimeOpenPbrVecPair primeLiteFresnelRt(PrimeOpenPbrSpecularFresnel fresnel, float cosineTheta) {
    return primeLiteFresnelRtCore(fresnel.ior, fresnel.color, cosineTheta);
}

public struct PrimeLiteReflectiveBase {
    public float factor;
    public float microCosine;
    public uint flags;
};

public PrimeLiteReflectiveBase primeLiteReflectiveEvalBase(float3 wi, float3 wo,
                                                           PrimeOpenPbrMicrofacet microfacet,
                                                           uint samplingFlags) {
    PrimeLiteReflectiveBase result;
    result.factor = 0.0;
    result.microCosine = 0.0;
    result.flags = PRIME_OPENPBR_FLAG_NONE;
    if (primeLiteMicrofacetEffectivelySmooth(microfacet) || wi.z < 0.0 || wo.z <= 0.0 ||
        (samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION) == 0u) {
        return result;
    }
    float3 sum = wi + wo;
    float lengthSquared = dot(sum, sum);
    if (lengthSquared == 0.0) {
        return result;
    }
    float3 halfVector = sum * rsqrt(lengthSquared);
    result.microCosine = dot(wi, halfVector);
    if (result.microCosine <= 0.0) {
        return result;
    }
    result.factor = primeLiteMicrofacetD(microfacet, halfVector) *
                    primeLiteMicrofacetG2(microfacet, wi, wo) / (4.0 * abs(wi.z));
    result.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    return result;
}

public PrimeOpenPbrSample primeLiteReflectiveSampleBase(float3 wi, float2 randomValue,
                                                        PrimeOpenPbrMicrofacet microfacet,
                                                        uint samplingFlags, out float microCosine) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    microCosine = 0.0;
    if (wi.z < 0.0) {
        return result;
    }
    bool smooth = primeLiteMicrofacetEffectivelySmooth(microfacet);
    if (smooth && !primeOpenPbrIsDelta(samplingFlags)) {
        return result;
    }
    if (!smooth && !primeOpenPbrIsSpecular(samplingFlags)) {
        return result;
    }
    float3 halfVector =
            smooth ? float3(0.0, 0.0, 1.0) : primeLiteMicrofacetSample(microfacet, wi, randomValue);
    microCosine = dot(wi, halfVector);
    float3 wo = reflect(-wi, halfVector);
    if (microCosine <= 0.0 || wo.z <= 0.0) {
        return primeOpenPbrZeroSample();
    }
    float3 reconstructedHalf = wi + wo;
    if (dot(reconstructedHalf, reconstructedHalf) == 0.0 || dot(wi, reconstructedHalf) <= 0.0) {
        // At the f32 tangent boundary reflection can cancel wi + wo enough to leave the
        // evaluation support even though the sampled half vector was valid.
        return primeOpenPbrZeroSample();
    }
    result.wo = wo;
    result.eta = 1.0;
    if (smooth) {
        result.throughput.value = float3(1.0);
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
        result.pdf = 1.0;
        return result;
    }
    float halfPdf = primeLiteMicrofacetPdf(microfacet, wi, halfVector);
    result.throughput.value = float3(primeLiteMicrofacetD(microfacet, halfVector) *
                                     primeLiteMicrofacetG2(microfacet, wi, wo) / (4.0 * abs(wi.z)));
    result.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    result.pdf = halfPdf / (4.0 * microCosine);
    return result;
}

public float primeLiteReflectivePdf(float3 wi, float3 wo, PrimeOpenPbrMicrofacet microfacet) {
    if (primeLiteMicrofacetEffectivelySmooth(microfacet) || wi.z < 0.0 || wo.z <= 0.0) {
        return 0.0;
    }
    float3 sum = wi + wo;
    float lengthSquared = dot(sum, sum);
    if (lengthSquared == 0.0) {
        return 0.0;
    }
    float3 halfVector = sum * rsqrt(lengthSquared);
    float microCosine = dot(wi, halfVector);
    return microCosine > 0.0
                   ? primeLiteMicrofacetPdf(microfacet, wi, halfVector) / (4.0 * microCosine)
                   : 0.0;
}

public PrimeOpenPbrThroughput primeLiteLambertEvalCore(float3 wi, float3 wo, float3 color,
                                                       uint samplingFlags) {
    if (wi.z < 0.0 || wo.z < 0.0 || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value = color * (PRIME_OPENPBR_INV_PI * wo.z);
    result.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    return result;
}

PrimeOpenPbrThroughput primeLiteLambertEval(float3 wi, float3 wo, PrimeLiteOpaqueState state,
                                            uint samplingFlags) {
    return primeLiteLambertEvalCore(wi, wo, state.baseColor, samplingFlags);
}

float primeLiteLambertPdf(float3 wi, float3 wo) {
    return wi.z >= 0.0 && wo.z > 0.0 ? PRIME_OPENPBR_INV_PI * wo.z : 0.0;
}

public PrimeOpenPbrSample primeLiteLambertSampleCore(float3 wi, float2 randomValue, float3 color,
                                                     uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (wi.z < 0.0 || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    result.wo = primeOpenPbrCosineSampleHemisphere(randomValue);
    result.pdf = PRIME_OPENPBR_INV_PI * result.wo.z;
    result.throughput.value = color * result.pdf;
    result.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    return result;
}

PrimeOpenPbrSampleResult primeLiteLambertSample(float3 wi, float3 randomValue,
                                                PrimeLiteOpaqueState state, uint samplingFlags,
                                                PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeLiteZeroSampleResult(state.rayT, stack);
    result.bsdfSample =
            primeLiteLambertSampleCore(wi, randomValue.xy, state.baseColor, samplingFlags);
    return result;
}

public float primeLiteSubsurfaceReflectionProbabilityCore(uint thinWalled, float anisotropy,
                                                          uint samplingFlags) {
    float probability = thinWalled != 0u ? 0.5 * (1.0 - anisotropy) : 0.0;
    return (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u ? probability : 0.0;
}

float primeLiteSubsurfaceReflectionProbability(PrimeLiteOpaqueState state, uint samplingFlags) {
    return primeLiteSubsurfaceReflectionProbabilityCore(state.thinWalled,
                                                        state.subsurfaceAnisotropy, samplingFlags);
}

public float primeLiteSubsurfaceTransmissionProbabilityCore(uint thinWalled, float anisotropy,
                                                            uint samplingFlags) {
    float probability = thinWalled != 0u ? 0.5 * (1.0 + anisotropy) : 1.0;
    return (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION) != 0u ? probability : 0.0;
}

float primeLiteSubsurfaceTransmissionProbability(PrimeLiteOpaqueState state, uint samplingFlags) {
    return primeLiteSubsurfaceTransmissionProbabilityCore(
            state.thinWalled, state.subsurfaceAnisotropy, samplingFlags);
}

public PrimeOpenPbrThroughput primeLiteSubsurfaceEvalCore(float3 wi, float3 wo, uint thinWalled,
                                                          float3 color, float anisotropy,
                                                          uint samplingFlags) {
    bool reflected = wi.z * wo.z > 0.0;
    float probability = reflected ? primeLiteSubsurfaceReflectionProbabilityCore(
                                            thinWalled, anisotropy, samplingFlags)
                                  : primeLiteSubsurfaceTransmissionProbabilityCore(
                                            thinWalled, anisotropy, samplingFlags);
    if (probability == 0.0) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    float3 effectiveColor = thinWalled != 0u ? color : float3(1.0);
    result.value = effectiveColor * (probability * PRIME_OPENPBR_INV_PI * abs(wo.z));
    result.flags = reflected ? PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION
                             : PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
    return result;
}

PrimeOpenPbrThroughput primeLiteSubsurfaceEval(float3 wi, float3 wo, PrimeLiteOpaqueState state,
                                               uint samplingFlags) {
    return primeLiteSubsurfaceEvalCore(wi, wo, state.thinWalled, state.subsurfaceColor,
                                       state.subsurfaceAnisotropy, samplingFlags);
}

public float primeLiteSubsurfacePdfCore(float3 wi, float3 wo, uint thinWalled, float anisotropy,
                                        uint samplingFlags) {
    float probability = wi.z * wo.z > 0.0 ? primeLiteSubsurfaceReflectionProbabilityCore(
                                                    thinWalled, anisotropy, samplingFlags)
                                          : primeLiteSubsurfaceTransmissionProbabilityCore(
                                                    thinWalled, anisotropy, samplingFlags);
    return probability * PRIME_OPENPBR_INV_PI * abs(wo.z);
}

float primeLiteSubsurfacePdf(float3 wi, float3 wo, PrimeLiteOpaqueState state, uint samplingFlags) {
    return primeLiteSubsurfacePdfCore(wi, wo, state.thinWalled, state.subsurfaceAnisotropy,
                                      samplingFlags);
}

public PrimeOpenPbrSample primeLiteSubsurfaceSampleCore(float3 wi, float3 randomValue,
                                                        uint thinWalled, float3 color,
                                                        float anisotropy, uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    float reflection =
            primeLiteSubsurfaceReflectionProbabilityCore(thinWalled, anisotropy, samplingFlags);
    float transmission =
            primeLiteSubsurfaceTransmissionProbabilityCore(thinWalled, anisotropy, samplingFlags);
    float sum = reflection + transmission;
    if (sum == 0.0) {
        return result;
    }
    reflection /= sum;
    transmission /= sum;
    result.wo = primeOpenPbrCosineSampleHemisphere(randomValue.xy);
    if (wi.z < 0.0) {
        result.wo.z = -result.wo.z;
    }
    bool transmitted = randomValue.z < transmission;
    if (transmitted) {
        result.wo.z = -result.wo.z;
    }
    float eventProbability = transmitted ? transmission : reflection;
    result.pdf = eventProbability * PRIME_OPENPBR_INV_PI * abs(result.wo.z);
    float3 effectiveColor = thinWalled != 0u ? color : float3(1.0);
    result.throughput.value = effectiveColor * result.pdf;
    result.throughput.flags = transmitted ? PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION
                                          : PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    return result;
}

PrimeOpenPbrSampleResult primeLiteSubsurfaceSample(float3 wi, float3 randomValue,
                                                   PrimeLiteOpaqueState state, uint samplingFlags,
                                                   PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeLiteZeroSampleResult(state.rayT, stack);
    result.bsdfSample =
            primeLiteSubsurfaceSampleCore(wi, randomValue, state.thinWalled, state.subsurfaceColor,
                                          state.subsurfaceAnisotropy, samplingFlags);
    return result;
}

public float3 primeLiteSubsurfaceEnergyCore(uint thinWalled, float3 color) {
    return thinWalled != 0u ? color : float3(1.0);
}

float3 primeLiteSubsurfaceEnergy(PrimeLiteOpaqueState state) {
    return primeLiteSubsurfaceEnergyCore(state.thinWalled, state.subsurfaceColor);
}

public PrimeOpenPbrThroughput primeLiteBaseEvalCore(float3 wi, float3 wo, uint kind,
                                                    float3 baseColor, float subsurfaceWeight,
                                                    uint thinWalled, float3 subsurfaceColor,
                                                    float anisotropy, uint samplingFlags) {
    if (kind == PRIME_LITE_OPAQUE_DIELECTRIC) {
        return primeLiteLambertEvalCore(wi, wo, baseColor, samplingFlags);
    }
    PrimeOpenPbrThroughput subsurface = primeLiteSubsurfaceEvalCore(
            wi, wo, thinWalled, subsurfaceColor, anisotropy, samplingFlags);
    if (kind == PRIME_LITE_OPAQUE_SUBSURFACE) {
        return subsurface;
    }
    PrimeOpenPbrThroughput diffuse = primeLiteLambertEvalCore(wi, wo, baseColor, samplingFlags);
    return primeOpenPbrThroughputAdd(primeOpenPbrThroughputScale(diffuse, 1.0 - subsurfaceWeight),
                                     primeOpenPbrThroughputScale(subsurface, subsurfaceWeight));
}

PrimeOpenPbrThroughput primeLiteBaseEval(float3 wi, float3 wo, PrimeLiteOpaqueState state,
                                         uint samplingFlags) {
    return primeLiteBaseEvalCore(wi, wo, state.kind, state.baseColor, state.subsurfaceWeight,
                                 state.thinWalled, state.subsurfaceColor,
                                 state.subsurfaceAnisotropy, samplingFlags);
}

public float primeLiteBasePdfCore(float3 wi, float3 wo, uint kind, uint thinWalled,
                                  float anisotropy, PrimeLiteMixState diffuseMix,
                                  uint samplingFlags) {
    if (kind == PRIME_LITE_OPAQUE_DIELECTRIC) {
        return (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
                       ? primeLiteLambertPdf(wi, wo)
                       : 0.0;
    }
    if (kind == PRIME_LITE_OPAQUE_SUBSURFACE) {
        return primeLiteSubsurfacePdfCore(wi, wo, thinWalled, anisotropy, samplingFlags);
    }
    float diffuse = (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
                            ? primeLiteLambertPdf(wi, wo)
                            : 0.0;
    float subsurface = primeLiteSubsurfacePdfCore(wi, wo, thinWalled, anisotropy, samplingFlags);
    return diffuseMix.firstSampleWeight * diffuse + diffuseMix.secondSampleWeight * subsurface;
}

float primeLiteBasePdf(float3 wi, float3 wo, PrimeLiteOpaqueState state, uint samplingFlags) {
    return primeLiteBasePdfCore(wi, wo, state.kind, state.thinWalled, state.subsurfaceAnisotropy,
                                state.diffuseMix, samplingFlags);
}

public float3 primeLiteBaseEnergyCore(uint kind, float3 baseColor, float subsurfaceWeight,
                                      uint thinWalled, float3 subsurfaceColor) {
    if (kind == PRIME_LITE_OPAQUE_DIELECTRIC) {
        return baseColor;
    }
    if (kind == PRIME_LITE_OPAQUE_SUBSURFACE) {
        return primeLiteSubsurfaceEnergyCore(thinWalled, subsurfaceColor);
    }
    return (1.0 - subsurfaceWeight) * baseColor +
           subsurfaceWeight * primeLiteSubsurfaceEnergyCore(thinWalled, subsurfaceColor);
}

float3 primeLiteBaseEnergy(PrimeLiteOpaqueState state) {
    return primeLiteBaseEnergyCore(state.kind, state.baseColor, state.subsurfaceWeight,
                                   state.thinWalled, state.subsurfaceColor);
}

public PrimeOpenPbrSample primeLiteBaseSampleCore(float3 wi, float3 randomValue, uint kind,
                                                  float3 baseColor, float subsurfaceWeight,
                                                  uint thinWalled, float3 subsurfaceColor,
                                                  float anisotropy, PrimeLiteMixState diffuseMix,
                                                  uint samplingFlags) {
    if (kind == PRIME_LITE_OPAQUE_DIELECTRIC) {
        return primeLiteLambertSampleCore(wi, randomValue.xy, baseColor, samplingFlags);
    }
    if (kind == PRIME_LITE_OPAQUE_SUBSURFACE) {
        return primeLiteSubsurfaceSampleCore(wi, randomValue, thinWalled, subsurfaceColor,
                                             anisotropy, samplingFlags);
    }
    PrimeOpenPbrSample result;
    if (randomValue.z < diffuseMix.secondSampleWeight && diffuseMix.secondSampleWeight > 0.0) {
        float remapped = randomValue.z / diffuseMix.secondSampleWeight;
        result = primeLiteSubsurfaceSampleCore(wi, float3(randomValue.xy, remapped), thinWalled,
                                               subsurfaceColor, anisotropy, samplingFlags);
        result.throughput = primeOpenPbrThroughputScale(result.throughput, subsurfaceWeight);
        result.pdf *= diffuseMix.secondSampleWeight;
        return result;
    }
    result = primeLiteLambertSampleCore(wi, randomValue.xy, baseColor, samplingFlags);
    result.throughput = primeOpenPbrThroughputScale(result.throughput, 1.0 - subsurfaceWeight);
    result.pdf *= diffuseMix.firstSampleWeight;
    return result;
}

PrimeOpenPbrSampleResult primeLiteBaseSample(float3 wi, float3 randomValue,
                                             PrimeLiteOpaqueState state, uint samplingFlags,
                                             PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeLiteZeroSampleResult(state.rayT, stack);
    result.bsdfSample = primeLiteBaseSampleCore(
            wi, randomValue, state.kind, state.baseColor, state.subsurfaceWeight, state.thinWalled,
            state.subsurfaceColor, state.subsurfaceAnisotropy, state.diffuseMix, samplingFlags);
    return result;
}

public PrimeOpenPbrThroughput primeLiteDielectricEvalCore(float3 wi, float3 wo,
                                                          PrimeOpenPbrMicrofacet microfacet,
                                                          float energyIor, float3 color,
                                                          float specularScale, uint samplingFlags) {
    PrimeLiteReflectiveBase base = primeLiteReflectiveEvalBase(wi, wo, microfacet, samplingFlags);
    if (base.flags == PRIME_OPENPBR_FLAG_NONE) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value = color * (primeLiteDielectricFresnel(energyIor, base.microCosine) * base.factor);
    result.flags = base.flags;
    if (primeOpenPbrIsNonDelta(result.flags)) {
        result.value *= specularScale;
    }
    return result;
}

PrimeOpenPbrThroughput primeLiteDielectricEval(float3 wi, float3 wo, PrimeLiteOpaqueState state,
                                               uint samplingFlags) {
    return primeLiteDielectricEvalCore(wi, wo, state.microfacet, state.dielectricFresnel.energyIor,
                                       state.dielectricFresnel.color, state.layer.specularScale,
                                       samplingFlags);
}

PrimeOpenPbrThroughput primeLiteConductorEval(float3 wi, float3 wo, PrimeLiteOpaqueState state,
                                              uint samplingFlags) {
    PrimeLiteReflectiveBase base =
            primeLiteReflectiveEvalBase(wi, wo, state.microfacet, samplingFlags);
    if (base.flags == PRIME_OPENPBR_FLAG_NONE) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value =
            primeLiteConductorFresnel(state.conductorFresnel, base.microCosine) * base.factor;
    result.flags = base.flags;
    return result;
}

PrimeOpenPbrSampleResult primeLiteSpecularSample(float3 wi, float3 randomValue,
                                                 PrimeLiteOpaqueState state,
                                                 PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeLiteZeroSampleResult(state.rayT, stack);
    float microCosine;
    result.bsdfSample = primeLiteReflectiveSampleBase(wi, randomValue.xy, state.microfacet,
                                                      PRIME_OPENPBR_FLAG_ALL, microCosine);
    if (result.bsdfSample.throughput.flags == PRIME_OPENPBR_FLAG_NONE) {
        return result;
    }
    float3 fresnel = state.kind == PRIME_LITE_OPAQUE_CONDUCTOR
                             ? primeLiteConductorFresnel(state.conductorFresnel, microCosine)
                             : state.dielectricFresnel.color *
                                       primeLiteDielectricFresnel(state.dielectricFresnel.energyIor,
                                                                  microCosine);
    result.bsdfSample.throughput.value *= fresnel;
    if (state.kind != PRIME_LITE_OPAQUE_CONDUCTOR &&
        primeOpenPbrIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.layer.specularScale;
    }
    return result;
}

PrimeOpenPbrThroughput primeLiteOpaqueLayerEval(float3 wi, float3 wo, PrimeLiteOpaqueState state,
                                                uint samplingFlags) {
    PrimeOpenPbrThroughput base = primeLiteBaseEval(wi, wo, state, samplingFlags);
    base = primeOpenPbrThroughputScale(base, state.layer.transmittanceIn);
    return primeOpenPbrThroughputAdd(base, primeLiteDielectricEval(wi, wo, state, samplingFlags));
}

float primeLiteOpaquePdf(float3 wi, float3 wo, PrimeLiteOpaqueState state) {
    if (state.kind == PRIME_LITE_OPAQUE_CONDUCTOR) {
        return primeLiteReflectivePdf(wi, wo, state.microfacet);
    }
    float basePdf = primeLiteBasePdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
    float specularPdf = primeLiteReflectivePdf(wi, wo, state.microfacet);
    return primeLiteLayerPdf(state.layer, basePdf, specularPdf);
}

public bool primeLiteOpaqueSupports(PrimeOpenPbrMaterial material) {
    return material.weight.base == 1.0 && material.weight.specular == 1.0 &&
           (material.weight.metalness == 0.0 || material.weight.metalness == 1.0) &&
           material.weight.transmission == 0.0 && material.weight.coat == 0.0 &&
           material.weight.fuzz == 0.0 && material.weight.thinFilm == 0.0 &&
           material.weight.diffraction == 0.0 && material.weight.diffuseRoughness == 0.0 &&
           material.weight.subsurface >= 0.0 && material.weight.subsurface <= 1.0 &&
           (material.weight.subsurface == 0.0 || material.weight.metalness == 0.0);
}

public PrimeLiteOpaqueState primeLiteOpaqueStateInit<Energy>(Energy ignored,
                                                             PrimeOpenPbrMaterial material,
                                                             float3 wi, float inverseOutsideIor,
                                                             float rayT) {
    PrimeLiteOpaqueState state;
    state.onb = material.geometry.onb;
    state.kind = material.weight.metalness == 1.0    ? PRIME_LITE_OPAQUE_CONDUCTOR
                 : material.weight.subsurface == 1.0 ? PRIME_LITE_OPAQUE_SUBSURFACE
                 : material.weight.subsurface > 0.0  ? PRIME_LITE_OPAQUE_MIXED_SUBSURFACE
                                                     : PRIME_LITE_OPAQUE_DIELECTRIC;
    state.thinWalled = material.geometry.thinWalled;
    state.rayT = rayT;
    state.baseColor = material.base.color;
    state.subsurfaceWeight = material.weight.subsurface;
    state.subsurfaceColor = material.subsurface.color;
    state.subsurfaceAnisotropy = material.subsurface.scatterAnisotropy;
    state.microfacet.alpha = primeOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness, material.specular.roughnessAnisotropy);
    float interfaceIor = material.specular.ior * inverseOutsideIor;
    state.dielectricFresnel.ior = interfaceIor;
    state.dielectricFresnel.energyIor =
            primeOpenPbrIorAdjustment(interfaceIor, material.weight.specular);
    state.dielectricFresnel.color = material.specular.color;
    state.conductorFresnel.f0 = material.base.color;
    state.conductorFresnel.f82 = material.specular.color;
    state.conductorFresnel.f90 = float3(1.0);
    state.conductorFresnel.weight = material.weight.specular;
    state.conductorFresnel.energyF0 = clamp(material.base.color, float3(0.0), float3(1.0));

    state.diffuseMix = primeLiteMixState(
            (1.0 - state.subsurfaceWeight) * primeLiteWeight(state.baseColor),
            state.subsurfaceWeight * primeLiteWeight(primeLiteSubsurfaceEnergy(state)));
    float reflectedEnergy;
    state.layer.specularScale = 1.0;
    if (primeLiteMicrofacetEffectivelySmooth(state.microfacet)) {
        reflectedEnergy = primeLiteDielectricFresnel(state.dielectricFresnel.energyIor, abs(wi.z));
    } else {
        reflectedEnergy = primeLiteDielectricDirectionalClosure(state.microfacet, abs(wi.z),
                                                                state.dielectricFresnel.energyIor,
                                                                state.layer.specularScale);
    }
    state.layer.transmittanceIn = 1.0 - reflectedEnergy;
    float3 baseEnergy = primeLiteBaseEnergy(state);
    float3 specularEnergy = state.dielectricFresnel.color * reflectedEnergy;
    PrimeLiteMixState layerMix =
            primeLiteMixState(primeLiteWeight(state.layer.transmittanceIn * baseEnergy),
                              primeLiteWeight(specularEnergy));
    state.layer.baseSampleWeight = layerMix.firstSampleWeight;
    state.layer.specularSampleWeight = layerMix.secondSampleWeight;
    return state;
}

public PrimeLiteOpaqueEvaluation primeLiteOpaqueEvaluate(float3 wi, float3 wo,
                                                         PrimeLiteOpaqueState state) {
    PrimeLiteOpaqueEvaluation result;
    result.throughput = state.kind == PRIME_LITE_OPAQUE_CONDUCTOR
                                ? primeLiteConductorEval(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
                                : primeLiteOpaqueLayerEval(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
    result.pdf = primeLiteOpaquePdf(wi, wo, state);
    return result;
}

public PrimeLiteOpaqueComponents primeLiteOpaqueEvaluateComponents(float3 wi, float3 wo,
                                                                   PrimeLiteOpaqueState state) {
    PrimeLiteOpaqueComponents result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (state.kind == PRIME_LITE_OPAQUE_CONDUCTOR) {
        result.specular = primeLiteConductorEval(wi, wo, state, PRIME_OPENPBR_FLAG_SPECULAR).value;
    } else {
        result.diffuse = primeLiteOpaqueLayerEval(wi, wo, state, PRIME_OPENPBR_FLAG_DIFFUSE).value;
        result.specular =
                primeLiteOpaqueLayerEval(wi, wo, state, PRIME_OPENPBR_FLAG_SPECULAR).value;
    }
    result.pdf = primeLiteOpaquePdf(wi, wo, state);
    return result;
}

public PrimeOpenPbrSampleResult primeLiteOpaqueSample(float3 wi, float3 randomValue,
                                                      PrimeLiteOpaqueState state,
                                                      PrimeOpenPbrVolumeStack stack) {
    if (state.kind == PRIME_LITE_OPAQUE_CONDUCTOR) {
        return primeLiteSpecularSample(wi, randomValue, state, stack);
    }
    if (randomValue.z < state.layer.specularSampleWeight &&
        state.layer.specularSampleWeight > 0.0) {
        float remapped = randomValue.z / state.layer.specularSampleWeight;
        PrimeOpenPbrSampleResult result =
                primeLiteSpecularSample(wi, float3(randomValue.xy, remapped), state, stack);
        result.bsdfSample.pdf *= state.layer.specularSampleWeight;
        return result;
    }
    float remapped = state.layer.baseSampleWeight > 0.0
                             ? (randomValue.z - state.layer.specularSampleWeight) /
                                       state.layer.baseSampleWeight
                             : 0.0;
    PrimeOpenPbrSampleResult result = primeLiteBaseSample(wi, float3(randomValue.xy, remapped),
                                                          state, PRIME_OPENPBR_FLAG_ALL, stack);
    result.bsdfSample.throughput =
            primeOpenPbrThroughputScale(result.bsdfSample.throughput, state.layer.transmittanceIn);
    result.bsdfSample.pdf *= state.layer.baseSampleWeight;
    return result;
}

public PrimeOpenPbrSampleResult primeLiteOpaqueSampleWithEonDiffuse(float3 wi, float3 randomValue,
                                                                    PrimeLiteOpaqueState state,
                                                                    PrimeOpenPbrVolumeStack stack) {
    return primeLiteOpaqueSample(wi, randomValue, state, stack);
}

public PrimeLiteOpaqueEnergy primeLiteOpaqueEnergy(float3 wi, PrimeLiteOpaqueState state) {
    PrimeLiteOpaqueEnergy result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (wi.z < 0.0) {
        return result;
    }
    if (state.kind == PRIME_LITE_OPAQUE_CONDUCTOR) {
        result.specular = primeLiteConductorFresnel(state.conductorFresnel, abs(wi.z));
        return result;
    }
    result.diffuse = primeLiteBaseEnergy(state) * state.layer.transmittanceIn;
    result.specular = state.dielectricFresnel.color * (1.0 - state.layer.transmittanceIn);
    return result;
}

public bool primeLiteTransmissionSupports(PrimeOpenPbrMaterial material) {
    return material.weight.base == 1.0 && material.weight.diffuseRoughness == 0.0 &&
           material.weight.specular == 1.0 && material.weight.metalness == 0.0 &&
           material.weight.subsurface == 0.0 && material.weight.transmission == 1.0 &&
           material.weight.coat == 0.0 && material.weight.fuzz == 0.0 &&
           material.weight.thinFilm == 0.0 && material.weight.diffraction == 0.0 &&
           material.transmission.dispersionScale == 0.0;
}

public PrimeLiteTransmissionState
primeLiteTransmissionStateInit<Energy>(Energy ignored, PrimeOpenPbrMaterial material, float3 wi,
                                       float inverseOutsideIor, float rayT) {
    PrimeLiteTransmissionState state;
    state.onb = material.geometry.onb;
    state.geometryThinWalled = material.geometry.thinWalled;
    state.entering = wi.z >= 0.0 ? 1u : 0u;
    state.rayT = rayT;
    state.originalIor = material.specular.ior;
    state.transmissionColor = material.transmission.color;
    state.specularMicrofacet.alpha = primeOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness, material.specular.roughnessAnisotropy);
    state.transmissionMicrofacet = state.specularMicrofacet;
    state.specularFresnel.ior = material.specular.ior * inverseOutsideIor;
    state.specularFresnel.energyIor =
            primeOpenPbrIorAdjustment(state.specularFresnel.ior, material.weight.specular);
    state.specularFresnel.color = material.specular.color;
    state.transmissionTint =
            material.transmission.depth == 0.0 ? material.transmission.color : float3(1.0);
    state.transmissionVolume = primeOpenPbrVolumeFromTransmission(material.transmission);
    return state;
}

public PrimeLiteTransmissionState
primeLiteTransmissionInterfaceState<Energy>(Energy ignored, float3 wi,
                                            PrimeLiteTransmissionState state) {
    return state;
}

float3 primeLiteTransmissionFrame(float3 direction, PrimeLiteTransmissionState state) {
    return state.entering != 0u ? direction : float3(-direction.x, direction.y, -direction.z);
}

public PrimeOpenPbrVecPair primeLiteThinWallRtCore(float cosineTheta, float ior, float3 color,
                                                   float3 tint, float3 extinction) {
    PrimeOpenPbrVecPair fresnel = primeLiteFresnelRtCore(ior, color, cosineTheta);
    float transmittedCosineSquared =
            1.0 - (1.0 - cosineTheta * cosineTheta) / primeOpenPbrSquare(ior);
    if (transmittedCosineSquared <= 0.0) {
        // The explicit interface endpoints also permit a lower-IOR slab. Its first
        // interface is totally internally reflecting; positive-cosine Schlick alone
        // would incorrectly leave a nonzero transmission probability here.
        fresnel.first = float3(1.0);
        fresnel.second = float3(0.0);
        return fresnel;
    }
    float transmittedCosine = sqrt(transmittedCosineSquared);
    float3 absorption = tint * exp(-extinction / transmittedCosine);
    float3 roundTrip = fresnel.first * absorption;
    float3 series = float3(1.0) /
                    max(float3(PRIME_OPENPBR_DENOM_TOLERANCE), float3(1.0) - roundTrip * roundTrip);
    PrimeOpenPbrVecPair result;
    result.first = fresnel.first * (float3(1.0) + fresnel.second * fresnel.second * absorption *
                                                          absorption * series);
    result.second = fresnel.second * fresnel.second * absorption * series;
    return result;
}

PrimeOpenPbrVecPair primeLiteThinWallRt(float cosineTheta, PrimeLiteTransmissionState state) {
    return primeLiteThinWallRtCore(cosineTheta, state.specularFresnel.ior,
                                   state.specularFresnel.color, state.transmissionTint,
                                   state.transmissionVolume.extinction);
}

public float primeLiteTransmissionDirectionalReflectance(PrimeLiteTransmissionState state,
                                                         float3 localView) {
    if (state.geometryThinWalled != 0u) {
        return primeLiteWeight(primeLiteThinWallRt(abs(localView.z), state).first);
    }
    return primeLiteDielectricFresnel(state.specularFresnel.ior, localView.z);
}

public PrimeOpenPbrThroughput primeLiteRefractiveEvalCore(float3 wi, float3 wo,
                                                          PrimeOpenPbrMicrofacet microfacet,
                                                          float ior, float3 color, float3 tint,
                                                          uint samplingFlags) {
    PrimeOpenPbrThroughput result = primeOpenPbrZeroThroughput();
    if (primeLiteMicrofacetEffectivelySmooth(microfacet) || ior == 1.0) {
        return result;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? ior : 1.0 / ior);
    float3 sum = wi + etaPath * wo;
    float lengthSquared = dot(sum, sum);
    if (wi.z == 0.0 || wo.z == 0.0 || lengthSquared == 0.0) {
        return result;
    }
    float3 halfVector = sum * rsqrt(lengthSquared);
    if (halfVector.z < 0.0) {
        halfVector = -halfVector;
    }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z <= 0.0 || microOutgoing * wo.z <= 0.0) {
        return result;
    }
    PrimeOpenPbrVecPair rt = primeLiteFresnelRtCore(ior, color, microIncident);
    float distribution = primeLiteMicrofacetD(microfacet, halfVector);
    float geometry = primeLiteMicrofacetG2(microfacet, wi, wo);
    if (reflected && (samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION) != 0u) {
        result.value = rt.first * distribution * geometry / (4.0 * abs(wi.z));
        result.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    } else if (!reflected && (samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION) != 0u) {
        float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath);
        result.value = rt.second * tint * distribution * geometry *
                       abs(microIncident * microOutgoing / (wi.z * denominator)) /
                       primeOpenPbrSquare(etaPath);
        result.flags = PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
    }
    return result;
}

PrimeOpenPbrThroughput primeLiteRefractiveEval(float3 wi, float3 wo,
                                               PrimeLiteTransmissionState state,
                                               uint samplingFlags) {
    return primeLiteRefractiveEvalCore(wi, wo, state.specularMicrofacet, state.specularFresnel.ior,
                                       state.specularFresnel.color, state.transmissionTint,
                                       samplingFlags);
}

public float primeLiteRefractivePdfCore(float3 wi, float3 wo, PrimeOpenPbrMicrofacet microfacet,
                                        float ior, float3 color, uint samplingFlags) {
    if (primeLiteMicrofacetEffectivelySmooth(microfacet) || ior == 1.0 ||
        !primeOpenPbrIsSpecular(samplingFlags)) {
        return 0.0;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? ior : 1.0 / ior);
    float3 sum = wi + etaPath * wo;
    float lengthSquared = dot(sum, sum);
    if (wi.z == 0.0 || wo.z == 0.0 || lengthSquared == 0.0) {
        return 0.0;
    }
    float3 halfVector = sum * rsqrt(lengthSquared);
    if (halfVector.z < 0.0) {
        halfVector = -halfVector;
    }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z <= 0.0 || microOutgoing * wo.z <= 0.0) {
        return 0.0;
    }
    PrimeOpenPbrVecPair rt = primeLiteFresnelRtCore(ior, color, microIncident);
    float reflectionWeight =
            primeOpenPbrIsReflective(samplingFlags) ? primeLiteWeight(rt.first) : 0.0;
    float transmissionWeight =
            primeOpenPbrIsTransmissive(samplingFlags) ? primeLiteWeight(rt.second) : 0.0;
    float sumWeight = reflectionWeight + transmissionWeight;
    if (sumWeight == 0.0) {
        return 0.0;
    }
    float3 sampleDirection = wi.z > 0.0 ? wi : -wi;
    float halfPdf = primeLiteMicrofacetPdf(microfacet, sampleDirection, halfVector);
    if (reflected) {
        return halfPdf / (4.0 * abs(microIncident)) * reflectionWeight / sumWeight;
    }
    float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath);
    return halfPdf * abs(microOutgoing) / denominator * transmissionWeight / sumWeight;
}

float primeLiteRefractivePdf(float3 wi, float3 wo, PrimeLiteTransmissionState state,
                             uint samplingFlags) {
    return primeLiteRefractivePdfCore(wi, wo, state.specularMicrofacet, state.specularFresnel.ior,
                                      state.specularFresnel.color, samplingFlags);
}

public PrimeOpenPbrSample primeLiteRefractiveSampleCore(float3 wi, float3 randomValue,
                                                        PrimeOpenPbrMicrofacet microfacet,
                                                        float ior, float3 color, float3 tint,
                                                        uint samplingFlags) {
    bool smooth = primeLiteMicrofacetEffectivelySmooth(microfacet) || ior == 1.0;
    if (smooth && !primeOpenPbrIsDelta(samplingFlags)) {
        return primeOpenPbrZeroSample();
    }
    if (!smooth && !primeOpenPbrIsSpecular(samplingFlags)) {
        return primeOpenPbrZeroSample();
    }
    float3 sampleDirection = wi.z > 0.0 ? wi : -wi;
    float3 halfVector =
            smooth ? float3(0.0, 0.0, 1.0)
                   : primeLiteMicrofacetSample(microfacet, sampleDirection, randomValue.xy);
    float microIncident = dot(halfVector, wi);
    PrimeOpenPbrVecPair rt = primeLiteFresnelRtCore(ior, color, microIncident);
    float reflectionWeight =
            primeOpenPbrIsReflective(samplingFlags) ? primeLiteWeight(rt.first) : 0.0;
    float transmissionWeight =
            primeOpenPbrIsTransmissive(samplingFlags) ? primeLiteWeight(rt.second) : 0.0;
    PrimeOpenPbrRefractResult refracted = primeLiteDielectricRefract(ior, -wi, halfVector);
    if (refracted.valid == 0u) {
        transmissionWeight = 0.0;
        rt.first = float3(1.0);
    }
    float weightSum = reflectionWeight + transmissionWeight;
    if (weightSum == 0.0) {
        return primeOpenPbrZeroSample();
    }
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (randomValue.z < transmissionWeight / weightSum) {
        float3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) {
            return result;
        }
        result.wo = wo;
        result.eta = refracted.relativeIor;
        result.throughput.value = rt.second * tint / primeOpenPbrSquare(result.eta);
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionWeight / weightSum;
        if (!smooth) {
            float microOutgoing = dot(wo, halfVector);
            float denominator = primeOpenPbrSquare(microOutgoing + microIncident / result.eta);
            float halfPdf = primeLiteMicrofacetPdf(microfacet, sampleDirection, halfVector);
            result.pdf *= halfPdf * abs(microOutgoing) / denominator;
            result.throughput.value = rt.second * tint *
                                      primeLiteMicrofacetD(microfacet, halfVector) *
                                      primeLiteMicrofacetG2(microfacet, wi, wo) *
                                      abs(microIncident * microOutgoing / (wi.z * denominator)) /
                                      primeOpenPbrSquare(result.eta);
            result.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
        }
        return result;
    }
    float3 wo = reflect(-wi, halfVector);
    if (wi.z * wo.z < 0.0) {
        return result;
    }
    float3 reconstructedHalf = wi + wo;
    // wi + wo still faces wi here. Multiplying this dot by a negative exiting wi.z
    // would reject every exiting reflection. Side checks follow after canonicalizing
    // the half vector toward +z, exactly as in evaluation.
    if (dot(reconstructedHalf, reconstructedHalf) == 0.0 || dot(wi, reconstructedHalf) <= 0.0) {
        return primeOpenPbrZeroSample();
    }
    float3 evaluationHalf = normalize(reconstructedHalf);
    if (evaluationHalf.z < 0.0) {
        evaluationHalf = -evaluationHalf;
    }
    if (dot(wi, evaluationHalf) * wi.z <= 0.0 || dot(wo, evaluationHalf) * wo.z <= 0.0) {
        return primeOpenPbrZeroSample();
    }
    result.wo = wo;
    result.eta = 1.0;
    result.throughput.value = rt.first;
    result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
    result.pdf = reflectionWeight / weightSum;
    if (!smooth) {
        float halfPdf = primeLiteMicrofacetPdf(microfacet, sampleDirection, halfVector);
        result.pdf *= halfPdf / (4.0 * abs(microIncident));
        result.throughput.value = rt.first * primeLiteMicrofacetD(microfacet, halfVector) *
                                  primeLiteMicrofacetG2(microfacet, wi, wo) / (4.0 * abs(wi.z));
        result.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    }
    return result;
}

PrimeOpenPbrSample primeLiteRefractiveSample(float3 wi, float3 randomValue,
                                             PrimeLiteTransmissionState state, uint samplingFlags) {
    return primeLiteRefractiveSampleCore(wi, randomValue, state.specularMicrofacet,
                                         state.specularFresnel.ior, state.specularFresnel.color,
                                         state.transmissionTint, samplingFlags);
}

public PrimeOpenPbrThroughput primeLiteThinWallEvalCore(float3 wi, float3 inputWo,
                                                        PrimeOpenPbrMicrofacet microfacet,
                                                        float ior, float3 color, float3 tint,
                                                        float3 extinction, uint samplingFlags) {
    float3 wo = inputWo;
    bool transmitted = wo.z < 0.0;
    if (transmitted) {
        if (!primeOpenPbrIsTransmissive(samplingFlags)) {
            return primeOpenPbrZeroThroughput();
        }
        wo.z = -wo.z;
    } else if (!primeOpenPbrIsReflective(samplingFlags)) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeLiteReflectiveBase base =
            primeLiteReflectiveEvalBase(wi, wo, microfacet, PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION);
    if (base.flags == PRIME_OPENPBR_FLAG_NONE) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrVecPair rt =
            primeLiteThinWallRtCore(base.microCosine, ior, color, tint, extinction);
    PrimeOpenPbrThroughput result;
    result.value = base.factor * (transmitted ? rt.second : rt.first);
    result.flags = transmitted ? PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION
                               : PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    return result;
}

PrimeOpenPbrThroughput primeLiteThinWallEval(float3 wi, float3 inputWo,
                                             PrimeLiteTransmissionState state, uint samplingFlags) {
    return primeLiteThinWallEvalCore(wi, inputWo, state.transmissionMicrofacet,
                                     state.specularFresnel.ior, state.specularFresnel.color,
                                     state.transmissionTint, state.transmissionVolume.extinction,
                                     samplingFlags);
}

public float primeLiteThinWallPdfCore(float3 wi, float3 inputWo, PrimeOpenPbrMicrofacet microfacet,
                                      float ior, float3 color, float3 tint, float3 extinction,
                                      uint samplingFlags) {
    float3 wo = inputWo;
    bool transmitted = wo.z < 0.0;
    if (transmitted) {
        wo.z = -wo.z;
    }
    float3 sum = wi + wo;
    float lengthSquared = dot(sum, sum);
    if (lengthSquared == 0.0) {
        return 0.0;
    }
    float3 halfVector = sum * rsqrt(lengthSquared);
    PrimeOpenPbrVecPair rt =
            primeLiteThinWallRtCore(dot(wi, halfVector), ior, color, tint, extinction);
    float reflectionWeight =
            primeOpenPbrIsReflective(samplingFlags) ? primeLiteWeight(rt.first) : 0.0;
    float transmissionWeight =
            primeOpenPbrIsTransmissive(samplingFlags) ? primeLiteWeight(rt.second) : 0.0;
    float weightSum = reflectionWeight + transmissionWeight;
    if (weightSum == 0.0) {
        return 0.0;
    }
    float directionPdf = primeLiteReflectivePdf(wi, wo, microfacet);
    return directionPdf * (transmitted ? transmissionWeight : reflectionWeight) / weightSum;
}

float primeLiteThinWallPdf(float3 wi, float3 inputWo, PrimeLiteTransmissionState state,
                           uint samplingFlags) {
    return primeLiteThinWallPdfCore(wi, inputWo, state.transmissionMicrofacet,
                                    state.specularFresnel.ior, state.specularFresnel.color,
                                    state.transmissionTint, state.transmissionVolume.extinction,
                                    samplingFlags);
}

public PrimeOpenPbrSample primeLiteThinWallSampleCore(float3 wi, float3 randomValue,
                                                      PrimeOpenPbrMicrofacet microfacet, float ior,
                                                      float3 color, float3 tint, float3 extinction,
                                                      uint samplingFlags) {
    float microCosine;
    PrimeOpenPbrSample result = primeLiteReflectiveSampleBase(wi, randomValue.xy, microfacet,
                                                              samplingFlags, microCosine);
    if (result.throughput.flags == PRIME_OPENPBR_FLAG_NONE) {
        return result;
    }
    PrimeOpenPbrVecPair rt = primeLiteThinWallRtCore(microCosine, ior, color, tint, extinction);
    float reflectionWeight =
            primeOpenPbrIsReflective(samplingFlags) ? primeLiteWeight(rt.first) : 0.0;
    float transmissionWeight =
            primeOpenPbrIsTransmissive(samplingFlags) ? primeLiteWeight(rt.second) : 0.0;
    float weightSum = reflectionWeight + transmissionWeight;
    if (weightSum == 0.0) {
        return primeOpenPbrZeroSample();
    }
    bool transmitted = randomValue.z < transmissionWeight / weightSum;
    result.throughput.value *= transmitted ? rt.second : rt.first;
    result.pdf *= (transmitted ? transmissionWeight : reflectionWeight) / weightSum;
    if (transmitted) {
        result.wo.z = -result.wo.z;
        result.throughput.flags = primeOpenPbrIsDelta(result.throughput.flags)
                                          ? PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION
                                          : PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
    }
    return result;
}

PrimeOpenPbrSample primeLiteThinWallSample(float3 wi, float3 randomValue,
                                           PrimeLiteTransmissionState state, uint samplingFlags) {
    return primeLiteThinWallSampleCore(wi, randomValue, state.transmissionMicrofacet,
                                       state.specularFresnel.ior, state.specularFresnel.color,
                                       state.transmissionTint, state.transmissionVolume.extinction,
                                       samplingFlags);
}

public PrimeLiteTransmissionEvaluation
primeLiteTransmissionEvaluate(float3 wi, float3 wo, PrimeLiteTransmissionState state,
                              uint samplingFlags) {
    PrimeLiteTransmissionEvaluation result;
    if (state.geometryThinWalled != 0u) {
        result.throughput = primeLiteThinWallEval(wi, wo, state, samplingFlags);
        result.pdf = primeLiteThinWallPdf(wi, wo, state, samplingFlags);
        return result;
    }
    result.throughput = primeLiteRefractiveEval(wi, wo, state, samplingFlags);
    result.pdf = primeLiteRefractivePdf(wi, wo, state, samplingFlags);
    return result;
}

public PrimeOpenPbrSampleResult primeLiteTransmissionSample(float3 wi, float3 randomValue,
                                                            PrimeLiteTransmissionState state,
                                                            uint samplingFlags,
                                                            PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeLiteZeroSampleResult(state.rayT, stack);
    if (state.geometryThinWalled != 0u) {
        result.bsdfSample = primeLiteThinWallSample(wi, randomValue, state, samplingFlags);
        return result;
    }
    result.bsdfSample = primeLiteRefractiveSample(wi, randomValue, state, samplingFlags);
    if (result.bsdfSample.throughput.flags == PRIME_OPENPBR_FLAG_NONE) {
        return result;
    }
    if (primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
        if (state.entering != 0u) {
            PrimeOpenPbrVolume volume = state.transmissionVolume;
            volume.ior = state.originalIor;
            primeOpenPbrStackPush(result.volumeStack, volume);
        } else if (result.volumeStack.count > 0u) {
            result.rayT = 0.0;
            primeOpenPbrStackPop(result.volumeStack);
        }
    }
    return result;
}

public float3 primeLiteTransmissionEnergy<Energy>(Energy ignored, float3 wi,
                                                  PrimeLiteTransmissionState state,
                                                  uint samplingFlags) {
    bool smooth = primeLiteMicrofacetEffectivelySmooth(state.specularMicrofacet) ||
                  state.specularFresnel.ior == 1.0;
    if (smooth && !primeOpenPbrIsDelta(samplingFlags)) {
        return float3(0.0);
    }
    if (!smooth && !primeOpenPbrIsSpecular(samplingFlags)) {
        return float3(0.0);
    }
    float reflection = primeLiteTransmissionDirectionalReflectance(state, wi);
    float3 result = float3(0.0);
    if (primeOpenPbrIsReflective(samplingFlags)) {
        result += reflection * state.specularFresnel.color;
    }
    if (primeOpenPbrIsTransmissive(samplingFlags)) {
        result += (1.0 - reflection) * state.transmissionTint;
    }
    return result;
}

public bool primeLiteFoliageSupports(PrimeOpenPbrMaterial material) {
    return material.weight.base == 1.0 && material.weight.specular == 1.0 &&
           (material.weight.metalness == 0.0 || material.weight.metalness == 1.0) &&
           material.weight.subsurface >= 0.0 && material.weight.subsurface <= 1.0 &&
           (material.weight.subsurface == 0.0 || material.weight.metalness == 0.0) &&
           material.weight.transmission >= 0.0 && material.weight.transmission <= 1.0 &&
           material.weight.coat == 0.0 && material.weight.fuzz == 0.0 &&
           material.weight.thinFilm == 0.0 && material.weight.diffraction == 0.0 &&
           material.weight.diffuseRoughness == 0.0 && material.geometry.thinWalled != 0u &&
           material.transmission.depth == 0.0 && material.transmission.dispersionScale == 0.0;
}

public PrimeLiteFoliageState primeLiteFoliageStateInit<Energy>(Energy ignored,
                                                               PrimeOpenPbrMaterial material,
                                                               float3 wi, float inverseOutsideIor,
                                                               float rayT) {
    PrimeLiteFoliageState state;
    PrimeOpenPbrMaterial baseMaterial = material;
    baseMaterial.weight.transmission = 0.0;
    state.base = primeLiteOpaqueStateInit(ignored, baseMaterial, wi, inverseOutsideIor, rayT);
    state.transmissionTint = material.transmission.color;
    state.transmissionWeight =
            state.base.kind == PRIME_LITE_OPAQUE_CONDUCTOR ? 0.0 : material.weight.transmission;
    PrimeLiteOpaqueEnergy baseEnergy = primeLiteOpaqueEnergy(wi, state.base);
    state.mix =
            primeLiteMixState((1.0 - state.transmissionWeight) *
                                      primeLiteWeight(baseEnergy.diffuse + baseEnergy.specular),
                              state.transmissionWeight * primeLiteWeight(state.transmissionTint));
    return state;
}

PrimeOpenPbrThroughput primeLiteFoliageTransmissionEval(float3 wi, float3 wo,
                                                        PrimeLiteFoliageState state) {
    if (wi.z * wo.z >= 0.0) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value = state.transmissionTint * (PRIME_OPENPBR_INV_PI * abs(wo.z));
    result.flags = PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
    return result;
}

float primeLiteFoliageTransmissionPdf(float3 wi, float3 wo) {
    return wi.z * wo.z < 0.0 ? PRIME_OPENPBR_INV_PI * abs(wo.z) : 0.0;
}

public PrimeLiteFoliageEvaluation primeLiteFoliageEvaluate(float3 wi, float3 wo,
                                                           PrimeLiteFoliageState state) {
    PrimeLiteOpaqueEvaluation base = primeLiteOpaqueEvaluate(wi, wo, state.base);
    PrimeOpenPbrThroughput transmission = primeLiteFoliageTransmissionEval(wi, wo, state);
    PrimeLiteFoliageEvaluation result;
    result.throughput = primeOpenPbrThroughputAdd(
            primeOpenPbrThroughputScale(base.throughput, 1.0 - state.transmissionWeight),
            primeOpenPbrThroughputScale(transmission, state.transmissionWeight));
    result.pdf = state.mix.firstSampleWeight * base.pdf +
                 state.mix.secondSampleWeight * primeLiteFoliageTransmissionPdf(wi, wo);
    return result;
}

public PrimeLiteFoliageComponents primeLiteFoliageEvaluateComponents(float3 wi, float3 wo,
                                                                     PrimeLiteFoliageState state) {
    PrimeLiteOpaqueComponents base = primeLiteOpaqueEvaluateComponents(wi, wo, state.base);
    PrimeOpenPbrThroughput transmission = primeLiteFoliageTransmissionEval(wi, wo, state);
    PrimeLiteFoliageComponents result;
    result.diffuse = (1.0 - state.transmissionWeight) * base.diffuse +
                     state.transmissionWeight * transmission.value;
    result.specular = (1.0 - state.transmissionWeight) * base.specular;
    result.pdf = state.mix.firstSampleWeight * base.pdf +
                 state.mix.secondSampleWeight * primeLiteFoliageTransmissionPdf(wi, wo);
    return result;
}

public PrimeOpenPbrSampleResult primeLiteFoliageSample(float3 wi, float3 randomValue,
                                                       PrimeLiteFoliageState state,
                                                       PrimeOpenPbrVolumeStack stack) {
    if (randomValue.z < state.mix.secondSampleWeight && state.mix.secondSampleWeight > 0.0) {
        PrimeOpenPbrSampleResult result = primeLiteZeroSampleResult(state.base.rayT, stack);
        result.bsdfSample.wo = primeOpenPbrCosineSampleHemisphere(randomValue.xy);
        if (wi.z >= 0.0) {
            result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
        }
        result.bsdfSample.pdf =
                state.mix.secondSampleWeight * PRIME_OPENPBR_INV_PI * abs(result.bsdfSample.wo.z);
        result.bsdfSample.throughput.value = state.transmissionWeight * state.transmissionTint *
                                             (PRIME_OPENPBR_INV_PI * abs(result.bsdfSample.wo.z));
        result.bsdfSample.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
        return result;
    }
    float remapped =
            state.mix.firstSampleWeight > 0.0
                    ? (randomValue.z - state.mix.secondSampleWeight) / state.mix.firstSampleWeight
                    : 0.0;
    PrimeOpenPbrSampleResult result =
            primeLiteOpaqueSample(wi, float3(randomValue.xy, remapped), state.base, stack);
    result.bsdfSample.throughput = primeOpenPbrThroughputScale(result.bsdfSample.throughput,
                                                               1.0 - state.transmissionWeight);
    result.bsdfSample.pdf *= state.mix.firstSampleWeight;
    return result;
}

public PrimeLiteFoliageEnergy primeLiteFoliageEnergy<Energy>(Energy ignored, float3 wi,
                                                             PrimeLiteFoliageState state) {
    PrimeLiteOpaqueEnergy base = primeLiteOpaqueEnergy(wi, state.base);
    PrimeLiteFoliageEnergy result;
    result.diffuse = (1.0 - state.transmissionWeight) * base.diffuse +
                     state.transmissionWeight * state.transmissionTint;
    result.specular = (1.0 - state.transmissionWeight) * base.specular;
    return result;
}

public float primeLiteLayerPdf(PrimeLiteLayerState layer, float basePdf, float specularPdf) {
    return layer.baseSampleWeight * basePdf + layer.specularSampleWeight * specularPdf;
}
