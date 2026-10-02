// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "pbr.slang";
__exported import "pbr/vertex.slang";
__exported import "pbr/guide_albedo.slang";
import "bsdf/full/math/spectrum.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/dielectric/state.slang";
import "bsdf/full/dielectric/refract.slang";
import "bsdf/full/dielectric/evaluate.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/opaque/evaluate.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/opaque/state.slang";

import "model/material/labpbr.slang";
import "model/material/defaults.slang";
import "model/material/optical.slang";
import "model/material/translation.slang";
import "model/material/fresnel_code.slang";
import "bsdf/common/common.slang";
import "bsdf/common/material.slang";
import "bsdf/lite/bsdf.slang";
import "bsdf/lite/pt.slang";
import "bsdf/full/pt.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/opaque/energy.slang";
import "bsdf/full/dielectric/energy.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/sanitization.slang";

public PrimeOpenPbrMaterial primePbrMaterial(PrimePbrSource source) {
    PrimeCanonicalOptics optics;
    optics.roughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    optics.opticalControl = 0u;
    if (source.hasSpecular)
        optics = primeDecodeOpticalSample(source.specularSample);
    if (source.hasNormal) {
        optics.roughness = primeCombineLabPbrNormalRoughness(
                optics.roughness,
                primeAdaptLabPbrNormalAtlas(source.normalSample).distributionRoughness);
    }
    uint fresnelCode = optics.opticalControl & PRIME_OPTICAL_FRESNEL_MASK;
    bool conductor = primeIsSupportedConductorCode(fresnelCode);
    PrimeOpenPbrMaterial material = primeCommonMaterialFromMetallic(
            source.baseColor, optics.roughness, conductor ? 1.0 : 0.0, source.normal);
    if (conductor) {
        PrimeConductorFresnel fresnel = primeConductorFresnel(source.baseColor, fresnelCode);
        material.base.color = fresnel.f0;
        material.specular.color = fresnel.f82Tint;
    } else {
        material.specular.ior = primeOpenPbrF0ToIor(primeDecodeDielectricF0(fresnelCode));
        // Historical LitePBR consumes authored subsurface weight for both thick and thin
        // surfaces. Its thick approximation transmits diffusely without a medium-stack change.
        uint subsurface = optics.opticalControl >> PRIME_OPTICAL_SUBSURFACE_SHIFT & 0xffu;
        material.weight.subsurface = float(subsurface) / 190.0;
        material.subsurface.color = source.baseColor;
        material.geometry.thinWalled = source.thinWalled ? 1u : 0u;
        material.geometry.thickness = 0.0625;
    }
    return material;
}

public PrimeOpenPbrVolume primePbrVolume(float4 medium) {
    PrimeOpenPbrVolume result = {};
    result.ior = medium.x;
    result.extinction = medium.yzw;
    return result;
}

PrimeOpenPbrVolumeStack primePbrStack(float4 medium) {
    PrimeOpenPbrVolumeStack result = {};
    result.values[0] = primePbrVolume(medium);
    result.count = 1u;
    return result;
}

PrimeOpenPbrMaterial primePbrTransmissionMaterial(PrimeOpenPbrMaterial material,
                                                  float3 viewDirection, float4 incidentMedium,
                                                  float4 transmittedMedium) {
    // Lite's signed Fresnel evaluates TIR on the exiting (negative-cosine) side. Retain
    // that source frame convention while using the caller's proven physical endpoints.
    bool exiting = material.geometry.thinWalled == 0u && incidentMedium.x > transmittedMedium.x;
    bool facesView = dot(material.geometry.onb.normal, viewDirection) >= 0.0;
    if (facesView == exiting) {
        material.geometry.onb.normal = -material.geometry.onb.normal;
        material.geometry.onb.bitangent = -material.geometry.onb.bitangent;
    }
    material.specular.ior = exiting ? incidentMedium.x : transmittedMedium.x;
    return material;
}

PrimeLiteTransmissionState primePbrTransmissionState(PrimeOpenPbrMaterial material,
                                                     float3 localView, float4 incidentMedium,
                                                     float4 transmittedMedium) {
    bool exiting = material.geometry.thinWalled == 0u && incidentMedium.x > transmittedMedium.x;
    float inverseOutsideIor = 1.0 / (exiting ? transmittedMedium.x : incidentMedium.x);
    PrimeLiteTransmissionState state = primeLiteTransmissionStateInit(
            primeLiteEnergy(), material, localView, inverseOutsideIor, 0.0);
    state.transmissionVolume = primePbrVolume(transmittedMedium);
    if (material.geometry.thinWalled != 0u)
        state.transmissionVolume.extinction *= material.geometry.thickness;
    return state;
}

