#language slang 2026
module "sphere_tree_sampling.slang";
import "light_direction.slang";

// Same outer table sizes as TREE, with variant-specific node and replay data.
public struct SphereNode {
    public float3 centroid;
    public float power;
    public uint direction;
    public uint child;
    public uint leaves;
    public float radius;
};
public struct TreeEmitter {
    public uint page;
    public uint emitter;
    public uint path;
    public float inverseArea;
};
public struct TreePage {
    public SphereNode *nodes;
    public uint *emitters;
    public float3 origin;
    public uint format;
    public uint first;
    public uint count;
    public uint path;
    public uint reserved;
};
public struct LightTree {
    public SphereNode *world;
    public TreePage *pages;
    public TreeEmitter *emitters;
    public float worldPower;
    public uint worldCount;
};
public struct SphereReceiver {
    public float3 position;
    public float3 normal;
};
float sphereCosineBound(float c, float sinBound, float cosBound) {
    return c >= cosBound ? 1.0
                         : clamp(c * cosBound + sqrt(max(1.0 - c * c, 0.0)) * sinBound, 0.0, 1.0);
}
float2 sphereCosineBoundPair(float c, float sinBound, float cosBound) {
    // Opposite lobes share their tangent term; keep each bound's original arithmetic.
    float tangent = sqrt(max(1.0 - c * c, 0.0)) * sinBound;
    return float2(c >= cosBound ? 1.0 : clamp(c * cosBound + tangent, 0.0, 1.0),
                  -c >= cosBound ? 1.0 : clamp(-c * cosBound + tangent, 0.0, 1.0));
}
float2 sphereNodeMetrics(SphereNode node, SphereReceiver receiver) {
    float3 delta = node.centroid - receiver.position;
    float d2 = dot(delta, delta), radius = node.radius;
    float angular = 1.0;
    if (d2 > radius * radius) {
        float inverse = rsqrt(d2);
        float3 wi = delta * inverse;
        float sinBound = radius * inverse;
        float cosBound = sqrt(max(1.0 - sinBound * sinBound, 0.0));
        float rec = dot(receiver.normal, receiver.normal) > 0.0
                            ? sphereCosineBound(dot(wi, receiver.normal), sinBound, cosBound)
                            : 1.0;
        angular = 0.0;
        if (rec != 0.0) {
            float3 d = -wi;
            uint mode = node.direction >> 30;
            float emission = 1.0;
            if (mode == 2) {
                float2 x = sphereCosineBoundPair(d.x, sinBound, cosBound);
                float2 y = sphereCosineBoundPair(d.y, sinBound, cosBound);
                float2 z = sphereCosineBoundPair(d.z, sinBound, cosBound);
                // Preserve +X,-X,+Y,-Y,+Z,-Z association; backend FP contraction can differ.
                emission = 0.0;
                emission += float(node.direction & 31u) / 31.0 * x.x;
                emission += float((node.direction >> 5) & 31u) / 31.0 * x.y;
                emission += float((node.direction >> 10) & 31u) / 31.0 * y.x;
                emission += float((node.direction >> 15) & 31u) / 31.0 * y.y;
                emission += float((node.direction >> 20) & 31u) / 31.0 * z.x;
                emission += float((node.direction >> 25) & 31u) / 31.0 * z.y;
                emission = min(emission, 1.0);
            } else if (mode < 2) {
                float c = dot(primeUnpackLightEmissionAxis(node.direction), d);
                if (mode == 1)
                    c = abs(c);
                float coneSin = float((node.direction >> 20) & 1023u) / 1023.0;
                float coneCos = sqrt(max(1.0 - coneSin * coneSin, 0.0));
                float sinTotal = coneSin * cosBound + coneCos * sinBound;
                float cosTotal = coneCos * cosBound - coneSin * sinBound;
                emission = cosTotal <= 0.0 ? 1.0 : sphereCosineBound(c, sinTotal, cosTotal);
                if (mode == 1)
                    emission *= 0.5;
            }
            angular = clamp(rec * emission, 0.0, 1.0);
        }
    }
    // Preserve the historical centroid-distance metric; only angle estimation changes.
    return float2(d2, max(node.power, 0.0) * (1.0 / 256.0 + 255.0 / 256.0 * angular));
}

