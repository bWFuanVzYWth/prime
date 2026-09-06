#language slang 2026
module "entry/lambert/camera.raygeneration.slang";
import "policy/runtime/config.slang";
import "service/trace/lambert_world.slang";
import "model/lambert/medium.slang";
import "state/lambert.slang";
import "contract/lambert.slang";
import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "model/lambert/path.slang";
import "math/lambert_transport.slang";
import "math/direct_sampling.slang";
import "state/lambert_transport.slang";
import "state/lambert_interface.slang";
import "state/lambert_hit.slang";
import "state/lambert_queue.slang";
import "service/camera/projection.slang";
import "service/trace/lambert_walk.slang";
import "service/reconstruct/lambert_receiver.slang";

[shader("raygeneration")]
void main() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint index = pixel.y * primePush.outputExtent.x + pixel.x;
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    float3 direction = primeCameraRayDirection(pixel, cameraSample);
    float3 origin = primePush.cameraPosition;
    uint2 source = uint2(0xffffffffu);
    primeLambertPaths[index] = primeLambertCameraPath(origin, direction);
    PrimeLambertMedium medium = primeLambertCameraMedium((primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u);
    PrimeLambertPayload hit = primeTraceLambert(origin, direction, source, 1u);
    bool visible = hit.distance >= 0.0;
    bool transparent = visible && (hit.flags & PRIME_LAMBERT_DELTA) != 0u;
    float fresnel = transparent ? primeLambertFresnel(dot(direction, hit.normal), (hit.flags & PRIME_LAMBERT_WATER) != 0u) : 0.0;
    primeLambertWriteVisible(index, cameraSample, hit, fresnel);
    primeLambertPaths[index].identity.w = visible ? PRIME_LAMBERT_VISIBLE : 0u;
    bool exhausted = false;
    // Camera initialization has at most one active medium, so one inferred entry cannot overflow.
    if (visible) primeLambertInferIncomingMedium(medium, hit.media, hit.albedo, hit.auxiliary, hit.flags);
    float3 throughput = primeLambertSegmentFilter(medium, visible ? length(hit.position - origin) : 1000000.0);
    if (visible && !transparent) {
        primeLambertWriteVisibleEmission(index, throughput * hit.auxiliary);
        hit.auxiliary = float3(0.0);
    }
    bool reflection = false;
    if (transparent) {
        primeLambertStoreInterface(index, hit, fresnel);
        PrimeDirectSampleBase samples = primeMakeDirectSampleBase(pixel, primeSampleIndex(), primeSampleEpoch(), 0u, 0u);
        reflection = primeLambertReflect(fresnel, primeDirectSample2D(samples, 0u).x);
        primeLambertPaths[index].identity.w |= PRIME_LAMBERT_TRANSPARENT | (reflection ? PRIME_LAMBERT_REFLECTION : 0u);
        throughput *= primeLambertBranchWeight(fresnel, reflection);
        origin = hit.position;
        source = uint2(hit.section, hit.triangle);
        if (reflection) direction = reflect(direction, hit.normal);
        else exhausted = !primeLambertTransmit(medium, throughput, hit);
        if (!exhausted) hit = primeWalkLambert(origin, direction, source, medium, throughput,
                1u, primeLambertDeltaLimit(), 1u, exhausted);
        primeLambertWriteReceiver(index, hit, direction, reflection, exhausted);
    }
    primeLambertPaths[index].direction.xyz = direction;
    primeLambertPublishHit(index, hit, throughput, exhausted);
    primeLambertStoreMedium(index, medium);
    // Exactly one lighting lane for every pixel. Missing guides never publish lighting successors.
    primeLambertQueue[primeLambertQueueWord(0u, index)] = index;
    if (index == 0u) primeLambertQueue[primeLambertCommandWord(0u)] = primeLambertPixelCount();
    primeLambertAppend(PRIME_LAMBERT_GUIDE_QUEUE, index, transparent);
}