BsdfEvaluation primePbrEvaluation(PrimeOpenPbrThroughput throughput, float pdf) {
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = throughput.value;
        result.pdf = pdf;
    }
    return primeSanitizeBsdfEvaluation(result);
}

PrimeOpenPbrSample primePbrOpaqueSample(float3 wi, float3 randomValue, PrimeLiteOpaqueState state,
                                        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSample sampled = primeLiteOpaqueSample(wi, randomValue, state, stack).bsdfSample;
    if (state.kind != PRIME_LITE_OPAQUE_CONDUCTOR && primeOpenPbrHasSample(sampled) &&
        !primeOpenPbrIsDelta(sampled.throughput.flags)) {
        // Production MIS compares complete BSDFs and their marginal proposal density.
        // The historical kernel returns the selected component's joint density instead.
        PrimeLiteOpaqueEvaluation evaluated = primeLiteOpaqueEvaluate(wi, sampled.wo, state);
        sampled.throughput.value = evaluated.throughput.value;
        sampled.pdf = evaluated.pdf;
    }
    return sampled;
}

PrimeOpenPbrSample primePbrFoliageSample(float3 wi, float3 randomValue, PrimeLiteFoliageState state,
                                         PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSample sampled = primeLiteFoliageSample(wi, randomValue, state, stack).bsdfSample;
    if (state.base.kind != PRIME_LITE_OPAQUE_CONDUCTOR && primeOpenPbrHasSample(sampled) &&
        !primeOpenPbrIsDelta(sampled.throughput.flags)) {
        PrimeLiteFoliageEvaluation evaluated = primeLiteFoliageEvaluate(wi, sampled.wo, state);
        sampled.throughput.value = evaluated.throughput.value;
        sampled.pdf = evaluated.pdf;
    }
    return sampled;
}

