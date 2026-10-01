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
import "service/bsdf/support/conversion.slang";
import "bsdf/common/common.slang";
import "bsdf/common/material.slang";
import "bsdf/lite/bsdf.slang";
import "math/transport.slang";
import "service/material/normal_mapping.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::push_constant]] ConstantBuffer<uint4> config;

PrimePbrSource loadSource(uint offset) {
    uint4 control = inputs[offset];
    PrimePbrSource source;
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

static const uint PRIME_TEST_FAILURE_NAN = 1 << 0;
static const uint PRIME_TEST_FAILURE_POSITIVE_INFINITY = 1 << 1;
static const uint PRIME_TEST_FAILURE_NEGATIVE_INFINITY = 1 << 2;
static const uint PRIME_TEST_FAILURE_RANGE = 1 << 3;
static const uint PRIME_TEST_FAILURE_UNIT_LENGTH = 1 << 4;
static const uint PRIME_TEST_FAILURE_HEMISPHERE = 1 << 5;
static const uint PRIME_TEST_FAILURE_ORTHOGONAL = 1 << 6;
static const uint PRIME_TEST_FAILURE_ROUND_TRIP = 1 << 7;
static const uint PRIME_TEST_FAILURE_IDENTITY = 1 << 8;
static const uint PRIME_TEST_FAILURE_SYMMETRY = 1 << 9;
static const uint PRIME_TEST_FAILURE_RECIPROCITY = 1 << 10;
static const uint PRIME_TEST_FAILURE_EVENT = 1 << 11;
static const uint PRIME_TEST_FAILURE_PDF = 1 << 12;
static const uint PRIME_TEST_FAILURE_VALUE = 1 << 13;
static const uint PRIME_TEST_FAILURE_STATE = 1 << 14;
static const uint PRIME_TEST_FAILURE_INDEX = 1 << 15;

uint primeTestClassify(float value) {
    if (isnan(value))
        return PRIME_TEST_FAILURE_NAN;
    if (isinf(value)) {
        return value < 0.0 ? PRIME_TEST_FAILURE_NEGATIVE_INFINITY
                           : PRIME_TEST_FAILURE_POSITIVE_INFINITY;
    }
    return 0;
}

uint primeTestClassify(float2 value) {
    return primeTestClassify(value.x) | primeTestClassify(value.y);
}

uint primeTestClassify(float3 value) {
    return primeTestClassify(value.x) | primeTestClassify(value.y) | primeTestClassify(value.z);
}

uint primeTestClassify(float4 value) {
    return primeTestClassify(value.xyz) | primeTestClassify(value.w);
}

bool primeTestClose(float first, float second, float absoluteTolerance, float relativeTolerance) {
    if (primeTestClassify(first) != 0 || primeTestClassify(second) != 0)
        return false;
    float scale = max(abs(first), abs(second));
    return abs(first - second) <= absoluteTolerance + relativeTolerance * scale;
}

bool primeTestClose(float3 first, float3 second, float absoluteTolerance, float relativeTolerance) {
    return primeTestClose(first.x, second.x, absoluteTolerance, relativeTolerance) &&
           primeTestClose(first.y, second.y, absoluteTolerance, relativeTolerance) &&
           primeTestClose(first.z, second.z, absoluteTolerance, relativeTolerance);
}

PrimeOpenPbrVolumeStack primePbrFixtureStack() {
    PrimeOpenPbrVolumeStack result = {};
    return result;
}
struct PrimeLiteTestResult {
    uint mask;
    uint kind;
    float3 evaluation;
    float evaluationPdf;
    float3 diffuse;
    float3 specular;
    float3 energy;
    PrimeOpenPbrSampleResult sample;
    float3 sampledEvaluation;
    float sampledEvaluationPdf;
};

float primeLiteTestParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeLiteTestDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeOpenPbrVolumeStack primeLiteTestStack(bool inside, PrimeOpenPbrMaterial material) {
    PrimeOpenPbrVolumeStack stack;
    PrimeOpenPbrVolume empty;
    empty.extinction = float3(0.0);
    empty.albedo = float3(0.0);
    empty.anisotropy = 0.0;
    empty.ior = 1.0;
    stack.values[0] = empty;
    stack.values[1] = empty;
    stack.count = 0u;
    if (inside) {
        stack.values[0] = primeOpenPbrVolumeFromTransmission(material.transmission);
        stack.values[0].ior = material.specular.ior;
        stack.count = 1u;
    }
    return stack;
}

void primeLiteTestClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeLiteTestClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeLiteTestNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeLiteTestNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
}

bool primeLiteTestUnit(float3 value) {
    return primeTestClose(length(value), 1.0, 3.0e-5, 3.0e-5);
}

