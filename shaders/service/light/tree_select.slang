#language slang 2026
module "service/light/tree_select.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "math/light_tree.slang";
import "service/light/emission.slang";
import "service/light/tree_common.slang";

struct LightTreePick {
    uint index;
    float pdf;
    float remapped;
    uint valid;
};

LightTreePick primeInvalidLightTreePick() {
    LightTreePick result;
    result.index = 0u;
    result.pdf = 0.0;
    result.remapped = 0.0;
    result.valid = 0u;
    return result;
}

LightTreePick primePickLightTree(
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
            if (leaf.index >= entryLimit) return primeInvalidLightTreePick();
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
            return primeInvalidLightTreePick();
        }
        float firstProbability = primeLightBranchProbability(firstMetrics, secondMetrics);
        if (!(firstProbability >= 0.0)) return primeInvalidLightTreePick();
        bool second = remapped >= firstProbability;
        float branchProbability = second ? 1.0 - firstProbability : firstProbability;
        if (!(branchProbability > 0.0)) return primeInvalidLightTreePick();
        remapped = second
                ? (remapped - firstProbability) / branchProbability
                : remapped / branchProbability;
        pdf *= branchProbability;
        nodeIndex = firstChild + uint(second);
    }
    return primeInvalidLightTreePick();
}

public AreaLightSelection primeInvalidAreaLightSelection() {
    AreaLightSelection result;
    result.sectionIndex = PRIME_NO_LIGHT_INDEX;
    result.emitterIndex = PRIME_NO_LIGHT_INDEX;
    result.pdf = 0.0;
    return result;
}

public AreaLightSelection primeSelectAreaLight(
        float3 surfacePosition,
        float3 receiverNormal,
        float treeSample) {
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t worldNodeAddress = sections[0].worldLightAddress;
    uint64_t worldLeafAddress = sections[0].worldLightLeafAddress;
    uint worldLeafCount = sections[0].worldLightLeafCount;
    if (worldNodeAddress == uint64_t(0)
            || worldLeafAddress == uint64_t(0)
            || worldLeafCount == 0u) {
        return primeInvalidAreaLightSelection();
    }
    LightNode* worldNodes = primeWorldLightNodes(worldNodeAddress);
    LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
    LightTreePick worldPick = primePickLightTree(
            worldNodes,
            worldLeaves,
            sections,
            uint64_t(0),
            PRIME_NO_LIGHT_INDEX,
            PRIME_LIGHT_LEAF_WORLD,
            0u,
            surfacePosition,
            receiverNormal,
            treeSample);
    if (worldPick.valid == 0u) {
        return primeInvalidAreaLightSelection();
    }
    uint64_t sectionLightAddress = sections[worldPick.index].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSelection();
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    LightTreePick sectionPick = primePickLightTree(
            (LightNode*)sectionBuffer[0].nodeAddress,
            (LightLeaf*)sectionBuffer[0].leafAddress,
            sections,
            sectionBuffer[0].emitterAddress,
            sectionBuffer[0].emitterCount,
            PRIME_LIGHT_LEAF_EMITTER,
            sectionBuffer[0].root,
            surfacePosition - sections[worldPick.index].translation,
            receiverNormal,
            worldPick.remapped);
    if (sectionPick.valid == 0u
            || sectionPick.index >= sectionBuffer[0].emitterCount) {
        return primeInvalidAreaLightSelection();
    }
    AreaLightSelection result;
    result.sectionIndex = worldPick.index;
    result.emitterIndex = sectionPick.index;
    result.pdf = worldPick.pdf * sectionPick.pdf;
    return result;
}
