#language slang 2026
module "state/realtime/surface.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "model/material/defaults.slang";
import "service/trace/surface.slang";
import "state/surface_storage.slang";
import "contract/path.slang";

public static const uint PRIME_WAVEFRONT_SURFACE_LANE_WORDS = 4u;

public void primeStoreWavefrontSurfaceLane(uint base, uint laneIndex, uint4 lane) {
    uint word = base + laneIndex * PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    primeWavefrontQueue[word + 0u] = lane.x;
    primeWavefrontQueue[word + 1u] = lane.y;
    primeWavefrontQueue[word + 2u] = lane.z;
    primeWavefrontQueue[word + 3u] = lane.w;
}

public uint4 primeLoadWavefrontSurfaceLane(uint base, uint laneIndex) {
    uint word = base + laneIndex * PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    return uint4(
            primeWavefrontQueue[word + 0u],
            primeWavefrontQueue[word + 1u],
            primeWavefrontQueue[word + 2u],
            primeWavefrontQueue[word + 3u]);
}

// Area guides and the two path-indexed hit arrays occupy separate contiguous regions. Keeping the
// surface arrays branch-major preserves coalesced ordinary-path access while both fixed transparent
// continuations can safely remain in flight. The shared 96-byte exact surface core is followed by
// realtime's phase-specific f32 texture LOD.
public uint primeWavefrontSurfaceWord(uint pathIndex) {
    uint guideWords = primeWavefrontPixelCount() * PRIME_WAVEFRONT_AREA_GUIDE_WORDS;
    return guideWords
            + pathIndex * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
}

public void primeStoreWavefrontSurface(uint pathIndex, SurfaceInteraction surface) {
    uint base = primeWavefrontSurfaceWord(pathIndex);
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeWavefrontQueue[base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS] =
                PRIME_HIT_NONE;
        return;
    }
    PrimeSurfaceStorageRecord record = primePackSurfaceStorageRecord(surface);
    primeStoreWavefrontSurfaceLane(base, 0u, record.geometry);
    primeStoreWavefrontSurfaceLane(base, 1u, record.identityBaseX);
    primeStoreWavefrontSurfaceLane(base, 2u, record.baseYzOpacityRoughness);
    primeStoreWavefrontSurfaceLane(base, 3u, record.opticalShadingNormal);
    primeStoreWavefrontSurfaceLane(base, 4u, record.adjacentBasePositionX);
    primeStoreWavefrontSurfaceLane(base, 5u, record.positionYzMotionAdjacent);
    primeWavefrontQueue[base + 24u] = asuint(surface.textureLod);
}

public SurfaceInteraction primeLoadWavefrontSurface(
        uint pathIndex, uint2 missSourcePrimitive) {
    uint base = primeWavefrontSurfaceWord(pathIndex);
    uint hitKind = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS]
            & PRIME_HIT_KIND_MASK;
    if (hitKind == PRIME_HIT_NONE) {
        return primeMissSurfaceInteraction(missSourcePrimitive);
    }
    PrimeSurfaceStorageRecord record;
    record.geometry = primeLoadWavefrontSurfaceLane(base, 0u);
    record.identityBaseX = primeLoadWavefrontSurfaceLane(base, 1u);
    record.baseYzOpacityRoughness = primeLoadWavefrontSurfaceLane(base, 2u);
    record.opticalShadingNormal = primeLoadWavefrontSurfaceLane(base, 3u);
    record.adjacentBasePositionX = primeLoadWavefrontSurfaceLane(base, 4u);
    record.positionYzMotionAdjacent = primeLoadWavefrontSurfaceLane(base, 5u);
    SurfaceInteraction surface = primeUnpackSurfaceStorageRecord(record);
    surface.textureLod = asfloat(primeWavefrontQueue[base + 24u]);
    return surface;
}

public SurfaceInteraction primeLoadWavefrontSurface(uint pathIndex) {
    return primeLoadWavefrontSurface(
            pathIndex,
            primeWavefrontPaths[pathIndex]
                    .transport.sourceAndPathControl.xy);
}