PrimePbrSample primePbrSampleFrame(PrimeOpenPbrSample sampled, PrimeOpenPbrOnb onb, bool thinWalled,
                                   float3 viewDirection, float4 incidentMedium,
                                   float4 transmittedMedium, bool dielectric) {
    PrimePbrSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.medium = incidentMedium;
    if (!primeOpenPbrHasSample(sampled))
        return result;
    result.bsdfSample.direction = primeOpenPbrOnbToWorld(onb, sampled.wo);
    if ((sampled.throughput.flags & PRIME_OPENPBR_FLAG_DELTA_REFLECTION) != 0u)
        result.bsdfSample.direction = normalize(reflect(-viewDirection, onb.normal));
    result.bsdfSample.response = sampled.throughput.value;
    result.bsdfSample.pdf = sampled.pdf;
    result.bsdfSample.relativeEta = sampled.eta;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(sampled.throughput.flags);
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (dielectric && !thinWalled &&
        (result.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u)
        result.medium = transmittedMedium;
    return result;
}

PrimePbrSample primePbrSample(PrimeOpenPbrSample sampled, PrimeOpenPbrMaterial material,
                              float3 viewDirection, float4 incidentMedium, float4 transmittedMedium,
                              bool dielectric) {
    return primePbrSampleFrame(sampled, material.geometry.onb, material.geometry.thinWalled != 0u,
                               viewDirection, incidentMedium, transmittedMedium, dielectric);
}

public BsdfEvaluation primeEvaluatePbrMaterial(PrimeOpenPbrMaterial material, float3 viewDirection,
                                               float3 scatterDirection, float4 incidentMedium,
                                               float4 transmittedMedium) {
    if (primeLiteTransmissionSupports(material)) {
        material = primePbrTransmissionMaterial(material, viewDirection, incidentMedium,
                                                transmittedMedium);
        float3 wi = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
        float3 wo = primeOpenPbrOnbToLocal(material.geometry.onb, scatterDirection);
        PrimeLiteTransmissionState state =
                primePbrTransmissionState(material, wi, incidentMedium, transmittedMedium);
        PrimeLiteTransmissionEvaluation evaluated =
                primeLiteTransmissionEvaluate(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
        return primePbrEvaluation(evaluated.throughput, evaluated.pdf);
    }
    float3 wi = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    float3 wo = primeOpenPbrOnbToLocal(material.geometry.onb, scatterDirection);
    if (material.weight.transmission > 0.0 && primeLiteFoliageSupports(material)) {
        PrimeLiteFoliageState state = primeLiteFoliageStateInit(primeLiteEnergy(), material, wi,
                                                                1.0 / incidentMedium.x, 0.0);
        PrimeLiteFoliageEvaluation evaluated = primeLiteFoliageEvaluate(wi, wo, state);
        return primePbrEvaluation(evaluated.throughput, evaluated.pdf);
    }
    if (primeLiteOpaqueSupports(material)) {
        PrimeLiteOpaqueState state = primeLiteOpaqueStateInit(primeLiteEnergy(), material, wi,
                                                              1.0 / incidentMedium.x, 0.0);
        PrimeLiteOpaqueEvaluation evaluated = primeLiteOpaqueEvaluate(wi, wo, state);
        return primePbrEvaluation(evaluated.throughput, evaluated.pdf);
    }
    return primeInvalidBsdfEvaluation();
}

public PrimePbrSample primeSamplePbrMaterial(PrimeOpenPbrMaterial material, float3 viewDirection,
                                             float3 randomValue, float4 incidentMedium,
                                             float4 transmittedMedium) {
    bool dielectric = primeLiteTransmissionSupports(material);
    if (dielectric)
        material = primePbrTransmissionMaterial(material, viewDirection, incidentMedium,
                                                transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    PrimeOpenPbrSample sampled = primeOpenPbrZeroSample();
    PrimeOpenPbrVolumeStack stack = primePbrStack(incidentMedium);
    if (dielectric) {
        PrimeLiteTransmissionState state =
                primePbrTransmissionState(material, wi, incidentMedium, transmittedMedium);
        sampled = primeLiteTransmissionSample(wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL, stack)
                          .bsdfSample;
    } else if (material.weight.transmission > 0.0 && primeLiteFoliageSupports(material)) {
        PrimeLiteFoliageState state = primeLiteFoliageStateInit(primeLiteEnergy(), material, wi,
                                                                1.0 / incidentMedium.x, 0.0);
        sampled = primePbrFoliageSample(wi, randomValue, state, stack);
    } else if (primeLiteOpaqueSupports(material)) {
        PrimeLiteOpaqueState state = primeLiteOpaqueStateInit(primeLiteEnergy(), material, wi,
                                                              1.0 / incidentMedium.x, 0.0);
        sampled = primePbrOpaqueSample(wi, randomValue, state, stack);
    }
    return primePbrSample(sampled, material, viewDirection, incidentMedium, transmittedMedium,
                          dielectric);
}

PrimeOpenPbrMaterial primePbrSurfaceMaterial(PrimePbrSource source, bool dielectric,
                                             float3 viewDirection) {
    if (dot(source.normal, viewDirection) < 0.0)
        source.normal = -source.normal;
    PrimeOpenPbrMaterial material = primePbrMaterial(source);
    if (dielectric) {
        material.weight.metalness = 0.0;
        material.weight.subsurface = 0.0;
        material.weight.transmission = 1.0;
        material.base.color = float3(1.0);
        material.specular.color = float3(1.0);
        material.geometry.thinWalled = source.thinWalled ? 1u : 0u;
        material.geometry.thickness = 0.0625;
        material.transmission.color = float3(1.0);
        material.transmission.depth = 1.0;
        if (!source.hasSpecular) {
            material.specular.roughness =
                    source.hasNormal ? primeCombineLabPbrNormalRoughness(
                                               0.0, primeAdaptLabPbrNormalAtlas(source.normalSample)
                                                            .distributionRoughness)
                                     : 0.0;
        }
    }
    return material;
}

public BsdfEvaluation primeEvaluatePbr(PrimePbrSource source, float3 viewDirection,
                                       float3 scatterDirection, float4 incidentMedium,
                                       float4 transmittedMedium, bool dielectric) {
    PrimeOpenPbrMaterial material = primePbrSurfaceMaterial(source, dielectric, viewDirection);
    if (dielectric) {
        material = primePbrTransmissionMaterial(material, viewDirection, incidentMedium,
                                                transmittedMedium);
        float3 wi = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
        float3 wo = primeOpenPbrOnbToLocal(material.geometry.onb, scatterDirection);
        PrimeLiteTransmissionState state =
                primePbrTransmissionState(material, wi, incidentMedium, transmittedMedium);
        PrimeLiteTransmissionEvaluation evaluated =
                primeLiteTransmissionEvaluate(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
        return primePbrEvaluation(evaluated.throughput, evaluated.pdf);
    }
    float3 wi = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    float3 wo = primeOpenPbrOnbToLocal(material.geometry.onb, scatterDirection);
    PrimeLiteOpaqueState state =
            primeLiteOpaqueStateInit(primeLiteEnergy(), material, wi, 1.0 / incidentMedium.x, 0.0);
    PrimeLiteOpaqueEvaluation evaluated = primeLiteOpaqueEvaluate(wi, wo, state);
    return primePbrEvaluation(evaluated.throughput, evaluated.pdf);
}

public PrimePbrSample primeSamplePbr(PrimePbrSource source, float3 viewDirection,
                                     float3 randomValue, float4 incidentMedium,
                                     float4 transmittedMedium, bool dielectric) {
    PrimeOpenPbrMaterial material = primePbrSurfaceMaterial(source, dielectric, viewDirection);
    if (dielectric)
        material = primePbrTransmissionMaterial(material, viewDirection, incidentMedium,
                                                transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    PrimeOpenPbrVolumeStack stack = primePbrStack(incidentMedium);
    PrimeOpenPbrSample sampled;
    if (dielectric) {
        PrimeLiteTransmissionState state =
                primePbrTransmissionState(material, wi, incidentMedium, transmittedMedium);
        sampled = primeLiteTransmissionSample(wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL, stack)
                          .bsdfSample;
    } else {
        PrimeLiteOpaqueState state = primeLiteOpaqueStateInit(primeLiteEnergy(), material, wi,
                                                              1.0 / incidentMedium.x, 0.0);
        sampled = primePbrOpaqueSample(wi, randomValue, state, stack);
    }
    return primePbrSample(sampled, material, viewDirection, incidentMedium, transmittedMedium,
                          dielectric);
}

PrimeLitePtOpaqueState primePbrVertexOpaqueState(PrimePbrVertex vertex, float3 wi,
                                                 float4 incidentMedium) {
    uint fresnelCode = vertex.control & PRIME_OPTICAL_FRESNEL_MASK;
    uint subsurface = vertex.control >> PRIME_OPTICAL_SUBSURFACE_SHIFT & 0xffu;
    float materialIor = primeOpenPbrF0ToIor(primeDecodeDielectricF0(fresnelCode));
    return primeLitePtOpaqueStateInit(vertex.baseColor, vertex.roughness, materialIor,
                                      1.0 / incidentMedium.x, float(subsurface) / 190.0,
                                      primePbrVertexIsThinWalled(vertex), wi);
}

public BsdfEvaluation primeEvaluateLitePbrVertex(PrimePbrVertex vertex, float3 viewDirection,
                                                 float3 scatterDirection, float4 incidentMedium,
                                                 float4 transmittedMedium) {
    PrimeOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(onb, viewDirection);
    float3 wo = primeOpenPbrOnbToLocal(onb, scatterDirection);
    if (primePbrVertexIsDielectric(vertex)) {
        PrimeOpenPbrMicrofacet microfacet;
        microfacet.alpha = float2(vertex.roughness * vertex.roughness);
        float ior = primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium);
        if (primePbrVertexIsThinWalled(vertex)) {
            float3 extinction = transmittedMedium.yzw * 0.0625;
            return primePbrEvaluation(
                    primeLiteThinWallEvalCore(wi, wo, microfacet, ior, float3(1.0), float3(1.0),
                                              extinction, PRIME_OPENPBR_FLAG_ALL),
                    primeLiteThinWallPdfCore(wi, wo, microfacet, ior, float3(1.0), float3(1.0),
                                             extinction, PRIME_OPENPBR_FLAG_ALL));
        }
        return primePbrEvaluation(primeLiteRefractiveEvalCore(wi, wo, microfacet, ior, float3(1.0),
                                                              float3(1.0), PRIME_OPENPBR_FLAG_ALL),
                                  primeLiteRefractivePdfCore(wi, wo, microfacet, ior, float3(1.0),
                                                             PRIME_OPENPBR_FLAG_ALL));
    }
    if (primePbrVertexIsConductor(vertex)) {
        PrimeConductorFresnel fresnel = primeConductorFresnel(
                vertex.baseColor, vertex.control & PRIME_OPTICAL_FRESNEL_MASK);
        PrimeOpenPbrMicrofacet microfacet;
        microfacet.alpha = float2(vertex.roughness * vertex.roughness);
        PrimeLiteReflectiveBase reflection =
                primeLiteReflectiveEvalBase(wi, wo, microfacet, PRIME_OPENPBR_FLAG_ALL);
        PrimeOpenPbrThroughput throughput = primeOpenPbrZeroThroughput();
        if (reflection.flags != PRIME_OPENPBR_FLAG_NONE) {
            throughput.value =
                    primeLiteConductorFresnelCore(fresnel.f0, fresnel.f82Tint, float3(1.0), 1.0,
                                                  reflection.microCosine) *
                    reflection.factor;
            throughput.flags = reflection.flags;
        }
        return primePbrEvaluation(throughput, primeLiteReflectivePdf(wi, wo, microfacet));
    }
    PrimeLitePtOpaqueState state = primePbrVertexOpaqueState(vertex, wi, incidentMedium);
    PrimeLiteOpaqueEvaluation evaluated = primeLitePtOpaqueEvaluate(wi, wo, state);
    return primePbrEvaluation(evaluated.throughput, evaluated.pdf);
}

public PrimePbrSample primeSampleLitePbrVertex(PrimePbrVertex vertex, float3 viewDirection,
                                               float3 randomValue, float4 incidentMedium,
                                               float4 transmittedMedium) {
    PrimeOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(onb, viewDirection);
    PrimeOpenPbrSample sampled;
    bool dielectric = primePbrVertexIsDielectric(vertex);
    if (dielectric) {
        PrimeOpenPbrMicrofacet microfacet;
        microfacet.alpha = float2(vertex.roughness * vertex.roughness);
        float ior = primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium);
        sampled = primePbrVertexIsThinWalled(vertex)
                          ? primeLiteThinWallSampleCore(
                                    wi, randomValue, microfacet, ior, float3(1.0), float3(1.0),
                                    transmittedMedium.yzw * 0.0625, PRIME_OPENPBR_FLAG_ALL)
                          : primeLiteRefractiveSampleCore(wi, randomValue, microfacet, ior,
                                                          float3(1.0), float3(1.0),
                                                          PRIME_OPENPBR_FLAG_ALL);
    } else if (primePbrVertexIsConductor(vertex)) {
        PrimeConductorFresnel fresnel = primeConductorFresnel(
                vertex.baseColor, vertex.control & PRIME_OPTICAL_FRESNEL_MASK);
        PrimeOpenPbrMicrofacet microfacet;
        microfacet.alpha = float2(vertex.roughness * vertex.roughness);
        float microCosine;
        sampled = primeLiteReflectiveSampleBase(wi, randomValue.xy, microfacet,
                                                PRIME_OPENPBR_FLAG_ALL, microCosine);
        if (sampled.throughput.flags != PRIME_OPENPBR_FLAG_NONE)
            sampled.throughput.value *= primeLiteConductorFresnelCore(
                    fresnel.f0, fresnel.f82Tint, float3(1.0), 1.0, microCosine);
    } else {
        PrimeLitePtOpaqueState state = primePbrVertexOpaqueState(vertex, wi, incidentMedium);
        sampled = primeLitePtOpaqueSample(wi, randomValue, state);
        if (primeOpenPbrHasSample(sampled) && !primeOpenPbrIsDelta(sampled.throughput.flags)) {
            // Preserve the full closure response and marginal proposal density used by MIS.
            PrimeLiteOpaqueEvaluation evaluated = primeLitePtOpaqueEvaluate(wi, sampled.wo, state);
            sampled.throughput.value = evaluated.throughput.value;
            sampled.pdf = evaluated.pdf;
        }
    }
    return primePbrSampleFrame(sampled, onb, primePbrVertexIsThinWalled(vertex), viewDirection,
                               incidentMedium, transmittedMedium, dielectric);
}

PrimeFullCompactOpaqueState primePbrFullOpaqueState<Energy>(Energy energy, PrimePbrVertex vertex,
                                                            float3 wi,
                                                            float4 incidentMedium) where Energy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    bool conductor = primePbrVertexIsConductor(vertex);
    uint fresnelCode = vertex.control & PRIME_OPTICAL_FRESNEL_MASK;
    float3 f0 = float3(0.0);
    float3 f82 = float3(0.0);
    float interfaceIor = 1.0;
    float subsurfaceWeight = 0.0;
    if (conductor) {
        PrimeConductorFresnel fresnel = primeConductorFresnel(vertex.baseColor, fresnelCode);
        f0 = fresnel.f0;
        f82 = fresnel.f82Tint;
    } else {
        interfaceIor = primeOpenPbrF0ToIor(primeDecodeDielectricF0(fresnelCode)) *
                       (1.0 / incidentMedium.x);
        subsurfaceWeight =
                float((vertex.control >> PRIME_OPTICAL_SUBSURFACE_SHIFT) & 0xffu) / 190.0;
    }
    return primeFullPtOpaqueState(energy, vertex.baseColor, vertex.roughness, interfaceIor,
                                  subsurfaceWeight, conductor, f0, f82, wi);
}

BsdfEvaluation primePbrFullEvaluation(PrimeFullOpenPbrThroughput throughput, float pdf) {
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (throughput.flags != PRIME_FULL_OPENPBR_FLAG_NONE) {
        result.response = throughput.value;
        result.pdf = pdf;
    }
    return primeSanitizeBsdfEvaluation(result);
}

public BsdfEvaluation primeEvaluatePbrVertex<Energy>(Energy energy, PrimePbrVertex vertex,
                                                     float3 viewDirection, float3 scatterDirection,
                                                     float4 incidentMedium,
                                                     float4 transmittedMedium) where Energy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    if (primePbrVertexUsesLegacyThickSss(vertex))
        return primeEvaluateLitePbrVertex(vertex, viewDirection, scatterDirection, incidentMedium,
                                          transmittedMedium);
    PrimeOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(onb, viewDirection);
    float3 wo = primeOpenPbrOnbToLocal(onb, scatterDirection);
    if (primePbrVertexIsDielectric(vertex)) {
        PrimeFullCompactTransmissionState state = primeFullPtTransmissionState(
                energy, vertex.roughness,
                primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium),
                primePbrVertexIsThinWalled(vertex), transmittedMedium.yzw * 0.0625, wi);
        PrimeFullCompactTransmissionEvaluation evaluated =
                primeFullCompactTransmissionEvaluate(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL);
        return primePbrFullEvaluation(evaluated.throughput, evaluated.pdf);
    }
    PrimeFullCompactOpaqueState state = primePbrFullOpaqueState(energy, vertex, wi, incidentMedium);
    PrimeFullCompactOpaqueEvaluation evaluated = primeFullCompactOpaqueEvaluate(wi, wo, state);
    return primePbrFullEvaluation(evaluated.throughput, evaluated.pdf);
}

