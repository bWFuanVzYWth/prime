// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module restir_history_test;
#include "restir/bindings.slang"
import "math/ray_offset.slang";

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pass.phase)
        return;
    RestirScene resources = restirScene();
    resources.sampleJitter = float2(0);
    uint2 pixel = resources.frame.dimensions.xy >> 1;
    float3 wo = -restirPrimaryDirection(resources.frame, pixel);
    RestirPrimary primary;
    float3 residual;
    PathReservoir source = restirGenerate(resources, pixel, id.x, primary, residual);
    RestirReplayData replay = source.pathFlags.rcVertexLength() <= 1
                                      ? restirDirectReplayData(resources, primary, wo)
                                      : restirReplay(resources, primary, wo, source);
    uint rc = uint(source.pathFlags.rcVertexLength());
    uint length = uint(source.pathFlags.pathLength());
    uint branch = rc > length + 1                                    ? 0
                  : !source.reconnectionHit.isValid()                ? 1
                  : rc == length + 1                                 ? 2
                  : rc == length && source.pathFlags.lastVertexNEE() ? 3
                                                                     : 4;
    float4 *output = parameters.accumulation + id.x * 8;
    // Keep one production call body: these probes differ only in runtime inputs.
    [loop] for (uint variant = 0; variant < 4; ++variant) {
        RestirScene variantScene = resources;
        PathReservoir variantSource = source;
        if (variant == 2) {
            variantSource.reconnectionIncidentRadiance = float3(123.0, 321.0, 456.0);
            variantSource.lightPdf = 17.0;
        } else if (variant == 3) {
            variantScene.frame.up.w *= 2.0;
        }
        PrecomputedShiftData shifted =
                restirShift(variantScene, primary, wo, variantSource, replay, variant != 0);
        float w = variant == 0   ? source.weight
                  : variant == 1 ? shifted.Jacobian
                  : variant == 2 ? asfloat(branch)
                                 : asfloat(source.pathFlags.lightType());
        output[variant] = float4(shifted.pathThroughput * shifted.Jacobian, w);
        if (variant == 1)
            output[4] = float4(variantSource.reconnectionIncidentRadiance,
                               asfloat(source.pathFlags.flags));
    }
    float3 changedMedium = float3(0);
    bool mediumCase = false;
    bool supportRejected = false;
    if (branch == 4 && source.reconnectionHit.isValid() && replay.data.rcPrevHit.isValid()) {
        SurfaceHit previous = restirLoadHit(resources, replay.data.rcPrevHit, replay.data.rcPrevWo,
                                            source.initRandomSeed);
        SurfacePoint target = restirHitSurface(resources, source.reconnectionHit);
        float3 direction = normalize(target.position - previous.surface.position);
        SurfaceHit rcHit =
                restirLoadHit(resources, source.reconnectionHit, -direction, source.initRandomSeed);
        bool transmission = dot(previous.surface.normal, replay.data.rcPrevWo) *
                                    dot(previous.surface.normal, direction) <
                            0;
        float4 medium = previous.optical && !previous.thin && transmission ? previous.transmitted
                                                                           : replay.medium;
        [loop] for (uint variant = 0; variant < 2; ++variant) {
            PathReservoir variantSource = source;
            float4 variantMedium = medium;
            if (variant == 0)
                variantMedium += float4(0, 0.2, 0.4, 0.7);
            else
                variantSource.reconnectionWi = -variantSource.reconnectionWi;
            bool accepted =
                    restirUpdateSuffix(resources, rcHit, -direction, variantMedium, variantSource);
            if (variant == 0) {
                mediumCase = accepted;
                changedMedium = variantSource.reconnectionIncidentRadiance;
            } else
                supportRejected = !accepted;
        }
    }
    output[5] = float4(changedMedium, asfloat(uint(mediumCase) | uint(supportRejected) << 1));
    output[6] = float4(source.reconnectionIncidentRadiance, asfloat(source.initRandomSeed));
    output[7] = float4(asfloat(primary.hit.data));
}
