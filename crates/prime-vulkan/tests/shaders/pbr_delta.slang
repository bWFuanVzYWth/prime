// Behavioral oracle: the unchanged full entry and full energy states are test-only dependencies.
#language slang 2026
module pbr_delta_test;
import "../../shaders/pbr.slang";
import "pbr/delta.slang";
import "pbr/guide_albedo.slang";
import "bsdf/full/math/frame.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/math/spectrum.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/pt.slang";
import "bsdf/full/dielectric/state.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/refract.slang";
import "bsdf/full/dielectric/energy.slang";
import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/energy.slang";
import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "model/material/defaults.slang";
import "model/material/fresnel_code.slang";
import "model/material/translation.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/sanitization.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::push_constant]] ConstantBuffer<uint4> config;

// Coordinates deliberately affect the response: comparing only a constant LUT would miss a
// changed roughness/view/IOR lookup. Production texture/filtering is covered by Full's LUT suite.
public struct DeltaTestEnergy : IPrimeFullOpenPbrTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw) {
        return float4(0.2 * uvw.x, -0.4 * uvw.y, clamp(uvw.z, 0.0, 1.0), 0.25 * uvw.x);
    }
};

bool close(float a, float b) {
    return primeBsdfFinite(a) && primeBsdfFinite(b) &&
           abs(a - b) <= 2.0e-5 * (1.0 + max(abs(a), abs(b)));
}
bool close(float3 a, float3 b) {
    return close(a.x, b.x) && close(a.y, b.y) && close(a.z, b.z);
}
bool same(PrimePbrSample a, PrimePbrSample b) {
    return a.bsdfSample.eventFlags == b.bsdfSample.eventFlags &&
           close(a.bsdfSample.direction, b.bsdfSample.direction) &&
           close(a.bsdfSample.response, b.bsdfSample.response) &&
           close(a.bsdfSample.pdf, b.bsdfSample.pdf) &&
           close(a.bsdfSample.relativeEta, b.bsdfSample.relativeEta) &&
           close(a.medium.x, b.medium.x) && close(a.medium.yzw, b.medium.yzw);
}

