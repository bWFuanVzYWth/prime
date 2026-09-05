#language slang 2026
import glsl;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> output;
[shader("anyhit")]
void main(inout uint payload, in BuiltInTriangleIntersectionAttributes attributes) {
    uint base = DispatchRaysIndex().x * 30u + 0u;
    for (uint i = 0; i < 3u; i++) {
        uint3 bits = asuint(HitTriangleVertexPosition(i));
        output[base + i * 3u] = bits.x;
        output[base + i * 3u + 1u] = bits.y;
        output[base + i * 3u + 2u] = bits.z;
    }
    output[base + 9u] = GeometryIndex() * 3u + PrimitiveIndex();
    payload |= 1u;
}