float2 sphereEmitterMetrics(TreePage page, SphereNode node, SphereReceiver receiver) {
    uint index = node.child & 0x7fffffffu;
    uint words = page.format == 3 ? 108 : (page.format == 2 ? 68 : 60);
    uint *record = page.emitters + index * words;
    float4 *positions = (float4 *)record;
    float3 p0 = positions[0].xyz, p1 = positions[1].xyz;
    float3 p2 = positions[2].xyz, p3 = positions[3].xyz;
    float3 lo = min(min(p0, p1), min(p2, p3));
    float3 hi = max(max(p0, p1), max(p2, p3));
    float3 delta = receiver.position - clamp(receiver.position, lo, hi);
    float d2 = dot(delta, delta), inverse = d2 > 0.0 ? rsqrt(d2) : 0.0;
    float rec = primeLightReceiverCosineBoundWithInverse(lo, hi, receiver.position, receiver.normal,
                                                         d2, inverse);
    float fraction = asfloat(record[3]);
    float emission = 0.0;
    bool twoSided = (record[59] & 2u) != 0;
    // The two geometric halves may differ; a zero-area repeated corner is never normalized.
    if (fraction > 0.0) {
        float3 normal = normalize(cross(p1 - p0, p2 - p0));
        float bound =
                primeLightEmissionAxisCosineBound(lo, hi, receiver.position, normal, d2, inverse);
        if (twoSided)
            bound = 0.5 * max(bound, primeLightEmissionAxisCosineBound(lo, hi, receiver.position,
                                                                       -normal, d2, inverse));
        emission += fraction * bound;
    }
    if (fraction < 1.0) {
        float3 normal = normalize(cross(p3 - p2, p0 - p2));
        float bound =
                primeLightEmissionAxisCosineBound(lo, hi, receiver.position, normal, d2, inverse);
        if (twoSided)
            bound = 0.5 * max(bound, primeLightEmissionAxisCosineBound(lo, hi, receiver.position,
                                                                       -normal, d2, inverse));
        emission += (1.0 - fraction) * bound;
    }
    float weighted = max(node.power, 0.0) *
                     (1.0 / 256.0 + (255.0 / 256.0) * clamp(rec * emission, 0.0, 1.0));
    if (d2 == 0.0) {
        delta = receiver.position - node.centroid;
        d2 = dot(delta, delta);
    }
    if (d2 == 0.0) {
        float3 u = p1 - p0, v = p3 - p0;
        d2 = dot(u, u) + dot(v, v);
    }
    return float2(d2, weighted);
}

float2 sphereChildMetrics(TreePage page, SphereNode node, bool world, SphereReceiver receiver) {
    if (world)
        return sphereNodeMetrics(node, receiver);
    if ((node.child & 0x80000000u) != 0)
        return sphereEmitterMetrics(page, node, receiver);
    return primeLightNodeMetrics(node.centroid, node.power, receiver.position, receiver.normal,
                                 node.direction);
}

public uint sphereLeftCount(TreePage page, SphereNode a, SphereNode b, bool world,
                            SphereReceiver receiver, uint count) {
    float p = clamp(primeLightBranchProbability(sphereChildMetrics(page, a, world, receiver),
                                                sphereChildMetrics(page, b, world, receiver)),
                    1e-6, 1.0 - 1e-6);
    // Counts/targets fit exactly in FP32's integer range. Reserving each descendant leaf
    // preserves support on the actual 24-bit random lattice, even for extreme score ratios.
    uint left = uint(round(float(count) * p));
    return clamp(left, a.leaves, count - b.leaves);
}

uint spherePick(SphereNode *nodes, TreePage page, bool world, SphereReceiver receiver, float sample,
                out float pdf) {
    uint target = min(uint(sample * 16777216.0), 16777215u);
    uint first = 0, count = 16777216u;
    SphereNode node = nodes[0];
    for (uint depth = 0; depth <= 27; ++depth) {
        if ((node.child & 0x80000000u) != 0) {
            pdf = float(count) * (1.0 / 16777216.0);
            return node.child & 0x7fffffffu;
        }
        SphereNode a = nodes[node.child], b = nodes[node.child + 1];
        uint left = sphereLeftCount(page, a, b, world, receiver, count);
        bool second = target >= first + left;
        if (second) {
            first += left;
            count -= left;
            node = b;
        } else {
            count = left;
            node = a;
        }
    }
    pdf = 0.0;
    return 0xffffffffu;
}

float sphereReplay(SphereNode *nodes, TreePage page, uint path, uint expected, bool world,
                   SphereReceiver receiver) {
    SphereNode node = nodes[0];
    uint count = 16777216u;
    for (uint depth = 0; depth < (path >> 27); ++depth) {
        if ((node.child & 0x80000000u) != 0)
            return 0.0;
        SphereNode a = nodes[node.child], b = nodes[node.child + 1];
        uint left = sphereLeftCount(page, a, b, world, receiver, count);
        bool second = ((path >> depth) & 1u) != 0;
        count = second ? count - left : left;
        node = second ? b : a;
    }
    return node.child == (0x80000000u | expected) ? float(count) * (1.0 / 16777216.0) : 0.0;
}

public uint selectTreeSphereLight(LightTree tree, float2 samples, float3 point, float3 normal,
                                  out float pdf, out TreePage page, out TreeEmitter emitter) {
    SphereReceiver receiver = {point, normal};
    float worldPdf, localPdf;
    uint selectedPage = spherePick(tree.world, tree.pages[0], true, receiver, samples.x, worldPdf);
    page = tree.pages[selectedPage];
    receiver.position -= page.origin;
    uint light = page.first + spherePick(page.nodes, page, false, receiver, samples.y, localPdf);
    emitter = tree.emitters[light];
    pdf = worldPdf * localPdf;
    return light;
}

public float treeSphereLightPdf(LightTree tree, uint light, float3 point, float3 normal) {
    TreeEmitter emitter = tree.emitters[light];
    TreePage page = tree.pages[emitter.page];
    SphereReceiver receiver = {point, normal};
    float worldPdf = sphereReplay(tree.world, page, page.path, emitter.page, true, receiver);
    receiver.position -= page.origin;
    return worldPdf *
           sphereReplay(page.nodes, page, emitter.path, emitter.emitter, false, receiver);
}
