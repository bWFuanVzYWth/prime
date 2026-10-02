// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "pbr/delta.slang";

__exported import "pbr/vertex.slang";
import "bsdf/common/common.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/delta.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/spectrum.slang";
import "model/material/fresnel_code.slang";
import "model/material/translation.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/sanitization.slang";

// Classify the whole authored closure, never only its specular component.
public bool primePbrVertexIsDiscreteOnly(PrimePbrVertex vertex, float4 incidentMedium,
                                         float4 transmittedMedium) {
    float alpha = vertex.roughness * vertex.roughness;
    if (primePbrVertexIsDielectric(vertex)) {
        float ior = primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium);
        if (primePbrVertexIsThinWalled(vertex))
            return clamp(alpha * primeFullOpenPbrThinDielectricRoughnessScaler2(ior), 0.0, 1.0) <
                   1.0e-4;
        return alpha < 1.0e-4 || ior == 1.0;
    }
    return primePbrVertexIsConductor(vertex) && alpha < 1.0e-4;
}

PrimeFullOpenPbrSpecularFresnel primePbrDeltaFresnel(float ior) {
    PrimeFullOpenPbrSpecularFresnel result;
    result.ior = ior;
    result.energyIor = primeFullOpenPbrIorAdjustment(ior, 1.0);
    result.color = float3(1.0);
    return result;
}

// One canonical world-space construction for both lighting and detached/shared guides.
// Thin transmission is straight through. Reconstructing -view through an ONB is redundant
// and permits different contraction/reassociation when its caller also consumes a response.
float3 primePbrDeltaWorldDirection(PrimeOpenPbrOnb onb, float3 viewDirection, float3 localDirection,
                                   bool reflection, bool thin) {
    if (reflection)
        return normalize(reflect(-viewDirection, onb.normal));
    if (thin)
        return -viewDirection;
    return primeOpenPbrOnbToWorld(onb, localDirection);
}

PrimePbrSample primePbrDeltaToWorld(PrimeFullOpenPbrSample fullSample, PrimeOpenPbrOnb onb,
                                    PrimePbrVertex vertex, float3 viewDirection,
                                    float3 geometricNormal, float4 incidentMedium,
                                    float4 transmittedMedium) {
    PrimePbrSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.medium = incidentMedium;
    PrimeOpenPbrSample sampled;
    sampled.wo = fullSample.wo;
    sampled.throughput.value = fullSample.throughput.value;
    sampled.throughput.flags = fullSample.throughput.flags;
    sampled.pdf = fullSample.pdf;
    sampled.eta = fullSample.eta;
    if (!primeOpenPbrHasSample(sampled))
        return result;
    result.bsdfSample.direction = primePbrDeltaWorldDirection(
            onb, viewDirection, sampled.wo,
            (sampled.throughput.flags & PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION) != 0u,
            primePbrVertexIsThinWalled(vertex));
    result.bsdfSample.response = sampled.throughput.value;
    result.bsdfSample.pdf = sampled.pdf;
    result.bsdfSample.relativeEta = sampled.eta;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(sampled.throughput.flags);
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (!primeGeometricSampleSupport(primePbrVertexHasNormal(vertex), geometricNormal,
                                     viewDirection, result.bsdfSample))
        result.bsdfSample = primeInvalidBsdfSample();
    if (primePbrVertexIsDielectric(vertex) && !primePbrVertexIsThinWalled(vertex) &&
        (result.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u)
        result.medium = transmittedMedium;
    return result;
}

public PrimePbrSample primeSamplePbrDelta(PrimePbrVertex vertex, float3 viewDirection,
                                          float3 geometricNormal, float randomEvent,
                                          float4 incidentMedium, float4 transmittedMedium) {
    PrimeOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(onb, viewDirection);
    PrimeFullOpenPbrSample sampled = primeFullOpenPbrZeroSample();
    if (primePbrVertexIsDiscreteOnly(vertex, incidentMedium, transmittedMedium)) {
        if (primePbrVertexIsDielectric(vertex)) {
            PrimeFullOpenPbrSpecularFresnel fresnel = primePbrDeltaFresnel(
                    primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium));
            sampled = primePbrVertexIsThinWalled(vertex)
                              ? primeFullThinWallDeltaSample(wi, randomEvent, fresnel, float3(1.0),
                                                             transmittedMedium.yzw * 0.0625,
                                                             PRIME_FULL_OPENPBR_FLAG_ALL)
                              : primeFullRefractiveDeltaSample(wi, randomEvent, fresnel,
                                                               PRIME_FULL_OPENPBR_FLAG_ALL);
        } else if (wi.z > 0.0) {
            PrimeConductorFresnel source = primeConductorFresnel(
                    vertex.baseColor, vertex.control & PRIME_OPTICAL_FRESNEL_MASK);
            PrimeFullOpenPbrConductorFresnel fresnel = {};
            fresnel.f0 = source.f0;
            fresnel.f82 = source.f82Tint;
            fresnel.f90 = float3(1.0);
            fresnel.weight = 1.0;
            sampled.wo = reflect(-wi, float3(0.0, 0.0, 1.0));
            sampled.throughput.value = primeFullOpenPbrConductorFresnelUnpolarized(fresnel, wi.z);
            sampled.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION;
            sampled.pdf = 1.0;
        }
    }
    return primePbrDeltaToWorld(sampled, onb, vertex, viewDirection, geometricNormal,
                                incidentMedium, transmittedMedium);
}