public PrimePbrSample primeSamplePbrVertex<Energy>(Energy energy, PrimePbrVertex vertex,
                                                   float3 viewDirection, float3 randomValue,
                                                   float4 incidentMedium,
                                                   float4 transmittedMedium) where Energy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    if (primePbrVertexUsesLegacyThickSss(vertex))
        return primeSampleLitePbrVertex(vertex, viewDirection, randomValue, incidentMedium,
                                        transmittedMedium);
    PrimeOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(onb, viewDirection);
    PrimeFullOpenPbrSample fullSample;
    bool dielectric = primePbrVertexIsDielectric(vertex);
    if (dielectric) {
        PrimeFullCompactTransmissionState state = primeFullPtTransmissionState(
                energy, vertex.roughness,
                primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium),
                primePbrVertexIsThinWalled(vertex), transmittedMedium.yzw * 0.0625, wi);
        fullSample = primeFullCompactTransmissionSampleRaw(
                wi, randomValue, primeFullCompactTransmissionClosureState(state),
                PRIME_FULL_OPENPBR_FLAG_ALL);
    } else {
        PrimeFullCompactOpaqueState state =
                primePbrFullOpaqueState(energy, vertex, wi, incidentMedium);
        fullSample = primeFullPtOpaqueSample(wi, randomValue, state);
    }
    // Flag bit layouts are locked and verified by the CPU full-model oracle.
    PrimeOpenPbrSample sampled;
    sampled.wo = fullSample.wo;
    sampled.throughput.value = fullSample.throughput.value;
    sampled.throughput.flags = fullSample.throughput.flags;
    sampled.pdf = fullSample.pdf;
    sampled.eta = fullSample.eta;
    return primePbrSampleFrame(sampled, onb, primePbrVertexIsThinWalled(vertex), viewDirection,
                               incidentMedium, transmittedMedium, dielectric);
}
