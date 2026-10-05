// Actual production source bank, one destination shift, and independent crossing observations.
#language slang 2026
module restir_medium_support_test;
#include "restir/bindings.slang"
import "restir/path.slang";
import "restir/shift.slang";
import "restir/history_support.slang";
import "restir/indexing.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
import "math/ray_offset.slang";

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pass.phase)
        return;
    float4 *rows = parameters.accumulation + id.x * 8u;
    for (uint i = 0u; i < 8u; ++i)
        rows[i] = float4(0);
    uint2 pixel = uint2(id.x % parameters.frame.dimensions.x, id.x / parameters.frame.dimensions.x);
    uint offset = restirOffset(pixel, parameters.frame.dimensions.xy);
    PathReservoir source = parameters.current[offset];
    uint rcLength = uint(source.pathFlags.rcVertexLength());
    uint pathLength = uint(source.pathFlags.pathLength());
    rows[0] = asfloat(uint4(0, source.pathFlags.flags, source.initRandomSeed, offset));
    if (!(source.weight > 0) || !source.reconnectionHit.isValid() || rcLength == 0u ||
        rcLength > pathLength)
        return;
    RestirScene destination = restirScene();
    destination.frame.position.x += asfloat(pass.reserved0);
    float3 wo = -restirPrimaryDirection(destination.frame, pixel, destination.sampleJitter);
    RestirPrimary primary = {};
    primary.coverageSeed = parameters.primary[offset].coverageSeed;
    restirTrace(destination, destination.frame.position.xyz, -wo, primary.coverageSeed,
                primary.hit);
    RestirReplayData replay = rcLength <= 1u ? restirDirectReplayData(destination, primary, wo)
                                             : restirReplay(destination, primary, wo, source);
    if (!primary.hit.isValid() || !replay.data.rcPrevHit.isValid() ||
        all(replay.data.pathThroughput == 0))
        return;
    SurfaceHit previous =
            restirLoadHit(destination, replay.data.rcPrevHit, replay.data.rcPrevWo,
                          rcLength == 1u ? primary.coverageSeed : source.initRandomSeed);
    SurfaceHit rc = restirLoadHit(destination, source.reconnectionHit, float3(0, 0, -1),
                                  source.initRandomSeed);
    // Restrict the oracle to opaque endpoints away from the physical interface.
    if (previous.optical || rc.optical || abs(previous.surface.position.x - 8.0) < 0.01 ||
        abs(rc.surface.position.x - 8.0) < 0.01)
        return;
    float3 displacement = rc.surface.position - previous.surface.position;
    float3 connection = normalize(rc.surface.position - spawnRay(previous.surface, displacement));
    float4 terminal;
    float3 visibility =
            restirConnectionVisibility(destination, previous.surface, rc.surface, true, connection,
                                       source.initRandomSeed, replay.medium, terminal);
    bool crossing = (previous.surface.position.x < 8.0) != (rc.surface.position.x < 8.0);
    bool ambiguous = restirSuffixNeedsRefresh(source.pathFlags, false, terminal);
    float3 originalCache = source.reconnectionIncidentRadiance;
    float3 originalWi = source.reconnectionWi;
    bool actualSourceNonVacuum = source.pathFlags.suffixNonVacuum();
    // A spatial region cannot prove the original path's incoming medium. Observe
    // RC1's actual source primary/replay and its connection separately; do not
    // duplicate the large full-prefix replay body for other reconnection lengths.
    bool sourceObserved = false;
    bool sameSide = false;
    float4 sourceTerminal = float4(0);
    if (rcLength == 1u) {
        RestirScene originalScene = restirScene();
        RestirPrimary originalPrimary = parameters.primary[offset];
        float3 originalWo =
                -restirPrimaryDirection(originalScene.frame, pixel, originalScene.sampleJitter);
        RestirReplayData originalReplay =
                restirDirectReplayData(originalScene, originalPrimary, originalWo);
        if (originalPrimary.hit.isValid() && any(originalReplay.data.pathThroughput > 0)) {
            SurfaceHit originalPrevious = restirLoadHit(originalScene, originalPrimary.hit,
                                                        originalWo, originalPrimary.coverageSeed);
            float3 originalDisplacement = rc.surface.position - originalPrevious.surface.position;
            float3 originalConnection = normalize(
                    rc.surface.position - spawnRay(originalPrevious.surface, originalDisplacement));
            float3 originalVisibility = restirConnectionVisibility(
                    originalScene, originalPrevious.surface, rc.surface, true, originalConnection,
                    source.initRandomSeed, originalReplay.medium, sourceTerminal);
            sourceObserved = !originalPrevious.optical && all(originalVisibility > 0);
            sameSide = (dot(rc.surface.normal, -originalConnection) >= 0) ==
                       (dot(rc.surface.normal, -connection) >= 0);
        }
    }
    if (ambiguous)
        source.reconnectionIncidentRadiance =
                float3(1e6); // proves the cached value is not consumed
    PrecomputedShiftData shifted =
            restirShift(destination, primary, wo, source, replay, pass.reserved1 != 0u);
    bool sameMedium = all(sourceTerminal == terminal);
    bool sameWi = all(originalWi == source.reconnectionWi);
    bool startsWater = all(replay.medium == float4(1.5, 0.03, 0.07, 0.11));
    bool startsVacuum = all(replay.medium == float4(1, 0, 0, 0));
    rows[0].x = asfloat(uint(crossing) | (uint(ambiguous) << 1) | 4u | (uint(sourceObserved) << 3) |
                        (uint(sameMedium) << 4) | (uint(sameSide) << 5) | (uint(sameWi) << 6) |
                        (uint(actualSourceNonVacuum) << 7) | (uint(startsWater) << 8) |
                        (uint(startsVacuum) << 9));
    rows[0].w = sourceTerminal.x;
    rows[1] = float4(shifted.pathThroughput, shifted.Jacobian);
    rows[2] = float4(visibility, terminal.x);
    rows[3] = float4(terminal.yzw, originalCache.x);
    rows[4] = float4(previous.surface.position, sourceTerminal.y);
    rows[5] = float4(rc.surface.position, originalCache.y);
    // Only full source/destination medium, opaque facing and fixed-Wi agreement
    // establish a source-cache invariant. Other actual sources still test auto/fresh.
    rows[6] = float4(previous.surface.offset, rc.surface.offset,
                     source.reconnectionIncidentRadiance.xy);
    rows[7] = float4(source.reconnectionIncidentRadiance.z, sourceTerminal.z, sourceTerminal.w,
                     originalCache.z);
}
