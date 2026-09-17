// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/light/tree_pdf.slang";

import "prime_abi_types.slang";
import "math/light_tree.slang";
import "contract/light_sample.slang";
import "service/light/tree_common.slang";

static const uint PRIME_LIGHT_PATH_DEPTH_SHIFT = 27u;
static const uint PRIME_LIGHT_PATH_TRAIL_MASK = 0x07ffffffu;

public float primeLightTreeSelectionPdf(
        LightNode* nodes,
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
    uint4 control = nodes[root].directionChildLeaf;
    float pdf = 1.0;
    [loop]
    for (uint level = 0u; level < PRIME_LIGHT_TREE_MAX_DEPTH; ++level) {
        if (level >= depth) break;
        uint firstChild = control.y;
        if ((firstChild & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            return 0.0;
        }
        // Reuse the same compiled child records and probability order as forward selection.
        LightNode first = nodes[firstChild];
        LightNode secondNode = nodes[firstChild + 1u];
        float2 firstMetrics;
        float2 secondMetrics;
        if (!primeLoadLightChildMetrics(
                    first,
                    sections,
                    emitterAddress,
                    entryLimit,
                    leafMode,
                    point,
                    receiverNormal,
                    firstMetrics)
                || !primeLoadLightChildMetrics(
                    secondNode,
                    sections,
                    emitterAddress,
                    entryLimit,
                    leafMode,
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
        control = second ? secondNode.directionChildLeaf : first.directionChildLeaf;
    }
    uint childOrLeaf = control.y;
    if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) == 0u) return 0.0;
    return control.z == expectedIndex && control.z < entryLimit ? pdf : 0.0;
}
