// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/lambert_world.slang";
import glsl;
import "prime_abi.slang";
import "contract/lambert.slang";
import "contract/lambert_trace.slang";
#if defined(PRIME_ENABLE_SER)
layout(location = 2) rayPayloadEXT PrimeLambertTracePayload primeLambertTrace;
#endif

layout(location = 0) rayPayloadEXT PrimeLambertPayload primeLambertPayload;

public PrimeLambertPayload primeTraceLambert(float3 origin, float3 direction, uint2 source, uint motion) {
    uint rayFlags = source.x == 0xffffffffu ? gl_RayFlagsNoneEXT : gl_RayFlagsNoOpaqueEXT;
#if defined(PRIME_ENABLE_SER)
    primeLambertTrace.source = source;
    primeLambertTrace.reserved = 0u;
    HitObject hitObject;
    hitObjectTraceRayEXT(hitObject, primeScene, rayFlags, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 2);
    // Camera / guide chains remain coherent; scattered world rays benefit from hit reordering.
    if (motion == 0u) {
        bool hit = hitObjectIsHitEXT(hitObject);
        uint hint = hit ? ((uint(hitObjectGetGeometryIndexEXT(hitObject)) & 0x3u) << 8u)
                | (uint(hitObjectGetInstanceIdEXT(hitObject)) & 0xffu) : 0u;
        reorderThreadEXT(hitObject, hint, 10u);
    }
    // None of the 96-byte shading output is initialized across traversal / reorder.
#endif
    primeLambertPayload = {};
    primeLambertPayload.section = source.x;
    primeLambertPayload.triangle = source.y;
    primeLambertPayload.motion = motion;
#if defined(PRIME_ENABLE_SER)
    hitObjectExecuteShaderEXT(hitObject, 0);
#else
    traceRayEXT(primeScene, rayFlags, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 0);
#endif
    return primeLambertPayload;
}
