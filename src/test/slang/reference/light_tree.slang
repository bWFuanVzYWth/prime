// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
// Traversal oracle from 5388f095: follows the original node -> leaf stream and rereads controls.
// Metric math is shared; only the record access and traversal state are under comparison.
import "service/light/tree_select.slang";
import "service/light/tree_pdf.slang";

bool referenceLoadLightChildMetrics(
        LightNode* nodes, LightLeaf* leaves, SectionRecord* sections,
        uint64_t emitterAddress, uint entryLimit, uint leafMode,
        uint nodeIndex, uint childOrLeaf, float3 point, float3 receiverNormal,
        out float2 metrics) {
    LightNode node = nodes[nodeIndex];
    if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
        LightLeaf leaf = leaves[childOrLeaf & PRIME_LIGHT_INDEX_MASK];
        node.directionChildLeaf.z = leaf.index;
        node.directionChildLeaf.w = asuint(leaf.power);
    }
    return primeLoadLightChildMetrics(
            node, sections, emitterAddress, entryLimit, leafMode, point, receiverNormal, metrics);
}
LightTreePick referenceInvalidLightTreePick() {
    LightTreePick result;
    result.index = 0u;
    result.pdf = 0.0;
    result.remapped = 0.0;
    result.valid = 0u;
    return result;
}

LightTreePick referencePickLightTree(
        LightNode* nodes,
        LightLeaf* leaves,
        SectionRecord* sections,
        uint64_t emitterAddress,
        uint entryLimit,
        uint leafMode,
        uint root,
        float3 point,
        float3 receiverNormal,
        float seed) {
    uint nodeIndex = root;
    float remapped = clamp(seed, 0.0, asfloat(0x3f7fffffu));
    float pdf = 1.0;
    [loop]
    for (uint depth = 0u; depth <= PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        uint childOrLeaf = primeLoadLightNodeChildOrLeaf(nodes, nodeIndex);
        if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            uint leafIndex = childOrLeaf & PRIME_LIGHT_INDEX_MASK;
            LightLeaf leaf = leaves[leafIndex];
            if (leaf.index >= entryLimit) return referenceInvalidLightTreePick();
            LightTreePick result;
            result.index = leaf.index;
            result.pdf = pdf;
            result.remapped = remapped;
            result.valid = 1u;
            return result;
        }
        uint firstChild = childOrLeaf;
        // Issue both independent control reads before following either child's pointer chain.
        uint firstChildOrLeaf = primeLoadLightNodeChildOrLeaf(nodes, firstChild);
        uint secondChildOrLeaf = primeLoadLightNodeChildOrLeaf(nodes, firstChild + 1u);
        float2 firstMetrics;
        float2 secondMetrics;
        if (!referenceLoadLightChildMetrics(
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
                || !referenceLoadLightChildMetrics(
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
            return referenceInvalidLightTreePick();
        }
        float firstProbability = primeLightBranchProbability(firstMetrics, secondMetrics);
        if (!(firstProbability >= 0.0)) return referenceInvalidLightTreePick();
        bool second = remapped >= firstProbability;
        float branchProbability = second ? 1.0 - firstProbability : firstProbability;
        if (!(branchProbability > 0.0)) return referenceInvalidLightTreePick();
        remapped = second
                ? (remapped - firstProbability) / branchProbability
                : remapped / branchProbability;
        pdf *= branchProbability;
        nodeIndex = firstChild + uint(second);
    }
    return referenceInvalidLightTreePick();
}

public float referenceLightTreeSelectionPdf(
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
    uint depth = packedPath >> 27u;
    uint trail = packedPath & 0x07ffffffu;
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
        if (!referenceLoadLightChildMetrics(
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
                || !referenceLoadLightChildMetrics(
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
