#language slang 2026
module "restir/parameters.slang";
import "frame.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
import "restir/config.slang";
import "restir/rr_decorrelation.slang";

// RA-001/009/015, docs/restir-adaptations.md: binding 23 is a 720-byte Prime ABI.
// All pointers borrow private GPU state until host completion.
public struct RestirParameters {
    public Frame frame;
    public Frame previousFrame;
    public uint4 control; // terrain count, accepted temporal history, padded pixels, linear display
    public PathReservoir *current;
    public PathReservoir *history;
    public RestirPrimary *primary;
    public RestirPrimary *previousPrimary;
    public float4 *residual;
    public int2 *reprojection;
    public NeighborValidMask *valid;
    public RestirReplayData *retrace;
    public uint *retraceOffsets;
    public PrecomputedShiftData *shifts;
    public uint2 *workItems;
    public uint *queueControl;
    public uint *pairedDeltas;
    public uint *tlasInstances;
    public float4 *accumulation;
    public float4 sampleJitter; // current.xy and accepted previous.zw, in input pixels
    public uint2 *historyStatic;
    public uint2 *historyDynamic;
    public uint2 *historyEmitters;
    public uint2 *historyStaticQuads; // Former alignment padding at byte 440.
    public uint4 identityControl;     // static/dynamic/emitter counts, accepted scene revision
    public RestirConfig config;
    public uint *sampleIds;
    public uint *duplicateCounts;
    public uint *currentAges;
    public uint *previousAges;
    public float4 *shadingSum;
    public RestirRrDecorrelationConfig rrDecorrelation;
    public float4 *rrInitialEstimate;
    public float *rrSmoothedCurrent;
    public float *rrSmoothedPrevious;
    public uint *rrFireflies;
    public uint *currentProfiles;
    public uint *historyProfiles;
    public float4 *sourceThresholds;
    public uint currentSourceProfile;
};
public struct RestirPass {
    public uint phase; // 0 temporal (two directions), 1 spatial (configured paired directions)
    public uint sampleOffset;
    public uint reserved0;
    public uint reserved1; // RA-015: spatial round index; ignored in temporal/initial passes
};
// RA-015: alternate the two existing reservoir banks; never copy a whole frame.
public PathReservoir *restirCurrentForPass(RestirParameters params, RestirPass pass) {
    return pass.phase == 1u && (pass.reserved1 & 1u) != 0u ? params.history : params.current;
}
public PathReservoir *restirDestinationForPass(RestirParameters params, RestirPass pass) {
    return (pass.reserved1 & 1u) != 0u ? params.current : params.history;
}
public PathReservoir *restirFinalReservoirs(RestirParameters params) {
    return (params.config.reuse.z & 1u) != 0u ? params.history : params.current;
}
// RA-015: allocate source profiles only after a threshold edit. Old accepted data
// then has implicit profile zero; readiness advances only on host acceptance.
public uint *restirCurrentProfilesForPass(RestirParameters params, RestirPass pass) {
    return pass.phase == 1u && (pass.reserved1 & 1u) != 0u ? params.historyProfiles
                                                           : params.currentProfiles;
}
public uint *restirDestinationProfilesForPass(RestirParameters params, RestirPass pass) {
    return (pass.reserved1 & 1u) != 0u ? params.currentProfiles : params.historyProfiles;
}
public uint restirPreviousProfile(RestirParameters params, uint offset) {
    return (params.config.queue.w & 16u) != 0u ? params.historyProfiles[offset] : 0u;
}
