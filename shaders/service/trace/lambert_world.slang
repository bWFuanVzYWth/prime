#language slang 2026
module "service/trace/lambert_world.slang";
import glsl;
import "prime_abi.slang";
import "contract/lambert.slang";

layout(location = 0) rayPayloadEXT PrimeLambertPayload primeLambertPayload;

public PrimeLambertPayload primeTraceLambert(float3 origin, float3 direction, uint2 source, uint motion) {
    primeLambertPayload = {};
    primeLambertPayload.section = source.x;
    primeLambertPayload.triangle = source.y;
    primeLambertPayload.motion = motion;
    traceRayEXT(primeScene, gl_RayFlagsNoOpaqueEXT, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 0);
    return primeLambertPayload;
}