PrimePbrSample referenceFrame(PrimeFullOpenPbrSample fullSample, PrimeFullOpenPbrOnb onb,
                              PrimePbrVertex vertex, float3 view, float3 geometric, float4 incident,
                              float4 transmitted) {
    PrimePbrSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.medium = incident;
    PrimeFullOpenPbrSample sampled;
    sampled.wo = fullSample.wo;
    sampled.throughput.value = fullSample.throughput.value;
    sampled.throughput.flags = fullSample.throughput.flags;
    sampled.pdf = fullSample.pdf;
    sampled.eta = fullSample.eta;
    if (!primeFullOpenPbrHasSample(sampled))
        return result;
    result.bsdfSample.direction = primeFullOpenPbrOnbToWorld(onb, sampled.wo);
    if ((sampled.throughput.flags & PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION) != 0u)
        result.bsdfSample.direction = normalize(reflect(-view, onb.normal));
    result.bsdfSample.response = sampled.throughput.value;
    result.bsdfSample.pdf = sampled.pdf;
    result.bsdfSample.relativeEta = sampled.eta;
    result.bsdfSample.eventFlags = primeFullOpenPbrToBsdfEventFlags(sampled.throughput.flags);
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (!primeGeometricSampleSupport(primePbrVertexHasNormal(vertex), geometric, view,
                                     result.bsdfSample))
        result.bsdfSample = primeInvalidBsdfSample();
    if (!primePbrVertexIsThinWalled(vertex) &&
        (result.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u)
        result.medium = transmitted;
    return result;
}

PrimeFullCompactOpaqueState referenceOpaque(PrimePbrVertex vertex, float3 wi, float4 incident) {
    bool conductor = primePbrVertexIsConductor(vertex);
    uint code = vertex.control & PRIME_OPTICAL_FRESNEL_MASK;
    PrimeConductorFresnel fresnel = primeConductorFresnel(vertex.baseColor, code);
    return primeFullPtOpaqueState(
            DeltaTestEnergy(), vertex.baseColor, vertex.roughness,
            conductor ? 1.0 : primeFullOpenPbrF0ToIor(primeDecodeDielectricF0(code)) / incident.x,
            conductor ? 0.0
                      : float((vertex.control >> PRIME_OPTICAL_SUBSURFACE_SHIFT) & 0xffu) / 190.0,
            conductor, conductor ? fresnel.f0 : float3(0.0),
            conductor ? fresnel.f82Tint : float3(0.0), wi);
}

PrimeDenoiseAlbedos referenceAlbedo(PrimePbrVertex vertex, float3 view, float4 incident,
                                    float4 transmitted) {
    PrimeFullOpenPbrOnb onb = primePbrVertexFrame(vertex, view, incident, transmitted);
    float3 wi = primeFullOpenPbrOnbToLocal(onb, view);
    PrimeDenoiseAlbedos result;
    if (primePbrVertexIsDielectric(vertex)) {
        PrimeFullCompactTransmissionState state = primeFullPtTransmissionState(
                DeltaTestEnergy(), vertex.roughness,
                primePbrVertexInterfaceIor(vertex, incident, transmitted),
                primePbrVertexIsThinWalled(vertex), transmitted.yzw * 0.0625, wi);
        result.diffuse = primeFullCompactTransmissionEnergy(DeltaTestEnergy(), wi, state,
                                                            PRIME_FULL_OPENPBR_FLAG_TRANSMISSION);
        result.specular = primeFullCompactTransmissionEnergy(DeltaTestEnergy(), wi, state,
                                                             PRIME_FULL_OPENPBR_FLAG_REFLECTION);
    } else {
        PrimeFullCompactOpaqueEnergy state =
                primeFullCompactOpaqueEnergy(wi, referenceOpaque(vertex, wi, incident));
        result.diffuse = state.diffuse;
        result.specular = state.specular;
    }
    result.diffuse = primeSanitizeDenoiseAlbedo(result.diffuse);
    result.specular = primeSanitizeDenoiseAlbedo(result.specular);
    return result;
}

float3 contribution(PrimePbrSample sample) {
    return sample.bsdfSample.eventFlags == 0u ? float3(0.0)
                                              : sample.bsdfSample.response / sample.bsdfSample.pdf;
}

// 74,088 combinations cover all canonical categories, both delta boundaries, IOR=1,
// exiting TIR, thin-wall alpha scaling, black/HDR materials, absorption and mapped normals.
uint4 pbrDeltaCase(uint index, uint mode) {
    uint originalIndex = index;
    uint category = index % 7u;
    index /= 7u;
    uint roughnessIndex = index % 7u;
    index /= 7u;
    uint viewIndex = index % 6u;
    index /= 6u;
    uint iorIndex = index % 6u;
    index /= 6u;
    uint normalIndex = index % 3u;
    index /= 3u;
    uint absorptionIndex = index % 2u;
    index /= 2u;
    uint randomIndex = index % 7u;
    float roughnesses[7] = {0.0, 0.0099, 0.01, 0.0101, 0.1, 0.45, 1.0};
    float cosines[6] = {1.0, 0.8, 0.01, -0.8, -0.5, -0.001};
    float2 iors[6] = {float2(1.0, 1.5),  float2(1.5, 1.0),      float2(1.0, 1.0),
                      float2(1.33, 1.5), float2(1.0, 1.000001), float2(1.5, 0.9)};
    float events[7] = {0.0, 0.03, 0.25, 0.5, 0.75, 0.97, 0.999999};
    float cosine = cosines[viewIndex];
    float3 view = float3(sqrt(1.0 - cosine * cosine), 0.0, cosine);
    float3 normal = normalIndex == 0u   ? float3(0.0, 0.0, 1.0)
                    : normalIndex == 1u ? float3(0.6, 0.0, 0.8)
                                        : float3(-0.8, 0.0, 0.6);
    float3 geometric = float3(0.0, 0.0, 1.0);
    uint fresnel = category == 3u ? 231u : category == 4u ? 239u : 4u;
    float3 base = originalIndex % 5u == 0u ? float3(0.0) : float3(0.2, 0.6, 1.2);
    PrimePbrVertex vertex = primePbrVertex(
            base, normal, 0.0,
            float3(1.0, float(fresnel) / 255.0,
                   category == 1u || category == 2u ? 126.0 / 255.0 : 0.0),
            normalIndex != 0u, true, category == 2u || category == 6u, category >= 5u, view);
    vertex.roughness = roughnesses[roughnessIndex];
    float4 incident = float4(iors[iorIndex].x, 0.01, 0.03, 0.02);
    float4 transmitted =
            float4(iors[iorIndex].y, absorptionIndex == 0u ? float3(0.1, 0.2, 0.3) : float3(1.0e8));
    PrimeFullOpenPbrOnb onb = primePbrVertexFrame(vertex, view, incident, transmitted);
    float3 wi = primeFullOpenPbrOnbToLocal(onb, view);
    PrimeFullCompactTransmissionState state = primeFullPtTransmissionState(
            DeltaTestEnergy(), vertex.roughness,
            primePbrVertexInterfaceIor(vertex, incident, transmitted),
            primePbrVertexIsThinWalled(vertex), transmitted.yzw * 0.0625, wi);
    bool fullDelta = category >= 5u ? (category == 6u ? primeFullOpenPbrMicrofacetEffectivelySmooth(
                                                                state.transmissionMicrofacet)
                                                      : primeFullOpenPbrMicrofacetEffectivelySmooth(
                                                                state.specularMicrofacet) ||
                                                                state.specularFresnel.ior == 1.0)
                                    : ((category == 3u || category == 4u) &&
                                       primeFullOpenPbrMicrofacetEffectivelySmooth(
                                               referenceOpaque(vertex, wi, incident).microfacet));
    bool delta = primePbrVertexIsDiscreteOnly(vertex, incident, transmitted);
    uint failure = delta == fullDelta ? 0u : 1u;
    PrimePbrSample sampled = primeSamplePbrDelta(vertex, view, geometric, events[randomIndex],
                                                 incident, transmitted);
    if (delta) {
        PrimePbrSample reference = primeSamplePbrVertex(DeltaTestEnergy(), vertex, view,
                                                        float3(0.31, 0.73, events[randomIndex]),
                                                        incident, transmitted);
        if (!primeGeometricSampleSupport(primePbrVertexHasNormal(vertex), geometric, view,
                                         reference.bsdfSample)) {
            reference.bsdfSample = primeInvalidBsdfSample();
            reference.medium = incident;
        }
        if (!same(sampled, reference))
            failure |= 2u;
    } else if (sampled.bsdfSample.eventFlags != 0u)
        failure |= 2u;
    PrimeDenoiseAlbedos albedo =
            primePbrGuideAlbedos(DeltaTestEnergy(), vertex, view, incident, transmitted);
    PrimeDenoiseAlbedos expectedAlbedo = referenceAlbedo(vertex, view, incident, transmitted);
    if (!close(albedo.diffuse, expectedAlbedo.diffuse) ||
        !close(albedo.specular, expectedAlbedo.specular))
        failure |= 4u;
    float weightT, weightR;
    PrimePbrSample selectedT = primeSamplePbrPrimaryDelta(vertex, view, geometric, 0.25, incident,
                                                          transmitted, weightT);
    PrimePbrSample selectedR = primeSamplePbrPrimaryDelta(vertex, view, geometric, 0.75, incident,
                                                          transmitted, weightR);
    PrimePbrGuideDirection reflectionGuide =
            primePbrReflectionGuideDirection(vertex, view, geometric, incident, transmitted);
    // Full-frame reference remains independent of the helper's narrow frame-normal path.
    float3 reflectedDirection = normalize(reflect(-view, onb.normal));
    uint reflectionEvent = PRIME_BSDF_EVENT_DISCRETE | PRIME_BSDF_EVENT_REFLECTION;
    bool reflectionGeometric =
            primeBsdfDirection(reflectedDirection) &&
            (!primePbrVertexHasNormal(vertex) ||
             primeGeometricEventSupport(geometric, view, reflectedDirection, reflectionEvent));
    if (delta && category >= 5u) {
        PrimePbrSample r = referenceFrame(primeFullCompactTransmissionSampleRaw(
                                                  wi, float3(0.31, 0.73, events[randomIndex]),
                                                  primeFullCompactTransmissionClosureState(state),
                                                  PRIME_FULL_OPENPBR_FLAG_REFLECTION),
                                          onb, vertex, view, geometric, incident, transmitted);
        PrimePbrSample t = referenceFrame(primeFullCompactTransmissionSampleRaw(
                                                  wi, float3(0.31, 0.73, events[randomIndex]),
                                                  primeFullCompactTransmissionClosureState(state),
                                                  PRIME_FULL_OPENPBR_FLAG_TRANSMISSION),
                                          onb, vertex, view, geometric, incident, transmitted);
        bool validR = r.bsdfSample.eventFlags != 0u;
        bool validT = t.bsdfSample.eventFlags != 0u;
        if (!same(selectedT, validT ? t : r) || !same(selectedR, validR ? r : t) ||
            weightT != (validR && validT   ? 2.0
                        : validR || validT ? 1.0
                                           : 0.0) ||
            weightR != weightT)
            failure |= 8u;
        if (!close(0.5 * (weightT * contribution(selectedT) + weightR * contribution(selectedR)),
                   contribution(r) + contribution(t)))
            failure |= 16u;
        if (reflectionGuide.geometricValid != reflectionGeometric ||
            reflectionGuide.eventFlags != reflectionEvent ||
            reflectionGuide.nextIor != incident.x || reflectionGuide.totalInternalReflection ||
            (validR && !reflectionGuide.geometricValid))
            failure |= 512u;
        if (reflectionGuide.geometricValid) {
            // A zero-energy conditional R may be absent. Its geometric guide must still exist;
            // compare actual lighting direction only when that R estimator is valid.
            if (any(reflectionGuide.direction != reflectedDirection) ||
                (validR && any(reflectionGuide.direction != r.bsdfSample.direction)) ||
                ((selectedR.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u &&
                 any(reflectionGuide.direction != selectedR.bsdfSample.direction)))
                failure |= 1024u;
            if (any(asuint(reflectionGuide.direction) != asuint(reflectedDirection)) ||
                (validR &&
                 any(asuint(reflectionGuide.direction) != asuint(r.bsdfSample.direction))) ||
                ((selectedR.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u &&
                 any(asuint(reflectionGuide.direction) != asuint(selectedR.bsdfSample.direction))))
                failure |= 128u;
        }
    } else if (selectedT.bsdfSample.eventFlags != 0u || selectedR.bsdfSample.eventFlags != 0u ||
               weightT != 0.0 || weightR != 0.0)
        failure |= 8u;
    PrimePbrGuideDirection guide =
            primePbrDeltaGuideDirection(vertex, view, geometric, incident.x, transmitted.x);
    PrimePbrVertex black = vertex;
    black.baseColor = float3(0.0);
    PrimePbrGuideDirection blackGuide =
            primePbrDeltaGuideDirection(black, view, geometric, incident.x, transmitted.x);
    if (guide.geometricValid != blackGuide.geometricValid ||
        guide.totalInternalReflection != blackGuide.totalInternalReflection ||
        guide.eventFlags != blackGuide.eventFlags ||
        !close(guide.direction, blackGuide.direction) || !close(guide.nextIor, blackGuide.nextIor))
        failure |= 32u;
    if (mode == 2u)
        return uint4(asuint(sampled.bsdfSample.direction), sampled.bsdfSample.eventFlags);
    if (mode == 3u)
        return uint4(asuint(guide.direction), guide.eventFlags);
    if (mode == 4u)
        return uint4(asuint(selectedT.bsdfSample.direction), selectedT.bsdfSample.eventFlags);
    if (mode == 5u)
        return uint4(asuint(selectedR.bsdfSample.direction), selectedR.bsdfSample.eventFlags);
    if (mode == 6u)
        return uint4(asuint(reflectionGuide.direction),
                     reflectionGuide.geometricValid ? reflectionGuide.eventFlags : 0u);
    if (mode == 7u)
        return uint4(asuint(reflectedDirection), reflectionGeometric ? reflectionEvent : 0u);
    if (guide.geometricValid) {
        if (sampled.bsdfSample.eventFlags == guide.eventFlags &&
            any(sampled.bsdfSample.direction != guide.direction))
            failure |= 256u;
        if (selectedT.bsdfSample.eventFlags == guide.eventFlags &&
            any(selectedT.bsdfSample.direction != guide.direction))
            failure |= 256u;
        if (selectedR.bsdfSample.eventFlags == guide.eventFlags &&
            any(selectedR.bsdfSample.direction != guide.direction))
            failure |= 256u;
        if (!delta || !primeBsdfDirection(guide.direction))
            failure |= 64u;
        if (sampled.bsdfSample.eventFlags == guide.eventFlags &&
            any(asuint(sampled.bsdfSample.direction) != asuint(guide.direction)))
            failure |= 128u;
        if (selectedT.bsdfSample.eventFlags == guide.eventFlags &&
            any(asuint(selectedT.bsdfSample.direction) != asuint(guide.direction)))
            failure |= 128u;
        if (selectedR.bsdfSample.eventFlags == guide.eventFlags &&
            any(asuint(selectedR.bsdfSample.direction) != asuint(guide.direction)))
            failure |= 128u;
        if ((guide.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u) {
            float expectedIor = category == 6u ? incident.x : transmitted.x;
            if (guide.totalInternalReflection || guide.nextIor != expectedIor)
                failure |= 64u;
        } else if (category >= 5u && !guide.totalInternalReflection)
            failure |= 64u;
    }
    return uint4(failure, delta ? 1u : 0u, guide.geometricValid ? 1u : 0u,
                 guide.totalInternalReflection ? 1u : 0u);
}

public uint4 pbrDeltaCheck(uint index) {
    return pbrDeltaCase(index, 0u);
}

public uint4 pbrDeltaEdgeCheck(uint index) {
    float3 view = float3(0.0, 0.0, 1.0);
    float3 geometric = view;
    float4 incident = float4(1.0, 0.0, 0.0, 0.0);
    float4 transmitted = float4(1.5, 0.0, 0.0, 0.0);
    PrimePbrVertex vertex = primePbrVertex(float3(0.0), view, 0.0, float3(1.0, 4.0 / 255.0, 0.0),
                                           false, true, false, true, view);
    uint failure = 0u;
    if (index == 0u) {
        vertex.control &= ~PRIME_PBR_VERTEX_DIELECTRIC;
        failure = primePbrVertexIsDiscreteOnly(vertex, incident, transmitted) ? 1u : 0u;
    } else if (index == 1u) {
        vertex = primePbrVertex(float3(0.5), view, 0.01, float3(1.0, 231.0 / 255.0, 0.0), true,
                                true, false, false, view);
        failure = primePbrVertexIsDiscreteOnly(vertex, incident, transmitted) ? 1u : 0u;
    } else if (index == 2u || index == 3u) {
        transmitted.x = 1.0;
        vertex.roughness = 1.0;
        if (index == 2u)
            vertex.control |= PRIME_PBR_VERTEX_THIN_WALLED;
        PrimePbrGuideDirection guide =
                primePbrDeltaGuideDirection(vertex, view, geometric, 1.0, 1.0);
        failure = !primePbrVertexIsDiscreteOnly(vertex, incident, transmitted) ||
                                  !guide.geometricValid || guide.totalInternalReflection ||
                                  any(guide.direction != -view) || guide.nextIor != 1.0
                          ? 1u
                          : 0u;
    } else if (index == 4u) {
        incident.x = 1.5;
        transmitted.x = 1.0;
        view = float3(sqrt(0.75), 0.0, 0.5);
        PrimePbrGuideDirection guide =
                primePbrDeltaGuideDirection(vertex, view, geometric, 1.5, 1.0);
        float weight;
        PrimePbrSample sample = primeSamplePbrPrimaryDelta(vertex, view, geometric, 0.0, incident,
                                                           transmitted, weight);
        failure = !guide.geometricValid || !guide.totalInternalReflection ||
                                  guide.eventFlags != (PRIME_BSDF_EVENT_DISCRETE |
                                                       PRIME_BSDF_EVENT_REFLECTION) ||
                                  sample.bsdfSample.eventFlags != guide.eventFlags || weight != 1.0
                          ? 1u
                          : 0u;
    } else if (index == 5u) {
        vertex.control |= PRIME_PBR_VERTEX_THIN_WALLED;
        transmitted.yzw = float3(1.0e30);
        PrimePbrGuideDirection guide =
                primePbrDeltaGuideDirection(vertex, view, geometric, 1.0, 1.5);
        float weight;
        PrimePbrSample sample = primeSamplePbrPrimaryDelta(vertex, view, geometric, 0.0, incident,
                                                           transmitted, weight);
        failure = !guide.geometricValid || guide.totalInternalReflection ||
                                  guide.eventFlags != (PRIME_BSDF_EVENT_DISCRETE |
                                                       PRIME_BSDF_EVENT_TRANSMISSION) ||
                                  (sample.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) ==
                                          0u ||
                                  weight != 1.0
                          ? 1u
                          : 0u;
    } else if (index == 6u) {
        vertex = primePbrVertex(float3(0.5), float3(0.8, 0.0, 0.6), 0.0,
                                float3(1.0, 231.0 / 255.0, 0.0), true, true, false, false, view);
        PrimePbrGuideDirection guide =
                primePbrDeltaGuideDirection(vertex, view, geometric, 1.0, 1.5);
        failure = guide.geometricValid || guide.totalInternalReflection ? 1u : 0u;
    } else if (index == 7u) {
        vertex = primePbrVertex(float3(0.0), view, 0.0, float3(1.0, 239.0 / 255.0, 0.0), false,
                                true, false, false, view);
        PrimePbrGuideDirection guide =
                primePbrDeltaGuideDirection(vertex, view, geometric, 1.0, 1.5);
        PrimePbrSample sample =
                primeSamplePbrDelta(vertex, view, geometric, 0.5, incident, transmitted);
        failure = !guide.geometricValid || guide.totalInternalReflection ||
                                  guide.eventFlags != (PRIME_BSDF_EVENT_DISCRETE |
                                                       PRIME_BSDF_EVENT_REFLECTION) ||
                                  any(sample.bsdfSample.response != float3(0.0))
                          ? 1u
                          : 0u;
    } else if (index == 8u) {
        PrimePbrGuideDirection guide =
                primePbrDeltaGuideDirection(vertex, float3(1.0, 0.0, 0.0), geometric, 1.0, 1.0);
        failure = guide.geometricValid || guide.totalInternalReflection ? 1u : 0u;
    } else if (index >= 9u && index < 13u) {
        // eta=1.5: sin(theta)=sqrt(0.75) causes TIR, while sin(theta)=0.6 admits refraction.
        // Each geometry is checked with zero/underflowing transmission energy independently.
        bool tir = index < 11u;
        bool absorbed = (index & 1u) == 0u;
        float cosine = tir ? 0.5 : 0.8;
        view = float3(sqrt(1.0 - cosine * cosine), 0.0, cosine);
        incident.x = 1.5;
        transmitted.x = 1.0;
        transmitted.yzw = absorbed ? float3(1.0e30) : float3(0.0);
        vertex.control |= PRIME_PBR_VERTEX_THIN_WALLED;
        PrimePbrGuideDirection guide =
                primePbrDeltaGuideDirection(vertex, view, geometric, incident.x, transmitted.x);
        uint expectedEvent = PRIME_BSDF_EVENT_DISCRETE |
                             (tir ? PRIME_BSDF_EVENT_REFLECTION : PRIME_BSDF_EVENT_TRANSMISSION);
        float3 expectedDirection = tir ? reflect(-view, geometric) : -view;
        float weightT, weightR;
        PrimePbrSample sampleT = primeSamplePbrPrimaryDelta(vertex, view, geometric, 0.25, incident,
                                                            transmitted, weightT);
        PrimePbrSample sampleR = primeSamplePbrPrimaryDelta(vertex, view, geometric, 0.75, incident,
                                                            transmitted, weightR);
        failure = !guide.geometricValid || guide.totalInternalReflection != tir ||
                                  guide.eventFlags != expectedEvent ||
                                  !close(guide.direction, expectedDirection) ||
                                  guide.nextIor != incident.x
                          ? 1u
                          : 0u;
        if (tir || absorbed) {
            if (sampleT.bsdfSample.eventFlags !=
                        (PRIME_BSDF_EVENT_DISCRETE | PRIME_BSDF_EVENT_REFLECTION) ||
                sampleR.bsdfSample.eventFlags != sampleT.bsdfSample.eventFlags || weightT != 1.0 ||
                weightR != 1.0 || sampleT.medium.x != incident.x)
                failure |= 2u;
        } else if (sampleT.bsdfSample.eventFlags != expectedEvent ||
                   (sampleR.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) == 0u ||
                   weightT != 2.0 || weightR != 2.0)
            failure |= 2u;
        if (tir && (!close(sampleT.bsdfSample.direction, guide.direction) ||
                    !close(sampleT.bsdfSample.response, float3(1.0))))
            failure |= 4u;
    }
    return uint4(failure, 0u, 0u, 0u);
}

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x < config.y)
        outputs[id.x] = config.x == 1u ? pbrDeltaEdgeCheck(inputs[id.x])
                                       : pbrDeltaCase(inputs[id.x], config.x);
}
