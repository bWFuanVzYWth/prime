import "math/triangle_uv.slang";
import "math/packed_geometry.slang";
[[vk::binding(0, 0)]] StructuredBuffer<float4> input;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;

[numthreads(1, 1, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    uint index = thread.x;
    float3 normal = primeTriangleUnitNormal(input[index].xyz);
    uint3 uv = uint3(0u, 0xffffu, 0xffff0000u);
    float2 triangle = primeTriangleUv(uv, float2(0.25, 0.5));
    float2 repeated = primeRepeatedUv(uv, float3(-1.25, 2.5, -3.75), normal);
    output[index * 2u] = float4(triangle, repeated);
    uint failures = 0u;
    // Exhaust small partition layouts, including no macros and an entirely macro partition.
    // Source triangles outside opaque storage must never alias an opaque candidate.
    for (uint count = 1u; count <= 8u; ++count) {
        for (uint macroBase = 0u; macroBase <= count; ++macroBase) {
            uint triangles = macroBase + 2u * (count - macroBase);
            for (uint triangle = 0u; triangle < triangles; ++triangle) {
                for (uint source = 0u; source < triangles + 4u; ++source) {
                    bool sameMacro = triangle >= macroBase && source >= macroBase
                            && source < triangles && (triangle - macroBase) / 2u == (source - macroBase) / 2u;
                    bool expected = triangle == source || sameMacro;
                    failures += primeOpaqueSourceTriangle(triangle, source, count, macroBase) != expected ? 1u : 0u;
                }
                failures += primeOpaqueSourceTriangle(triangle, 0xffffffffu, count, macroBase) ? 1u : 0u;
            }
        }
    }
    output[index * 2u + 1u] = float4(normal, float(failures));
}
