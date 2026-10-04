#language slang 2026
module sphere_tree_contract;
import "sphere_tree_sampling.slang";
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

uint2 interval(SphereNode *nodes, TreePage page, uint path, bool world, SphereReceiver receiver) {
    SphereNode node = nodes[0];
    uint first = 0, count = 16777216u;
    for (uint depth = 0; depth < (path >> 27); ++depth) {
        SphereNode a = nodes[node.child], b = nodes[node.child + 1];
        uint left = sphereLeftCount(page, a, b, world, receiver, count);
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

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    TestInput args = inputs[i];
    LightTree tree = *args.tree;
    float3 point = args.point.xyz;
    float3 normal = args.normal.xyz;
    uint light = args.light;
    TreeEmitter reference = tree.emitters[light];
    TreePage page = tree.pages[reference.page];
    SphereReceiver receiver = {point, normal};
    uint2 world = interval(tree.world, page, page.path, true, receiver);
    receiver.position -= page.origin;
    uint2 local = interval(page.nodes, page, reference.path, false, receiver);
    uint endpoint = args.endpoint & 1u;
    float2 sample =
            float2(world.x + endpoint * (world.y - 1u), local.x + endpoint * (local.y - 1u)) *
            (1.0 / 16777216.0);
    float forward;
    TreePage selectedPage;
    TreeEmitter selectedEmitter;
    uint selected = selectTreeSphereLight(tree, sample, point, normal, forward, selectedPage,
                                          selectedEmitter);
    float reverse = treeSphereLightPdf(tree, light, point, normal);
    outputs[i * 2] = uint4(selected, asuint(forward), asuint(reverse), selectedEmitter.emitter);
    outputs[i * 2 + 1] = uint4(world, local);
}
