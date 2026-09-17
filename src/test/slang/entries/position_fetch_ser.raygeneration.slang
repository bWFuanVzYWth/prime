// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
import glsl;
import "contract/lambert.slang";
import "contract/trace.slang";
[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> output;
layout(location = 2) rayPayloadEXT PrimeTraceRequest traversal;
layout(location = 0) rayPayloadEXT PrimeLambertPayload shading;
[shader("raygeneration")]
void main() {
    uint id = DispatchRaysIndex().x;
    traversal.source = uint2(id ^ 0x55u, id * 17u);
    traversal.reserved = 0u;
    HitObject hit;
    hitObjectTraceRayEXT(hit, scene, gl_RayFlagsNoneEXT, 255, 0, 0, 0,
            float3(float(id) * 4.0 + 0.625 + 11.0, 0.5 - 3.0, 20.0),
            0.0, float3(0, 0, -1), 100.0, 2);
    reorderThreadEXT(hit, id % 3u, 2u);
    shading = {};
    shading.section = id ^ 0x55u;
    shading.triangle = id * 17u;
    shading.flags = traversal.reserved;
    hitObjectExecuteShaderEXT(hit, 0);
    bool identity = shading.section == (id ^ 0x55u) && shading.triangle == id * 17u;
    output[id * 30u + 29u] = identity && id == DispatchRaysIndex().x ? shading.flags : 0xffffffffu;
}
