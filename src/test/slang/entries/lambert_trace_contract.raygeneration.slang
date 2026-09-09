// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
import glsl;
import "service/trace/lambert_world.slang";
import "contract/lambert.slang";
import "policy/queue/single_successor.slang";
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> output;
struct TestStorage : IPrimeSuccessorQueueStorage {
    uint reserve(uint queue, uint count) {
        uint base = DispatchRaysDimensions().x * 30u;
        uint first;
        InterlockedAdd(output[base], count, first);
        InterlockedAdd(output[base + 1u], 1u);
        return first;
    }
    void store(uint queue, uint entry, uint path) {
        output[DispatchRaysDimensions().x * 30u + 2u + entry] = path;
    }
};
[shader("raygeneration")]
void main() {
    uint id = DispatchRaysIndex().x;
    uint ray = id % 10u;
    uint2 source = uint2(id ^ 0x55u, id * 17u);
    float3 origin = float3(float(ray) * 4.0 + 0.625 + 11.0, 0.5 - 3.0, 20.0);
    uint valid = 1u;
    [loop] for (uint bounce = 0u; bounce < 1u + id % 24u; bounce++) {
        uint motion = bounce % 3u;
        PrimeLambertPayload hit = primeTraceLambert(origin, float3(0, 0, -1), source, motion);
        valid &= uint(hit.section == source.x && hit.triangle == source.y && hit.motion == motion);
        valid &= uint(hit.flags == (ray == 9u ? 99u : 2u));
        valid &= uint(id == DispatchRaysIndex().x);
    }
    output[id * 30u + 29u] = valid != 0u ? (ray == 9u ? 99u : 3u) : 0xffffffffu;
    // Divergent trace chains join here, as in the production camera entry.
    primeAppendSuccessor(TestStorage(), 0u, id, ray != 9u && id % 3u != 0u);
}
