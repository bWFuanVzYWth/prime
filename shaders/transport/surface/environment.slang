#language slang 2026
module "transport/surface/environment.slang";

import "contract/light_sample.slang";
import "contract/path.slang";
import "math/transport.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "prime_abi_types.slang";
import "service/atmosphere/starmap.slang";
import "service/light/sample.slang";
import "service/trace/context.slang";
import "state/path.slang";
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
