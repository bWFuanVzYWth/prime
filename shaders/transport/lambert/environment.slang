// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/lambert/environment.slang";
import "contract/lambert.slang";
import "prime_abi.slang";
import "state/lambert.slang";
import "policy/runtime/config.slang";
import "model/atmosphere/sky.slang";
import "service/atmosphere/trace.slang";
import "service/atmosphere/starmap.slang";
import "service/light/sample.slang";
import "prime_abi_types.slang";

public bool primeFinishLambert(uint index) {
    if ((primeLambertPaths[index].identity.w & PRIME_LAMBERT_LIMIT) != 0u) return true;
    if (primeLambertPaths[index].normalDistance.w >= 0.0) return false;
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    float3 direction = primeLambertPaths[index].direction.xyz;
    bool nativeStars = (primePush.path.w & PRIME_PATH_NATIVE_STARS_MASK) != 0u
            && (primeLambertPaths[index].identity.w & PRIME_LAMBERT_VISIBLE) == 0u;
    float3 environment = primeEnvironmentRadiance(integrator, direction)
            + (nativeStars ? float3(0.0) : primeStarmapRadiance(integrator, direction));
    if (primeLambertPaths[index].identity.z == 0u) environment += primeEvaluateSun(integrator, direction).radiance;
    primeLambertPaths[index].radiance.rgb += primeLambertPaths[index].throughput.rgb * environment;
    return true;
}
