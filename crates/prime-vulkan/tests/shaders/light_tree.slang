#language slang 2026
module light_tree_test;
import "tree_sampling.slang";

public uint treeCpuSelect(TreeNode *nodes, float sample) {
    return selectTreeNode(nodes, sample);
}
public uint4 treeCpuSelection(LightTree tree, float2 samples) {
    float pdf;
    TreePage page;
    TreeEmitter emitter;
    uint light = selectTreeLight(tree, samples, pdf, page, emitter);
    return uint4(light, emitter.page, emitter.emitter, asuint(pdf));
}
public TreePage treeCpuPage(LightTree tree, float2 samples) {
    float pdf;
    TreePage page;
    TreeEmitter emitter;
    selectTreeLight(tree, samples, pdf, page, emitter);
    return page;
}
public float treeCpuReverse(LightTree tree, uint light) {
    return treeLightPdf(tree, light);
}

struct Config {
    LightTree *tree;
    float2 *samples;
    uint4 *results;
    uint count;
};
[[vk::push_constant]] ConstantBuffer<Config> config;

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x < config.count) {
        LightTree tree = *config.tree;
        float2 samples = config.samples[id.x];
        uint4 selected = treeCpuSelection(tree, samples);
        config.results[id.x * 3] = selected;
        config.results[id.x * 3 + 1] = uint4(asuint(treeCpuReverse(tree, selected.x)),
                                             treeCpuSelect(tree.world, samples.x), 0, 0);
        TreePage page = treeCpuPage(tree, samples);
        config.results[id.x * 3 + 2] =
                uint4(page.format, asuint(page.origin.x), page.first, page.count);
    }
}
