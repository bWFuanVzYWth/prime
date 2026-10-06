#language slang 2026
module pbr_test;
import "../../shaders/pbr.slang";
import "model/material/labpbr.slang";
import "model/material/optical.slang";
import "model/material/translation.slang";
import "model/material/defaults.slang";
import "model/material/fresnel_code.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/components.slang";
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
    return primePbrVertex(source.baseColor, source.normal, source.normalSample.a,
                          source.specularSample.rgb, source.hasNormal, source.hasSpecular,
                          source.thinWalled, dielectric, view);
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
    if (config.x == 0u) {
        PbrTestSource source = loadSource(inputOffset);
        PrimeCanonicalOptics optical = primeDecodeOpticalSample(source.specularSample);
        uint code = optical.opticalControl & PRIME_OPTICAL_FRESNEL_MASK;
        PrimeLabPbrNormalAtlasSample normal = primeAdaptLabPbrNormalAtlas(source.normalSample);
        PrimePbrVertex vertex = testVertex(source, float3(0, 0, 1), false);
        bool metal = primePbrVertexIsConductor(vertex);
        outputs[outputOffset] = asuint(float4(
                optical.roughness, primeDecodeDielectricF0(code),
                primeCombineLabPbrNormalRoughness(optical.roughness, normal.distributionRoughness),
                primeDecodeLabPbrEmission(source.specularSample.a)));
        outputs[outputOffset + 1] = uint4(optical.opticalControl, metal ? code : 0u, 0u, 0u);
        float f0 = primeDecodeDielectricF0(vertex.control & PRIME_OPTICAL_FRESNEL_MASK);
        outputs[outputOffset + 2] = asuint(
                float4(vertex.roughness, primeFullOpenPbrF0ToIor(f0), metal ? 1.0 : 0.0,
                       float((vertex.control >> PRIME_OPTICAL_SUBSURFACE_SHIFT) & 255u) / 190.0));
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
    if (config.x == 2u || config.x == 7u || config.x == 12u) {
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
        if (config.x == 7u) {
            outputs[outputOffset + 2].yzw = uint3(
                    source.thinWalled ? 1u : 0u,
                    primePbrVertexIsThinWalled(vertex) && !primePbrVertexIsConductor(vertex) ? 1u
                                                                                             : 0u,
                    asuint(float((vertex.control >> PRIME_OPTICAL_SUBSURFACE_SHIFT) & 255u) /
                           190.0));
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
        source.normalSample = float4(0.5, 0.5, 1.0, 0.0);
        source.hasNormal = normalMapped;
        source.specularSample = float4(128.0, 11.0, 0.0, 255.0) / 255.0;
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
