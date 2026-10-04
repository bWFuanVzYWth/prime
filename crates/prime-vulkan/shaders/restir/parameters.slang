#language slang 2026
module "restir/parameters.slang";
import "frame.slang";
import "restir/types.slang";
import "restir/paired_neighbors.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";

// Binding 23, 400 bytes. All pointers borrow private GPU state until host completion.
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
};
public struct RestirPass {
    public uint phase; // 0 temporal (two directions), 1 spatial (three paired directions)
    public uint sampleOffset;
    public uint reserved0;
    public uint reserved1;
};
public uint restirOffset(uint2 pixel, uint2 extent) {
    uint2 tile = pixel >> 4;
    return (tile.y * ((extent.x + 15u) >> 4) + tile.x) * 256u + (interleave_32bit(pixel) & 255u);
}
public bool restirInBounds(int2 pixel, uint2 extent) {
    return all(pixel >= 0) && all(pixel < int2(extent));
}
public PairedNeighborTransform restirPairing(RestirParameters params, uint sampleOffset) {
    // Original defaults reserve the second temporal stream even in the point profile.
    SampleGenerator shared = TinyUniformSampleGenerator(
            params.frame.dimensions.xy + 1u,
            4u * (params.frame.target.y + sampleOffset + params.frame.target.w) + 3u);
    return PairedNeighborTransform(shared, params.frame.dimensions.x, params.frame.dimensions.y);
}
public int2 restirNeighbor(RestirParameters params, PairedNeighborTransform pairing, uint2 pixel,
                           uint neighbor) {
    int dx = 0, dy = 0;
    pairing.readTransformedDelta(params.pairedDeltas, neighbor, pixel.x, pixel.y, dx, dy);
    return int2(pixel) + int2(dx, dy);
}
