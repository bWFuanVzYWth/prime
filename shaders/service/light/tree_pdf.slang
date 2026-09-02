#language slang 2026
module "service/light/tree_pdf.slang";

import "prime_abi_types.slang";
import "math/light_tree.slang";
import "service/light/emission.slang";
import "service/light/tree_common.slang";

static const uint PRIME_LIGHT_PATH_DEPTH_SHIFT = 27u;
static const uint PRIME_LIGHT_PATH_TRAIL_MASK = 0x07ffffffu;

public float primeLightTreeSelectionPdf(
        LightNode* nodes,
        LightLeaf* leaves,
        SectionRecord* sections,
        uint64_t emitterAddress,
        uint entryLimit,
        uint leafMode,
        uint root,
        uint packedPath,
        uint expectedIndex,
        float3 point,
        float3 receiverNormal) {
    if (packedPath == PRIME_NO_LIGHT_INDEX) {
        return 0.0;
    }
    uint depth = packedPath >> PRIME_LIGHT_PATH_DEPTH_SHIFT;
    uint trail = packedPath & PRIME_LIGHT_PATH_TRAIL_MASK;
    if (depth > PRIME_LIGHT_TREE_MAX_DEPTH
            || (depth < PRIME_LIGHT_TREE_MAX_DEPTH && (trail >> depth) != 0u)) {
        return 0.0;
    }
    uint nodeIndex = root;
    float pdf = 1.0;
    [loop]
    for (uint level = 0u; level < PRIME_LIGHT_TREE_MAX_DEPTH; ++level) {
        if (level >= depth) break;
        uint firstChild = primeLoadLightNodeChildOrLeaf(nodes, nodeIndex);
        if ((firstChild & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            return 0.0;
        }
        // Keep reverse MIS on the same two-read schedule as forward selection.
        uint firstChildOrLeaf = primeLoadLightNodeChildOrLeaf(nodes, firstChild);
        uint secondChildOrLeaf = primeLoadLightNodeChildOrLeaf(nodes, firstChild + 1u);
        float2 firstMetrics;
        float2 secondMetrics;
        if (!primeLoadLightChildMetrics(
                    nodes,
                    leaves,
                    sections,
                    emitterAddress,
                    entryLimit,
                    leafMode,
                    firstChild,
                    firstChildOrLeaf,
                    point,
                    receiverNormal,
                    firstMetrics)
                || !primeLoadLightChildMetrics(
                    nodes,
                    leaves,
                    sections,
                    emitterAddress,
                    entryLimit,
                    leafMode,
                    firstChild + 1u,
                    secondChildOrLeaf,
                    point,
                    receiverNormal,
                    secondMetrics)) {
            return 0.0;
        }
        float firstProbability = primeLightBranchProbability(firstMetrics, secondMetrics);
        if (!(firstProbability >= 0.0)) return 0.0;
        bool second = ((trail >> level) & 1u) != 0u;
        float branchProbability = second ? 1.0 - firstProbability : firstProbability;
        if (!(branchProbability > 0.0)) return 0.0;
        pdf *= branchProbability;
        nodeIndex = firstChild + uint(second);
    }
    uint childOrLeaf = primeLoadLightNodeChildOrLeaf(nodes, nodeIndex);
    if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) == 0u) return 0.0;
    LightLeaf leaf = leaves[childOrLeaf & PRIME_LIGHT_INDEX_MASK];
    return leaf.index == expectedIndex && leaf.index < entryLimit ? pdf : 0.0;
}
