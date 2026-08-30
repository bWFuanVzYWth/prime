
#define PRIME_COLOR_WORKING_SPACE_LINEAR_REC2020_D65 1
#define PRIME_COLOR_TEXTURE_ENCODING_SRGB 1
#define PRIME_COLOR_DISPLAY_ENCODING_SRGB 1
#define PRIME_COLOR_DISPLAY_SPACE_REC709_D65 1
#include "core/prime_test_abi.slang"

bool primeUsesAirGap() {
    return true;
}

bool primeUsesSeamlessGlass() {
    return false;
}

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/opaque/traits.slang";
import "bsdf/compact/opaque/evaluate.slang";
import "bsdf/compact/opaque/sample.slang";
import "bsdf/compact/opaque/energy.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/refract.slang";
import "bsdf/compact/dielectric/state.slang";
import "bsdf/compact/dielectric/evaluate.slang";
import "bsdf/compact/dielectric/sample.slang";
import "bsdf/compact/dielectric/energy.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/foliage/lobes.slang";
import "bsdf/compact/foliage/state.slang";
import "bsdf/compact/foliage/evaluate.slang";
import "bsdf/compact/foliage/sample.slang";
import "bsdf/compact/foliage/energy.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/contract/components.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/volume_transition.slang";
import "service/bsdf/opaque/albedo.slang";
import "service/bsdf/opaque/state.slang";
import "service/bsdf/opaque/material.slang";
import "service/bsdf/opaque/discrete.slang";
import "bsdf/compact/opaque/discrete.slang";
import "service/bsdf/opaque/traits.slang";
import "service/bsdf/opaque/evaluate.slang";
import "service/bsdf/opaque/sample.slang";
import "service/bsdf/dielectric/albedo.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/traits.slang";
import "service/bsdf/dielectric/conditional.slang";
import "service/bsdf/dielectric/evaluate.slang";
import "service/bsdf/dielectric/sample.slang";
import "service/bsdf/dielectric/discrete.slang";
import "service/bsdf/foliage/albedo.slang";
import "service/bsdf/foliage/state.slang";
import "service/bsdf/foliage/traits.slang";
import "service/bsdf/foliage/evaluate.slang";
import "service/bsdf/foliage/sample.slang";
import "service/bsdf/dielectric/primary.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/math/frame.slang";
import "model/material/defaults.slang";
import "model/material/medium_boundary.slang";
import "model/material/translation.slang";
import "model/material/transparent.slang";
bool3 equal(float3 first, float3 second) { return first == second; }
bool3 greaterThan(float3 first, float3 second) { return first > second; }
bool2 greaterThan(uint2 first, uint2 second) { return first > second; }

struct SurfaceInteraction {
    float3 baseColor;
    uint materialControl;
    float t;
    float opacity;
    float3 shadingNormal;
    float roughness;
    uint opticalControl;
    uint mediumId;
    float3 adjacentBaseColor;
    uint adjacentInterfaceControl;
    uint adjacentMediumId;
};

float3 primeSurfaceOutwardShadingNormal(SurfaceInteraction surface) {
    return float3(0.0, 0.0, 1.0);
}

float primeSurfaceOpacity(SurfaceInteraction surface) {
    return clamp(surface.opacity, 0.0, 1.0);
}

#include "core/offline_bsdf_test.h"

static const uint PRIME_ADAPTER_INPUT_WORDS = 7u;
static const uint PRIME_ADAPTER_WITNESS_WORDS = 12u;

static const uint PRIME_ADAPTER_OPAQUE = 0u;
static const uint PRIME_ADAPTER_TRANSMISSION = 1u;
static const uint PRIME_ADAPTER_FOLIAGE = 2u;
static const uint PRIME_ADAPTER_PRIMARY_SPLIT = 3u;
static const uint PRIME_ADAPTER_COMPLETE_TRANSMISSION = 4u;
static const uint PRIME_ADAPTER_MEDIUM_BOUNDARY = 5u;
static const uint PRIME_ADAPTER_AIR_GAP = 6u;

struct PrimeAdapterResult {
    uint mask;
    BsdfSample first;
    BsdfSample second;
    BsdfEvaluation sampledEvaluation;
    BsdfEvaluation probeEvaluation;
    PrimeDenoiseAlbedos albedos;
    uint2 stackCounts;
};