void primeLiteTestObserve(inout PrimeLiteTestResult result, PrimeOpenPbrThroughput throughput,
                          float pdf) {
    primeLiteTestClassify(result.mask, throughput.value);
    primeLiteTestClassify(result.mask, pdf);
    if (!primeLiteTestNonnegative(throughput.value) || !primeLiteTestNonnegative(pdf)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
}

PrimeLiteTestResult primeLiteTestRun(uint4 encoded[4]) {
    PrimeLiteTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    result.evaluation = float3(0.0);
    result.evaluationPdf = 0.0;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    result.energy = float3(0.0);
    result.sample = primeLiteZeroSampleResult(
            0.0, primeLiteTestStack(false, primeCommonMaterialFromMetallic(float3(1.0), 0.5, 0.0,
                                                                           float3(0.0, 0.0, 1.0))));
    result.sampledEvaluation = float3(0.0);
    result.sampledEvaluationPdf = 0.0;

    float roughness = primeLiteTestParam(encoded, 0u);
    float ior = primeLiteTestParam(encoded, 1u);
    float3 wi = primeLiteTestDirection(primeLiteTestParam(encoded, 2u),
                                       primeLiteTestParam(encoded, 3u));
    float3 wo = primeLiteTestDirection(primeLiteTestParam(encoded, 4u),
                                       primeLiteTestParam(encoded, 5u));
    float3 randomValue = float3(primeLiteTestParam(encoded, 6u), primeLiteTestParam(encoded, 7u),
                                primeLiteTestParam(encoded, 8u));
    float3 color = float3(primeLiteTestParam(encoded, 9u), primeLiteTestParam(encoded, 10u),
                          primeLiteTestParam(encoded, 11u));
    float inverseOutsideIor = primeLiteTestParam(encoded, 12u);
    float rayT = primeLiteTestParam(encoded, 13u);
    bool alternate = primeLiteTestParam(encoded, 14u) >= 0.5;

    float normalF0 = primeOpenPbrIorToF0(ior);
    float normalFresnel = primeLiteDielectricFresnel(ior, 1.0);
    float grazingFresnel = primeLiteDielectricFresnel(ior, 0.0);
    primeLiteTestClassify(result.mask, normalFresnel);
    primeLiteTestClassify(result.mask, grazingFresnel);
    if (!primeTestClose(normalFresnel, normalF0, 2.0e-6, 2.0e-5) || normalFresnel < 0.0 ||
        normalFresnel > 1.0 || grazingFresnel < 0.0 || grazingFresnel > 1.0) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }

    if (result.kind <= 2u) {
        bool conductor = result.kind == 1u;
        PrimeOpenPbrMaterial material = primeCommonMaterialFromMetallic(
                color, roughness, conductor ? 1.0 : 0.0, float3(0.0, 0.0, 1.0));
        material.specular.ior = ior;
        if (result.kind == 2u) {
            material.weight.subsurface = 0.5;
            material.subsurface.color = color;
            material.subsurface.scatterAnisotropy = alternate ? 0.6 : -0.35;
            material.geometry.thinWalled = alternate ? 1u : 0u;
        }
        if (!primeLiteOpaqueSupports(material)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeOpenPbrMaterial unsupported = material;
        unsupported.weight.coat = 0.25;
        if (primeLiteOpaqueSupports(unsupported)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeLiteOpaqueState state =
                primeLiteOpaqueStateInit(primeLiteEnergy(), material, wi, inverseOutsideIor, rayT);
        PrimeLiteOpaqueEvaluation evaluated = primeLiteOpaqueEvaluate(wi, wo, state);
        PrimeLiteOpaqueComponents components = primeLiteOpaqueEvaluateComponents(wi, wo, state);
        PrimeLiteOpaqueEnergy energy = primeLiteOpaqueEnergy(wi, state);
        result.evaluation = evaluated.throughput.value;
        result.evaluationPdf = evaluated.pdf;
        result.diffuse = components.diffuse;
        result.specular = components.specular;
        result.energy = energy.diffuse + energy.specular;
        PrimeOpenPbrVolumeStack stack = primeLiteTestStack(false, material);
        result.sample = primeLiteOpaqueSample(wi, randomValue, state, stack);
        if (result.kind != 1u &&
            (!(state.layer.transmittanceIn >= 0.0) || !(state.layer.transmittanceIn <= 1.0) ||
             !(state.layer.specularScale >= 1.0) ||
             primeTestClassify(state.layer.transmittanceIn) != 0u ||
             primeTestClassify(state.layer.specularScale) != 0u)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if (!primeTestClose(result.evaluation, result.diffuse + result.specular, 5.0e-6, 3.0e-5) ||
            !primeTestClose(result.evaluationPdf, components.pdf, 5.0e-6, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
        if (result.kind == 1u && wi.z > 0.0 && wo.z > 0.0 &&
            !primeLiteMicrofacetEffectivelySmooth(state.microfacet)) {
            PrimeLiteOpaqueState reverseState = primeLiteOpaqueStateInit(
                    primeLiteEnergy(), material, wo, inverseOutsideIor, rayT);
            PrimeLiteOpaqueEvaluation reverse = primeLiteOpaqueEvaluate(wo, wi, reverseState);
            float3 forwardBsdf = result.evaluation / max(wo.z, 1.0e-6);
            float3 reverseBsdf = reverse.throughput.value / max(wi.z, 1.0e-6);
            if (!primeTestClose(forwardBsdf, reverseBsdf, 2.0e-4, 1.5e-3)) {
                result.mask |= PRIME_TEST_FAILURE_RECIPROCITY;
            }
        }
        if (result.kind == 0u && wi.z > 0.0) {
            float3 normalLight = float3(0.0, 0.0, 1.0);
            float3 grazingLight = primeLiteTestDirection(0.01, 0.7);
            PrimeLiteOpaqueComponents normalComponents =
                    primeLiteOpaqueEvaluateComponents(wi, normalLight, state);
            PrimeLiteOpaqueComponents grazingComponents =
                    primeLiteOpaqueEvaluateComponents(wi, grazingLight, state);
            float3 normalDiffuseBrdf = normalComponents.diffuse;
            float3 grazingDiffuseBrdf = grazingComponents.diffuse / grazingLight.z;
            if (!primeTestClose(normalDiffuseBrdf, grazingDiffuseBrdf, 2.0e-5, 2.0e-4)) {
                result.mask |= PRIME_TEST_FAILURE_VALUE;
            }

            PrimeOpenPbrMaterial grazingMaterial =
                    primeCommonMaterialFromMetallic(float3(1.0), 0.5, 0.0, float3(0.0, 0.0, 1.0));
            grazingMaterial.specular.ior = 1.5;
            PrimeLiteOpaqueState normalState = primeLiteOpaqueStateInit(
                    primeLiteEnergy(), grazingMaterial, normalLight, 1.0, rayT);
            PrimeLiteOpaqueState grazingState = primeLiteOpaqueStateInit(
                    primeLiteEnergy(), grazingMaterial, grazingLight, 1.0, rayT);
            float3 normalViewDiffuse =
                    primeLiteOpaqueEvaluateComponents(normalLight, normalLight, normalState)
                            .diffuse;
            float3 grazingViewDiffuse =
                    primeLiteOpaqueEvaluateComponents(grazingLight, normalLight, grazingState)
                            .diffuse;
            if (any(grazingViewDiffuse < 0.5 * normalViewDiffuse)) {
                result.mask |= PRIME_TEST_FAILURE_VALUE;
            }
        }
        if (result.sample.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE &&
            !primeOpenPbrIsDelta(result.sample.bsdfSample.throughput.flags)) {
            PrimeLiteOpaqueEvaluation sampled =
                    primeLiteOpaqueEvaluate(wi, result.sample.bsdfSample.wo, state);
            result.sampledEvaluation = sampled.throughput.value;
            result.sampledEvaluationPdf = sampled.pdf;
        }
    } else if (result.kind == 3u) {
        PrimeOpenPbrMaterial material =
                primeCommonMaterialFromMetallic(color, roughness, 0.0, float3(0.0, 0.0, 1.0));
        material.specular.ior = 1.45;
        material.geometry.thinWalled = 1u;
        material.weight.transmission = 0.37;
        material.transmission.color = color;
        material.transmission.depth = 0.0;
        if (!primeLiteFoliageSupports(material)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeLiteFoliageState state =
                primeLiteFoliageStateInit(primeLiteEnergy(), material, wi, inverseOutsideIor, rayT);
        PrimeLiteFoliageEvaluation evaluated = primeLiteFoliageEvaluate(wi, wo, state);
        PrimeLiteFoliageComponents components = primeLiteFoliageEvaluateComponents(wi, wo, state);
        PrimeLiteFoliageEnergy energy = primeLiteFoliageEnergy(primeLiteEnergy(), wi, state);
        result.evaluation = evaluated.throughput.value;
        result.evaluationPdf = evaluated.pdf;
        result.diffuse = components.diffuse;
        result.specular = components.specular;
        result.energy = energy.diffuse + energy.specular;
        result.sample =
                primeLiteFoliageSample(wi, randomValue, state, primeLiteTestStack(false, material));
        if (!primeTestClose(result.evaluation, result.diffuse + result.specular, 5.0e-6, 3.0e-5) ||
            !primeTestClose(result.evaluationPdf, components.pdf, 5.0e-6, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
        if (result.sample.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE &&
            !primeOpenPbrIsDelta(result.sample.bsdfSample.throughput.flags)) {
            PrimeLiteFoliageEvaluation sampled =
                    primeLiteFoliageEvaluate(wi, result.sample.bsdfSample.wo, state);
            result.sampledEvaluation = sampled.throughput.value;
            result.sampledEvaluationPdf = sampled.pdf;
        }
    } else {
        bool thinWalled = result.kind == 5u;
        PrimeOpenPbrMaterial material =
                primeCommonMaterialFromMetallic(float3(1.0), roughness, 0.0, float3(0.0, 0.0, 1.0));
        material.weight.transmission = 1.0;
        material.specular.ior = ior;
        material.geometry.thinWalled = thinWalled ? 1u : 0u;
        material.transmission.color = color;
        material.transmission.depth = thinWalled ? 0.0 : 1.0;
        if (!primeLiteTransmissionSupports(material)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeOpenPbrVolumeStack stack = primeLiteTestStack(wi.z < 0.0, material);
        uint originalStackCount = stack.count;
        PrimeLiteTransmissionState state = primeLiteTransmissionStateInit(
                primeLiteEnergy(), material, wi, inverseOutsideIor, rayT);
        state = primeLiteTransmissionInterfaceState(primeLiteEnergy(), wi, state);
        PrimeLiteTransmissionEvaluation evaluated =
                primeLiteTransmissionEvaluate(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
        result.evaluation = evaluated.throughput.value;
        result.evaluationPdf = evaluated.pdf;
        result.specular = evaluated.throughput.value;
        result.energy =
                primeLiteTransmissionEnergy(primeLiteEnergy(), wi, state, PRIME_OPENPBR_FLAG_ALL);
        result.sample =
                primeLiteTransmissionSample(wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL, stack);
        uint sampleFlags = result.sample.bsdfSample.throughput.flags;
        if (sampleFlags != PRIME_OPENPBR_FLAG_NONE && !primeOpenPbrIsDelta(sampleFlags)) {
            PrimeLiteTransmissionEvaluation sampled = primeLiteTransmissionEvaluate(
                    wi, result.sample.bsdfSample.wo, state, PRIME_OPENPBR_FLAG_ALL);
            result.sampledEvaluation = sampled.throughput.value;
            result.sampledEvaluationPdf = sampled.pdf;
        }
        if (sampleFlags != PRIME_OPENPBR_FLAG_NONE) {
            uint expectedStackCount = originalStackCount;
            if (!thinWalled && primeOpenPbrIsTransmissive(sampleFlags)) {
                expectedStackCount = wi.z >= 0.0 ? 1u : 0u;
            }
            if (result.sample.volumeStack.count != expectedStackCount) {
                result.mask |= PRIME_TEST_FAILURE_STATE;
            }
        }
    }

    PrimeOpenPbrThroughput evaluationThroughput;
    evaluationThroughput.value = result.evaluation;
    evaluationThroughput.flags = all(result.evaluation == float3(0.0))
                                         ? PRIME_OPENPBR_FLAG_NONE
                                         : PRIME_OPENPBR_FLAG_SPECULAR;
    primeLiteTestObserve(result, evaluationThroughput, result.evaluationPdf);
    primeLiteTestClassify(result.mask, result.diffuse);
    primeLiteTestClassify(result.mask, result.specular);
    primeLiteTestClassify(result.mask, result.energy);
    if (!primeLiteTestNonnegative(result.diffuse) || !primeLiteTestNonnegative(result.specular) ||
        !primeLiteTestNonnegative(result.energy) || any(result.energy > float3(1.0005))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }

    uint flags = result.sample.bsdfSample.throughput.flags;
    if (flags != PRIME_OPENPBR_FLAG_NONE) {
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.throughput.value);
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.pdf);
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.eta);
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.wo);
        if (!primeLiteTestNonnegative(result.sample.bsdfSample.throughput.value) ||
            !(result.sample.bsdfSample.pdf > 0.0) || !(result.sample.bsdfSample.eta > 0.0) ||
            !primeLiteTestUnit(result.sample.bsdfSample.wo)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        bool reflected = wi.z * result.sample.bsdfSample.wo.z > 0.0;
        if (reflected != primeOpenPbrIsReflective(flags) ||
            reflected == primeOpenPbrIsTransmissive(flags)) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        if (!primeOpenPbrIsDelta(flags)) {
            primeLiteTestClassify(result.mask, result.sampledEvaluation);
            primeLiteTestClassify(result.mask, result.sampledEvaluationPdf);
            if (!primeLiteTestNonnegative(result.sampledEvaluation) ||
                !(result.sampledEvaluationPdf > 0.0) ||
                all(result.sampledEvaluation == float3(0.0))) {
                result.mask |= PRIME_TEST_FAILURE_PDF;
            }
        }
    }
    return result;
}

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    uint inputOffset = i * 8u;
    uint outputOffset = i * 16u;
    for (uint index = 0u; index < 16u; ++index)
        outputs[outputOffset + index] = uint4(0u);
    if (config.x == 13u) {
        PrimePbrSource source = loadSource(inputOffset);
        source.normal = asfloat(inputs[inputOffset + 7]).xyz;
        bool dielectric = (inputs[inputOffset].z & 8u) != 0u;
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float4 incident = asfloat(inputs[inputOffset + 4]);
        float4 transmitted = asfloat(inputs[inputOffset + 5]);
        float3 scatter = asfloat(inputs[inputOffset + 6]).xyz;
        PrimePbrVertex vertex = primePbrVertex(source, view, dielectric);
        PrimePbrSample original =
                primeSamplePbr(source, view, randomValue, incident, transmitted, dielectric);
        PrimePbrSample compact =
                primeSamplePbrVertex(vertex, view, randomValue, incident, transmitted);
        writeSample(outputOffset, original.bsdfSample);
        outputs[outputOffset + 3] = asuint(original.medium);
        BsdfEvaluation evaluated =
                primeEvaluatePbr(source, view, scatter, incident, transmitted, dielectric);
        outputs[outputOffset + 4] = asuint(float4(evaluated.response, evaluated.pdf));
        evaluated = primeEvaluatePbr(source, view, original.bsdfSample.direction, incident,
                                     transmitted, dielectric);
        outputs[outputOffset + 5] = asuint(float4(evaluated.response, evaluated.pdf));
        writeSample(outputOffset + 6, compact.bsdfSample);
        outputs[outputOffset + 9] = asuint(compact.medium);
        evaluated = primeEvaluatePbrVertex(vertex, view, scatter, incident, transmitted);
        outputs[outputOffset + 10] = asuint(float4(evaluated.response, evaluated.pdf));
        evaluated = primeEvaluatePbrVertex(vertex, view, compact.bsdfSample.direction, incident,
                                           transmitted);
        outputs[outputOffset + 11] = asuint(float4(evaluated.response, evaluated.pdf));
        outputs[outputOffset + 12] = asuint(float4(vertex.baseColor, vertex.roughness));
        outputs[outputOffset + 13] = uint4(asuint(vertex.normal), vertex.control);
        return;
    }
    if (config.x == 12u) {
        PrimePbrSource source = loadSource(inputOffset);
        PrimeOpenPbrMaterial material = primePbrMaterial(source);
        uint kind = inputs[inputOffset + 7].x;
        bool generic = kind == 3u || inputs[inputOffset + 7].y != 0u;
        if (kind == 3u) {
            material.weight.transmission = 0.37;
            material.geometry.thinWalled = 1u;
            material.transmission.color = float3(0.3, 0.5, 0.7);
            material.transmission.depth = 0.0;
        }
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float4 incident = asfloat(inputs[inputOffset + 4]);
        float4 transmitted = asfloat(inputs[inputOffset + 5]);
        PrimePbrSample sampled =
                generic ? primeSamplePbrMaterial(material, view, randomValue, incident, transmitted)
                        : primeSamplePbr(source, view, randomValue, incident, transmitted, false);
        writeSample(outputOffset, sampled.bsdfSample);
        outputs[outputOffset + 3] = asuint(sampled.medium);
        PrimeOpenPbrSample raw;
        if (kind == 3u) {
            PrimeLiteFoliageState state = primeLiteFoliageStateInit(primeLiteEnergy(), material,
                                                                    view, 1.0 / incident.x, 0.0);
            raw = primeLiteFoliageSample(view, randomValue, state, primePbrFixtureStack())
                          .bsdfSample;
        } else {
            PrimeLiteOpaqueState state = primeLiteOpaqueStateInit(primeLiteEnergy(), material, view,
                                                                  1.0 / incident.x, 0.0);
            raw = primeLiteOpaqueSample(view, randomValue, state, primePbrFixtureStack())
                          .bsdfSample;
        }
        outputs[outputOffset + 5] = asuint(float4(raw.throughput.value, raw.pdf));
        outputs[outputOffset + 6] = asuint(float4(raw.wo, raw.eta));
        outputs[outputOffset + 7] =
                uint4(primeOpenPbrToBsdfEventFlags(raw.throughput.flags), 0u, 0u, 0u);
        if (sampled.bsdfSample.eventFlags == 0u)
            return;
        BsdfEvaluation evaluated =
                generic ? primeEvaluatePbrMaterial(material, view, sampled.bsdfSample.direction,
                                                   incident, transmitted)
                        : primeEvaluatePbr(source, view, sampled.bsdfSample.direction, incident,
                                           transmitted, false);
        outputs[outputOffset + 4] = asuint(float4(evaluated.response, evaluated.pdf));
        bool continuousReflection =
                (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u &&
                (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u;
        float lightPdf = 0.2;
        float neeWeight =
                continuousReflection ? primePowerHeuristicValue(lightPdf, evaluated.pdf) : 0.0;
        float bsdfWeight = continuousReflection
                                   ? primePowerHeuristicValue(sampled.bsdfSample.pdf, lightPdf)
                                   : 1.0;
        float3 emission = float3(0.7, 1.3, 2.1);
        float3 nee = continuousReflection ? primeTripleProduct(evaluated.response, emission,
                                                               primePowerHeuristicOverPdfValue(
                                                                       lightPdf, evaluated.pdf))
                                          : float3(0.0);
        float3 continuation = primeTripleProduct(
                sampled.bsdfSample.response, emission,
                continuousReflection
                        ? primePowerHeuristicOverPdfValue(sampled.bsdfSample.pdf, lightPdf)
                        : 1.0 / sampled.bsdfSample.pdf);
        // Compare the two techniques' density-weighted contributions at this same sampled
        // direction. Their sum must recover the whole integrand with complementary MIS weights.
        float3 integrand = primeTripleProduct(nee, float3(lightPdf), 1.0) +
                           primeTripleProduct(continuation, float3(sampled.bsdfSample.pdf), 1.0);
        outputs[outputOffset + 8] =
                asuint(float4(neeWeight, bsdfWeight, neeWeight + bsdfWeight,
                              primeDirectLightingMisPdf(false, sampled.bsdfSample)));
        outputs[outputOffset + 9] = asuint(float4(integrand, 0.0));
        outputs[outputOffset + 10] = asuint(float4(sampled.bsdfSample.response * emission, 0.0));
        outputs[outputOffset + 11] = asuint(float4(
                continuousReflection ? neeWeight + primePowerHeuristicValue(raw.pdf, lightPdf)
                                     : 1.0,
                0.0, 0.0, 0.0));
        return;
    }
    if (config.x == 10u) {
        PrimeOpenPbrMaterial material = primeCommonMaterialFromMetallic(float3(0.4, 0.6, 0.2), 0.5,
                                                                        0.0, float3(0.0, 0.0, 1.0));
        uint kind = inputs[inputOffset].x;
        switch (kind) {
        case 0u:
            material.weight.coat = 0.25;
            break;
        case 1u:
            material.weight.fuzz = 0.25;
            break;
        case 2u:
            material.weight.thinFilm = 0.25;
            break;
        case 3u:
            material.weight.diffraction = 0.25;
            break;
        case 4u:
            material.weight.metalness = 0.5;
            break;
        case 5u:
            material.weight.diffuseRoughness = 0.25;
            break;
        case 6u:
            material.weight.base = 0.5;
            break;
        case 7u:
            material.weight.specular = 0.5;
            break;
        case 8u:
            material.weight.subsurface = 1.1;
            break;
        case 9u:
            material.weight.subsurface = -0.1;
            break;
        case 10u:
            material.weight.transmission = 0.5;
            break;
        case 11u:
            material.weight.transmission = 1.0;
            material.transmission.dispersionScale = 1.0;
            break;
        case 12u:
            material.weight.metalness = 1.0;
            material.weight.subsurface = 0.5;
            break;
        case 101u:
            material.weight.metalness = 1.0;
            break;
        case 102u:
            material.weight.subsurface = 0.5;
            break;
        case 103u:
            material.weight.transmission = 0.37;
            material.geometry.thinWalled = 1u;
            material.transmission.color = float3(0.3, 0.5, 0.7);
            break;
        case 104u:
            material.weight.transmission = 1.0;
            break;
        case 105u:
            material.weight.transmission = 1.0;
            material.geometry.thinWalled = 1u;
            break;
        }
        float4 incident = float4(1.0, 0.0, 0.0, 0.0);
        float4 transmitted = float4(1.5, 0.1, 0.2, 0.3);
        float3 view = float3(0.0, 0.0, 1.0);
        BsdfEvaluation evaluated =
                primeEvaluatePbrMaterial(material, view, view, incident, transmitted);
        PrimePbrSample sampled = primeSamplePbrMaterial(material, view, float3(0.2, 0.3, 0.4),
                                                        incident, transmitted);
        outputs[outputOffset] = uint4(primeLiteOpaqueSupports(material) ? 1u : 0u,
                                      primeLiteFoliageSupports(material) ? 1u : 0u,
                                      primeLiteTransmissionSupports(material) ? 1u : 0u, 0u);
        outputs[outputOffset + 1] = asuint(float4(evaluated.response, evaluated.pdf));
        writeSample(outputOffset + 2, sampled.bsdfSample);
        outputs[outputOffset + 5] = asuint(sampled.medium);
        return;
    }
    if (config.x == 11u) {
        PrimePbrSource source = loadSource(inputOffset);
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float4 incident = asfloat(inputs[inputOffset + 4]);
        float4 transmitted = asfloat(inputs[inputOffset + 5]);
        PrimePbrSample sampled =
                primeSamplePbr(source, view, randomValue, incident, transmitted, true);
        writeSample(outputOffset, sampled.bsdfSample);
        outputs[outputOffset + 3] = asuint(sampled.medium);
        BsdfEvaluation reflected = primeEvaluatePbr(source, view, sampled.bsdfSample.direction,
                                                    incident, transmitted, true);
        float3 opposite = sampled.bsdfSample.direction;
        opposite.z = -opposite.z;
        BsdfEvaluation through =
                primeEvaluatePbr(source, view, opposite, incident, transmitted, true);
        outputs[outputOffset + 4] = asuint(float4(reflected.response, reflected.pdf));
        outputs[outputOffset + 5] = asuint(float4(through.response, through.pdf));
        PrimeOpenPbrMaterial material = primePbrMaterial(source);
        material.weight.transmission = 1.0;
        material.specular.ior = transmitted.x;
        PrimeLiteTransmissionState state = primeLiteTransmissionStateInit(
                primeLiteEnergy(), material, view, 1.0 / incident.x, 0.0);
        state.transmissionVolume = primePbrVolume(transmitted);
        state.transmissionVolume.extinction *= material.geometry.thickness;
        outputs[outputOffset + 6] =
                asuint(float4(primeLiteTransmissionEnergy(primeLiteEnergy(), view, state,
                                                          PRIME_OPENPBR_FLAG_REFLECTION),
                              0.0));
        outputs[outputOffset + 7] =
                asuint(float4(primeLiteTransmissionEnergy(primeLiteEnergy(), view, state,
                                                          PRIME_OPENPBR_FLAG_TRANSMISSION),
                              0.0));
        return;
    }
    if (config.x == 9u) {
        uint4 encoded[4];
        for (uint index = 0u; index < 4u; ++index)
            encoded[index] = inputs[inputOffset + index];
        PrimeLiteTestResult result = primeLiteTestRun(encoded);
        outputs[outputOffset] = uint4(result.mask, result.kind, 0u, 0u);
        outputs[outputOffset + 1] = asuint(float4(result.evaluation, result.evaluationPdf));
        outputs[outputOffset + 2] = asuint(float4(result.diffuse, 0.0));
        outputs[outputOffset + 3] = asuint(float4(result.specular, 0.0));
        outputs[outputOffset + 4] = asuint(float4(result.energy, 0.0));
        outputs[outputOffset + 5] = asuint(
                float4(result.sample.bsdfSample.throughput.value, result.sample.bsdfSample.pdf));
        outputs[outputOffset + 6] =
                asuint(float4(result.sample.bsdfSample.wo, result.sample.bsdfSample.eta));
        outputs[outputOffset + 7] =
                uint4(result.sample.bsdfSample.throughput.flags, result.sample.volumeStack.count,
                      asuint(result.sample.rayT), 0u);
        outputs[outputOffset + 8] =
                asuint(float4(result.sampledEvaluation, result.sampledEvaluationPdf));
        return;
    }
    if (config.x == 0u) {
        PrimePbrSource source = loadSource(inputOffset);
        PrimeCanonicalOptics optical = primeDecodeOpticalSample(source.specularSample);
        uint fresnelCode = optical.opticalControl & PRIME_OPTICAL_FRESNEL_MASK;
        PrimeLabPbrNormalAtlasSample normal = primeAdaptLabPbrNormalAtlas(source.normalSample);
        PrimeOpenPbrMaterial material = primePbrMaterial(source);
        outputs[outputOffset] = asuint(float4(
                optical.roughness, primeDecodeDielectricF0(fresnelCode),
                primeCombineLabPbrNormalRoughness(optical.roughness, normal.distributionRoughness),
                primeDecodeLabPbrEmission(source.specularSample.a)));
        outputs[outputOffset + 1] =
                uint4(optical.opticalControl,
                      primeIsSupportedConductorCode(fresnelCode) ? fresnelCode : 0u, 0u, 0u);
        outputs[outputOffset + 2] =
                asuint(float4(material.specular.roughness, material.specular.ior,
                              material.weight.metalness, material.weight.subsurface));
        outputs[outputOffset + 3] =
                asuint(float4(material.base.color, float(material.geometry.thinWalled)));
        outputs[outputOffset + 4] = asuint(float4(material.specular.color, 0.0));
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
    if (config.x == 2u) {
        PrimePbrSource source = loadSource(inputOffset);
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float4 incident = asfloat(inputs[inputOffset + 4]);
        float4 transmitted = asfloat(inputs[inputOffset + 5]);
        bool dielectric = inputs[inputOffset].w != 0;
        PrimePbrSample sampled =
                primeSamplePbr(source, view, randomValue, incident, transmitted, dielectric);
        writeSample(outputOffset, sampled.bsdfSample);
        outputs[outputOffset + 3] = asuint(sampled.medium);
        BsdfEvaluation evaluation = primeEvaluatePbr(source, view, sampled.bsdfSample.direction,
                                                     incident, transmitted, dielectric);
        outputs[outputOffset + 4] = asuint(float4(evaluation.response, evaluation.pdf));
        evaluation = primeEvaluatePbr(source, view, asfloat(inputs[inputOffset + 6]).xyz, incident,
                                      transmitted, dielectric);
        outputs[outputOffset + 5] = asuint(float4(evaluation.response, evaluation.pdf));
        return;
    }
    if (config.x == 7u) {
        PrimePbrSource source = loadSource(inputOffset);
        uint scattering = source.hasSpecular ? uint(round(source.specularSample.b * 255.0)) : 0u;
        source.thinWalled =
                primePbrSourceThinWalled(source.thinWalled, inputs[inputOffset + 7].x, scattering);
        PrimeOpenPbrMaterial material = primePbrMaterial(source);
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float4 incident = asfloat(inputs[inputOffset + 4]);
        float4 transmitted = asfloat(inputs[inputOffset + 5]);
        PrimePbrSample sampled =
                primeSamplePbr(source, view, randomValue, incident, transmitted, false);
        writeSample(outputOffset, sampled.bsdfSample);
        outputs[outputOffset + 2].yzw =
                uint3(source.thinWalled ? 1u : 0u, material.geometry.thinWalled,
                      asuint(material.weight.subsurface));
        outputs[outputOffset + 3] = asuint(sampled.medium);
        BsdfEvaluation evaluation = primeEvaluatePbr(source, view, sampled.bsdfSample.direction,
                                                     incident, transmitted, false);
        outputs[outputOffset + 4] = asuint(float4(evaluation.response, evaluation.pdf));
        outputs[outputOffset + 5] = asuint(float4(
                material.weight.transmission, primeDirectLightingMisPdf(false, sampled.bsdfSample),
                primeDirectLightingMisPdf(true, sampled.bsdfSample), 0.0));
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
        PrimePbrSource source = {};
        source.baseColor = float3(0.4, 0.6, 0.2);
        source.normal = shading;
        source.normalSample = float4(0.5, 0.5, 1.0, 0.0);
        source.hasNormal = normalMapped;
        source.specularSample = float4(128.0, 11.0, 0.0, 255.0) / 255.0;
        source.hasSpecular = true;
        BsdfEvaluation evaluated =
                primeEvaluatePbr(source, view, scatter, float4(1.0, 0.0, 0.0, 0.0),
                                 float4(1.5, 0.0, 0.0, 0.0), dielectric);
        outputs[outputOffset] = uint4(supported ? 1u : 0u, 0u, 0u, 0u);
        outputs[outputOffset + 1] = asuint(float4(evaluated.response, evaluated.pdf));
        if (!supported)
            evaluated = primeInvalidBsdfEvaluation();
        outputs[outputOffset + 2] = asuint(float4(evaluated.response, evaluated.pdf));
        return;
    }
    if (config.x == 3u) {
        PrimePbrSource source = loadSource(inputOffset);
        float3 view = asfloat(inputs[inputOffset + 2]).xyz;
        float3 randomValue = asfloat(inputs[inputOffset + 3]).xyz;
        float3 scatter = asfloat(inputs[inputOffset + 6]).xyz;
        PrimeOpenPbrMaterial material = primePbrMaterial(source);
        material.weight.transmission = 0.37;
        material.geometry.thinWalled = 1u;
        material.geometry.thickness = 0.0625;
        material.specular.ior = 1.45;
        material.transmission.color = clamp(source.baseColor, float3(0.02), float3(1.0));
        material.transmission.depth = 0.0;
        PrimeLiteFoliageState state = primeLiteFoliageStateInit(
                primeLiteEnergy(), material, view, 1.0 / asfloat(inputs[inputOffset + 4]).x, 0.0);
        PrimeOpenPbrSample sampled =
                primeLiteFoliageSample(view, randomValue, state, primePbrFixtureStack()).bsdfSample;
        outputs[outputOffset] = asuint(float4(sampled.wo, sampled.eta));
        outputs[outputOffset + 1] = asuint(float4(sampled.throughput.value, sampled.pdf));
        outputs[outputOffset + 2] = uint4(sampled.throughput.flags,
                                          primeLiteFoliageSupports(material) ? 1u : 0u, 0u, 0u);
        PrimeLiteFoliageEvaluation evaluation = primeLiteFoliageEvaluate(view, scatter, state);
        PrimeLiteFoliageComponents components =
                primeLiteFoliageEvaluateComponents(view, scatter, state);
        PrimeLiteFoliageEnergy directionalEnergy =
                primeLiteFoliageEnergy(primeLiteEnergy(), view, state);
        outputs[outputOffset + 3] = asuint(float4(evaluation.throughput.value, evaluation.pdf));
        outputs[outputOffset + 4] = asuint(float4(components.diffuse, components.pdf));
        outputs[outputOffset + 5] = asuint(float4(components.specular, 0.0));
        outputs[outputOffset + 6] = asuint(float4(directionalEnergy.diffuse, 0.0));
        outputs[outputOffset + 7] = asuint(float4(directionalEnergy.specular, 0.0));
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
