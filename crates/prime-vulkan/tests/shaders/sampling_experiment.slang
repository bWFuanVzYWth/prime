#language slang 2026
module sampling_experiment;
import "light_sampling.slang";
import "math/z_sobol.slang";

[[vk::constant_id(0)]] const uint method = 0;   // power tree, two-level alias, spatial tree
[[vk::constant_id(1)]] const uint sequence = 0; // IID, production Z-Sobol (S=8)

struct Alias {
    float cut;
    uint other;
};
struct Page {
    uint nodes;
    uint first;
    uint count;
    uint aliases;
};
struct Emitter {
    float3 position;
    float halfSize;
    float radiance;
    float power;
    uint2 padding;
};
struct Config {
    LightNode *world;
    LightNode *local;
    Alias *aliases;
    Page *pages;
    Emitter *emitters;
    float *output;
    uint width;
    uint height;
    uint sampleIndex;
    uint seed;
    uint pageCount;
    uint wall;
    uint log2Resolution;
    uint inspect;
};
[[vk::push_constant]] ConstantBuffer<Config> config;

uint mixBits(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    return x ^ (x >> 16);
}
float uniform(uint seed, uint dimension) {
    return float(mixBits(seed ^ (dimension * 0x9e3779b9u)) >> 8) * (1.0 / 16777216.0);
}
uint selectAlias(Alias *table, uint count, inout float u) {
    float v = u * float(count);
    uint i = min(uint(v), count - 1);
    float residual = v - float(i);
    Alias a = table[i];
    bool first = residual < a.cut;
    u = min(first ? residual / a.cut : (residual - a.cut) / (1.0 - a.cut), asfloat(0x3f7fffffu));
    return first ? i : a.other;
}
float importance(LightNode node, float3 point) {
    float3 delta = (node.lower + node.upper) * 0.5 - point;
    float3 extent = (node.upper - node.lower) * 0.5;
    return node.power / max(max(dot(delta, delta), dot(extent, extent)), 1e-12);
}
uint selectSpatial(LightNode *nodes, float3 point, inout float u, inout float pdf) {
    LightNode node = nodes[0];
    while ((node.child & 0x80000000u) == 0) {
        LightNode left = nodes[node.child], right = nodes[node.child + 1];
        float a = importance(left, point), b = importance(right, point);
        float p = a / (a + b);
        if (u < p) {
            pdf *= p;
            u /= p;
            node = left;
        } else {
            pdf *= 1.0 - p;
            u = (u - p) / (1.0 - p);
            node = right;
        }
        u = min(u, asfloat(0x3f7fffffu));
    }
    return node.child & 0x7fffffffu;
}

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= config.width || id.y >= config.height)
        return;
    uint pixel = id.y * config.width + id.x;
    uint rx = id.x * 8 / config.width, rz = id.y * 4 / config.height;
    float3 receiver = float3(float(rx) - 3.5, 0, float(rz) * 2.0 - 3.0);
    float u;
    float2 area;
    if (sequence == 0) {
        uint key = mixBits(pixel ^ mixBits(config.seed)) ^ mixBits(config.sampleIndex ^ 0x91727u);
        u = uniform(key, 1);
        area = float2(uniform(key, 2), uniform(key, 3));
    } else {
        ZSobol sampler = makeZSobol(id.xy, config.sampleIndex & 255, config.log2Resolution, 8,
                                    config.seed ^ mixBits(config.sampleIndex >> 8));
        u = sampler.sample1D(512);
        area = sampler.sample2D(513);
    }
    uint page, index;
    float pdf = 1.0;
    if (method == 1) {
        page = selectAlias(config.aliases, config.pageCount, u);
        Page p = config.pages[page];
        index = p.first + selectAlias(config.aliases + p.aliases, p.count, u);
    } else if (method == 2) {
        page = selectSpatial(config.world, receiver, u, pdf);
        Page p = config.pages[page];
        index = p.first + selectSpatial(config.local + p.nodes, receiver, u, pdf);
    } else {
        page = selectPowerLight(config.world, u);
        Page p = config.pages[page];
        index = p.first + selectPowerLight(config.local + p.nodes, u);
    }
    Emitter light = config.emitters[index];
    if (method != 2)
        pdf = light.power / config.world[0].power;
    if (config.inspect != 0) {
        config.output[pixel] = asfloat(index);
        config.output[config.width * config.height + pixel] = pdf;
        return;
    }
    // Same area-weighted two-triangle mapping as the production quad emitter.
    float root = sqrt(area.x < 0.5 ? area.x * 2.0 : area.x * 2.0 - 1.0);
    float2 offset = (2.0 * float2(root, root * area.y) - 1.0) * light.halfSize;
    if (area.x >= 0.5)
        offset = -offset;
    float3 delta = light.position + float3(offset.x, 0, offset.y) - receiver;
    float d2 = dot(delta, delta);
    float value = light.radiance * (4.0 * light.halfSize * light.halfSize) * delta.y * delta.y /
                  (3.141592653589793 * d2 * d2 * pdf);
    // Analytic infinite opaque divider x=0. Whole emitters lie on one side, so reference
    // visibility is exact. This experiment intentionally does not time a shadow ray.
    if (config.wall != 0 && (receiver.x > 0) != (light.position.x > 0))
        value = 0;
    float mean = config.sampleIndex == 0 ? 0.0 : config.output[pixel];
    config.output[pixel] = mean + (value - mean) / float(config.sampleIndex + 1);
}