float primeAdapterFloat(uint4 encoded[7], uint index) {
    uint flatIndex = index + 4u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

PrimeOpenPbrVolumeStack primeAdapterStack(uint count) {
    PrimeOpenPbrVolumeStack stack = primeEmptyVolumeStack();
    PrimeOpenPbrVolume volume;
    volume.extinction = float3(0.01, 0.02, 0.04);
    volume.albedo = float3(0.0);
    volume.anisotropy = 0.0;
    volume.ior = 1.333;
    volume.mediumId = 1u;
    volume.iorEncoding = 1u << 8u;
    primeOpenPbrStackPush(stack, volume);
    volume.extinction = float3(0.02, 0.01, 0.005);
    volume.ior = 1.5;
    volume.mediumId = 2u;
    volume.iorEncoding = 0u;
    primeOpenPbrStackPush(stack, volume);
    stack.count = min(count, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE);
    return stack;
}

void primeAdapterClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeAdapterClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeAdapterZero(float3 value) {
    return all(equal(value, float3(0.0)));
}

bool primeAdapterSameSample(BsdfSample first, BsdfSample second) {
    return all(equal(first.direction, second.direction))
            && all(equal(first.response, second.response))
            && first.pdf == second.pdf
            && first.relativeEta == second.relativeEta
            && first.eventFlags == second.eventFlags;
}

bool primeAdapterSameStack(
        PrimeOpenPbrVolumeStack first,
        PrimeOpenPbrVolumeStack second) {
    if (first.count != second.count) return false;
    for (uint index = 0u; index < first.count; index++) {
        PrimeOpenPbrVolume a = first.values[index];
        PrimeOpenPbrVolume b = second.values[index];
        if (!all(equal(a.extinction, b.extinction))
                || !all(equal(a.albedo, b.albedo))
                || a.anisotropy != b.anisotropy
                || a.ior != b.ior
                || a.mediumId != b.mediumId
                || a.iorEncoding != b.iorEncoding) {
            return false;
        }
    }
    return true;
}

uint primeAdapterExpectedTransparentStackCount(
        PrimeCompactTransmissionState state,
        uint materialControl,
        PrimeOpenPbrVolumeStack stack) {
    if (state.geometryThinWalled != 0u) {
        return stack.count;
    }
    if (state.entering != 0u) {
        return min(stack.count + 1u, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE);
    }
    return stack.count > 0u ? stack.count - 1u : 0u;
}

float primeAdapterExpectedInterfaceIor(
        PrimeCompactTransmissionState state,
        uint materialControl,
        PrimeOpenPbrVolumeStack stack) {
    return state.specularFresnel.ior;
}

float primeAdapterExpectedRelativeIor(
        PrimeCompactTransmissionState state,
        uint materialControl,
        PrimeOpenPbrVolumeStack stack) {
    float interfaceIor =
            primeAdapterExpectedInterfaceIor(state, materialControl, stack);
    return state.entering != 0u ? interfaceIor : 1.0 / interfaceIor;
}

bool primeAdapterCanRefract(
        PrimeCompactTransmissionState state,
        uint materialControl,
        float3 outwardNormal,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack stack) {
    if (state.geometryThinWalled != 0u) return true;
    float relativeIor =
            primeAdapterExpectedRelativeIor(state, materialControl, stack);
    float cosine = dot(outwardNormal, viewDirection);
    float sineSquared = max(0.0, 1.0 - cosine * cosine);
    return 1.0 - sineSquared / (relativeIor * relativeIor) > 0.0;
}

float3 primeAdapterExpectedRefraction(
        PrimeCompactTransmissionState state,
        uint materialControl,
        float3 outwardNormal,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack stack) {
    float relativeIor =
            primeAdapterExpectedRelativeIor(state, materialControl, stack);
    float3 interfaceNormal = state.entering != 0u
            ? outwardNormal
            : -outwardNormal;
    return refract(-viewDirection, interfaceNormal, 1.0 / relativeIor);
}

bool primeAdapterSnellConsistent(
        BsdfSample sampleValue,
        float3 outwardNormal,
        float3 viewDirection) {
    float incidentSine = length(
            viewDirection - dot(outwardNormal, viewDirection) * outwardNormal);
    float transmittedSine = length(
            sampleValue.direction
                    - dot(outwardNormal, sampleValue.direction) * outwardNormal);
    return primeTestClose(
            incidentSine,
            sampleValue.relativeEta * transmittedSine,
            2.0e-5,
            2.0e-5);
}

void primeAdapterCheckEvaluation(
        inout uint mask, BsdfEvaluation evaluation) {
    primeAdapterClassify(mask, evaluation.response);
    primeAdapterClassify(mask, evaluation.pdf);
    if (any(lessThan(evaluation.response, float3(0.0))) || evaluation.pdf < 0.0) {
        mask |= PRIME_TEST_FAILURE_RANGE;
    }
}

void primeAdapterCheckTransmissionMaterial(
        inout uint mask,
        PrimeCompactTransmissionState state,
        float3 baseColor,
        uint materialControl,
        float roughness,
        uint opticalControl) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, materialControl);
    float expectedRoughness = primeMaterialEffectiveLinearRoughness(
            materialControl, translated.perceptualRoughness);
    float2 expectedAlpha = primeOpenPbrSpecularNdfRoughnesses(expectedRoughness, 0.0);
    if (state.specularMicrofacet.alpha.x != expectedAlpha.x
            || state.specularMicrofacet.alpha.y != expectedAlpha.y) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    if (primeMaterialIsWater(materialControl)) {
        return;
    }
    float3 expectedColor = primeMaterialIsColorlessGlass(materialControl)
            ? float3(1.0)
            : primeGlassVolumeTransmittance(
                    baseColor, PRIME_STAINED_GLASS_ABSORPTION_OPACITY);
    if (!primeTestClose(
                    state.transmissionColor,
                    expectedColor,
                    0.0,
                    0.0)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
}

