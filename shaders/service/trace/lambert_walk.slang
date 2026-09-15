// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/lambert_walk.slang";
import "contract/lambert.slang";
import "service/trace/lambert_world.slang";
import "model/lambert/medium.slang";

public void primeLambertTransmit(inout PrimeLambertMedium medium, inout float3 throughput, PrimeLambertPayload hit) {
    if ((hit.flags & PRIME_LAMBERT_THIN) != 0u) throughput *= exp(-hit.albedo);
    else primeLambertCrossBoundary(medium, hit.media, hit.albedo, hit.auxiliary, hit.flags);
}

// One invocation owns a straight segment; no Lambert, light sampler or roulette is visible here.
public PrimeLambertPayload primeWalkLambert(inout float3 origin, float3 direction,
        inout uint2 source, inout PrimeLambertMedium medium, inout float3 throughput,
        uint transitions, uint limit, uint motion, out bool exhausted) {
    exhausted = false;
    float distance = 0.0;
    [loop] for (;;) {
        PrimeLambertPayload hit = primeTraceLambert(origin, direction, source, motion);
        float segment = hit.distance >= 0.0 ? length(hit.position - origin) : 1000000.0;
        throughput *= primeLambertSegmentFilter(medium, segment);
        if (hit.distance < 0.0) return hit;
        distance += segment;
        hit.distance = distance;
        if ((hit.flags & PRIME_LAMBERT_DELTA) == 0u) return hit;
        if (transitions == limit) {
            exhausted = true;
            return hit;
        }
        primeLambertTransmit(medium, throughput, hit);
        origin = hit.position;
        source = uint2(hit.section, hit.triangle);
        transitions++;
    }
}
