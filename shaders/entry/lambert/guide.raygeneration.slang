// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/lambert/guide.raygeneration.slang";
import "state/lambert.slang";
import "contract/lambert.slang";
import glsl;
import "state/lambert_interface.slang";
import "service/trace/lambert_world.slang";
import "service/reconstruct/lambert_receiver.slang";
import "service/camera/projection.slang";

[shader("raygeneration")]
void main() {
    uint launch = DispatchRaysIndex().x;
    uint index = primeLambertQueuedPath(PRIME_LAMBERT_GUIDE_QUEUE, launch);
    if (launch == 0u) primeLambertQueue[primeLambertCommandWord(PRIME_LAMBERT_GUIDE_QUEUE)] = 0u;
    float3 origin = primeLambertInterfaceRow(index, 0u).xyz;
    float3 normal = primeLambertPaths[index].firstDirection.xyz;
    uint2 source = uint2(asuint(primeLambertInterfaceRow(index, 2u).w), asuint(primeLambertInterfaceRow(index, 1u).w));
    uint2 pixel = primeLambertPixel(index);
    float3 direction = primeCameraRayDirection(pixel, primeRealtimeCameraSample(pixel));
    bool reflection = (primeLambertPaths[index].identity.w & PRIME_LAMBERT_REFLECTION) == 0u;
    if (reflection) direction = reflect(direction, normal);
    uint transitions = 1u;
    float distance = 0.0;
    // Straight geometry needs no medium, Fresnel, radiance or RR state on this omitted branch.
    [loop] for (;;) {
        PrimeLambertPayload hit = primeTraceLambert(origin, direction, source, 2u);
        if (hit.distance >= 0.0) distance += length(hit.position - origin);
        bool delta = hit.distance >= 0.0 && (hit.flags & PRIME_LAMBERT_DELTA) != 0u;
        bool exhausted = delta && transitions == primeLambertDeltaLimit();
        if (!delta || exhausted) {
            if (hit.distance >= 0.0) hit.distance = distance;
            primeLambertWriteReceiver(index, hit, direction, reflection, exhausted);
            return;
        }
        origin = hit.position;
        source = uint2(hit.section, hit.triangle);
        transitions++;
    }
}
