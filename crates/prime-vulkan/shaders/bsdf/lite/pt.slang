// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/lite/pt.slang";

import "bsdf/common/common.slang";
import "bsdf/lite/bsdf.slang";

// PT's authored opaque closure has unit specular tint and isotropic, zero-anisotropy SSS.
// The frame belongs to the current consumer; sampling returns only its consumed proposal.
public struct PrimeLitePtOpaqueState {
    public uint kind;
    public uint thinWalled;
    public float3 baseColor;
    public float subsurfaceWeight;
    public PrimeOpenPbrMicrofacet microfacet;
    public float energyIor;
    public PrimeLiteMixState diffuseMix;
    public PrimeLiteLayerState layer;
};

float3 primeLitePtSubsurfaceColor(PrimeLitePtOpaqueState state) {
    return state.thinWalled != 0u ? state.baseColor : float3(1.0);
}

float3 primeLitePtBaseEnergy(PrimeLitePtOpaqueState state) {
    return primeLiteBaseEnergyCore(state.kind, state.baseColor, state.subsurfaceWeight,
                                   state.thinWalled, state.baseColor);
}

public PrimeLitePtOpaqueState primeLitePtOpaqueStateInit(float3 baseColor, float roughness,
                                                         float materialIor, float inverseOutsideIor,
                                                         float subsurfaceWeight, bool thinWalled,
                                                         float3 wi) {
    PrimeLitePtOpaqueState state;
    state.kind = subsurfaceWeight == 1.0  ? PRIME_LITE_OPAQUE_SUBSURFACE
                 : subsurfaceWeight > 0.0 ? PRIME_LITE_OPAQUE_MIXED_SUBSURFACE
                                          : PRIME_LITE_OPAQUE_DIELECTRIC;
    state.thinWalled = thinWalled ? 1u : 0u;
    state.baseColor = baseColor;
    state.subsurfaceWeight = subsurfaceWeight;
    state.microfacet.alpha = float2(roughness * roughness);
    state.energyIor = primeOpenPbrIorAdjustment(materialIor * inverseOutsideIor, 1.0);
    state.diffuseMix = primeLiteMixState(
            (1.0 - subsurfaceWeight) * primeLiteWeight(baseColor),
            subsurfaceWeight * primeLiteWeight(primeLitePtSubsurfaceColor(state)));
    float reflectedEnergy;
    state.layer.specularScale = 1.0;
    if (primeLiteMicrofacetEffectivelySmooth(state.microfacet)) {
        reflectedEnergy = primeLiteDielectricFresnel(state.energyIor, abs(wi.z));
    } else {
        reflectedEnergy = primeLiteDielectricDirectionalClosure(
                state.microfacet, abs(wi.z), state.energyIor, state.layer.specularScale);
    }
    state.layer.transmittanceIn = 1.0 - reflectedEnergy;
    PrimeLiteMixState layerMix = primeLiteMixState(
            primeLiteWeight(state.layer.transmittanceIn * primeLitePtBaseEnergy(state)),
            primeLiteWeight(float3(reflectedEnergy)));
    state.layer.baseSampleWeight = layerMix.firstSampleWeight;
    state.layer.specularSampleWeight = layerMix.secondSampleWeight;
    return state;
}

public PrimeLiteOpaqueEvaluation primeLitePtOpaqueEvaluate(float3 wi, float3 wo,
                                                           PrimeLitePtOpaqueState state) {
    PrimeLiteOpaqueEvaluation result;
    PrimeOpenPbrThroughput base =
            primeLiteBaseEvalCore(wi, wo, state.kind, state.baseColor, state.subsurfaceWeight,
                                  state.thinWalled, state.baseColor, 0.0, PRIME_OPENPBR_FLAG_ALL);
    result.throughput = primeOpenPbrThroughputAdd(
            primeOpenPbrThroughputScale(base, state.layer.transmittanceIn),
            primeLiteDielectricEvalCore(wi, wo, state.microfacet, state.energyIor, float3(1.0),
                                        state.layer.specularScale, PRIME_OPENPBR_FLAG_ALL));
    result.pdf = primeLiteLayerPdf(state.layer,
                                   primeLiteBasePdfCore(wi, wo, state.kind, state.thinWalled, 0.0,
                                                        state.diffuseMix, PRIME_OPENPBR_FLAG_ALL),
                                   primeLiteReflectivePdf(wi, wo, state.microfacet));
    return result;
}

public PrimeOpenPbrSample primeLitePtOpaqueSample(float3 wi, float3 randomValue,
                                                  PrimeLitePtOpaqueState state) {
    if (randomValue.z < state.layer.specularSampleWeight &&
        state.layer.specularSampleWeight > 0.0) {
        float microCosine;
        PrimeOpenPbrSample result = primeLiteReflectiveSampleBase(
                wi, randomValue.xy, state.microfacet, PRIME_OPENPBR_FLAG_ALL, microCosine);
        if (result.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
            result.throughput.value *=
                    float3(primeLiteDielectricFresnel(state.energyIor, microCosine));
            if (primeOpenPbrIsNonDelta(result.throughput.flags))
                result.throughput.value *= state.layer.specularScale;
        }
        result.pdf *= state.layer.specularSampleWeight;
        return result;
    }
    float remapped = state.layer.baseSampleWeight > 0.0
                             ? (randomValue.z - state.layer.specularSampleWeight) /
                                       state.layer.baseSampleWeight
                             : 0.0;
    PrimeOpenPbrSample result =
            primeLiteBaseSampleCore(wi, float3(randomValue.xy, remapped), state.kind,
                                    state.baseColor, state.subsurfaceWeight, state.thinWalled,
                                    state.baseColor, 0.0, state.diffuseMix, PRIME_OPENPBR_FLAG_ALL);
    result.throughput = primeOpenPbrThroughputScale(result.throughput, state.layer.transmittanceIn);
    result.pdf *= state.layer.baseSampleWeight;
    return result;
}