void primeAdapterCheckMinimumRoughness(
        inout uint mask,
        PrimeOpenPbrMaterial original,
        PrimeOpenPbrMaterial clamped,
        float minimumLinearRoughness) {
    float expected = max(original.specular.roughness, minimumLinearRoughness);
    if (!primeTestClose(clamped.specular.roughness, expected, 0.0, 0.0)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
}

void primeAdapterCheckSample(
        inout uint mask,
        BsdfSample sampleValue,
        float3 outwardNormal,
        float3 viewDirection) {
    const uint knownEvents = PRIME_BSDF_EVENT_REFLECTION
            | PRIME_BSDF_EVENT_TRANSMISSION
            | PRIME_BSDF_EVENT_DIFFUSE
            | PRIME_BSDF_EVENT_GLOSSY
            | PRIME_BSDF_EVENT_DISCRETE;
    if (sampleValue.eventFlags == 0u) {
        if (!primeAdapterZero(sampleValue.direction)
                || !primeAdapterZero(sampleValue.response)
                || sampleValue.pdf != 0.0
                || sampleValue.relativeEta != 1.0) {
            mask |= PRIME_TEST_FAILURE_EVENT;
        }
        return;
    }

    primeAdapterClassify(mask, sampleValue.direction);
    primeAdapterClassify(mask, sampleValue.response);
    primeAdapterClassify(mask, sampleValue.pdf);
    primeAdapterClassify(mask, sampleValue.relativeEta);
    if ((sampleValue.eventFlags & ~knownEvents) != 0u
            || ((sampleValue.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u)
                    == ((sampleValue.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u)
            || sampleValue.pdf <= 0.0
            || sampleValue.relativeEta <= 0.0
            || any(lessThan(sampleValue.response, float3(0.0)))) {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }
    uint lobe = sampleValue.eventFlags
            & (PRIME_BSDF_EVENT_DIFFUSE
                    | PRIME_BSDF_EVENT_GLOSSY
                    | PRIME_BSDF_EVENT_DISCRETE);
    if (lobe == 0u || (lobe & (lobe - 1u)) != 0u) {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (!primeTestClose(length(sampleValue.direction), 1.0, 8.0e-5, 8.0e-5)) {
        mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
    }
    float sideProduct = dot(outwardNormal, viewDirection)
            * dot(outwardNormal, sampleValue.direction);
    bool reflected = (sampleValue.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u;
    if ((reflected && sideProduct <= 0.0) || (!reflected && sideProduct >= 0.0)) {
        mask |= PRIME_TEST_FAILURE_HEMISPHERE;
    }
}

void primeAdapterProperties(
        uint kind, uint4 encoded[7], out PrimeAdapterResult result) {
    result.mask = 0u;
    result.first = primeInvalidBsdfSample();
    result.second = primeInvalidBsdfSample();
    result.sampledEvaluation = primeInvalidBsdfEvaluation();
    result.probeEvaluation = primeInvalidBsdfEvaluation();
    result.albedos.diffuse = float3(0.0);
    result.albedos.specular = float3(0.0);
    result.stackCounts = uint2(0u);

    uint materialControl = encoded[0].y;
    float roughness = asfloat(encoded[0].z);
    uint opticalControl = encoded[0].w;
    float3 baseColor = float3(
            primeAdapterFloat(encoded, 0u),
            primeAdapterFloat(encoded, 1u),
            primeAdapterFloat(encoded, 2u));
    float opacity = primeAdapterFloat(encoded, 3u);
    float3 outwardNormal = normalize(float3(
            primeAdapterFloat(encoded, 4u),
            primeAdapterFloat(encoded, 5u),
            primeAdapterFloat(encoded, 6u)));
    float rayT = primeAdapterFloat(encoded, 7u);
    float3 viewDirection = normalize(float3(
            primeAdapterFloat(encoded, 8u),
            primeAdapterFloat(encoded, 9u),
            primeAdapterFloat(encoded, 10u)));
    uint stackCount = min(encoded[3].w, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE);
    float3 firstSample = float3(
            primeAdapterFloat(encoded, 12u),
            primeAdapterFloat(encoded, 13u),
            primeAdapterFloat(encoded, 14u));
    float3 secondSample = float3(
            primeAdapterFloat(encoded, 15u),
            primeAdapterFloat(encoded, 16u),
            primeAdapterFloat(encoded, 17u));
    float3 probeDirection = normalize(float3(
            primeAdapterFloat(encoded, 18u),
            primeAdapterFloat(encoded, 19u),
            primeAdapterFloat(encoded, 20u)));
    PrimeOpenPbrVolumeStack stack = primeAdapterStack(stackCount);

    if (kind == PRIME_ADAPTER_OPAQUE) {
        result.first = primeSampleOpaque(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl,
                viewDirection,
                firstSample,
                rayT,
                stack);
        if (result.first.eventFlags != 0u) {
            PrimeBsdfComponents components = primeEvaluateOpaqueComponents(
                    baseColor,
                    outwardNormal,
                    roughness,
                    opticalControl,
                    materialControl,
                    viewDirection,
                    result.first.direction,
                    rayT,
                    stack);
            result.sampledEvaluation.response =
                    components.diffuseResponse + components.specularResponse;
            result.sampledEvaluation.pdf = components.pdf;
        }
        PrimeBsdfComponents probe = primeEvaluateOpaqueComponents(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl,
                viewDirection,
                probeDirection,
                rayT,
                stack);
        result.probeEvaluation.response =
                probe.diffuseResponse + probe.specularResponse;
        result.probeEvaluation.pdf = probe.pdf;
        PrimeCompactOpaqueState state = primeOpaqueState(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl,
                viewDirection,
                rayT,
                stack);
        PrimeClosureTraits opaqueTraits = primeOpaqueClosureTraits(state);
        PrimeClosureTraits fastOpaqueTraits = primeOpaqueClosureTraits(
                roughness, opticalControl, materialControl);
        if (opaqueTraits.measureMask != fastOpaqueTraits.measureMask
                || opaqueTraits.eventMask != fastOpaqueTraits.eventMask) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        const float minimumLinearRoughness = 0.4;
        PrimeOpenPbrMaterial opaqueMaterial = primeOpaqueMaterial(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl);
        PrimeOpenPbrMaterial minimumOpaqueMaterial = primeOpaqueMaterialWithMinimumRoughness(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl,
                minimumLinearRoughness);
        PrimeOpticalMaterial translatedOpaque = primeDecodeOpticalMaterial(
                roughness, opticalControl, materialControl);
        bool thinSubsurface = !primeOpticalMaterialIsMetal(translatedOpaque)
                && translatedOpaque.thinWalled != 0u
                && translatedOpaque.subsurfaceWeight > 0.0;
        float expectedSubsurfaceWeight = thinSubsurface
                ? translatedOpaque.subsurfaceWeight : 0.0;
        if (opaqueMaterial.weight.subsurface != expectedSubsurfaceWeight
                || (expectedSubsurfaceWeight == 0.0
                        && (state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                                || state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE))) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if ((materialControl & PRIME_MATERIAL_THIN_WALLED) == 0u) {
            uint diffuseOpticalControl = opticalControl
                    & ~(0xffu << PRIME_OPTICAL_SUBSURFACE_SHIFT);
            BsdfSample diffuseFallback = primeSampleOpaque(
                    baseColor,
                    outwardNormal,
                    roughness,
                    diffuseOpticalControl,
                    materialControl,
                    viewDirection,
                    firstSample,
                    rayT,
                    stack);
            PrimeBsdfComponents diffuseProbe = primeEvaluateOpaqueComponents(
                    baseColor,
                    outwardNormal,
                    roughness,
                    diffuseOpticalControl,
                    materialControl,
                    viewDirection,
                    probeDirection,
                    rayT,
                    stack);
            if (!primeAdapterSameSample(result.first, diffuseFallback)
                    || !all(equal(
                            result.probeEvaluation.response,
                            diffuseProbe.diffuseResponse + diffuseProbe.specularResponse))
                    || result.probeEvaluation.pdf != diffuseProbe.pdf) {
                result.mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        }
        if (!primeCompactOpaqueSupports(opaqueMaterial)
                || !primeCompactOpaqueSupports(minimumOpaqueMaterial)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        primeAdapterCheckMinimumRoughness(
                result.mask,
                opaqueMaterial,
                minimumOpaqueMaterial,
                minimumLinearRoughness);
        result.albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_OPAQUE);
        if (primeClosureIsDiscreteOnly(opaqueTraits)) {
            PrimeCompactOpaqueDiscreteState discreteState = primeOpaqueDiscreteState(
                    baseColor,
                    outwardNormal,
                    roughness,
                    opticalControl,
                    materialControl,
                    rayT);
            BsdfSample discreteSample = primeSampleOpaqueDiscreteFromState(
                    discreteState, outwardNormal, viewDirection, stack);
            PrimeDenoiseAlbedos discreteAlbedos = primeOpaqueDiscreteAlbedosFromState(
                    discreteState, viewDirection);
            if (!primeAdapterSameSample(result.first, discreteSample)
                    || !all(equal(result.albedos.diffuse, discreteAlbedos.diffuse))
                    || !all(equal(result.albedos.specular, discreteAlbedos.specular))) {
                result.mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        }
    } else if (kind == PRIME_ADAPTER_FOLIAGE) {
        result.first = primeSampleMinecraftFoliage(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl,
                viewDirection,
                firstSample,
                rayT,
                stack);
        if (result.first.eventFlags != 0u) {
            result.sampledEvaluation = primeEvaluateMinecraftFoliage(
                    baseColor,
                    outwardNormal,
                    roughness,
                    opticalControl,
                    materialControl,
                    viewDirection,
                    result.first.direction,
                    rayT,
                    stack);
        }
        result.probeEvaluation = primeEvaluateMinecraftFoliage(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl,
                viewDirection,
                probeDirection,
                rayT,
                stack);
        PrimeCompactFoliageState state = primeMinecraftFoliageState(
                baseColor,
                outwardNormal,
                roughness,
                opticalControl,
                materialControl,
                viewDirection,
                rayT,
                stack);
        PrimeClosureTraits fastFoliageTraits = primeFoliageClosureTraits(
                roughness, opticalControl, materialControl);
        if ((fastFoliageTraits.eventMask & PRIME_BSDF_EVENT_DIFFUSE) == 0u
                || (fastFoliageTraits.eventMask
                        & PRIME_BSDF_EVENT_TRANSMISSION) == 0u) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        const float minimumLinearRoughness = 0.4;
        primeAdapterCheckMinimumRoughness(
                result.mask,
                primeMinecraftFoliageMaterial(
                        baseColor,
                        outwardNormal,
                        roughness,
                        opticalControl,
                        materialControl),
                primeMinecraftFoliageMaterialWithMinimumRoughness(
                        baseColor,
                        outwardNormal,
                        roughness,
                        opticalControl,
                        materialControl,
                        minimumLinearRoughness),
                minimumLinearRoughness);
        result.albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_FOLIAGE);
    } else if (kind == PRIME_ADAPTER_TRANSMISSION) {
        PrimeCompactTransmissionState state = primeMinecraftTransmissionState(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                rayT,
                stack,
                37u);
        PrimeClosureTraits transmissionTraits =
                primeMinecraftTransmissionClosureTraits(state);
        PrimeClosureTraits fastTransmissionTraits =
                primeMinecraftTransmissionClosureTraits(
                        outwardNormal,
                        materialControl,
                        roughness,
                        opticalControl,
                        viewDirection,
                        stack,
                        0u,
                        primeUsesAirGap());
        if (transmissionTraits.measureMask != fastTransmissionTraits.measureMask
                || transmissionTraits.eventMask != fastTransmissionTraits.eventMask) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        uint expectedMeasure = primeMinecraftTransmissionIsDiscrete(state)
                ? PRIME_MEASURE_DISCRETE
                : PRIME_MEASURE_SOLID_ANGLE;
        if (transmissionTraits.measureMask != expectedMeasure) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        PrimeClosureTraits mixedTraits = primeOpaqueClosureTraits(
                0.0, 0u, PRIME_MATERIAL_FAMILY_OPAQUE);
        if (mixedTraits.measureMask
                != (PRIME_MEASURE_SOLID_ANGLE | PRIME_MEASURE_DISCRETE)) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        const float minimumLinearRoughness = 0.4;
        primeAdapterCheckMinimumRoughness(
                result.mask,
                primeMinecraftTransmissionMaterial(
                        baseColor,
                        opacity,
                        outwardNormal,
                        materialControl,
                        roughness,
                        opticalControl),
                primeMinecraftTransmissionMaterialWithMinimumRoughness(
                        baseColor,
                        opacity,
                        outwardNormal,
                        materialControl,
                        roughness,
                        opticalControl,
                        minimumLinearRoughness),
                minimumLinearRoughness);
        primeAdapterCheckTransmissionMaterial(
                result.mask,
                state,
                baseColor,
                materialControl,
                roughness,
                opticalControl);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftRefractedTransmissionFromState(
                        state,
                        outwardNormal,
                        viewDirection,
                        float3(1.0),
                        true,
                        stack);
        result.first = sampled.bsdfSample;
        result.stackCounts.x = sampled.volumeStack.count;
        PrimeTransmissiveBsdfSample repeated =
                primeSampleMinecraftRefractedTransmissionFromState(
                        state,
                        outwardNormal,
                        viewDirection,
                        float3(1.0),
                        true,
                        stack);
        result.albedos.diffuse = state.transmissionTint;
        result.albedos.specular = float3(0.0);
        bool thinWalled = state.geometryThinWalled != 0u;
        bool canRefract = primeAdapterCanRefract(
                state,
                materialControl,
                outwardNormal,
                viewDirection,
                stack);
        bool totalInternalReflection = !thinWalled && !canRefract;
        uint expectedEvents = totalInternalReflection
                ? PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DISCRETE
                : PRIME_BSDF_EVENT_TRANSMISSION | PRIME_BSDF_EVENT_DISCRETE;
        float3 expectedDirection = thinWalled
                ? -viewDirection
                : (totalInternalReflection
                        ? reflect(-viewDirection, outwardNormal)
                        : primeAdapterExpectedRefraction(
                                state,
                                materialControl,
                                outwardNormal,
                                viewDirection,
                                stack));
        float expectedRelativeIor = thinWalled || totalInternalReflection
                ? 1.0
                : primeAdapterExpectedRelativeIor(
                        state, materialControl, stack);
        float3 expectedResponse = totalInternalReflection
                ? float3(1.0)
                : state.transmissionTint
                        / primeOpenPbrSquare(expectedRelativeIor);
        uint expectedStackCount = totalInternalReflection
                ? stack.count
                : primeAdapterExpectedTransparentStackCount(
                        state, materialControl, stack);
        if (!primeAdapterSameSample(result.first, repeated.bsdfSample)
                || !primeAdapterSameStack(sampled.volumeStack, repeated.volumeStack)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if (!primeTestClose(
                result.first.direction, expectedDirection, 2.0e-6, 2.0e-6)) {
            result.mask |= PRIME_TEST_FAILURE_HEMISPHERE;
        }
        if (!primeTestClose(
                result.first.response, expectedResponse, 2.0e-6, 2.0e-6)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
        if (result.first.pdf != 1.0) result.mask |= PRIME_TEST_FAILURE_PDF;
        if (!primeTestClose(
                result.first.relativeEta, expectedRelativeIor, 2.0e-6, 2.0e-6)) {
            result.mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
        }
        if (result.first.eventFlags != expectedEvents) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        if (sampled.volumeStack.count != expectedStackCount) {
            result.mask |= PRIME_TEST_FAILURE_INDEX;
        }
        if (!thinWalled
                && !totalInternalReflection
                && !primeAdapterSnellConsistent(
                        result.first, outwardNormal, viewDirection)) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == PRIME_ADAPTER_PRIMARY_SPLIT) {
        PrimeTransmissivePrimarySample sampled =
                primeSampleMinecraftTransmissionPrimary(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                firstSample,
                rayT,
                stack,
                37u,
                float3(0.0),
                0u,
                0u,
                primeUsesAirGap());
        result.first = sampled.paths.reflection.bsdfSample;
        result.second = sampled.paths.transmission.bsdfSample;
        result.stackCounts = uint2(
                sampled.paths.reflection.volumeStack.count,
                sampled.paths.transmission.volumeStack.count);
        result.albedos = sampled.albedos;
        PrimeCompactTransmissionState state = primeMinecraftTransmissionState(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                rayT,
                stack,
                37u);
        primeAdapterCheckTransmissionMaterial(
                result.mask,
                state,
                baseColor,
                materialControl,
                roughness,
                opticalControl);
        float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
        PrimeMinecraftMirrorSplit mirror =
                primeMinecraftMirrorSplit(state, localView);
        PrimeTransmissivePrimarySample repeated =
                primeSampleMinecraftTransmissionPrimary(
                        baseColor,
                        opacity,
                        outwardNormal,
                        materialControl,
                        roughness,
                        opticalControl,
                        viewDirection,
                        secondSample,
                        rayT,
                        stack,
                        37u,
                        float3(0.0),
                        0u,
                        0u,
                        primeUsesAirGap());
        bool thinWalled = state.geometryThinWalled != 0u;
        bool canRefract = primeAdapterCanRefract(
                state,
                materialControl,
                outwardNormal,
                viewDirection,
                stack);
        bool validTransmission = thinWalled || canRefract;
        PrimeOpenPbrMicrofacet transmissionMicrofacet =
                primeMinecraftTransmissionMicrofacet(state);
        bool smoothTransmission = primeMinecraftTransmissionIsDiscrete(state);
        float3 expectedDirection = thinWalled
                ? -viewDirection
                : primeAdapterExpectedRefraction(
                        state,
                        materialControl,
                        outwardNormal,
                        viewDirection,
                        stack);
        float expectedRelativeIor = thinWalled
                ? 1.0
                : primeAdapterExpectedRelativeIor(
                        state, materialControl, stack);
        uint expectedStackCount = primeAdapterExpectedTransparentStackCount(
                state, materialControl, stack);
        if ((smoothTransmission
                        && !primeAdapterSameSample(
                                result.second,
                                repeated.paths.transmission.bsdfSample))
                || (smoothTransmission
                        && !primeAdapterSameStack(
                                sampled.paths.transmission.volumeStack,
                                repeated.paths.transmission.volumeStack))
                || !primeAdapterSameStack(
                        sampled.paths.reflection.volumeStack, stack)
                || !all(equal(
                        result.albedos.diffuse,
                        primeMinecraftPrimaryTransmissionAlbedo(
                                state, materialControl)))
                || !all(equal(result.albedos.specular, mirror.reflectance))) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        if (!smoothTransmission) {
            PrimeOpenPbrSampleResult reference = primeCompactTransmissionSample(
                    localView,
                    firstSample,
                    state,
                    PRIME_OPENPBR_FLAG_TRANSMISSION,
                    stack);
            BsdfSample expected = primeInvalidBsdfSample();
            if (primeOpenPbrHasSample(reference.bsdfSample)) {
                expected.direction = primeOpenPbrOnbToWorld(
                        state.onb, reference.bsdfSample.wo);
                expected.response = reference.bsdfSample.throughput.value;
                expected.pdf = reference.bsdfSample.pdf;
                expected.relativeEta = reference.bsdfSample.eta;
                expected.eventFlags = primeOpenPbrToBsdfEventFlags(
                        reference.bsdfSample.throughput.flags);
                expected = primeSanitizeBsdfSample(expected);
            }
            PrimeOpenPbrVolumeStack expectedStack = expected.eventFlags == 0u
                    ? stack
                    : reference.volumeStack;
            if (!primeAdapterSameSample(result.second, expected)
                    || !primeAdapterSameStack(
                            sampled.paths.transmission.volumeStack,
                            expectedStack)) {
                result.mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
            if (result.second.eventFlags != 0u) {
                BsdfEvaluation evaluated =
                        primeEvaluateMinecraftTransparentTransmission(
                                baseColor,
                                opacity,
                                outwardNormal,
                                materialControl,
                                roughness,
                                opticalControl,
                                viewDirection,
                                result.second.direction,
                                rayT,
                                stack);
                float3 localScatter = primeOpenPbrOnbToLocal(
                        state.onb, result.second.direction);
                bool stableReconstruction = min(
                                transmissionMicrofacet.alpha.x,
                                transmissionMicrofacet.alpha.y) >= 5.0e-3
                        && abs(localView.z) >= 1.0e-1
                        && abs(localScatter.z) >= 5.0e-2;
                if (stableReconstruction
                        && (!primeTestClose(
                                        result.second.response,
                                        evaluated.response,
                                        3.0e-6,
                                        2.0e-3)
                                || !primeTestClose(
                                        result.second.pdf,
                                        evaluated.pdf,
                                        3.0e-6,
                                        2.0e-3))) {
                    result.mask |= PRIME_TEST_FAILURE_PDF;
                }
            }
        } else if (validTransmission) {
            uint expectedEvents = PRIME_BSDF_EVENT_TRANSMISSION
                    | PRIME_BSDF_EVENT_DISCRETE;
            float3 expectedResponse = (float3(1.0) - mirror.reflectance)
                    * state.transmissionTint
                    / primeOpenPbrSquare(expectedRelativeIor);
            if (!primeTestClose(
                    result.second.direction, expectedDirection, 2.0e-6, 2.0e-6)) {
                result.mask |= PRIME_TEST_FAILURE_HEMISPHERE;
            }
            if (!primeTestClose(
                    result.second.response,
                    expectedResponse,
                    2.0e-6,
                    2.0e-6)) {
                result.mask |= PRIME_TEST_FAILURE_VALUE;
            }
            if (result.second.pdf != 1.0) result.mask |= PRIME_TEST_FAILURE_PDF;
            if (!primeTestClose(
                    result.second.relativeEta,
                    expectedRelativeIor,
                    2.0e-6,
                    2.0e-6)) {
                result.mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
            }
            if (result.second.eventFlags != expectedEvents) {
                result.mask |= PRIME_TEST_FAILURE_EVENT;
            }
            if (sampled.paths.transmission.volumeStack.count != expectedStackCount) {
                result.mask |= PRIME_TEST_FAILURE_INDEX;
            }
            if (!thinWalled
                    && !primeAdapterSnellConsistent(
                            result.second, outwardNormal, viewDirection)) {
                result.mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        } else if (result.second.eventFlags != 0u
                || !primeAdapterZero(result.second.direction)
                || !primeAdapterZero(result.second.response)
                || result.second.pdf != 0.0
                || result.second.relativeEta != 1.0
                || !primeAdapterSameStack(
                        sampled.paths.transmission.volumeStack, stack)) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        if (result.first.eventFlags != 0u
                && (result.first.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u) {
            result.sampledEvaluation =
                    primeEvaluateMinecraftTransparentReflection(
                    baseColor,
                    opacity,
                    outwardNormal,
                    materialControl,
                    roughness,
                    opticalControl,
                    viewDirection,
                    result.first.direction,
                    rayT,
                    stack);
            float3 localScatter =
                    primeOpenPbrOnbToLocal(state.onb, result.first.direction);
            bool stableReconstruction =
                    min(
                            state.specularMicrofacet.alpha.x,
                            state.specularMicrofacet.alpha.y) >= 5.0e-3
                    && abs(localView.z) >= 1.0e-1
                    && abs(localScatter.z) >= 5.0e-2;
            if (stableReconstruction
                    && !primeTestClose(
                            result.first.response,
                            result.sampledEvaluation.response,
                            3.0e-6,
                            2.0e-3)) {
                result.mask |= PRIME_TEST_FAILURE_VALUE;
            }
            if (stableReconstruction
                    && !primeTestClose(
                            result.first.pdf,
                            result.sampledEvaluation.pdf,
                            3.0e-6,
                            2.0e-3)) {
                result.mask |= PRIME_TEST_FAILURE_PDF;
            }
        }
        result.probeEvaluation = primeEvaluateMinecraftTransparentReflection(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                probeDirection,
                rayT,
                stack);
    } else if (kind == PRIME_ADAPTER_MEDIUM_BOUNDARY) {
        uint adjacentMaterialControl = PRIME_MATERIAL_FAMILY_DIELECTRIC
                | (primeMaterialIsWater(materialControl)
                        ? PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS
                        : PRIME_MATERIAL_MEDIUM_WATER);
        uint adjacentInterfaceControl = PRIME_ADJACENT_INTERFACE_VALID
                | adjacentMaterialControl
                | (opticalControl & PRIME_OPTICAL_FRESNEL_MASK)
                        << PRIME_ADJACENT_FRESNEL_SHIFT;
        float3 adjacentBaseColor = float3(0.2, 0.5, 0.8);
        PrimeOpenPbrVolume adjacent = primeAdjacentMediumVolume(
                adjacentBaseColor, adjacentInterfaceControl, 41u);
        PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                rayT,
                stack,
                37u,
                adjacentBaseColor,
                adjacentInterfaceControl,
                41u,
                primeUsesAirGap());
        PrimeClosureTraits boundaryTraits =
                primeMinecraftTransmissionClosureTraits(state);
        PrimeClosureTraits fastBoundaryTraits =
                primeMinecraftTransmissionClosureTraits(
                        outwardNormal,
                        materialControl,
                        roughness,
                        opticalControl,
                        viewDirection,
                        stack,
                        adjacentInterfaceControl,
                        primeUsesAirGap());
        if (boundaryTraits.measureMask != fastBoundaryTraits.measureMask
                || boundaryTraits.eventMask != fastBoundaryTraits.eventMask) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftRefractedTransmissionFromState(
                        state,
                        outwardNormal,
                        viewDirection,
                        float3(1.0),
                        true,
                        stack);
        sampled = primeApplyAdjacentMediumTransition(
                sampled,
                state,
                stack,
                baseColor,
                opacity,
                materialControl,
                opticalControl,
                37u,
                adjacentBaseColor,
                adjacentInterfaceControl,
                41u,
                primeUsesAirGap());
        result.first = sampled.bsdfSample;
        result.stackCounts.x = sampled.volumeStack.count;
        float expectedInterfaceIor = state.originalIor / adjacent.ior;
        // The state evaluates originalIor * (1 / adjacentIor). Division is mathematically
        // equivalent but can differ in the last bit across Vulkan drivers.
        if (!primeTestClose(
                state.specularFresnel.ior, expectedInterfaceIor, 2.0e-7, 2.0e-7)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if ((result.first.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u) {
            PrimeOpenPbrVolume target;
            if (state.entering != 0u) {
                target = state.transmissionVolume;
                target.ior = state.originalIor;
            } else {
                target = adjacent;
            }
            PrimeOpenPbrVolumeStack expectedStack = stack;
            if (expectedStack.count == 0u) {
                primeOpenPbrStackPush(expectedStack, target);
            } else {
                expectedStack.values[expectedStack.count - 1u] = target;
            }
            if (!primeAdapterSameStack(sampled.volumeStack, expectedStack)) {
                result.mask |= PRIME_TEST_FAILURE_STATE;
            }
        } else if (!primeAdapterSameStack(sampled.volumeStack, stack)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if ((result.first.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u
                && !primeAdapterSnellConsistent(
                        result.first, outwardNormal, viewDirection)) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == PRIME_ADAPTER_AIR_GAP) {
        uint adjacentInterfaceControl = PRIME_ADJACENT_INTERFACE_VALID
                | PRIME_ADJACENT_MICRO_GAP_ELIGIBLE
                | (materialControl & PRIME_ADJACENT_MATERIAL_MASK)
                | (opticalControl & PRIME_OPTICAL_FRESNEL_MASK)
                        << PRIME_ADJACENT_FRESNEL_SHIFT;
        float3 adjacentBaseColor = float3(0.2, 0.5, 0.8);
        PrimeOpenPbrVolume surfaceMedium = primeBoundarySurfaceVolume(
                baseColor, opacity, materialControl, opticalControl, 37u);
        PrimeOpenPbrVolume adjacent = primeAdjacentMediumVolume(
                adjacentBaseColor, adjacentInterfaceControl, 41u);
        PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                rayT,
                stack,
                37u,
                adjacentBaseColor,
                adjacentInterfaceControl,
                41u,
                primeUsesAirGap());
        PrimeClosureTraits airGapTraits =
                primeMinecraftTransmissionClosureTraits(state);
        PrimeClosureTraits fastAirGapTraits =
                primeMinecraftTransmissionClosureTraits(
                        outwardNormal,
                        materialControl,
                        roughness,
                        opticalControl,
                        viewDirection,
                        stack,
                        adjacentInterfaceControl,
                        primeUsesAirGap());
        if (airGapTraits.measureMask != fastAirGapTraits.measureMask
                || airGapTraits.eventMask != fastAirGapTraits.eventMask) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftRefractedTransmissionFromState(
                        state,
                        outwardNormal,
                        viewDirection,
                        float3(1.0),
                        true,
                        stack);
        sampled = primeApplyAdjacentMediumTransition(
                sampled,
                state,
                stack,
                baseColor,
                opacity,
                materialControl,
                opticalControl,
                37u,
                adjacentBaseColor,
                adjacentInterfaceControl,
                41u,
                primeUsesAirGap());
        result.first = sampled.bsdfSample;
        result.stackCounts.x = sampled.volumeStack.count;
        bool expectedEntering = dot(outwardNormal, viewDirection) >= 0.0;
        if (surfaceMedium.ior != adjacent.ior
                || state.geometryThinWalled == 0u
                || state.originalIor != 1.0
                || state.specularFresnel.ior != 1.0 / surfaceMedium.ior
                || (state.entering != 0u) != expectedEntering) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if ((result.first.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u) {
            PrimeOpenPbrVolume target = expectedEntering ? surfaceMedium : adjacent;
            PrimeOpenPbrVolumeStack expectedStack = stack;
            if (expectedStack.count == 0u) {
                primeOpenPbrStackPush(expectedStack, target);
            } else {
                expectedStack.values[expectedStack.count - 1u] = target;
            }
            if (!primeAdapterSameStack(sampled.volumeStack, expectedStack)
                    || !primeTestClose(
                            result.first.direction,
                            -viewDirection,
                            3.0e-6,
                            3.0e-6)
                    || result.first.relativeEta != 1.0) {
                result.mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        } else if (!primeAdapterSameStack(sampled.volumeStack, stack)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
    } else {
        PrimeCompactTransmissionState state = primeMinecraftTransmissionState(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                rayT,
                stack,
                37u);
        primeAdapterCheckTransmissionMaterial(
                result.mask,
                state,
                baseColor,
                materialControl,
                roughness,
                opticalControl);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionCompleteFromState(
                        state,
                        baseColor,
                        opacity,
                        materialControl,
                        viewDirection,
                        firstSample,
                        stack);
        PrimeTransmissiveBsdfSample offline =
                primeSampleOfflineMinecraftTransmissionFromState(
                        state,
                        baseColor,
                        opacity,
                        materialControl,
                        viewDirection,
                        firstSample,
                        stack);
        result.first = sampled.bsdfSample;
        result.stackCounts.x = sampled.volumeStack.count;
        PrimeClosureTraits transmissionTraits =
                primeMinecraftTransmissionClosureTraits(state);
        if (primeClosureIsDiscreteOnly(transmissionTraits)) {
            PrimeTransmissiveBsdfSample discrete =
                    primeSampleMinecraftTransmissionDiscreteFromState(
                            state, viewDirection, firstSample.z, stack);
            if (!primeAdapterSameSample(sampled.bsdfSample, discrete.bsdfSample)
                    || !primeAdapterSameStack(sampled.volumeStack, discrete.volumeStack)) {
                result.mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        }
        if (result.first.eventFlags != 0u
                && ((result.first.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u
                        ? !primeClosureSupports(
                                transmissionTraits, PRIME_MEASURE_DISCRETE)
                        : !primeClosureSupports(
                                transmissionTraits, PRIME_MEASURE_SOLID_ANGLE))) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }

        float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
        PrimeOpenPbrSampleResult reference = primeCompactTransmissionSample(
                localView, firstSample, state, PRIME_OPENPBR_FLAG_ALL, stack);
        BsdfSample expected = primeInvalidBsdfSample();
        if (primeOpenPbrHasSample(reference.bsdfSample)) {
            expected.direction = primeOpenPbrOnbToWorld(
                    state.onb, reference.bsdfSample.wo);
            expected.response = reference.bsdfSample.throughput.value;
            expected.pdf = reference.bsdfSample.pdf;
            expected.relativeEta = reference.bsdfSample.eta;
            expected.eventFlags = primeOpenPbrToBsdfEventFlags(
                    reference.bsdfSample.throughput.flags);
            expected = primeSanitizeBsdfSample(expected);
        }
        PrimeOpenPbrVolumeStack expectedStack = expected.eventFlags == 0u
                ? stack
                : reference.volumeStack;
        if (!primeAdapterSameSample(result.first, expected)
                || !primeAdapterSameStack(sampled.volumeStack, expectedStack)
                || !primeAdapterSameSample(
                        sampled.bsdfSample, offline.bsdfSample)
                || !primeAdapterSameStack(
                        sampled.volumeStack, offline.volumeStack)
                || ((result.first.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u
                        && !primeAdapterSameStack(sampled.volumeStack, stack))
                || primeOfflineSkipsRussianRoulette(result.first)) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        result.probeEvaluation =
                primeEvaluateMinecraftTransmissionCompleteFromState(
                        state,
                        baseColor,
                        opacity,
                        materialControl,
                        viewDirection,
                        probeDirection);
        if (result.first.eventFlags != 0u
                && (result.first.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u) {
            result.sampledEvaluation =
                    primeEvaluateMinecraftTransmissionCompleteFromState(
                            state,
                            baseColor,
                            opacity,
                            materialControl,
                            viewDirection,
                            result.first.direction);
            float3 localScatter = primeOpenPbrOnbToLocal(
                    state.onb, result.first.direction);
            bool stableReconstruction = min(
                    state.transmissionMicrofacet.alpha.x,
                    state.transmissionMicrofacet.alpha.y) >= 5.0e-3
                    && abs(localView.z) >= 1.0e-1
                    && abs(localScatter.z) >= 5.0e-2;
            if (stableReconstruction && (!primeTestClose(
                            result.first.response,
                            result.sampledEvaluation.response,
                            3.0e-6,
                            2.0e-3)
                    || !primeTestClose(
                            result.first.pdf,
                            result.sampledEvaluation.pdf,
                            3.0e-6,
                            2.0e-3))) {
                result.mask |= PRIME_TEST_FAILURE_PDF;
            }
        }
    }

    primeAdapterCheckSample(
            result.mask, result.first, outwardNormal, viewDirection);
    primeAdapterCheckSample(
            result.mask, result.second, outwardNormal, viewDirection);
    primeAdapterCheckEvaluation(result.mask, result.sampledEvaluation);
    primeAdapterCheckEvaluation(result.mask, result.probeEvaluation);
    primeAdapterClassify(result.mask, result.albedos.diffuse);
    primeAdapterClassify(result.mask, result.albedos.specular);
    if (any(lessThan(result.albedos.diffuse, float3(0.0)))
            || any(greaterThan(result.albedos.diffuse, float3(1.0)))
            || any(lessThan(result.albedos.specular, float3(0.0)))
            || any(greaterThan(result.albedos.specular, float3(1.0)))
            || any(greaterThan(
                    result.stackCounts, uint2(PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE)))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }

}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_ADAPTER_INPUT_WORDS);
    uint4 encoded[7];
    for (uint word = 0u; word < PRIME_ADAPTER_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    uint kind = encoded[0].x;
    PrimeAdapterResult result;
    primeAdapterProperties(kind, encoded, result);

    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, kind, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) return;
    primeTestOutput[outputOffset + 1u] = uint4(
            asuint(result.first.direction), result.first.eventFlags);
    primeTestOutput[outputOffset + 2u] = uint4(
            asuint(result.first.response), asuint(result.first.pdf));
    primeTestOutput[outputOffset + 3u] = uint4(
            asuint(result.first.relativeEta),
            result.second.eventFlags,
            result.stackCounts);
    primeTestOutput[outputOffset + 4u] = uint4(
            asuint(result.second.direction), 0u);
    primeTestOutput[outputOffset + 5u] = uint4(
            asuint(result.second.response), asuint(result.second.pdf));
    primeTestOutput[outputOffset + 6u] = uint4(
            asuint(result.sampledEvaluation.response),
            asuint(result.sampledEvaluation.pdf));
    primeTestOutput[outputOffset + 7u] = uint4(
            asuint(result.probeEvaluation.response),
            asuint(result.probeEvaluation.pdf));
    primeTestOutput[outputOffset + 8u] = uint4(
            asuint(result.albedos.diffuse), 0u);
    primeTestOutput[outputOffset + 9u] = uint4(
            asuint(result.albedos.specular), 0u);
    primeTestOutput[outputOffset + 10u] = encoded[0];
    primeTestOutput[outputOffset + 11u] = encoded[3];
}
