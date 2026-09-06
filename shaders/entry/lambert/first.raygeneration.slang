#language slang 2026
module "entry/lambert/first.raygeneration.slang";
import "service/trace/lambert_shadow.slang";
import "policy/runtime/config.slang";
import "math/lambert.slang";
import "math/direct_sampling.slang";
import "state/lambert_transport.slang";
import "state/lambert_interface.slang";
import "state/lambert.slang";
import "contract/light_sample.slang";
import "contract/lambert.slang";
import "prime_lambert_contract.slang";
import "prime_abi.slang";
import glsl;
import "state/lambert_queue.slang";
import "transport/lambert/continuation.slang";
import "transport/lambert/environment.slang";
import "transport/lambert/sun.slang";
import "service/light/tree_select.slang";
import "service/light/area_sample.slang";
import "service/reconstruct/lambert_direct.slang";
import "service/reconstruct/lambert_sun.slang";

struct PrimeLambertAreaTarget {
    float3 direction;
    float distance;
    float pdf;
    uint2 identity;
    float2 uv;
};

PrimeLambertAreaTarget primeLambertAreaTarget(uint index) {
    uint flags = primeLambertPaths[index].identity.w;
    PrimeDirectSampleBase samples = primeMakeDirectSampleBase(primeLambertPixel(index), primeSampleIndex(),
            primeSampleEpoch(), 2u, uint((flags & PRIME_LAMBERT_REFLECTION) != 0u));
    float2 selectionSample = primeDirectSample2D(samples, 0u);
    AreaLightSelection selection = primeSelectAreaLight(primeLambertPaths[index].origin.xyz,
            primeLambertPaths[index].normalDistance.xyz, selectionSample.x);
    AreaLightSample area = primeSampleAreaLightSelection(primeLambertPaths[index].origin.xyz,
            selection, selectionSample.y, primeDirectSample2D(samples, 1u));
    PrimeLambertAreaTarget result;
    result.direction = area.light.direction;
    result.distance = area.light.distance;
    result.pdf = area.light.pdf;
    result.identity = uint2(area.sectionIndex, area.emitterIndex);
    result.uv = area.uv;
    return result;
}

void primeLambertArea(uint index) {
    // Tree traversal and unused generic LightSample lanes end before the shadow query.
    PrimeLambertAreaTarget area = primeLambertAreaTarget(index);
    float cosine = dot(primeLambertPaths[index].normalDistance.xyz, area.direction);
    if (!(cosine > 0.0) || !(area.pdf > 0.0)) return;
    PrimeLambertVisibility visibility = primeTraceLambertShadow(primeLambertPaths[index].origin.xyz,
            area.direction, area.distance, primeLambertPaths[index].identity.xy,
            area.identity, primeLambertLoadMedium(index), primeLambertDeltaLimit());
    if (visibility.blockerDistance >= 0.0) return;
    float3 direct = primeLambertPaths[index].throughput.rgb * visibility.filter
            * primeLambertDirect(primeLambertPaths[index].albedo.rgb, cosine,
                    primeResolveSampledAreaLightRadiance(area.identity.x, area.identity.y, area.uv), area.pdf);
    primeLambertPaths[index].radiance.rgb += direct;
    primeLambertRecordDirect(index, direct, area.direction);
}

[shader("raygeneration")]
void main() {
    uint launch = DispatchRaysIndex().x;
    uint index = primeLambertQueuedPath(0u, launch);
    if (launch == 0u) primeLambertQueue[primeLambertCommandWord(0u)] = 0u;
    primeLambertBeginDirect(index);
    bool continuation = !primeFinishLambert(index);
    // A front-chain sky result has no Lambert scattering direction to encode in SH.
    if (!continuation) primeLambertPaths[index].firstDirection.xyz = float3(0.0);
    if (continuation) {
        primeLambertArea(index);
        PrimeLambertSunSample sun = primeLambertSun(index);
        if ((primeLambertPaths[index].identity.w & PRIME_LAMBERT_TRANSPARENT) == 0u)
            primeLambertWriteSun(primeLambertPixel(index), sun.radiance, sun.blockerDistance);
        else if (sun.blockerDistance < 0.0) {
            primeLambertPaths[index].radiance.rgb += sun.radiance;
            primeLambertRecordDirect(index, sun.radiance, sun.direction);
        }
        continuation = primeContinueLambert(index);
        if (continuation) {
            float3 direction = primeLambertPaths[index].direction.xyz;
            primeLambertPaths[index].firstDirection.xyz =
                    (primeLambertPaths[index].identity.w & PRIME_LAMBERT_REFLECTION) != 0u
                    ? reflect(direction, primeLambertPaths[index].firstDirection.xyz) : direction;
        }
    }
    primeLambertAppend(1u, index, continuation);
}
