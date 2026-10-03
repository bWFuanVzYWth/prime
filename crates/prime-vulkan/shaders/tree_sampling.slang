#language slang 2026
module "tree_sampling.slang";

// Historical two-level power proposal, with exact support on each 24-bit selector.
// Rust retains spatial median topology/power; only integer selection data goes to GPU.
public struct TreeNode {
    public uint split;
    public uint child;
};
public struct TreeEmitter {
    public uint page;
    public uint emitter;
    public float pdf;
    public float inverseArea;
};
public struct TreePage {
    public TreeNode *nodes;
    public uint *emitters;
    public float3 origin;
    public uint format;
    public uint first;
    public uint count;
    public float pdf;
    public uint reserved;
};
public struct LightTree {
    public TreeNode *world;
    public TreePage *pages;
    public TreeEmitter *emitters;
    public float worldPower;
    public uint worldCount;
};

public uint selectTreeNode(TreeNode *nodes, float sample) {
    uint target = uint(sample * 16777216.0);
    TreeNode node = nodes[0];
    while ((node.child & 0x80000000u) == 0) {
        uint next = node.child + uint(target >= node.split);
        node = nodes[next];
    }
    return node.child & 0x7fffffffu;
}
public float treeEmitterPdf(TreePage page, TreeEmitter emitter) {
    return page.pdf * emitter.pdf;
}
public float treeLightPdf(LightTree tree, uint light) {
    TreeEmitter emitter = tree.emitters[light];
    return tree.pages[emitter.page].pdf * emitter.pdf;
}
public uint selectTreeLight(LightTree tree, float2 samples, out float pdf, out TreePage page,
                            out TreeEmitter emitter) {
    uint selectedPage = selectTreeNode(tree.world, samples.x);
    page = tree.pages[selectedPage];
    uint light = page.first + selectTreeNode(page.nodes, samples.y);
    emitter = tree.emitters[light];
    pdf = treeEmitterPdf(page, emitter);
    return light;
}
