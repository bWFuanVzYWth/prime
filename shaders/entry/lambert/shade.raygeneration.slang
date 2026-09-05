#language slang 2026
module "entry/lambert/shade.raygeneration.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_lambert_contract.slang";
import "contract/lambert.slang";
import "contract/light_sample.slang";
import "policy/runtime/config.slang";
import "model/atmosphere/sky.slang";
import "service/atmosphere/trace.slang";
import "service/atmosphere/starmap.slang";
import glsl;
import "state/lambert.slang";
import "state/lambert_queue.slang";
import "math/lambert.slang";
import "math/lambert_roulette.slang";
import "math/direct_sampling.slang";
import "service/light/sample.slang";
import "service/reconstruct/lambert_sun.slang";
struct PrimeLambertShaderRecord { uint queue; };
[[vk::shader_record]] ConstantBuffer<PrimeLambertShaderRecord> primeLambertShaderRecord;
layout(location = 1) rayPayloadEXT PrimeLambertShadowPayload primeLambertShadow;

bool primeShadeLambertPath(uint index) {
    uint2 pixel = primeLambertPixel(index);
    uint bounce = primeLambertPaths[index].identity.z;
    float3 origin = primeLambertPaths[index].origin.xyz;
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    if (primeLambertPaths[index].normalDistance.w < 0.0) {
        float3 direction = primeLambertPaths[index].direction.xyz;
        float3 environment = primeEnvironmentRadiance(integrator, direction)
                + primeStarmapRadiance(integrator, direction);
        // Sun uses NEE exclusively after Lambert vertices; the camera sees its disk directly.
        // These disjoint estimators require no MIS or previous-BSDF state.
        if (bounce == 0u) environment += primeEvaluateSun(integrator, direction).radiance;
        primeLambertPaths[index].radiance.rgb += primeLambertPaths[index].throughput.rgb * environment;
        return false;
    }
    // The ray after the twelfth scatter can still reach the environment. A further surface
    // terminates before NEE/scattering, keeping the same transport order for sky and sun.
    if (bounce >= PRIME_LAMBERT_MAXIMUM_BOUNCES) return false;
    PrimeDirectSampleBase samples = primeMakeDirectSampleBase(
            pixel, primeSampleIndex(), primeSampleEpoch(), bounce + 1u, 0u);
    LightSample sun = primeSampleSun(integrator, primeDirectSample2D(
            samples, PRIME_DIRECT_SAMPLE_SUN));
    float cosine = dot(primeLambertPaths[index].normalDistance.xyz, sun.direction);
    if (cosine > 0.0 && primeAtmosphereDistantDirectionVisible(sun.direction)) {
        primeLambertShadow.source = primeLambertPaths[index].identity.xy;
        traceRayEXT(primeScene, gl_RayFlagsNoOpaqueEXT, 0xff, 3, 1, 1,
                origin, 0.0, sun.direction, sun.distance, 1);
        // Only the visible sun term needs unshadowed lighting for SIGMA.
        if (bounce == 0u || primeLambertShadow.distance < 0.0) {
            float3 direct = primeLambertDirect(primeLambertPaths[index].albedo.rgb, cosine,
                    primeResolveSampledSunRadiance(integrator, sun), sun.pdf);
            if (bounce == 0u) primeLambertWriteSun(pixel, direct, primeLambertShadow.distance);
            else primeLambertPaths[index].radiance.rgb += primeLambertPaths[index].throughput.rgb * direct;
        }
    }
    // Material and continuation state are loaded after shadow traversal instead of kept across it.
    // f*cos/pdf cancels exactly for cosine-weighted Lambert sampling.
    float3 throughput = primeLambertPaths[index].throughput.rgb * primeLambertPaths[index].albedo.rgb;
    if (!primeLambertRoulette(throughput, bounce + 1u, primeLambertRouletteSample(
            pixel, primeSampleIndex(), primeSampleEpoch(), bounce + 1u))) return false;
    // Bank 0 is unused by light sampling in this model; bank 2 independently samples the sun.
    float3 direction = primeLambertDirection(primeLambertPaths[index].normalDistance.xyz, primeDirectSample2D(
            samples, PRIME_DIRECT_SAMPLE_AREA_PRIMARY));
    primeLambertPaths[index].direction.xyz = direction;
    primeLambertPaths[index].throughput.rgb = throughput;
    primeLambertPaths[index].identity.z = bounce + 1u;
    if (bounce == 0u) primeLambertPaths[index].firstDirection.xyz = direction;
    return true;
}

[shader("raygeneration")]
void main() {
    uint launch = DispatchRaysIndex().x;
    uint queue = primeLambertShaderRecord.queue;
    uint index = primeLambertQueuedPath(queue, launch);
    // The indirect launch has consumed this counter; entries do not read it again.
    // An empty queue is already zero from its last consumer.
    if (launch == 0u) primeLambertQueue[queue * (PRIME_LAMBERT_COMMAND_STRIDE / 4u)] = 0u;
    bool continuation = primeShadeLambertPath(index);
    primeLambertAppend(queue ^ 1u, index, continuation);
}
