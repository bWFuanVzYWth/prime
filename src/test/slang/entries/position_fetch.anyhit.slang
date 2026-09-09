// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
import glsl;
import "contract/lambert_trace.slang";
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> output;
#if defined(PRIME_TEST_SER)
layout(location = 2) rayPayloadInEXT PrimeLambertTracePayload traversal;
#endif
[shader("anyhit")]
#if defined(PRIME_TEST_SER)
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    uint id = DispatchRaysIndex().x;
    traversal.reserved = 1u;
    if (any(traversal.source != uint2(id ^ 0x55u, id * 17u))) traversal.reserved |= 0x80u;
#else
void main(inout uint payload, in BuiltInTriangleIntersectionAttributes attributes) {
#endif
    uint base = DispatchRaysIndex().x * 30u + 0u;
    for (uint i = 0; i < 3u; i++) {
        uint3 bits = asuint(HitTriangleVertexPosition(i));
        output[base + i * 3u] = bits.x;
        output[base + i * 3u + 1u] = bits.y;
        output[base + i * 3u + 2u] = bits.z;
    }
    output[base + 9u] = GeometryIndex() * 3u + PrimitiveIndex();
#if !defined(PRIME_TEST_SER)
    payload |= 1u;
#endif
}