// Both conditional estimators share the frame, Fresnel, refraction and thin-wall series.
// This is not an ALL sample with a later factor of two. The selected conditional PDF is one.
public PrimePbrSample primeSamplePbrPrimaryDelta(PrimePbrVertex vertex, float3 viewDirection,
                                                 float3 geometricNormal, float branchCoin,
                                                 float4 incidentMedium, float4 transmittedMedium,
                                                 out float branchWeight) {
    branchWeight = 0.0;
    PrimePbrSample invalid;
    invalid.bsdfSample = primeInvalidBsdfSample();
    invalid.medium = incidentMedium;
    if (!primePbrVertexIsDielectric(vertex) ||
        !primePbrVertexIsDiscreteOnly(vertex, incidentMedium, transmittedMedium))
        return invalid;
    PrimeOpenPbrOnb onb =
            primePbrVertexFrame(vertex, viewDirection, incidentMedium, transmittedMedium);
    float3 wi = primeOpenPbrOnbToLocal(onb, viewDirection);
    bool thin = primePbrVertexIsThinWalled(vertex);
    if (thin && wi.z <= 0.0)
        return invalid;
    PrimeFullOpenPbrSpecularFresnel fresnel = primePbrDeltaFresnel(
            primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium));
    PrimeFullOpenPbrVecPair rt =
            thin ? primeFullThinWallRt(wi.z, fresnel, float3(1.0), transmittedMedium.yzw * 0.0625)
                 : primeFullOpenPbrSpecularFresnelRt(fresnel, wi.z);
    PrimeFullOpenPbrSample reflection = primeFullOpenPbrZeroSample();
    PrimeFullOpenPbrSample transmission = primeFullOpenPbrZeroSample();
    reflection.wo = reflect(-wi, float3(0.0, 0.0, 1.0));
    reflection.throughput.value = rt.first;
    if (thin) {
        transmission.wo = reflection.wo;
        transmission.wo.z = -transmission.wo.z;
        transmission.throughput.value = rt.second;
    } else {
        PrimeFullOpenPbrRefractResult refracted =
                primeFullOpenPbrDielectricRefract(fresnel.ior, -wi, float3(0.0, 0.0, 1.0));
        transmission.wo = refracted.wo;
        transmission.eta = refracted.relativeIor;
        transmission.throughput.value = rt.second / primeFullOpenPbrSquare(refracted.relativeIor);
        if (refracted.valid == 0u) {
            rt.second = float3(0.0);
            reflection.throughput.value = float3(1.0);
        }
    }
    if (primeFullOpenPbrSpectrumToWeight(rt.first) > 0.0 && wi.z * reflection.wo.z >= 0.0) {
        reflection.pdf = 1.0;
        reflection.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION;
    }
    if (primeFullOpenPbrSpectrumToWeight(rt.second) > 0.0 && wi.z * transmission.wo.z < 0.0) {
        transmission.pdf = 1.0;
        transmission.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_TRANSMISSION;
    }
    PrimePbrSample reflected =
            primePbrDeltaToWorld(reflection, onb, vertex, viewDirection, geometricNormal,
                                 incidentMedium, transmittedMedium);
    PrimePbrSample transmitted =
            primePbrDeltaToWorld(transmission, onb, vertex, viewDirection, geometricNormal,
                                 incidentMedium, transmittedMedium);
    bool validR = reflected.bsdfSample.eventFlags != 0u;
    bool validT = transmitted.bsdfSample.eventFlags != 0u;
    branchWeight = validR && validT ? 2.0 : (validR || validT ? 1.0 : 0.0);
    return validT && (!validR || branchCoin < 0.5) ? transmitted : reflected;
}

