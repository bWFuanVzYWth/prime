#language slang 2026
module restir_math_test;
import "restir/rng.slang";
import "restir/reservoir.slang";
import "restir/pairwise.slang";
import "restir/paired_neighbors.slang";
import "restir/identity.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::binding(2, 0)]] StructuredBuffer<uint> pairingDeltaTextures;
[[vk::binding(3, 0)]] RWStructuredBuffer<PathReservoir> reservoirLayout;
[[vk::binding(4, 0)]] RWStructuredBuffer<ReconnectionData> reconnectionLayout;
[[vk::binding(5, 0)]] RWStructuredBuffer<PrecomputedShiftData> shiftLayout;
[[vk::push_constant]] ConstantBuffer<uint4> config;

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    if (config.x == 0u) {
        SampleGenerator sg = SampleGenerator(inputs[i].xy, inputs[i].z);
        uint4 a, b;
        a.x = sg.next();
        a.y = sg.next();
        a.z = sg.next();
        a.w = sg.next();
        b.x = sg.next();
        b.y = sg.next();
        b.z = sg.next();
        b.w = sg.next();
        outputs[2 * i] = a;
        outputs[2 * i + 1] = b;
    } else if (config.x == 1u) {
        SampleGenerator sg = SampleGenerator(uint2(i & 0xffffu, (i >> 16u) + 37u), 11u);
        PathRISState state = {};
        state.addPrimaryVisibleEmitterCandidate(sg.next1D(), float3(1.f, .2f, .1f), .7f);
        state.addPrimaryVisibleEmitterCandidate(sg.next1D(), float3(.1f, 2.f, .3f), .2f);
        state.addPrimaryVisibleEmitterCandidate(sg.next1D(), float3(.4f, .1f, 3.f), .4f);
        PathReservoir reservoir = PathReservoir.createFromRisState(state, sg.rng, 0.f);
        reservoir.finalizeReservoirWeight();
        outputs[i] = asuint(float4(reservoir.integrand * reservoir.weight, reservoir.M));
    } else if (config.x == 2u) {
        SampleGenerator sg = SampleGenerator(0u);
        PathRISState state = {};
        bool selected = state.addPSSSample(.1f, 0.f);
        PathReservoir reservoir = {};
        reservoir.init();
        reservoir.finalizeReservoirWeight();
        bool merged = reservoir.merge(0.f, 1.f, reservoir, sg, 1.f);
        outputs[i] = uint4(asuint(reservoir.weight), selected, merged,
                           reservoir.reconnectionHit.isValid());
    } else if (config.x == 3u) {
        float4 a = asfloat(inputs[2 * i]), b = asfloat(inputs[2 * i + 1]);
        PairwiseFactor C = {a.x + a.y, a.x};
        float spatial =
                spatialCanonicalInitialWeight(C) + spatialCanonicalWeightTerm(C, a.y, a.z, a.w);
        float neighbor = spatialNeighborWeight(C, a.y, a.w, a.z);
        float temporal = temporalCanonicalWeight(a.x, a.y, a.z, a.w);
        float temporalNeighbor = temporalNeighborWeight(a.x, a.y, a.w, a.z);
        float gs = reconnectionGeometryTerm(b.x, b.y), gd = reconnectionGeometryTerm(b.z, b.w);
        float jacobian = reconnectionShiftJacobian(gd, gs);
        outputs[2 * i] = asuint(float4(spatial, neighbor, temporal, temporalNeighbor));
        outputs[2 * i + 1] = asuint(float4(jacobian, reconnectionShiftJacobian(gs, gd), gs, gd));
    } else if (config.x == 4u) {
        uint neighborIndex = inputs[i].z;
        int x = int(inputs[i].x), y = int(inputs[i].y);
        SampleGenerator sg = SampleGenerator(config.z);
        PairedNeighborTransform transform = PairedNeighborTransform(sg, 1920, 1080);
        int dx = 0, dy = 0, ix = 0, iy = 0;
        transform.readTransformedDelta(pairingDeltaTextures, neighborIndex, x, y, dx, dy);
        // The pattern is periodic in its actual table extent. In production, frame OOBs are rejected.
        int size = int(PAIRED_NEIGHBOR_SIZES[neighborIndex]);
        int nx = transform.applyDeltaPeriodic(x, dx, size);
        int ny = transform.applyDeltaPeriodic(y, dy, size);
        transform.readTransformedDelta(pairingDeltaTextures, neighborIndex, nx, ny, ix, iy);
        outputs[i] = uint4(dx, dy, ix, iy);
    } else if (config.x == 6u) {
        SampleGenerator sg = SampleGenerator(uint2(i & 0xffffu, (i >> 16u) + 51u), 19u);
        PathReservoir a = {}, b = {}, merged = {};
        a.M = b.M = 1.f;
        a.integrand = float3(1.f, 2.f, 3.f);
        a.weight = 2.f;
        b.integrand = float3(.3f, .2f, .1f);
        b.weight = 4.f;
        merged.merge(a.integrand, 1.f, a, sg, .4f);
        merged.merge(b.integrand, 2.f, b, sg, .6f);
        merged.finalizeReservoirWeight();
        outputs[i] = asuint(float4(merged.integrand * merged.weight, merged.M));
    } else if (config.x == 8u) {
        uint4 record = inputs[i];
        outputs[i] = uint4(restirHistoryRecordValid(record.xy, record.z, record.w), 0, 0, 0);
    } else if (config.x == 7u) {
        ReSTIRPathFlags flags = {};
        flags.tryInsertBounceTypesBeforeRcVertex(i & 63u, true);
        flags.tryInsertBounceTypesAfterRcVertex((i >> 6u) & 31u, true);
        flags.insertPathLength(i & 255u);
        flags.insertRcVertexLength((i >> 8u) & 255u);
        flags.insertLastVertexNEE((i & 1u) != 0u);
        flags.insertIsVeryDirectLight((i & 2u) != 0u);
        flags.insertLightType((i >> 2u) & 3u);
        uint before = uint(flags.decodeIsDeltaEvent(true)) |
                      uint(flags.decodeIsTransmissionEvent(true)) << 1u |
                      flags.decodeBSDFComponent(true) << 2u |
                      uint(flags.decodeSupportBSDFIndexingBeforeRcVertex()) << 5u;
        uint after = uint(flags.decodeIsDeltaEvent(false)) |
                     uint(flags.decodeIsTransmissionEvent(false)) << 1u |
                     flags.decodeBSDFComponent(false) << 2u;
        outputs[i] = uint4(flags.flags, before, after,
                           uint(flags.pathLength()) | uint(flags.rcVertexLength()) << 8u);
    } else {
        PathReservoir reservoir = {};
        reservoir.init();
        reservoir.M = 2.f;
        reservoir.weight = 3.f;
        reservoir.integrand = float3(4.f, 5.f, 6.f);
        reservoir.reconnectionHit = HitInfo(7u, 8u, float2(.25f, .5f));
        reservoirLayout[i] = reservoir;
        ReconnectionData rc =
                ReconnectionData(reservoir.reconnectionHit, float3(1.f, 0.f, 0.f), 1.f, .5f);
        reconnectionLayout[i] = rc;
        PrecomputedShiftData shift = {float3(1.f, 2.f, 3.f), 4.f, 5.f};
        shiftLayout[i] = shift;
    }
}
