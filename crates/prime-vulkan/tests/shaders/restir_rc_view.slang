// Windowless behavior oracle: one production shift per invocation; the host compares
// matching seeded cached/refreshed dispatches without duplicating the shift megakernel.
#language slang 2026
module restir_rc_view_test;
#include "restir/bindings.slang"
import "restir/path.slang";
import "restir/shift.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
import "math/ray_offset.slang";
import "math/transport.slang";
import "service/bsdf/contract/sample.slang";

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pass.phase)
        return;
    uint sample = id.x;
    float4 *rows = parameters.accumulation + id.x * 8;

    for (uint row = 0; row < 8; ++row)
        rows[row] = float4(0);
    RestirScene resources = restirScene();
    resources.frame.right.w = resources.frame.up.w = 0;
    uint2 pixel = resources.frame.dimensions.xy >> 1;
    float3 sourceWo = -restirPrimaryDirection(resources.frame, pixel, resources.sampleJitter);
    RestirPrimary primary;
    float3 residual;
    PathReservoir source = restirGenerate(
            resources, pixel, resources.frame.target.y + resources.frame.target.w + sample, primary,
            residual);
    uint rcLength = uint(source.pathFlags.rcVertexLength());
    uint pathLength = uint(source.pathFlags.pathLength());
    if (!(source.weight > 0) || rcLength != 1 || pathLength != 1 ||
        source.pathFlags.lastVertexNEE() || source.pathFlags.lightType() != RESTIR_LIGHT_EMISSIVE ||
        !primary.hit.isValid() || !source.reconnectionHit.isValid())
        return;
    SurfaceHit sourcePrimary =
            restirLoadHit(resources, primary.hit, sourceWo, primary.coverageSeed);
    SurfacePoint rcSurface = restirHitSurface(resources, source.reconnectionHit);
    float3 physical = rcSurface.position - sourcePrimary.surface.position;
    float3 sourceConnection =
            normalize(rcSurface.position - spawnRay(sourcePrimary.surface, physical));
    SurfaceHit sourceRc = restirLoadHit(resources, source.reconnectionHit, -sourceConnection,
                                        source.initRandomSeed);
    if (sourceRc.optical || (sourceRc.materialFlags & 1u) == 0u)
        return;

    // A controlled reconnection changes the RC view using another actual point of the
    // primary triangle; RC position and its cached outgoing direction remain fixed.
    RestirPrimary destinationPrimary = primary;
    float2 destinationBary = pass.reserved0 == 0 ? float2(.25, .25) : float2(.65, .15);
    destinationPrimary.hit.data.zw = asuint(destinationBary);
    SurfacePoint destinationSurface = restirHitSurface(resources, destinationPrimary.hit);
    float3 destinationWo = normalize(resources.frame.position.xyz - destinationSurface.position);
    RestirReplayData destinationReplay =
            restirDirectReplayData(resources, destinationPrimary, destinationWo);
    PathReservoir shiftedSource = source;
    PrecomputedShiftData shifted =
            restirShift(resources, destinationPrimary, destinationWo, shiftedSource,
                        destinationReplay, pass.reserved1 != 0);

    physical = rcSurface.position - destinationSurface.position;
    float3 destinationConnection =
            normalize(rcSurface.position - spawnRay(destinationSurface, physical));
    SurfaceHit destinationRc = restirLoadHit(resources, source.reconnectionHit,
                                             -destinationConnection, source.initRandomSeed);
    float3 sourceNormal = restirMaterial(sourceRc, -sourceConnection).normal;
    float3 destinationNormal = restirMaterial(destinationRc, -destinationConnection).normal;
    // This independent test-only trace obtains the terminal emitter identity that the
    // production cached suffix does not retain in its 80-byte reservoir.
    HitInfo endpointIdentity;
    SurfaceHit endpoint =
            restirTrace(resources, spawnRay(sourceRc.surface, source.reconnectionWi),
                        source.reconnectionWi, source.initRandomSeed, endpointIdentity);
    if (!endpoint.found || !any(endpoint.emission > 0))
        return;
    float sourceLightPdf = restirEmitterPdf(resources, endpoint, sourceRc.surface.position);
    float destinationLightPdf =
            restirEmitterPdf(resources, endpoint, destinationRc.surface.position);
    BsdfEvaluation second = restirEvaluate(resources, destinationRc, -destinationConnection,
                                           source.reconnectionWi, destinationReplay.medium);
    float cachedMis = primePowerHeuristicValue(second.pdf, source.lightPdf);
    float freshMis = primePowerHeuristicValue(second.pdf, destinationLightPdf);
    float expectedRatio = cachedMis > 0 ? freshMis / cachedMis : 0;
    float3 incidentDifference =
            abs(source.reconnectionIncidentRadiance - shiftedSource.reconnectionIncidentRadiance) /
            max(max(abs(source.reconnectionIncidentRadiance),
                    abs(shiftedSource.reconnectionIncidentRadiance)),
                float3(1e-8));
    uint flags = uint(source.pathFlags.lastVertexNEE()) | (rcLength << 8) | (pathLength << 16) |
                 (source.pathFlags.lightType() << 24);
    rows[0] = asfloat(uint4(flags, source.initRandomSeed, 1, endpointIdentity.pageID()));
    rows[1] = float4(shifted.pathThroughput, shifted.Jacobian);
    rows[2] = float4(source.integrand, source.weight);
    rows[3] = float4(source.lightPdf, shiftedSource.lightPdf, sourceLightPdf, destinationLightPdf);
    rows[4] = float4(second.pdf, length(sourceNormal - destinationNormal),
                     source.reconnectionJacobian, shifted.reconnectionJacobian);
    rows[5] = float4(sourceNormal, length(source.reconnectionIncidentRadiance));
    rows[6] = float4(destinationNormal,
                     max(incidentDifference.x, max(incidentDifference.y, incidentDifference.z)));
    rows[7] = float4(shiftedSource.reconnectionIncidentRadiance, expectedRatio);
}
