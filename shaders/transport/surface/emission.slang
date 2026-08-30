#language slang 2026
module "transport/surface/emission.slang";

import "contract/light_sample.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "contract/path.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "service/atmosphere/starmap.slang";
import "math/transport.slang";
import "service/light/emission.slang";
import "service/light/evaluate.slang";
import "service/light/sample.slang";
import "model/material/defaults.slang";
import "service/trace/context.slang";
import "transport/surface/access.slang";

public float3 primeEvaluateEnvironmentContribution(
        inout PrimeIntegratorContext context,
        PathState path, IntegratorRecord integrator) {
    float3 stars = primeStarmapRadiance(
            integrator, path.physicalOrigin, path.rayDirection);
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.physicalOrigin, path.rayDirection);
    bool previousUsedSunNee =
            (primePathFlags(path.control)
                    & PRIME_PATH_PREVIOUS_NO_SUN_NEE) == 0u;
    float sunWeight = primePreviousCannotUseMis(path) || !previousUsedSunNee
            ? 1.0
            : primePowerHeuristic(
                    path.previousBsdfPdf, sun.pdf);
    return path.throughput
            * (primeEnvironmentRadiance(integrator, path.rayDirection)
            + stars
            + sun.radiance * sunWeight);
}

public float3 primeEvaluateLocalHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    if ((surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) != 0u) {
        return surface.baseColor
                * PRIME_LEVEL_15_BLOCK_INTENSITY
                * primeBlockLightRadianceMultiplier();
    }
    // Reconstruct the two-level reverse tree PDF only when the previous renderer-specific
    // transport vertex actually had a competing Area NEE technique.
    bool evaluateAreaPdf = previousUsedAreaNee
            && !primePreviousCannotUseMis(path);
    LightEvaluation hitAreaLight = primeEvaluateAreaLight(
            surface,
            path.physicalOrigin,
            path.rayDirection,
            path.previousLightNormal,
            evaluateAreaPdf);
    float hitAreaWeight = primeAreaHitMisWeightValue(
            previousUsedAreaNee,
            !evaluateAreaPdf,
            path.previousBsdfPdf,
            hitAreaLight.pdf);
    return hitAreaLight.radiance * hitAreaWeight;
}

public float3 primeEvaluateHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    return path.throughput * primeEvaluateLocalHitEmission(
            context, path, surface, previousUsedAreaNee);
}

public struct PrimeIndirectReuseResult {
    public float3 contribution;
    public bool hit;
};

public interface IPrimeIndirectReuse {
    PrimeIndirectReuseResult query(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            bool previousUsedAreaNee);
}

public struct PrimeNoIndirectReuse : IPrimeIndirectReuse {
    PrimeIndirectReuseResult query(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            bool previousUsedAreaNee) {
        PrimeIndirectReuseResult result;
        result.contribution = float3(0.0);
        result.hit = false;
        return result;
    }
}
