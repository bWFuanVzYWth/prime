// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "pbr.slang";
__exported import "pbr/vertex.slang";
__exported import "pbr/guide_albedo.slang";
import "bsdf/full/pt.slang";
import "bsdf/full/math/spectrum.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/math/frame.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/state.slang";
import "bsdf/full/dielectric/evaluate.slang";
import "bsdf/full/dielectric/refract.slang";
import "bsdf/full/opaque/evaluate.slang";
import "bsdf/full/opaque/state.slang";
import "model/material/defaults.slang";
import "model/material/optical.slang";
import "model/material/fresnel_code.slang";
import "model/material/translation.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/sanitization.slang";

PrimePbrSample primePbrSampleFrame(PrimeFullOpenPbrSample sampled, PrimeFullOpenPbrOnb onb,
                                   bool thinWalled, float3 viewDirection, float4 incidentMedium,
                                   float4 transmittedMedium, bool dielectric) {
    PrimePbrSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.medium = incidentMedium;
    if (!primeFullOpenPbrHasSample(sampled))
        return result;
    result.bsdfSample.direction = primeFullOpenPbrOnbToWorld(onb, sampled.wo);
    if ((sampled.throughput.flags & PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION) != 0u)
        result.bsdfSample.direction = normalize(reflect(-viewDirection, onb.normal));
    result.bsdfSample.response = sampled.throughput.value;
    result.bsdfSample.pdf = sampled.pdf;
    result.bsdfSample.relativeEta = sampled.eta;
    result.bsdfSample.eventFlags = primeFullOpenPbrToBsdfEventFlags(sampled.throughput.flags);
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (dielectric && !thinWalled &&
        (result.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u)
        result.medium = transmittedMedium;
    return result;
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
        interfaceIor = primeFullOpenPbrF0ToIor(primeDecodeDielectricF0(fresnelCode)) *
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
    PrimeFullOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeFullOpenPbrOnbToLocal(onb, viewDirection);
    float3 wo = primeFullOpenPbrOnbToLocal(onb, scatterDirection);
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
    PrimeFullOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeFullOpenPbrOnbToLocal(onb, viewDirection);
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
    return primePbrSampleFrame(fullSample, onb, primePbrVertexIsThinWalled(vertex), viewDirection,
                               incidentMedium, transmittedMedium, dielectric);
}
