// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dielectric/traits.slang";

import "service/bsdf/contract/sample.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "model/material/defaults.slang";
import "model/material/translation.slang";
import "model/material/medium_boundary.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/support/microfacet.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/model/material.slang";

public PrimeOpenPbrMicrofacet primeMinecraftTransmissionMicrofacet(
        PrimeCompactTransmissionState state) {
    // RoboCute scales a thin dielectric's apparent interface roughness by its relative IOR.
    // At an index-matched boundary this can be discrete even when the authored roughness is not.
    return state.geometryThinWalled != 0u
            ? state.transmissionMicrofacet
            : state.specularMicrofacet;
}

public bool primeMinecraftTransmissionIsDiscrete(PrimeCompactTransmissionState state) {
    // An index-matched dielectric has no visible rough boundary. RoboCute therefore collapses
    // transmission to straight-through discrete transport even when the authored NDF is rough.
    return state.specularFresnel.ior == 1.0
            || primeOpenPbrMicrofacetEffectivelySmooth(
                    primeMinecraftTransmissionMicrofacet(state));
}


public PrimeClosureTraits primeMinecraftTransmissionClosureTraits(
        PrimeCompactTransmissionState state) {
    PrimeClosureTraits result;
    result.measureMask = primeMinecraftTransmissionIsDiscrete(state)
            ? PRIME_MEASURE_DISCRETE
            : PRIME_MEASURE_SOLID_ANGLE;
    result.eventMask = PRIME_BSDF_EVENT_REFLECTION
            | PRIME_BSDF_EVENT_TRANSMISSION
            | (result.measureMask == PRIME_MEASURE_DISCRETE
                    ? PRIME_BSDF_EVENT_DISCRETE
                    : PRIME_BSDF_EVENT_GLOSSY);
    return result;
}

public PrimeClosureTraits primeMinecraftTransmissionClosureTraits(
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        PrimeOpenPbrVolume medium,
        uint adjacentInterfaceControl,
        bool usesAirGap) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, materialControl);
    float interfaceRoughness = primeMaterialEffectiveLinearRoughness(
            materialControl, translated.perceptualRoughness);
    bool thinWalled = (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u;
    float originalIor = primeMaterialIsWater(materialControl)
            ? 1.333
            : primeOpenPbrF0ToIor(translated.dielectricF0);
    float relativeIor;
    if (primeHasAdjacentMedium(adjacentInterfaceControl)) {
        float adjacentIor = primeMinecraftInterfaceIor(
                primeAdjacentMaterialControl(adjacentInterfaceControl),
                primeAdjacentFresnelCode(adjacentInterfaceControl));
        if (primeUsesAdjacentAirGap(
                usesAirGap,
                materialControl,
                opticalControl,
                adjacentInterfaceControl)) {
            interfaceRoughness = 0.0;
            thinWalled = true;
            relativeIor = 1.0 / adjacentIor;
        } else {
            relativeIor = originalIor * (1.0 / adjacentIor);
        }
    } else {
        relativeIor = originalIor * (thinWalled ? 1.0 / medium.ior : 1.0);
    }
    PrimeOpenPbrMicrofacet microfacet =
            primeClosureMicrofacet(interfaceRoughness);
    if (thinWalled) {
        microfacet.alpha = clamp(
                microfacet.alpha
                        * primeOpenPbrThinDielectricRoughnessScaler2(relativeIor),
                float2(0.0),
                float2(1.0));
    }
    PrimeClosureTraits result;
    bool discrete = relativeIor == 1.0
            || primeOpenPbrMicrofacetEffectivelySmooth(microfacet);
    result.measureMask = discrete
            ? PRIME_MEASURE_DISCRETE
            : PRIME_MEASURE_SOLID_ANGLE;
    result.eventMask = PRIME_BSDF_EVENT_REFLECTION
            | PRIME_BSDF_EVENT_TRANSMISSION
            | (discrete
                    ? PRIME_BSDF_EVENT_DISCRETE
                    : PRIME_BSDF_EVENT_GLOSSY);
    return result;
}
