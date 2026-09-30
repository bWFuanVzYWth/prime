#language slang 2026
module "grid_sampling.slang";

// Private Rust/GPU records. IDs stay stable while immutable source light pages live.
public struct GridAlias {
    public uint light;
    public uint alias;
    public float cut;
    public float pdf;
};
public struct PageAlias {
    public float cut;
    public uint alias;
};
public struct GridEmitter {
    public uint page;
    public uint emitter;
    public float pdf;
    public float inverseArea;
};
public struct GridPage {
    public uint *emitters;
    public PageAlias *aliases;
    public float3 origin;
    public uint format;
    public uint first;
    public uint count;
    public float pdf;
    public uint reserved;
};
public struct LightCell {
    public int3 key;
    public uint count;
    public GridAlias *entries;
    public uint2 reserved;
};
public struct LightGrid {
    public GridPage *pages;
    public GridEmitter *emitters;
    public GridAlias *world;
    public LightCell *cells;
    public int3 anchorCell;
    public uint cellMask;
    public float3 anchorOffset;
    public uint worldCount;
};

uint cellHash(int3 key) {
    uint value = uint(key.x) * 73856093u ^ uint(key.y) * 19349663u ^ uint(key.z) * 83492791u;
    value ^= value >> 16;
    value *= 0x7feb352du;
    value ^= value >> 15;
    value *= 0x846ca68bu;
    return value ^ (value >> 16);
}
public LightCell receiverLightCell(LightGrid grid, float3 receiver) {
    // Split the f64 anchor on the CPU so distant Minecraft coordinates do not erase subcells.
    int3 key = int3(floor((receiver + grid.anchorOffset) * (1.0 / 16.0))) + grid.anchorCell;
    uint slot = cellHash(key) & grid.cellMask;
    for (;;) {
        LightCell cell = grid.cells[slot];
        if (cell.count == 0 || all(cell.key == key))
            return cell;
        slot = (slot + 1) & grid.cellMask;
    }
}
public GridAlias selectGridAlias(GridAlias *entries, uint count, float sample) {
    precise float scaled = sample * float(count);
    uint column = min(uint(scaled), count - 1);
    GridAlias entry = entries[column];
    precise float residual = scaled - float(column);
    return residual < entry.cut ? entry : entries[entry.alias];
}
float localLightPdf(LightCell cell, uint light) {
    uint lower = 0, upper = cell.count;
    while (lower < upper) {
        uint middle = lower + (upper - lower) / 2;
        GridAlias entry = cell.entries[middle];
        if (entry.light == light)
            return entry.pdf;
        if (entry.light < light)
            lower = middle + 1;
        else
            upper = middle;
    }
    return 0;
}
// The same full mixture is used after either route and for the reverse MIS evaluation.
float mixtureLightPdf(LightGrid grid, LightCell cell, uint light, float localPdf) {
    GridEmitter emitter = grid.emitters[light];
    float globalPdf = grid.pages[emitter.page].pdf * emitter.pdf;
    return cell.count == 0 ? globalPdf : 0.25 * globalPdf + 0.75 * localPdf;
}
public float gridLightPdf(LightGrid grid, float3 receiver, uint light) {
    LightCell cell = receiverLightCell(grid, receiver);
    return mixtureLightPdf(grid, cell, light, localLightPdf(cell, light));
}
public uint selectGridLight(LightGrid grid, float3 receiver, float4 samples, out float pdf) {
    LightCell cell = receiverLightCell(grid, receiver);
    uint light;
    float localPdf;
    if (cell.count != 0 && samples.x >= 0.25) {
        GridAlias entry = selectGridAlias(cell.entries, cell.count, samples.y);
        light = entry.light;
        localPdf = entry.pdf;
    } else {
        GridAlias world = selectGridAlias(grid.world, grid.worldCount, samples.z);
        GridPage page = grid.pages[world.light];
        precise float scaled = samples.w * float(page.count);
        uint column = min(uint(scaled), page.count - 1);
        PageAlias entry = page.aliases[column];
        precise float residual = scaled - float(column);
        light = page.first + (residual < entry.cut ? column : entry.alias);
        localPdf = localLightPdf(cell, light);
    }
    pdf = mixtureLightPdf(grid, cell, light, localPdf);
    return light;
}
