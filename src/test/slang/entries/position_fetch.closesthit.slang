// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
import glsl;
import "contract/lambert.slang";
import "prime_abi_types.slang";
import "prime_surface_abi.slang";
import "service/trace/identity.slang";
import "service/trace/candidate_record.slang";
struct TestPush { uint64_t keys; uint64_t direct; uint64_t relation; };
[[vk::push_constant]] ConstantBuffer<TestPush> testPush;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> output;
[shader("closesthit")]
#if defined(PRIME_TEST_SURFACE)
void main(inout TracePayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
#elif defined(PRIME_TEST_SER)
void main(inout PrimeLambertPayload payload, in BuiltInTriangleIntersectionAttributes attributes) {
#else
void main(inout uint payload, in BuiltInTriangleIntersectionAttributes attributes) {
#endif
    uint base = DispatchRaysIndex().x * 30u + 10u;
    for (uint i = 0; i < 3u; i++) {
        uint3 bits = asuint(HitTriangleVertexPosition(i));
        output[base + i * 3u] = bits.x;
        output[base + i * 3u + 1u] = bits.y;
        output[base + i * 3u + 2u] = bits.z;
    }
    output[base + 9u] = GeometryIndex() * 3u + PrimitiveIndex();
    SectionRecord section = (SectionRecord)0;
    bool keyed = (DispatchRaysIndex().x & 1u) == 0u;
    section.primitiveAddress = keyed ? testPush.keys : testPush.direct;
    section.instanceTint = keyed ? PRIME_SURFACE_KEYS_FLAG : 0u;
    section.cutoutBase = 2u; section.transmissiveBase = 4u;
    section.opaqueMacroTriangleBase = 1u;
    section.cutoutMacroTriangleBase = 1u;
    section.transmissiveMacroTriangleBase = 1u;
    PrimitiveRecord record = primeRawPrimitive(section);
    PrimeCandidateRecord candidate;
    candidate.address = testPush.direct;
    candidate.index = primeGlobalPrimitiveIndex(section);
    candidate.kind = keyed ? 1u : 0u;
    if (keyed) candidate.index = ((uint*)testPush.keys)[candidate.index];
    if (DispatchRaysIndex().x == 8u) {
        candidate.kind = 2u;
        candidate.address = testPush.relation;
    }
    uint4 uv = primeCandidateUvWords(candidate);
    uint3 header = primeCandidateHeader(candidate);
    uint offset = DispatchRaysIndex().x * 30u + 20u;
    output[offset] = uv.x; output[offset + 1u] = uv.y;
    output[offset + 2u] = uv.z; output[offset + 3u] = header.x;
    output[offset + 4u] = header.y; output[offset + 5u] = header.z;
    output[offset + 6u] = uv.w; output[offset + 7u] = record.tangent;
    output[offset + 8u] = primeGlobalTriangleIndex(section);
#if defined(PRIME_TEST_SURFACE)
    payload.hitKind = 1u;
    payload.roughness = 0.25;
    payload.baseColor = float3(0.125, 0.5, 0.75);
    payload.mediumId = 37u;
#elif defined(PRIME_TEST_SER)
    payload.flags |= 2u;
#else
    payload |= 2u;
#endif
}
