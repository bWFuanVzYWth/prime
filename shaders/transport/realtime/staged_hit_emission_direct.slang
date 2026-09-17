// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/staged_hit_emission_direct.slang";

import "prime_abi_types.slang";
import "contract/medium.slang";
import "contract/light_sample.slang";
import "contract/path.slang";
import "math/transport.slang";
import "model/material/defaults.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/light/emission.slang";
import "service/light/evaluate.slang";
import "state/realtime/contract.slang";
import "state/realtime/direct.slang";

public float3 primeEvaluateStagedHitEmission(
        SurfaceInteraction surface,
        PrimeWavefrontHitEmissionState path,
        float3 throughput,
        bool previousUsedAreaNee) {
    if ((surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) != 0u) {
        return throughput * surface.baseColor
                    * PRIME_LEVEL_15_BLOCK_INTENSITY
                    * primeBlockLightRadianceMultiplier();
    }
    bool previousCannotUseMis = path.previousCannotUseMis;
    bool evaluateAreaPdf = previousUsedAreaNee && !previousCannotUseMis;
    LightEvaluation hitArea = primeEvaluateAreaLight(
            surface,
            path.physicalOrigin,
            -path.viewDirection,
            path.previousLightNormal,
            evaluateAreaPdf);
    float weight = primeAreaHitMisWeightValue(
            previousUsedAreaNee,
            previousCannotUseMis,
            path.previousBsdfPdf,
            hitArea.pdf);
    return throughput * hitArea.radiance * weight;
}

public bool primeStagedHitEmissionDiffuse(
        uint pathIndex,
        SurfaceInteraction surface,
        PrimeWavefrontHitEmissionState path,
        bool mayReplacePrimarySurface) {
    uint control = path.control;
    if (!primeWavefrontTransparentBranch(control)) {
        return (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    }
    if (!primeWavefrontGuideEnabled(control)) {
        return primeWavefrontTransmissionBranch(control);
    }
    bool pendingReplacement = mayReplacePrimarySurface
            && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u
            && !primeWavefrontGuideFallback(path.guideControl);
    if (pendingReplacement) {
        PrimeMedium medium =
                primeLoadWavefrontHitMedium(pathIndex);
        PrimeClosureTraits traits = primeSurfaceClosureTraits(
                surface, path.viewDirection, medium, primeUsesAirGap());
        if (!primeClosureIsDiscreteOnly(traits)) return true;
    }
    return (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
}
