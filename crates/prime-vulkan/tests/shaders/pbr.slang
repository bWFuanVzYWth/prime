#language slang 2026
module pbr_test;
import "../../shaders/pbr.slang";
import "pbr/delta.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/components.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/full/energy.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/math/spectrum.slang";
import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/opaque/traits.slang";
import "bsdf/full/model/material.slang";
import "bsdf/full/math/frame.slang";
import "bsdf/full/pt.slang";
import "bsdf/full/opaque/evaluate.slang";
import "bsdf/full/opaque/energy.slang";
import "math/transport.slang";
import "service/material/normal_mapping.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::binding(9, 0)]] Sampler3D<float4> transmissionEnergy;
[[vk::push_constant]] ConstantBuffer<uint4> config;

// Test input adapter only; production consumes the CPU-translated narrow vertex inputs.
struct PbrTestSource {
    float3 baseColor;
    float3 normal;
    float4 normalSample;
    float4 specularSample;
    bool hasNormal;
    bool hasSpecular;
    bool thinWalled;
};
PrimePbrVertex testVertex(PbrTestSource source, float3 view, bool dielectric) {
    return primePbrVertex(source.baseColor, source.normal, source.normalSample.b,
                          source.specularSample, source.normalSample.a, source.hasNormal,
                          source.hasSpecular, source.thinWalled, dielectric, view);
}

PbrTestSource loadSource(uint offset) {
    uint4 control = inputs[offset];
    PbrTestSource source;
    source.baseColor = asfloat(inputs[offset + 1]).rgb;
    source.normal = float3(0.0, 0.0, 1.0);
    source.normalSample = unpackUnorm4x8ToFloat(control.x);
    source.specularSample = unpackUnorm4x8ToFloat(control.y);
    source.hasNormal = (control.z & 1u) != 0;
    source.hasSpecular = (control.z & 2u) != 0;
    source.thinWalled = (control.z & 4u) != 0;
    return source;
}

