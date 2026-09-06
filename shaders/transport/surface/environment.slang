#language slang 2026
module "transport/surface/environment.slang";

import "contract/light_sample.slang";
import "contract/path.slang";
import "math/transport.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "prime_abi.slang";
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
            integrator, path.rayDirection);
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.rayDirection);
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

public float3 primeEvaluateEnvironmentContribution(
        inout PrimeIntegratorContext context, PathState path) {
    // Miss-only construction keeps the sun descriptor out of the scatter call's live state.
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection, ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    return primeEvaluateEnvironmentContribution(context, path, integrator);
}
