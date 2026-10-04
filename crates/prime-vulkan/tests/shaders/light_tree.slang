#language slang 2026
module light_tree_test;
import "tree_sampling.slang";

public uint4 treeCpuSelection(LightTree tree, float2 samples, float3 point) {
    float pdf;
    TreePage page;
    TreeEmitter emitter;
    uint light = selectTreeLight(tree, samples, point, pdf, page, emitter);
    return uint4(light, emitter.page, emitter.emitter, asuint(pdf));
}
public TreePage treeCpuPage(LightTree tree, float2 samples, float3 point) {
    float pdf;
    TreePage page;
    TreeEmitter emitter;
    selectTreeLight(tree, samples, point, pdf, page, emitter);
    return page;
}
public float treeCpuReverse(LightTree tree, uint light, float3 point) {
    return treeLightPdf(tree, light, point);
}

public uint treeCpuLeft(TreePage page, TreeNode a, TreeNode b, bool world, float3 point,
                        uint count) {
    return treeLeftCount(page, a, b, world, point, count);
}

// Locate actual lattice interval endpoints, then exercise production forward selection.
public uint2 treeCpuInterval(TreeNode *nodes, TreePage page, uint path, bool world, float3 point) {
    TreeNode node = nodes[0];
    uint first = 0, count = 16777216u;
    for (uint depth = 0; depth < (path >> 27); ++depth) {
        TreeNode a = nodes[node.child], b = nodes[node.child + 1];
        uint left = treeCpuLeft(page, a, b, world, point, count);
        if (((path >> depth) & 1u) != 0) {
            first += left;
            count -= left;
            node = b;
        } else {
            count = left;
            node = a;
        }
    }
    return uint2(first, count);
}

// Same fixture input/output contract as sphere_tree; normals are deliberately unused.
struct TestInput {
    LightTree *tree;
    uint light;
    uint endpoint;
    float4 point;
    float4 normal;
};
[[vk::binding(0, 0)]] StructuredBuffer<TestInput> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::push_constant]] ConstantBuffer<uint4> config;

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    TestInput args = inputs[i];
    LightTree tree = *args.tree;
    float3 point = args.point.xyz;
    TreeEmitter reference = tree.emitters[args.light];
    TreePage page = tree.pages[reference.page];
    uint2 world = treeCpuInterval(tree.world, page, page.path, true, point);
    uint2 local = treeCpuInterval(page.nodes, page, reference.path, false, point - page.origin);
    uint endpoint = args.endpoint & 1u;
    float2 samples =
            float2(world.x + endpoint * (world.y - 1u), local.x + endpoint * (local.y - 1u)) *
            (1.0 / 16777216.0);
    uint4 selected = treeCpuSelection(tree, samples, point);
    outputs[i * 2] = uint4(selected.x, selected.w, asuint(treeCpuReverse(tree, args.light, point)),
                           selected.z);
    outputs[i * 2 + 1] = uint4(world, local);
}
