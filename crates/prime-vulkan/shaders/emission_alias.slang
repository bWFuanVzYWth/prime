#language slang 2026
module "emission_alias.slang";
__exported import "tree_sampling.slang";

public struct EmissionAreaSample {
    public uint half;
    public float2 bary;
};

public EmissionAreaSample sampleEmitterArea(TreePage page, uint *light, uint offset,
                                            float2 sample) {
    EmissionAreaSample result;
    if (offset == 0u) {
        float fraction = asfloat(light[3]);
        bool first = fraction == 1.0 || sample.x < fraction;
        float u = first ? sample.x / fraction : (sample.x - fraction) / (1.0 - fraction);
        float root = sqrt(u);
        result.half = uint(!first);
        result.bary = float2(root * (1.0 - sample.y), root * sample.y);
        return result;
    }
    uint *header = (uint *)page.nodes + offset;
    uint target = min(uint(sample.x * 16777216.0), 16777215u);
    uint shift = header[3];
    uint capacity = 1u << shift;
    uint column = target >> shift;
    uint local = target & (capacity - 1u);
    uint *entry = header + header[5] + column * 8u;
    uint threshold = entry[0];
    float u;
    if (local < threshold) {
        u = (float(local) + 0.5) / float(threshold);
    } else {
        u = (float(local - threshold) + 0.5) / float(capacity - threshold);
        entry = header + header[5] + entry[1] * 8u;
    }
    // The interval residual is uniform on the actual accepted lattice, rather than the
    // ideal pre-quantization weight. One column may use all 24 bits: keep its endpoint open.
    u = min(u, 0.9999999403953552);
    uint *patch = header + header[6] + entry[3] * 8u;
    float area = asfloat(entry[6]);
    float targetArea = u * area;
    uint count = entry[4];
    for (uint i = 0; i + 1u < count; ++i) {
        float patchArea = asfloat(patch[1]);
        if (targetArea < patchArea)
            break;
        targetArea -= patchArea;
        patch = patch + 8u;
    }
    u = min(targetArea / asfloat(patch[1]), 0.9999999403953552);
    float root = sqrt(u);
    float2 b = float2(root * (1.0 - sample.y), root * sample.y);
    result.half = patch[0];
    float2 a = asfloat(uint2(patch[2], patch[3]));
    float2 c = asfloat(uint2(patch[4], patch[5]));
    float2 d = asfloat(uint2(patch[6], patch[7]));
    result.bary = a + b.x * (c - a) + b.y * (d - a);
    return result;
}

public float emitterAliasDensity(TreePage page, uint offset, float2 uv) {
    uint *header = (uint *)page.nodes + offset;
    uint2 extent = uint2(header[0], header[1]);
    uint2 cell = min(uint2(saturate(uv) * float2(extent)), extent - 1u);
    uint index = header[header[4] + cell.y * extent.x + cell.x];
    return index == 0xffffffffu ? 0.0 : asfloat(header[header[5] + index * 8u + 5u]);
}

public float emitterAreaDensity(LightTree tree, uint lightIndex, float2 uv) {
    TreeEmitter reference = tree.emitters[lightIndex];
    TreePage page = tree.pages[reference.page];
    uint words = page.format == 3 ? 108u : (page.format == 2 ? 68u : 60u);
    uint offset = page.emitters[reference.emitter * words + 11u];
    return offset == 0u ? reference.inverseArea : emitterAliasDensity(page, offset, uv);
}
