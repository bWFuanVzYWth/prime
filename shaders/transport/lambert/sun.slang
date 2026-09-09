// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/lambert/sun.slang";
import "state/lambert.slang";
import "contract/lambert.slang";
import "prime_abi_types.slang";
import "prime_abi.slang";
import "state/lambert_transport.slang";
import "contract/light_sample.slang";
import "math/lambert.slang";
import "math/direct_sampling.slang";
import "policy/runtime/config.slang";
import "service/light/sample.slang";
import "service/atmosphere/trace.slang";
import "model/atmosphere/sky.slang";
import "service/trace/lambert_shadow.slang";

public struct PrimeLambertSunSample {
    public float3 radiance;
    public float3 direction;
    public float blockerDistance;
};

public PrimeLambertSunSample primeLambertSun(uint index) {
    PrimeLambertSunSample result = {};
    result.blockerDistance = -1.0;
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(primePush.sunDirection, ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    uint completed = primeLambertPaths[index].identity.z;
    uint flags = primeLambertPaths[index].identity.w;
    PrimeDirectSampleBase samples = primeMakeDirectSampleBase(primeLambertPixel(index), primeSampleIndex(),
            primeSampleEpoch(), 2u * (completed + 1u), uint((flags & PRIME_LAMBERT_REFLECTION) != 0u));
    LightSample sun = primeSampleSun(integrator, primeDirectSample2D(samples, PRIME_DIRECT_SAMPLE_SUN));
    float cosine = dot(primeLambertPaths[index].normalDistance.xyz, sun.direction);
    if (!(cosine > 0.0) || !primeAtmosphereDistantDirectionVisible(sun.direction)) return result;
    PrimeLambertVisibility visibility = primeTraceLambertShadow(primeLambertPaths[index].origin.xyz,
            sun.direction, sun.distance, primeLambertPaths[index].identity.xy, uint2(0xffffffffu),
            primeLambertLoadMedium(index), primeLambertDeltaLimit());
    result.radiance = primeLambertDirect(primeLambertPaths[index].albedo.rgb, cosine,
            primeResolveSampledSunRadiance(integrator, sun), sun.pdf)
            * primeLambertPaths[index].throughput.rgb * visibility.filter;
    result.direction = sun.direction;
    result.blockerDistance = visibility.blockerDistance;
    return result;
}
