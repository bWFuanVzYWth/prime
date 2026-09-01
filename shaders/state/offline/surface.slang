#language slang 2026
module "state/offline/surface.slang";

import "prime_offline_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "state/offline/contract.slang";
import "state/offline/path_storage.slang";
import "state/offline/index.slang";
import "state/surface_storage.slang";
import "service/trace/surface.slang";
import "contract/path.slang";

public static const uint PRIME_OFFLINE_SURFACE_WORDS =
        PRIME_OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE / 4u;

public uint primeOfflineSurfaceWord(uint pathIndex) {
    return pathIndex * PRIME_OFFLINE_SURFACE_WORDS;
}

void primeStoreOfflineSurfaceLane(uint base, uint laneIndex, uint4 lane) {
    uint word = base + laneIndex * 4u;
    primeOfflineQueue[word + 0u] = lane.x;
    primeOfflineQueue[word + 1u] = lane.y;
    primeOfflineQueue[word + 2u] = lane.z;
    primeOfflineQueue[word + 3u] = lane.w;
}

uint4 primeLoadOfflineSurfaceLane(uint base, uint laneIndex) {
    uint word = base + laneIndex * 4u;
    return uint4(
            primeOfflineQueue[word + 0u],
            primeOfflineQueue[word + 1u],
            primeOfflineQueue[word + 2u],
            primeOfflineQueue[word + 3u]);
}

// Offline does not consume texture LOD after traversal. The exact continuous values and bounded
// integer identities therefore fit the shared 24-word surface core.
public void primeStoreOfflineSurface(uint pathIndex, SurfaceInteraction surface) {
    uint word = primeOfflineSurfaceWord(pathIndex);
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeOfflineQueue[word + 4u] = PRIME_HIT_NONE;
        return;
    }
    PrimeSurfaceStorageRecord record = primePackSurfaceStorageRecord(surface);
    primeStoreOfflineSurfaceLane(word, 0u, record.geometry);
    primeStoreOfflineSurfaceLane(word, 1u, record.identityBaseX);
    primeStoreOfflineSurfaceLane(word, 2u, record.baseYzOpacityRoughness);
    primeStoreOfflineSurfaceLane(word, 3u, record.opticalShadingNormal);
    primeStoreOfflineSurfaceLane(word, 4u, record.adjacentBasePositionX);
    primeStoreOfflineSurfaceLane(word, 5u, record.positionYzMotionAdjacent);
}

public SurfaceInteraction primeLoadOfflineSurface(uint pathIndex) {
    uint word = primeOfflineSurfaceWord(pathIndex);
    uint hitKind = primeOfflineQueue[word + 4u] & PRIME_HIT_KIND_MASK;
    if (hitKind == PRIME_HIT_NONE) {
        return primeMissSurfaceInteraction(
                primeOfflineSourcePrimitive(
                        primeOfflinePaths[pathIndex].transport));
    }
    PrimeSurfaceStorageRecord record;
    record.geometry = primeLoadOfflineSurfaceLane(word, 0u);
    record.identityBaseX = primeLoadOfflineSurfaceLane(word, 1u);
    record.baseYzOpacityRoughness = primeLoadOfflineSurfaceLane(word, 2u);
    record.opticalShadingNormal = primeLoadOfflineSurfaceLane(word, 3u);
    record.adjacentBasePositionX = primeLoadOfflineSurfaceLane(word, 4u);
    record.positionYzMotionAdjacent = primeLoadOfflineSurfaceLane(word, 5u);
    return primeUnpackSurfaceStorageRecord(record);
}