public struct PrimePbrGuideDirection {
    public float3 direction;
    public float nextIor;
    public uint eventFlags;
    public bool geometricValid;
    public bool totalInternalReflection;
};

// Geometry only: no F0, energy IOR, absorption, throughput, PDF, LUT or event random input.
public PrimePbrGuideDirection primePbrDeltaGuideDirection(PrimePbrVertex vertex,
                                                          float3 viewDirection,
                                                          float3 geometricNormal, float incidentIor,
                                                          float transmittedIor) {
    PrimePbrGuideDirection result = {};
    result.nextIor = incidentIor;
    float4 incident = float4(incidentIor, 0.0, 0.0, 0.0);
    float4 transmitted = float4(transmittedIor, 0.0, 0.0, 0.0);
    if (!primePbrVertexIsDiscreteOnly(vertex, incident, transmitted) ||
        !primeBsdfDirection(viewDirection) || !primeBsdfDirection(vertex.normal) ||
        !primeBsdfFinite(incidentIor) || !primeBsdfFinite(transmittedIor) || incidentIor <= 0.0 ||
        transmittedIor <= 0.0)
        return result;
    // Use the lighting frame and canonical world direction. The branch coin must not
    // change the ray when the guide changes between shared and detached traversal.
    PrimeOpenPbrOnb onb = primePbrVertexFrame(vertex, viewDirection, incident, transmitted);
    float3 wi = primeOpenPbrOnbToLocal(onb, viewDirection);
    if (wi.z == 0.0)
        return result;
    float3 normal = onb.normal;
    result.eventFlags = PRIME_BSDF_EVENT_DISCRETE | PRIME_BSDF_EVENT_REFLECTION;
    result.direction = primePbrDeltaWorldDirection(onb, viewDirection, float3(0.0), true, false);
    if (primePbrVertexIsDielectric(vertex)) {
        if (primePbrVertexIsThinWalled(vertex)) {
            if (wi.z <= 0.0)
                return result;
            result.direction =
                    primePbrDeltaWorldDirection(onb, viewDirection, float3(0.0), false, true);
            result.eventFlags = PRIME_BSDF_EVENT_DISCRETE | PRIME_BSDF_EVENT_TRANSMISSION;
        } else {
            PrimeFullOpenPbrRefractResult refracted = primeFullOpenPbrDielectricRefract(
                    primePbrVertexInterfaceIor(vertex, incident, transmitted), -wi,
                    float3(0.0, 0.0, 1.0));
            result.totalInternalReflection = refracted.valid == 0u;
            if (!result.totalInternalReflection) {
                result.direction =
                        primePbrDeltaWorldDirection(onb, viewDirection, refracted.wo, false, false);
                result.nextIor = transmittedIor;
                result.eventFlags = PRIME_BSDF_EVENT_DISCRETE | PRIME_BSDF_EVENT_TRANSMISSION;
            }
        }
    } else if (wi.z <= 0.0) {
        return result;
    }
    result.geometricValid = primeBsdfDirection(result.direction) &&
                            (!primePbrVertexHasNormal(vertex) ||
                             primeGeometricEventSupport(geometricNormal, viewDirection,
                                                        result.direction, result.eventFlags));
    return result;
}
