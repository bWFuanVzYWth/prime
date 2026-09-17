// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "prime_abi_types.slang";
import "math/light_tree.slang";
import "service/light/emission.slang";
import "service/light/tree_common.slang";
#include "reference/light_tree.slang"

struct ReplayPush { uint64_t address; };
[[vk::push_constant]] ConstantBuffer<ReplayPush> replayPush;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> replayOutput;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    uint query = dispatchId.x;
    uint* data = (uint*)replayPush.address;
    if (query >= data[7]) return;
    LightNode* nodes = (LightNode*)(replayPush.address + data[0]);
    LightLeaf* leaves = (LightLeaf*)(replayPush.address + data[1]);
    uint64_t emitterAddress = replayPush.address + data[2];
    SectionRecord* sections = (SectionRecord*)(replayPush.address + data[3]);
    uint* paths = (uint*)(replayPush.address + data[4]);
    float4* queries = (float4*)(replayPush.address + data[5]);
    float3 point = queries[query * 2].xyz;
    float seed = queries[query * 2].w;
    float3 normal = queries[query * 2 + 1].xyz;
    uint mode = asuint(queries[query * 2 + 1].w);
    uint limit = data[6];
    uint firstChild = primeLoadLightNodeChildOrLeaf(nodes, 0u);
    if ((firstChild & PRIME_LIGHT_LEAF_FLAG) == 0u && (query & 7u) < 3u) {
        float2 first;
        float2 second;
        bool valid = referenceLoadLightChildMetrics(
                nodes, leaves, sections, emitterAddress, limit, mode, firstChild,
                primeLoadLightNodeChildOrLeaf(nodes, firstChild), point, normal, first);
        valid = referenceLoadLightChildMetrics(
                nodes, leaves, sections, emitterAddress, limit, mode, firstChild + 1u,
                primeLoadLightNodeChildOrLeaf(nodes, firstChild + 1u), point, normal, second) && valid;
        float probability = primeLightBranchProbability(first, second);
        if (valid && probability > 0.0 && probability < 1.0) {
            seed = asfloat(asuint(probability) + (query & 7u) - 1u);
        }
    }
    LightTreePick expected = referencePickLightTree(
            nodes, leaves, sections, emitterAddress, limit, mode, 0u, point, normal, seed);
    LightTreePick actual = primePickLightTree(
            nodes, sections, emitterAddress, limit, mode, 0u, point, normal, seed);
    uint mask = uint(expected.valid != actual.valid || expected.index != actual.index);
    mask |= uint(asuint(expected.pdf) != asuint(actual.pdf)) << 1;
    mask |= uint(asuint(expected.remapped) != asuint(actual.remapped)) << 2;
    uint index = expected.valid != 0u ? expected.index : query % limit;
    uint path = paths[index];
    float expectedPdf = referenceLightTreeSelectionPdf(
            nodes, leaves, sections, emitterAddress, limit, mode, 0u, path, index, point, normal);
    float actualPdf = primeLightTreeSelectionPdf(
            nodes, sections, emitterAddress, limit, mode, 0u, path, index, point, normal);
    mask |= uint(asuint(expectedPdf) != asuint(actualPdf)) << 3;
    mask |= uint(expected.valid != 0u && asuint(actual.pdf) != asuint(actualPdf)) << 4;
    // Malformed paths and mismatched identities must remain rejected by both traversals.
    uint badPath = (query & 1u) != 0u ? 0xffffffffu : (path | 0xf8000000u);
    float badPdf = primeLightTreeSelectionPdf(
            nodes, sections, emitterAddress, limit, mode, 0u, badPath, index, point, normal);
    mask |= uint(badPdf != 0.0) << 5;
    float mismatchPdf = primeLightTreeSelectionPdf(
            nodes, sections, emitterAddress, limit, mode, 0u, path, limit, point, normal);
    mask |= uint(mismatchPdf != 0.0) << 6;
    replayOutput[query * 2] = uint4(mask, expected.index, actual.index, mode);
    replayOutput[query * 2 + 1] = uint4(asuint(expected.pdf), asuint(actual.pdf),
            asuint(expected.remapped), asuint(actual.remapped));
}
