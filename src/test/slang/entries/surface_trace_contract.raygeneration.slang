// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
import glsl;
import "prime_abi_types.slang";
import "contract/path.slang";
import "service/trace/surface.slang";
import "policy/queue/wavefront.slang";
import "policy/queue/post_trace.slang";
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> output;
struct TestStorage : IPrimeWavefrontQueueStorage {
    uint capacity(uint queue) { return DispatchRaysDimensions().x; }
    void markOverflow(uint queue, uint capacity) {
        InterlockedOr(output[DispatchRaysDimensions().x * 30u + 1u], 0x80000000u);
    }
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
        SurfaceInteraction hit = primeTraceSurfaceClassified(PrimeSurfaceTracePolicy(), origin,
                float3(0, 0, -1), source, bounce % 3u, 0.375);
        valid &= uint(hit.sectionIndex == source.x && hit.motionZFlags == source.y);
        valid &= uint(hit.hitKind == (ray == 9u ? PRIME_HIT_NONE : PRIME_HIT_SURFACE));
        valid &= uint(hit.roughness == (ray == 9u ? 0.375 : 0.25));
        valid &= uint(id == DispatchRaysIndex().x);
        // A miss after a hit must not inherit the previous shading output.
        SurfaceInteraction miss = primeTraceSurfaceClassified(PrimeSurfaceTracePolicy(), origin,
                float3(0, 0, 1), source.yx, 0u, 0.625);
        valid &= uint(miss.hitKind == PRIME_HIT_NONE && miss.roughness == 0.625
                && miss.sectionIndex == source.y && miss.motionZFlags == source.x
                && all(asuint(miss.baseColor) == uint3(0u)) && miss.mediumId == 0u
                && all(miss.position == float3(0.0)) && miss.t == 0.0
                && all(miss.geometricNormal == float3(0, 1, 0))
                && all(miss.shadingNormal == float3(0, 1, 0))
                && miss.materialControl == 0u && miss.emitterIndex == 0xffffffffu
                && miss.textureLod == 0.0 && miss.opacity == 0.0 && miss.opticalControl == 0u
                && miss.adjacentMediumId == 0u && all(asuint(miss.adjacentBaseColor) == uint3(0u))
                && miss.adjacentInterfaceControl == 0u);
    }
    output[id * 30u + 29u] = valid != 0u ? (ray == 9u ? 99u : 3u) : 0xffffffffu;
    primeAppendPostTraceQueueEntry(TestStorage(), 0u, id, ray != 9u && id % 3u != 0u);
}
