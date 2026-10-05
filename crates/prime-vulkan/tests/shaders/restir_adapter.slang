// Windowless behavior test: production generation, compact replay and self shift.
#language slang 2026
module restir_adapter_test;
#include "restir/bindings.slang"
import "restir/path.slang";
import "restir/shift.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
import "math/ray_offset.slang";
import "service/bsdf/contract/sample.slang";

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pass.phase)
        return;
    if (pass.reserved1 == 3) {
        // An independent chart-domain counterexample using the actual spawn primitive.
        // At physical tangency with negative incoming view, both transmission booleans
        // are false even though the safe-origin side changes; +/-zero both mean >=0.
        SurfacePoint previous = {float3(0), float3(0, 0, 1), 1e-6};
        float3 target = float3(0.1, 0, id.x == 1 ? 0.5e-6 : asfloat(id.x == 2 ? 0x80000000u : 0u));
        float3 view = float3(0, 0, id.x == 1 ? 1 : -1);
        float3 physical = target - previous.position;
        float3 begin = spawnRay(previous, physical);
        float3 query = target - begin;
        float3 connection = normalize(query);
        float3 late = spawnRay(previous, connection);
        float physicalSide = dot(previous.normal, physical);
        float querySide = dot(previous.normal, connection);
        float4 *rows = parameters.accumulation + id.x * 8;
        rows[0] = float4(begin, previous.offset);
        rows[1] = float4(late, length(query));
        rows[2] = float4(physical, physicalSide);
        rows[3] = float4(query, querySide);
        rows[4] = asfloat(uint4(physicalSide >= 0, querySide >= 0,
                                physicalSide * dot(previous.normal, view) < 0,
                                querySide * dot(previous.normal, view) < 0));
        rows[5] = float4(view, 0);
        rows[6] = float4(previous.normal, 0);
        rows[7] = float4(target, 0);
        return;
    }
    RestirScene resources = restirScene();
    // Existing finite-emitter fixtures isolate their source domain. The Sun witness
    // explicitly retains the same prepared atmosphere without duplicating this kernel.
    if (pass.reserved0 == 0) {
        resources.frame.right.w = 0;
        resources.frame.up.w = 0;
    }
    uint2 pixel = resources.frame.dimensions.xy >> 1;
    float3 wo = -restirPrimaryDirection(resources.frame, pixel);
    RestirPrimary primary;
    float3 residual;
    PathReservoir reservoir = restirGenerate(
            resources, pixel, resources.frame.target.y + resources.frame.target.w + id.x, primary,
            residual);
    float4 generatedSource = float4(reservoir.integrand, reservoir.weight);
    if (pass.reserved1 == 4 && reservoir.weight > 0 && any(reservoir.integrand > 0)) {
        // Synthetic stale terminal Sun NEE on the authored rear side. This is a
        // consumer witness, not lossless reconstruction of the before raw reservoir.
        uint seed = reservoir.initRandomSeed;
        reservoir.init();
        reservoir.initRandomSeed = seed;
        reservoir.M = reservoir.weight = 1;
        reservoir.integrand = float3(1, 2, 3);
        reservoir.pathFlags.insertRcVertexLength(1);
        reservoir.pathFlags.insertLastVertexNEE(true);
        reservoir.pathFlags.insertLightType(RESTIR_LIGHT_SUN);
        reservoir.pathFlags.insertIsTransmissionEvent(true, true);
        reservoir.reconnectionJacobian = 1;
        reservoir.reconnectionIncidentRadiance = float3(1);
        reservoir.reconnectionWi = float3(0, 1, 0);
        reservoir.lightPdf = 1;
    }
    RestirReplayData fullReplay = restirReplay(resources, primary, wo, reservoir);
    RestirReplayData replay = reservoir.pathFlags.rcVertexLength() <= 1
                                      ? restirDirectReplayData(resources, primary, wo)
                                      : fullReplay;
    float sourceGeometry = reservoir.reconnectionJacobian;
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
    // Optional test-only observations retain the same 128-byte record and production UBO.
    // The host independently derives the area/solid-angle measure from these physical
    // endpoints. Do not infer the source measure from the shift's destination cache.
    if (pass.reserved1 == 4) {
        // Preserve actual generated-source coverage separately from the synthetic clone.
        rows[7] = generatedSource;
    } else if (pass.reserved1 != 0) {
        rows[4] = float4(sourceGeometry, 0, 0, 0);
        rows[5] = rows[6] = rows[7] = float4(0);
        uint rcLength = uint(reservoir.pathFlags.rcVertexLength());
        uint pathLength = uint(reservoir.pathFlags.pathLength());
        if (reservoir.weight > 0 && rcLength <= pathLength + 1 &&
            reservoir.reconnectionHit.isValid() && replay.data.rcPrevHit.isValid()) {
            SurfaceHit primaryHit = restirLoadHit(resources, primary.hit, wo, primary.coverageSeed);
            SurfaceHit previous =
                    rcLength == 1 ? primaryHit
                                  : restirLoadHit(resources, replay.data.rcPrevHit,
                                                  replay.data.rcPrevWo, reservoir.initRandomSeed);
            SurfacePoint target = restirHitSurface(resources, reservoir.reconnectionHit);
            float3 displacement = target.position - previous.surface.position;
            float3 physicalConnection = normalize(displacement);
            float3 connection =
                    rcLength == pathLength + 1 && reservoir.pathFlags.lastVertexNEE()
                            ? physicalConnection
                            : normalize(target.position - spawnRay(previous.surface, displacement));
            BsdfEvaluation first = restirEvaluate(resources, previous, replay.data.rcPrevWo,
                                                  connection, replay.medium);
            float secondPdf = RESTIR_DIFFUSE_PSEUDO_PDF;
            if (rcLength <= pathLength) {
                SurfaceHit targetHit = restirLoadHit(resources, reservoir.reconnectionHit,
                                                     -connection, reservoir.initRandomSeed);
                secondPdf = restirEvaluate(resources, targetHit, -connection,
                                           reservoir.reconnectionWi, replay.medium)
                                    .pdf;
            }
            SampleGenerator thresholds = SampleGenerator(reservoir.initRandomSeed);
            float distanceThreshold, roughnessThreshold;
            restirSampleThresholds(thresholds, distanceThreshold, roughnessThreshold);
            rows[4] = float4(sourceGeometry,
                             restirPrimaryFootprint(resources, primaryHit, wo) * distanceThreshold,
                             first.pdf, secondPdf);
            rows[5] = float4(displacement, previous.surface.normal.z);
            rows[6] = float4(previous.shadingNormal, target.normal.z);
            rows[7] = float4(replay.data.rcFootprintFactor, shifted.reconnectionJacobian,
                             roughnessThreshold, previous.surface.offset);
            if (pass.reserved1 == 2 && rcLength == 1 && pathLength == 0 &&
                !reservoir.pathFlags.lastVertexNEE()) {
                // Isolate the direct BSDF-hit emitter residual without changing production
                // offset/sampling. Replay the exact first scatter's dimensions and evaluate
                // both that sampled ray direction and the physical endpoint connection.
                SampleGenerator sourceRng = SampleGenerator(reservoir.initRandomSeed);
                restirSampleThresholds(sourceRng, distanceThreshold, roughnessThreshold);
                sampleNext1D(sourceRng); // BSDF RIS
                sampleNext1D(sourceRng); // NEE RIS
                restirSkipLightSample(sourceRng);
                sampleNext1D(sourceRng); // Roulette
                BsdfSample source =
                        restirScatter(resources, primaryHit, wo, replay.medium, sourceRng)
                                .bsdfSample;
                BsdfEvaluation original =
                        restirEvaluate(resources, primaryHit, wo, source.direction, replay.medium);
                BsdfEvaluation physical = restirEvaluate(resources, primaryHit, wo,
                                                         physicalConnection, replay.medium);
                SurfaceHit emitterHit =
                        restirLoadHit(resources, reservoir.reconnectionHit, -physicalConnection,
                                      reservoir.initRandomSeed);
                float destinationLightPdf =
                        restirEmitterPdf(resources, emitterHit, primaryHit.surface.position);
                float3 sourceDisplacement =
                        target.position - spawnRay(primaryHit.surface, source.direction);
                float sourceG = abs(dot(target.normal, -source.direction)) /
                                dot(sourceDisplacement, sourceDisplacement);
                float physicalG = abs(dot(target.normal, -physicalConnection)) /
                                  dot(displacement, displacement);
                rows[2] = float4(original.pdf, destinationLightPdf, sourceG, physicalG);
                rows[4] = float4(source.direction, source.pdf);
                rows[5] = float4(source.response, reservoir.lightPdf / sourceGeometry);
                rows[6] = float4(physical.response, physical.pdf);
                rows[7] = float4(original.response, primaryHit.surface.offset);
            }
        }
    }
}
