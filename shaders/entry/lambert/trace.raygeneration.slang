#language slang 2026
module "entry/lambert/trace.raygeneration.slang";
import "prime_abi.slang";
import "contract/lambert.slang";
import glsl;
import "state/lambert.slang";
import "service/camera/projection.slang";
import "service/reconstruct/lambert_guides.slang";
struct PrimeLambertShaderRecord { uint queue; };
[[vk::shader_record]] ConstantBuffer<PrimeLambertShaderRecord> primeLambertShaderRecord;
layout(location = 0) rayPayloadEXT PrimeLambertPayload primeLambertPayload;
[shader("raygeneration")]
void main() {
    uint index = primeLambertQueuedPath(primeLambertShaderRecord.queue, DispatchRaysIndex().x);
    uint4 identity = primeLambertPaths[index].identity;
    primeLambertPayload = {};
    primeLambertPayload.section = identity.x;
    primeLambertPayload.triangle = identity.y;
    primeLambertPayload.motion = uint(identity.z == 0u);
    traceRayEXT(primeScene, identity.z == 0u ? gl_RayFlagsNoneEXT : gl_RayFlagsNoOpaqueEXT,
            0xff, 0, 1, 0, primeLambertPaths[index].origin.xyz, 0.0,
            primeLambertPaths[index].direction.xyz, 1000000.0, 0);
    if (primeLambertPayload.distance >= 0.0) {
        primeLambertPaths[index].origin = float4(primeLambertPayload.position, 0.0);
        primeLambertPaths[index].albedo = float4(primeLambertPayload.albedo, 0.0);
        primeLambertPaths[index].identity.xy = uint2(primeLambertPayload.section, primeLambertPayload.triangle);
    }
    primeLambertPaths[index].normalDistance = float4(primeLambertPayload.normal, primeLambertPayload.distance);
    if (identity.z == 0u) {
        uint2 pixel = primeLambertPixel(index);
        primeLambertPaths[index].identity.w = uint(primeLambertPayload.distance >= 0.0);
        primeLambertWriteGuides(pixel, primeRealtimeCameraSample(pixel), primeLambertPayload);
    } else if (identity.z == 1u) {
        primeLambertPaths[index].radiance.w = primeLambertPayload.distance >= 0.0
                ? primeLambertPayload.distance : 65504.0;
    }
}
