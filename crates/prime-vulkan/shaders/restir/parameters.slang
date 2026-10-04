#language slang 2026
module "restir/parameters.slang";
import "frame.slang";
import "restir/types.slang";
import "restir/reservoir.slang";

// Binding 23, 464 bytes. All pointers borrow private GPU state until host completion.
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
    public uint4 identityControl; // static/dynamic/emitter counts, accepted scene revision
};
public struct RestirPass {
    public uint phase; // 0 temporal (two directions), 1 spatial (three paired directions)
    public uint sampleOffset;
    public uint reserved0;
    public uint reserved1;
};
