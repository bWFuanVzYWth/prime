// Windowless behavior test: production generation, compact replay and self shift.
#language slang 2026
module restir_adapter_test;
#include "restir/bindings.slang"
import "restir/path.slang";
import "restir/shift.slang";
import "restir/types.slang";
import "restir/reservoir.slang";

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pass.phase)
        return;
    RestirScene resources = restirScene();
    // Isolate finite emitters while still using the prepared production atmosphere bindings.
    resources.frame.right.w = 0;
    resources.frame.up.w = 0;
    uint2 pixel = resources.frame.dimensions.xy >> 1;
    float3 wo = -restirPrimaryDirection(resources.frame, pixel);
    RestirPrimary primary;
    float3 residual;
    PathReservoir reservoir = restirGenerate(
            resources, pixel, resources.frame.target.y + resources.frame.target.w + id.x, primary,
            residual);
    RestirReplayData fullReplay = restirReplay(resources, primary, wo, reservoir);
    RestirReplayData replay = reservoir.pathFlags.rcVertexLength() <= 1
                                      ? restirDirectReplayData(resources, primary, wo)
                                      : fullReplay;
    PrecomputedShiftData shifted = restirShift(resources, primary, wo, reservoir, replay);
    float4 *rows = parameters.accumulation + id.x * 8;
    rows[0] = float4(reservoir.integrand, reservoir.weight);
    rows[1] = float4(shifted.pathThroughput, shifted.Jacobian);
    rows[2] = float4(residual, reservoir.M);
    rows[3] = asfloat(uint4(
            uint(reservoir.pathFlags.lastVertexNEE()) |
                    (uint(reservoir.pathFlags.decodeIsTransmissionEvent(true)) << 1) |
                    (uint(reservoir.pathFlags.decodeIsTransmissionEvent(false)) << 2) |
                    (uint(reservoir.pathFlags.rcVertexLength()) << 8) |
                    (uint(reservoir.pathFlags.pathLength()) << 16) |
                    (reservoir.pathFlags.lightType() << 24),
            reservoir.initRandomSeed, primary.hit.pageID(), reservoir.reconnectionHit.pageID()));
    rows[4] = float4(replay.data.pathThroughput, replay.medium.x);
    rows[5] = float4(replay.medium.yzw, replay.data.rcFootprintFactor);
    rows[6] = float4(fullReplay.data.pathThroughput, fullReplay.medium.x);
    rows[7] = float4(fullReplay.medium.yzw, shifted.reconnectionJacobian);
}
