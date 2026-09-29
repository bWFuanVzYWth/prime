// Entry-local resource binding. Five original 512-square toroidal leaf grids.
// Request epoch, world X/Y and blocker depth per cell.
[[vk::binding(4, 2)]] RWStructuredBuffer<uint4> shadowCells;
uint shadowIndex(int cascade, int2 cell) {
    uint2 wrapped = uint2(cell) & 511u;
    return uint(cascade) * 512u * 512u + wrapped.y * 512u + wrapped.x;
}
struct ShadowSource : IPrimeSunShadowProfileSource {
    bool enabled() {
        return frames[0].up.w >= 0.5;
    }
    float texelSize(int cascade) {
        return sunShadowTexelSize(cascade);
    }
    float2 hierarchyRange(int cascade, int level, int2 worldNode) {
        float depth = -1e20;
        for (int y = -1; y <= 1; y++)
            for (int x = -1; x <= 1; x++) {
                uint index = shadowIndex(cascade, worldNode + int2(x, y));
#ifdef ATM_SHADOW_DEMAND
                uint previous;
                InterlockedExchange(shadowCells[index].x, asuint(frames[0].epipole.z), previous);
#else
                uint4 cell = shadowCells[index];
                depth = max(depth, cell.x == asuint(frames[0].epipole.z) ? asfloat(cell.w) : 1e20);
#endif
            }
        return float2(depth);
    }
};
ShadowSource shadowSource() {
    return {};
}
