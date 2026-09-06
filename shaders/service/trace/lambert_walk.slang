#language slang 2026
module "service/trace/lambert_walk.slang";
import "contract/lambert.slang";
import "service/trace/lambert_world.slang";
import "model/lambert/medium.slang";

public bool primeLambertTransmit(inout PrimeLambertMedium medium, inout float3 throughput, PrimeLambertPayload hit) {
    if ((hit.flags & PRIME_LAMBERT_THIN) != 0u) throughput *= exp(-hit.albedo);
    else if (!primeLambertCrossBoundary(medium, hit.media, hit.albedo, hit.auxiliary, hit.flags)) return false;
    // Zero illumination must not make the selected branch's reconstruction anchor disappear.
    return true;
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
        if (hit.distance >= 0.0 && !primeLambertInferIncomingMedium(
                medium, hit.media, hit.albedo, hit.auxiliary, hit.flags)) {
            exhausted = true;
            return hit;
        }
        throughput *= primeLambertSegmentFilter(medium, segment);
        if (hit.distance < 0.0) return hit;
        distance += segment;
        hit.distance = distance;
        if ((hit.flags & PRIME_LAMBERT_DELTA) == 0u) return hit;
        if (transitions == limit || !primeLambertTransmit(medium, throughput, hit)) {
            exhausted = true;
            return hit;
        }
        origin = hit.position;
        source = uint2(hit.section, hit.triangle);
        transitions++;
    }
}
