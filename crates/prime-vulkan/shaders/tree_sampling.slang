#language slang 2026
module "tree_sampling.slang";
import "light_distance.slang";

// Two-level power / distance-squared proposal on the historical spatial median topology.
// No angular estimate: receiver position alone defines both selection and inverse PMF.
public struct TreeNode {
    public float3 centroid;
    public float power;
    public uint child;
    public uint leaves;
};
public struct TreeEmitter {
    public uint page;
    public uint emitter;
    public uint path;
    public float inverseArea;
};
public struct TreePage {
    public TreeNode *nodes;
    public uint *emitters;
    public float3 origin;
    public uint format;
    public uint first;
    public uint count;
    public uint path;
    public uint reserved;
};
public struct LightTree {
    public TreeNode *world;
    public TreePage *pages;
    public TreeEmitter *emitters;
    public float worldPower;
    public uint worldCount;
};

float2 treeChildMetrics(TreePage page, TreeNode node, bool world, float3 point) {
    float3 delta = node.centroid - point;
    float d2 = dot(delta, delta);
    if (!world && (node.child & 0x80000000u) != 0) {
        uint index = node.child & 0x7fffffffu;
        uint words = page.format == 3 ? 108 : (page.format == 2 ? 68 : 60);
        float4 *positions = (float4 *)(page.emitters + index * words);
        float3 p0 = positions[0].xyz, p1 = positions[1].xyz;
        float3 p2 = positions[2].xyz, p3 = positions[3].xyz;
        float3 lo = min(min(p0, p1), min(p2, p3));
        float3 hi = max(max(p0, p1), max(p2, p3));
        delta = point - clamp(point, lo, hi);
        float boundsD2 = dot(delta, delta);
        if (boundsD2 > 0.0)
            d2 = boundsD2;
        if (d2 == 0.0) {
            float3 u = p1 - p0, v = p3 - p0;
            d2 = dot(u, u) + dot(v, v);
        }
    }
    return float2(d2, max(node.power, 0.0));
}

public uint treeLeftCount(TreePage page, TreeNode a, TreeNode b, bool world, float3 point,
                          uint count) {
    float p = clamp(primeLightBranchProbability(treeChildMetrics(page, a, world, point),
                                                treeChildMetrics(page, b, world, point)),
                    1e-6, 1.0 - 1e-6);
    // Reserve support for every descendant on the actual 24-bit random lattice.
    uint left = uint(round(float(count) * p));
    return clamp(left, a.leaves, count - b.leaves);
}

uint treePick(TreeNode *nodes, TreePage page, bool world, float3 point, float sample,
              out float pdf) {
    uint target = min(uint(sample * 16777216.0), 16777215u);
    uint first = 0, count = 16777216u;
    TreeNode node = nodes[0];
    for (uint depth = 0; depth <= 27; ++depth) {
        if ((node.child & 0x80000000u) != 0) {
            pdf = float(count) * (1.0 / 16777216.0);
            return node.child & 0x7fffffffu;
        }
        TreeNode a = nodes[node.child], b = nodes[node.child + 1];
        uint left = treeLeftCount(page, a, b, world, point, count);
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

float treeReplay(TreeNode *nodes, TreePage page, uint path, uint expected, bool world,
                 float3 point) {
    TreeNode node = nodes[0];
    uint count = 16777216u;
    for (uint depth = 0; depth < (path >> 27); ++depth) {
        if ((node.child & 0x80000000u) != 0)
            return 0.0;
        TreeNode a = nodes[node.child], b = nodes[node.child + 1];
        uint left = treeLeftCount(page, a, b, world, point, count);
        bool second = ((path >> depth) & 1u) != 0;
        count = second ? count - left : left;
        node = second ? b : a;
    }
    return node.child == (0x80000000u | expected) ? float(count) * (1.0 / 16777216.0) : 0.0;
}

public float treeLightPdf(LightTree tree, uint light, float3 point) {
    TreeEmitter emitter = tree.emitters[light];
    TreePage page = tree.pages[emitter.page];
    float worldPdf = treeReplay(tree.world, page, page.path, emitter.page, true, point);
    return worldPdf *
           treeReplay(page.nodes, page, emitter.path, emitter.emitter, false, point - page.origin);
}

public uint selectTreeLight(LightTree tree, float2 samples, float3 point, out float pdf,
                            out TreePage page, out TreeEmitter emitter) {
    float worldPdf, localPdf;
    uint selectedPage = treePick(tree.world, tree.pages[0], true, point, samples.x, worldPdf);
    page = tree.pages[selectedPage];
    uint light = page.first +
                 treePick(page.nodes, page, false, point - page.origin, samples.y, localPdf);
    emitter = tree.emitters[light];
    pdf = worldPdf * localPdf;
    return light;
}
