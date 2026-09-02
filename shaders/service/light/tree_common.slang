#language slang 2026
module "service/light/tree_common.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/light_tree.slang";
import "service/light/emission.slang";

public static const uint PRIME_LIGHT_LEAF_FLAG = 0x80000000u;
public static const uint PRIME_LIGHT_INDEX_MASK = 0x7fffffffu;
public static const uint PRIME_LIGHT_TREE_MAX_DEPTH = 27u;
public static const uint PRIME_LIGHT_LEAF_WORLD = 0u;
public static const uint PRIME_LIGHT_LEAF_EMITTER = 1u;
static const uint PRIME_LIGHT_NODE_WORDS = PRIME_LIGHT_NODE_SIZE / 4u;
static const uint PRIME_LIGHT_NODE_DIRECTION_WORD = 4u;
static const uint PRIME_LIGHT_NODE_CHILD_WORD = 5u;

public LightNode* primeWorldLightNodes(uint64_t worldLightAddress) {
    return (LightNode*)worldLightAddress;
}

public uint primeLoadLightNodeWord(LightNode* nodes, uint index, uint word) {
    // LightNode is a fixed 32-byte ABI. Raw words keep hot scalar fields as narrow loads.
    return ((uint*)nodes)[index * PRIME_LIGHT_NODE_WORDS + word];
}

public uint primeLoadLightNodeChildOrLeaf(LightNode* nodes, uint index) {
    return primeLoadLightNodeWord(nodes, index, PRIME_LIGHT_NODE_CHILD_WORD);
}

public float2 primeLoadLightNodeMetrics(
        LightNode* nodes,
        uint index,
        float3 point,
        float3 receiverNormal) {
    float4 centroidPower = nodes[index].centroidPower;
    return primeLightNodeMetrics(
            centroidPower.xyz,
            centroidPower.w,
            point,
            receiverNormal,
            primeLoadLightNodeWord(nodes, index, PRIME_LIGHT_NODE_DIRECTION_WORD));
}

public float2 primeEmitterMetrics(
        LightEmitter emitter,
        float power,
        float3 point,
        float3 receiverNormal) {
    float3 first = emitter.cornerArea.xyz;
    float3 second = first + emitter.edgeOneScale.xyz;
    float3 third = first + emitter.edgeTwoPower.xyz;
    float3 boundsMin = min(first, min(second, third));
    float3 boundsMax = max(first, max(second, third));
    float3 closest = clamp(point, boundsMin, boundsMax);
    float3 delta = point - closest;
    float boundsDistanceSquared = dot(delta, delta);
    float inverseDistance = boundsDistanceSquared > 0.0
            ? rsqrt(boundsDistanceSquared)
            : 0.0;
    float receiverBound = primeLightReceiverCosineBoundWithInverse(
            boundsMin,
            boundsMax,
            point,
            receiverNormal,
            boundsDistanceSquared,
            inverseDistance);
    float emitterBound = primeLightEmissionAxisCosineBound(
            boundsMin,
            boundsMax,
            point,
            emitter.normal,
            boundsDistanceSquared,
            inverseDistance);
    if ((emitter.metadata.z & PRIME_EMITTER_TWO_SIDED) != 0u) {
        emitterBound = 0.5 * max(
                emitterBound,
                primeLightEmissionAxisCosineBound(
                        boundsMin,
                        boundsMax,
                        point,
                        -emitter.normal,
                        boundsDistanceSquared,
                        inverseDistance));
    }
    float angularImportance = clamp(receiverBound * emitterBound, 0.0, 1.0);
    float weightedPower = max(power, 0.0)
            * (PRIME_LIGHT_RECEIVER_COSINE_FLOOR
                    + (1.0 - PRIME_LIGHT_RECEIVER_COSINE_FLOOR)
                            * angularImportance);
    float targetDistanceSquared = boundsDistanceSquared;
    if (targetDistanceSquared == 0.0) {
        float3 centroidDelta = point - (first + second + third) / 3.0;
        targetDistanceSquared = dot(centroidDelta, centroidDelta);
    }
    if (targetDistanceSquared == 0.0) {
        // Valid emissive triangles have nonzero extent; this preserves the exact centroid case.
        float3 edgeOne = second - first;
        float3 edgeTwo = third - first;
        targetDistanceSquared = dot(edgeOne, edgeOne) + dot(edgeTwo, edgeTwo);
    }
    return float2(targetDistanceSquared, weightedPower);
}

public float2 primeLoadEmitterMetrics(
        LightEmitter* emitters,
        LightLeaf leaf,
        float3 point,
        float3 receiverNormal) {
    return primeEmitterMetrics(
            emitters[leaf.index],
            leaf.power,
            point,
            receiverNormal);
}

bool primeLoadWorldLeafEntryMetrics(
        SectionRecord* sections,
        LightLeaf leaf,
        float3 point,
        float3 receiverNormal,
        out float2 metrics) {
    SectionRecord section = sections[leaf.index];
    if (section.lightAddress == uint64_t(0)) {
        metrics = float2(0.0);
        return false;
    }
    SectionLightHeader* header = (SectionLightHeader*)section.lightAddress;
    if (header[0].nodeAddress == uint64_t(0) || header[0].emitterCount == 0u) {
        metrics = float2(0.0);
        return false;
    }
    metrics = primeLoadLightNodeMetrics(
            (LightNode*)header[0].nodeAddress,
            header[0].root,
            point - section.translation,
            receiverNormal);
    return true;
}

// Terminal children use their exact section root or emitter bounds in the final branch decision.
// Internal children retain the compact centroid target. Selection and reverse MIS share this load.
public bool primeLoadLightChildMetrics(
        LightNode* nodes,
        LightLeaf* leaves,
        SectionRecord* sections,
        uint64_t emitterAddress,
        uint entryLimit,
        uint leafMode,
        uint nodeIndex,
        uint childOrLeaf,
        float3 point,
        float3 receiverNormal,
        out float2 metrics) {
    metrics = float2(0.0);
    if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) == 0u) {
        metrics = primeLoadLightNodeMetrics(nodes, nodeIndex, point, receiverNormal);
        return true;
    }
    LightLeaf leaf = leaves[childOrLeaf & PRIME_LIGHT_INDEX_MASK];
    if (leaf.index >= entryLimit) return false;
    if (leafMode == PRIME_LIGHT_LEAF_WORLD) {
        return primeLoadWorldLeafEntryMetrics(
                sections, leaf, point, receiverNormal, metrics);
    }
    if (leafMode == PRIME_LIGHT_LEAF_EMITTER) {
        metrics = primeLoadEmitterMetrics(
                (LightEmitter*)emitterAddress, leaf, point, receiverNormal);
        return true;
    }
    return false;
}
