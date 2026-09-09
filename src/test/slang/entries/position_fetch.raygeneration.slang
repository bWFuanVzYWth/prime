// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
import glsl;
[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> output;
[shader("raygeneration")]
void main() {
    uint id = DispatchRaysIndex().x;
    RayDesc ray;
    ray.Origin = float3(float(id) * 4.0 + 0.625 + 11.0, 0.5 - 3.0, 20.0);
    ray.Direction = float3(0, 0, -1);
    ray.TMin = 0.0; ray.TMax = 100.0;
    uint payload = 0u;
    TraceRay(scene, RAY_FLAG_NONE, 255, 0, 0, 0, ray, payload);
    output[id * 30u + 29u] = payload;
}