void writeSample(uint offset, BsdfSample sampleValue) {
    outputs[offset] = asuint(float4(sampleValue.direction, sampleValue.relativeEta));
    outputs[offset + 1] = asuint(float4(sampleValue.response, sampleValue.pdf));
    outputs[offset + 2] = uint4(sampleValue.eventFlags, 0u, 0u, 0u);
}

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    uint inputOffset = i * 8u;
    uint outputOffset = i * 16u;
    for (uint index = 0u; index < 16u; ++index)
        outputs[outputOffset + index] = uint4(0u);
    PrimeFullTransmissionGgxEnergy energy = {transmissionEnergy};
    if (config.x == 16u) {
        PbrTestSource source = loadSource(inputOffset);
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float4 incident = asfloat(inputs[inputOffset + 4]);
        float4 transmitted = asfloat(inputs[inputOffset + 5]);
        float3 front = asfloat(inputs[inputOffset + 6]).xyz;
        float3 back = float3(front.xy, -front.z);
        PrimePbrVertex vertex = testVertex(source, view, false);
        PrimePbrVertex ordinary = vertex;
        ordinary.control &= ~(255u << 8u);
        BsdfEvaluation reflected =
                primeEvaluatePbrVertex(energy, vertex, view, front, incident, transmitted);
        BsdfEvaluation transmittedEval =
                primeEvaluatePbrVertex(energy, vertex, view, back, incident, transmitted);
        BsdfEvaluation baseline =
                primeEvaluatePbrVertex(energy, ordinary, view, front, incident, transmitted);
        PrimePbrSample sampled =
                primeSamplePbrVertex(energy, vertex, view, randomValue, incident, transmitted);
        BsdfEvaluation evaluated = primeEvaluatePbrVertex(
                energy, vertex, view, sampled.bsdfSample.direction, incident, transmitted);
        PrimeFullOpenPbrOnb onb = primePbrVertexFrame(vertex, view, incident, transmitted);
        float3 wi = primeFullOpenPbrOnbToLocal(onb, view);
        float3 wo = primeFullOpenPbrOnbToLocal(onb, sampled.bsdfSample.direction);
        PrimeFullCompactOpaqueState state = primeFullPtOpaqueState(
                energy, vertex.baseColor, vertex.roughness, primePbrVertexIor(vertex) / incident.x,
                primePbrVertexSubsurface(vertex), false, float3(0), float3(0), wi);
        PrimeFullCompactOpaqueEnergy albedo = primeFullCompactOpaqueEnergy(wi, state);
        PrimeDenoiseAlbedos guide =
                primePbrGuideAlbedos(energy, vertex, view, incident, transmitted);
        float3 localFront = primeFullOpenPbrOnbToLocal(onb, front);
        float3 localBack = primeFullOpenPbrOnbToLocal(onb, back);
        float3 baselineDiffuse =
                primeFullCompactLambertEval(wi, localFront, state, PRIME_FULL_OPENPBR_FLAG_ALL)
                        .value *
                state.layer.transmittanceIn;
        float roulette = sampled.bsdfSample.eventFlags != 0u
                                 ? primeRussianRouletteSurvival(
                                           sampled.bsdfSample.response / sampled.bsdfSample.pdf, 1)
                                 : 0.0;
        outputs[outputOffset] = asuint(float4(reflected.response, reflected.pdf));
        outputs[outputOffset + 1] = asuint(float4(transmittedEval.response, transmittedEval.pdf));
        outputs[outputOffset + 2] = asuint(float4(baseline.response, baseline.pdf));
        outputs[outputOffset + 3] =
                uint4(asuint(sampled.bsdfSample.direction), sampled.bsdfSample.eventFlags);
        outputs[outputOffset + 4] =
                asuint(float4(sampled.bsdfSample.response, sampled.bsdfSample.pdf));
        outputs[outputOffset + 5] = asuint(float4(evaluated.response, evaluated.pdf));
        outputs[outputOffset + 6] = asuint(float4(guide.diffuse, state.layer.baseSampleWeight));
        outputs[outputOffset + 7] =
                asuint(float4(guide.specular, state.layer.specularSampleWeight));
        outputs[outputOffset + 8] =
                asuint(float4(albedo.diffuse, primePbrVertexSubsurface(vertex)));
        outputs[outputOffset + 9] =
                asuint(float4(albedo.specular, state.diffuseMix.secondSampleWeight));
        outputs[outputOffset + 10] =
                asuint(float4(state.subsurfaceColor, state.subsurfaceAnisotropy));
        outputs[outputOffset + 11] = asuint(float4(baselineDiffuse, 0));
        outputs[outputOffset + 12] = asuint(float4(
                primeFullCompactSubsurfaceEval(wi, localFront, state, PRIME_FULL_OPENPBR_FLAG_ALL)
                        .value,
                primeFullCompactSubsurfaceReflectionProbability(state,
                                                                PRIME_FULL_OPENPBR_FLAG_ALL)));
        outputs[outputOffset + 13] = asuint(float4(
                primeFullCompactSubsurfaceEval(wi, localBack, state, PRIME_FULL_OPENPBR_FLAG_ALL)
                        .value,
                primeFullCompactSubsurfaceTransmissionProbability(state,
                                                                  PRIME_FULL_OPENPBR_FLAG_ALL)));
        outputs[outputOffset + 14] = asuint(
                float4(primeFullCompactDielectricPdf(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL),
                       wo.z, roulette, 0));
        outputs[outputOffset + 15] = asuint(float4(state.layer.transmittanceIn, 0));
        return;
    }
    if (config.x == 0u) {
        PbrTestSource source = loadSource(inputOffset);
        float roughness = source.specularSample.b;
        uint m = uint(round(source.specularSample.r * 255.0));
        uint subsurface = uint(round(source.specularSample.a * 255.0));
        PrimeCanonicalNormalSample normal = primeDecodeCanonicalNormal(source.normalSample);
        PrimePbrVertex vertex = testVertex(source, float3(0, 0, 1), false);
        float ior = primeDecodeMaterialIor(source.normalSample.a);
        float f0 = pow((ior - 1.0) / (ior + 1.0), 2.0);
        outputs[outputOffset] = asuint(float4(
                roughness, f0, primeCombineNormalRoughness(roughness, normal.distributionRoughness),
                source.specularSample.g));
        outputs[outputOffset + 1] = uint4(m | subsurface << 8u, m, 0u, 0u);
        outputs[outputOffset + 2] =
                asuint(float4(vertex.roughness, primePbrVertexIor(vertex),
                              primePbrVertexMetalness(vertex), primePbrVertexSubsurface(vertex)));
        outputs[outputOffset + 3] = asuint(float4(vertex.baseColor, source.thinWalled ? 1.0 : 0.0));
        outputs[outputOffset + 4] = asuint(float4(float3(1), 0));
        outputs[outputOffset + 5] =
                asuint(float4(normal.tangentNormal, normal.distributionRoughness));
        return;
    }
    if (config.x == 1u) {
        float4 directionEta = asfloat(inputs[inputOffset]);
        float4 responsePdf = asfloat(inputs[inputOffset + 1]);
        BsdfSample sampleValue;
        sampleValue.direction = directionEta.xyz;
        sampleValue.relativeEta = directionEta.w;
        sampleValue.response = responsePdf.xyz;
        sampleValue.pdf = responsePdf.w;
        sampleValue.eventFlags = inputs[inputOffset + 2].x;
        writeSample(outputOffset, primeSanitizeBsdfSample(sampleValue));
        BsdfEvaluation evaluation;
        evaluation.response = responsePdf.xyz;
        evaluation.pdf = responsePdf.w;
        evaluation = primeSanitizeBsdfEvaluation(evaluation);
        outputs[outputOffset + 3] = asuint(float4(evaluation.response, evaluation.pdf));
        PrimeBsdfComponents components;
        components.diffuseResponse = responsePdf.xyz;
        components.specularResponse = asfloat(inputs[inputOffset + 3]).xyz;
        components.pdf = responsePdf.w;
        components = primeSanitizeBsdfComponents(components);
        outputs[outputOffset + 4] = asuint(float4(components.diffuseResponse, components.pdf));
        outputs[outputOffset + 5] = asuint(float4(components.specularResponse, 0.0));
        outputs[outputOffset + 6] = asuint(
                float4(primeSanitizeDenoiseAlbedo(asfloat(inputs[inputOffset + 4]).xyz), 0.0));
        return;
    }
    if (config.x == 2u || config.x == 7u || config.x == 12u || config.x == 15u) {
        PbrTestSource source = loadSource(inputOffset);
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float4 incident = asfloat(inputs[inputOffset + 4]);
        float4 transmitted = asfloat(inputs[inputOffset + 5]);
        bool dielectric = config.x == 7u ? false : inputs[inputOffset].w != 0u;
        PrimePbrVertex vertex = testVertex(source, view, dielectric);
        PrimePbrSample sampled =
                primeSamplePbrVertex(energy, vertex, view, randomValue, incident, transmitted);
        writeSample(outputOffset, sampled.bsdfSample);
        outputs[outputOffset + 3] = asuint(sampled.medium);
        BsdfEvaluation evaluation = primeEvaluatePbrVertex(
                energy, vertex, view, sampled.bsdfSample.direction, incident, transmitted);
        outputs[outputOffset + 4] = asuint(float4(evaluation.response, evaluation.pdf));
        BsdfEvaluation other = primeEvaluatePbrVertex(
                energy, vertex, view, asfloat(inputs[inputOffset + 6]).xyz, incident, transmitted);
        outputs[outputOffset + 5] = asuint(float4(other.response, other.pdf));
        if (config.x == 15u) {
            // Independent endpoint composition: the mixed kernel is compared to two
            // separately prepared endpoint vertices, including their proposal measures.
            float m = primePbrVertexMetalness(vertex);
            PrimePbrVertex nonmetal = vertex;
            nonmetal.control &= ~255u;
            PrimePbrVertex metal = vertex;
            metal.control |= 255u;
            // Keep the mathematical oracle at the original local direction. Near the
            // delta threshold a float world/ONB roundtrip can perturb a very sharp GGX peak.
            PrimeFullOpenPbrOnb onb = primePbrVertexFrame(vertex, view, incident, transmitted);
            float3 wi = primeFullOpenPbrOnbToLocal(onb, view);
            PrimeFullCompactOpaqueState mixedState =
                    primeFullPtMetallicState(energy, vertex.baseColor, vertex.roughness,
                                             primePbrVertexIor(vertex) / incident.x,
                                             primePbrVertexSubsurface(vertex), m, wi);
            PrimeFullOpenPbrSample localSample =
                    primeFullPtOpaqueSample(wi, randomValue, mixedState);
            PrimeFullCompactOpaqueState dState = primeFullPtOpaqueState(
                    energy, vertex.baseColor, vertex.roughness,
                    primePbrVertexIor(vertex) / incident.x, primePbrVertexSubsurface(vertex), false,
                    float3(0.0), float3(0.0), wi);
            PrimeFullCompactOpaqueState cState =
                    primeFullPtOpaqueState(energy, vertex.baseColor, vertex.roughness, 1.0, 0.0,
                                           true, saturate(vertex.baseColor), float3(1.0), wi);
            PrimeFullCompactOpaqueEvaluation d =
                    primeFullCompactOpaqueEvaluate(wi, localSample.wo, dState);
            PrimeFullCompactOpaqueEvaluation c =
                    primeFullCompactOpaqueEvaluate(wi, localSample.wo, cState);
            outputs[outputOffset + 6] = asuint(
                    float4(lerp(d.throughput.value, c.throughput.value, m), lerp(d.pdf, c.pdf, m)));
            PrimePbrSample sd = primeSamplePbrVertex(
                    energy, nonmetal, view, float3(randomValue.xy, 0.0), incident, transmitted);
            PrimePbrSample sc = primeSamplePbrVertex(
                    energy, metal, view, float3(randomValue.xy, 0.0), incident, transmitted);
            outputs[outputOffset + 7] =
                    asuint(float4(lerp(sd.bsdfSample.response, sc.bsdfSample.response, m),
                                  lerp(sd.bsdfSample.pdf, sc.bsdfSample.pdf, m)));
            PrimeDenoiseAlbedos ad =
                    primePbrGuideAlbedos(energy, nonmetal, view, incident, transmitted);
            PrimeDenoiseAlbedos ac =
                    primePbrGuideAlbedos(energy, metal, view, incident, transmitted);
            PrimeDenoiseAlbedos actual =
                    primePbrGuideAlbedos(energy, vertex, view, incident, transmitted);
            outputs[outputOffset + 8] = asuint(float4(lerp(ad.diffuse, ac.diffuse, m), 0.0));
            outputs[outputOffset + 9] = asuint(float4(lerp(ad.specular, ac.specular, m), 0.0));
            outputs[outputOffset + 10] = asuint(float4(actual.diffuse, m));
            outputs[outputOffset + 11] = asuint(float4(actual.specular, 0.0));
            outputs[outputOffset + 12] =
                    uint4(primePbrVertexIsDiscreteOnly(vertex, incident, transmitted) ? 1u : 0u,
                          (sd.bsdfSample.eventFlags & sc.bsdfSample.eventFlags &
                           PRIME_BSDF_EVENT_DISCRETE) != 0u
                                  ? 1u
                                  : 0u,
                          0u, 0u);
            outputs[outputOffset + 13] =
                    asuint(float4(localSample.throughput.value, localSample.pdf));
            PrimeFullOpenPbrSample endpointSample =
                    primeFullPtOpaqueSample(wi, randomValue, m == 1.0 ? cState : dState);
            outputs[outputOffset + 14] =
                    asuint(float4(endpointSample.throughput.value, endpointSample.pdf));
        }
        if (config.x == 7u) {
            outputs[outputOffset + 2].yzw = uint3(
                    source.thinWalled ? 1u : 0u,
                    primePbrVertexIsThinWalled(vertex) && !primePbrVertexIsConductor(vertex) ? 1u
                                                                                             : 0u,
                    asuint(primePbrVertexSubsurface(vertex)));
            outputs[outputOffset + 5] =
                    asuint(float4(0.0, primeDirectLightingMisPdf(false, sampled.bsdfSample),
                                  primeDirectLightingMisPdf(true, sampled.bsdfSample), 0.0));
        }
        if (config.x == 12u && sampled.bsdfSample.eventFlags != 0u) {
            bool reflection = (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u &&
                              (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u;
            float lightPdf = 0.2;
            float neeWeight = reflection ? primePowerHeuristicValue(lightPdf, evaluation.pdf) : 0.0;
            float bsdfWeight =
                    reflection ? primePowerHeuristicValue(sampled.bsdfSample.pdf, lightPdf) : 1.0;
            float3 emission = float3(0.7, 1.3, 2.1);
            float3 nee = reflection ? primeTripleProduct(evaluation.response, emission,
                                                         primePowerHeuristicOverPdfValue(
                                                                 lightPdf, evaluation.pdf))
                                    : float3(0.0);
            float3 continuation = primeTripleProduct(
                    sampled.bsdfSample.response, emission,
                    reflection ? primePowerHeuristicOverPdfValue(sampled.bsdfSample.pdf, lightPdf)
                               : 1.0 / sampled.bsdfSample.pdf);
            outputs[outputOffset + 8] =
                    asuint(float4(neeWeight, bsdfWeight, neeWeight + bsdfWeight,
                                  primeDirectLightingMisPdf(false, sampled.bsdfSample)));
            outputs[outputOffset + 9] =
                    asuint(float4(nee * lightPdf + continuation * sampled.bsdfSample.pdf, 0));
            outputs[outputOffset + 10] = asuint(float4(sampled.bsdfSample.response * emission, 0));
        }
        return;
    }
    if (config.x == 8u) {
        float3 geometric = asfloat(inputs[inputOffset]).xyz;
        float3 shading = asfloat(inputs[inputOffset + 1]).xyz;
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 scatter = asfloat(inputs[inputOffset + 3]).xyz;
        bool normalMapped = inputs[inputOffset + 4].x != 0u;
        bool dielectric = inputs[inputOffset + 4].y != 0u;
        bool supported = primeGeometricEvaluationSupport(normalMapped, geometric, shading, view,
                                                         scatter, dielectric);
        PbrTestSource source = {};
        source.baseColor = float3(0.4, 0.6, 0.2);
        source.normal = shading;
        source.normalSample = float4(128.0 / 255.0, 128.0 / 255.0, 0.0, 85.0 / 255.0);
        source.hasNormal = normalMapped;
        source.specularSample = float4(0.0, 0.0, 128.0, 0.0) / 255.0;
        source.hasSpecular = true;
        PrimePbrVertex vertex = testVertex(source, view, dielectric);
        BsdfEvaluation evaluated =
                primeEvaluatePbrVertex(energy, vertex, view, scatter, float4(1.0, 0.0, 0.0, 0.0),
                                       float4(1.5, 0.0, 0.0, 0.0));
        outputs[outputOffset] = uint4(supported ? 1u : 0u, 0u, 0u, 0u);
        outputs[outputOffset + 1] = asuint(float4(evaluated.response, evaluated.pdf));
        if (!supported)
            evaluated = primeInvalidBsdfEvaluation();
        outputs[outputOffset + 2] = asuint(float4(evaluated.response, evaluated.pdf));
        return;
    }
    if (config.x == 5u) {
        float3 geometric = asfloat(inputs[inputOffset]).xyz;
        float3 view = asfloat(inputs[inputOffset + 1]).xyz;
        float3 original = asfloat(inputs[inputOffset + 2]).xyz;
        float3 corrected = primeEnsureValidNormalMappedReflection(geometric, view, original);
        bool front = dot(geometric, view) >= 0.0;
        float3 facing = front ? corrected : -corrected;
        float3 reflected = 2.0 * dot(facing, view) * facing - view;
        outputs[outputOffset] = asuint(float4(corrected, dot(corrected, corrected)));
        outputs[outputOffset + 1] =
                asuint(float4(reflected, dot(front ? geometric : -geometric, reflected)));
        return;
    }
    if (config.x == 14u) {
        PbrTestSource source = loadSource(inputOffset);
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 scatter = asfloat(inputs[inputOffset + 6]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        PrimeFullOpenPbrMaterial material =
                primeFullCompactMaterialFromMetallic(source.baseColor, 0.5, 0.0, float3(0, 0, 1));
        material.weight.subsurface = 1.0;
        material.geometry.thinWalled = source.thinWalled ? 1u : 0u;
        material.subsurface.color = source.baseColor;
        bool supported = primeFullCompactOpaqueSupports(material);
        outputs[outputOffset + 7] = uint4(supported ? 1u : 0u, 0, 0, 0);
        if (!supported)
            return;
        PrimeFullCompactOpaqueState state =
                primeFullCompactOpaqueStateInit(energy, material, view, 1.0);
        PrimeFullOpenPbrThroughput evaluated =
                primeFullCompactSubsurfaceEval(view, scatter, state, PRIME_FULL_OPENPBR_FLAG_ALL);
        float pdf =
                primeFullCompactSubsurfacePdf(view, scatter, state, PRIME_FULL_OPENPBR_FLAG_ALL);
        outputs[outputOffset] = asuint(float4(evaluated.value, pdf));
        PrimeFullOpenPbrSample sampled = primeFullCompactSubsurfaceSample(
                view, randomValue, state, PRIME_FULL_OPENPBR_FLAG_ALL);
        outputs[outputOffset + 1] = asuint(float4(sampled.wo, sampled.eta));
        outputs[outputOffset + 2] = asuint(float4(sampled.throughput.value, sampled.pdf));
        outputs[outputOffset + 3] = uint4(sampled.throughput.flags, evaluated.flags, 0, 0);
        return;
    }
    float4 first = asfloat(inputs[inputOffset]);
    float4 second = asfloat(inputs[inputOffset + 1]);
    float4 factors = asfloat(inputs[inputOffset + 2]);
    outputs[outputOffset] = asuint(float4(primeProductOver(first.xyz, second.xyz, factors.x), 0.0));
    outputs[outputOffset + 1] =
            asuint(float4(primeTripleProduct(first.xyz, second.xyz, factors.y), 0.0));
    outputs[outputOffset + 2] =
            asuint(float4(primePowerHeuristicValue(factors.z, factors.w),
                          primePowerHeuristicOverPdfValue(factors.z, factors.w), 0.0, 0.0));
}
