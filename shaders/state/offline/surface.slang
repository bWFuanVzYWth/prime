#language slang 2026
module "state/offline/surface.slang";

import "prime_offline_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";
import "state/offline/surface_record.slang";
import "service/trace/surface.slang";
import "contract/path.slang";

public static const uint PRIME_OFFLINE_SURFACE_WORDS =
        PRIME_OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE / 4u;

public uint primeOfflineSurfaceWord(uint pathIndex) {
    return pathIndex * PRIME_OFFLINE_SURFACE_WORDS;
}

// Offline does not consume texture LOD after traversal. Source triangle identity remains exact;
// the scratch record therefore needs 25 words instead of the realtime 28-word record.
public void primeStoreOfflineSurface(uint pathIndex, SurfaceInteraction surface) {
    uint word = primeOfflineSurfaceWord(pathIndex);
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeOfflineQueue[word + 4u] = PRIME_HIT_NONE;
        return;
    }
    PrimeOfflineSurfaceScratch scratch = primePackOfflineSurfaceScratch(surface);
    primeOfflineQueue[word + 0u] = scratch.geometry.x;
    primeOfflineQueue[word + 1u] = scratch.geometry.y;
    primeOfflineQueue[word + 2u] = scratch.geometry.z;
    primeOfflineQueue[word + 3u] = scratch.geometry.w;
    primeOfflineQueue[word + 4u] = scratch.identity.x;
    primeOfflineQueue[word + 5u] = scratch.identity.y;
    primeOfflineQueue[word + 6u] = scratch.identity.z;
    primeOfflineQueue[word + 7u] = scratch.identity.w;
    primeOfflineQueue[word + 8u] = scratch.baseOpacity.x;
    primeOfflineQueue[word + 9u] = scratch.baseOpacity.y;
    primeOfflineQueue[word + 10u] = scratch.baseOpacity.z;
    primeOfflineQueue[word + 11u] = scratch.baseOpacity.w;
    primeOfflineQueue[word + 12u] = scratch.shadingRoughness.x;
    primeOfflineQueue[word + 13u] = scratch.shadingRoughness.y;
    primeOfflineQueue[word + 14u] = scratch.shadingRoughness.z;
    primeOfflineQueue[word + 15u] = scratch.shadingRoughness.w;
    primeOfflineQueue[word + 16u] = scratch.materialAdjacent.x;
    primeOfflineQueue[word + 17u] = scratch.materialAdjacent.y;
    primeOfflineQueue[word + 18u] = scratch.materialAdjacent.z;
    primeOfflineQueue[word + 19u] = scratch.materialAdjacent.w;
    primeOfflineQueue[word + 20u] = scratch.positionControl.x;
    primeOfflineQueue[word + 21u] = scratch.positionControl.y;
    primeOfflineQueue[word + 22u] = scratch.positionControl.z;
    primeOfflineQueue[word + 23u] = scratch.positionControl.w;
    primeOfflineQueue[word + 24u] = scratch.motion;
}

public SurfaceInteraction primeLoadOfflineSurface(uint pathIndex) {
    uint word = primeOfflineSurfaceWord(pathIndex);
    uint hitKind = primeOfflineQueue[word + 4u] & PRIME_HIT_KIND_MASK;
    if (hitKind == PRIME_HIT_NONE) {
        return primeMissSurfaceInteraction(
                primeOfflinePaths[pathIndex].transport.sourceAndPathControl.xy);
    }
    PrimeOfflineSurfaceScratch scratch;
    scratch.geometry = uint4(
            primeOfflineQueue[word + 0u],
            primeOfflineQueue[word + 1u],
            primeOfflineQueue[word + 2u],
            primeOfflineQueue[word + 3u]);
    scratch.identity = uint4(
            hitKind,
            primeOfflineQueue[word + 5u],
            primeOfflineQueue[word + 6u],
            primeOfflineQueue[word + 7u]);
    scratch.baseOpacity = uint4(
            primeOfflineQueue[word + 8u],
            primeOfflineQueue[word + 9u],
            primeOfflineQueue[word + 10u],
            primeOfflineQueue[word + 11u]);
    scratch.shadingRoughness = uint4(
            primeOfflineQueue[word + 12u],
            primeOfflineQueue[word + 13u],
            primeOfflineQueue[word + 14u],
            primeOfflineQueue[word + 15u]);
    scratch.materialAdjacent = uint4(
            primeOfflineQueue[word + 16u],
            primeOfflineQueue[word + 17u],
            primeOfflineQueue[word + 18u],
            primeOfflineQueue[word + 19u]);
    scratch.positionControl = uint4(
            primeOfflineQueue[word + 20u],
            primeOfflineQueue[word + 21u],
            primeOfflineQueue[word + 22u],
            primeOfflineQueue[word + 23u]);
    scratch.motion = primeOfflineQueue[word + 24u];
    SurfaceInteraction surface = primeUnpackOfflineSurfaceScratch(scratch);
    return surface;
}
