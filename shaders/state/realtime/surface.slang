#language slang 2026
module "state/realtime/surface.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "model/material/defaults.slang";
import "service/trace/surface.slang";
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
// continuations can safely remain in flight. Position is retained as f32 hit output rather than
// reconstructed from ray arithmetic. hitKind occupies the low payload byte while motionZFlags
// occupies the remaining bits, so their original packed word is also lossless.
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
    primeStoreWavefrontSurfaceLane(base, 0u, uint4(
            asuint(surface.t),
            asuint(surface.geometricNormal.x),
            asuint(surface.geometricNormal.y),
            asuint(surface.geometricNormal.z)));
    primeStoreWavefrontSurfaceLane(base, 1u, uint4(
            surface.hitKind,
            surface.materialControl,
            surface.sectionIndex,
            surface.emitterIndex));
    primeStoreWavefrontSurfaceLane(base, 2u, uint4(
            asuint(surface.baseColor.x),
            asuint(surface.baseColor.y),
            asuint(surface.baseColor.z),
            asuint(surface.textureLod)));
    primeStoreWavefrontSurfaceLane(base, 3u, uint4(
            asuint(surface.opacity),
            asuint(surface.roughness),
            surface.opticalControl,
            0u));
    primeStoreWavefrontSurfaceLane(base, 4u, uint4(
            asuint(surface.shadingNormal.x),
            asuint(surface.shadingNormal.y),
            asuint(surface.shadingNormal.z),
            asuint(surface.adjacentBaseColor.x)));
    primeStoreWavefrontSurfaceLane(base, 5u, uint4(
            asuint(surface.adjacentBaseColor.y),
            asuint(surface.adjacentBaseColor.z),
            surface.adjacentInterfaceControl,
            asuint(surface.position.x)));
    primeStoreWavefrontSurfaceLane(base, 6u, uint4(
            asuint(surface.position.y),
            asuint(surface.position.z),
            surface.motionZFlags,
            0u));
}

public SurfaceInteraction primeLoadWavefrontSurface(uint pathIndex) {
    uint base = primeWavefrontSurfaceWord(pathIndex);
    uint hitKind = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS]
            & PRIME_HIT_KIND_MASK;
    if (hitKind == PRIME_HIT_NONE) {
        return primeMissSurfaceInteraction(
                primeWavefrontPaths[pathIndex]
                        .transport.sourceAndPathControl.xy);
    }
    SurfaceInteraction surface;
    uint4 lane = primeLoadWavefrontSurfaceLane(base, 0u);
    surface.t = asfloat(lane.x);
    surface.geometricNormal = float3(
            asfloat(lane.y), asfloat(lane.z), asfloat(lane.w));
    surface.hitKind = hitKind;
    surface.materialControl = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS + 1u];
    surface.sectionIndex = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS + 2u];
    surface.emitterIndex = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS + 3u];
    lane = primeLoadWavefrontSurfaceLane(base, 2u);
    surface.baseColor = float3(
            asfloat(lane.x), asfloat(lane.y), asfloat(lane.z));
    surface.textureLod = asfloat(lane.w);
    lane = primeLoadWavefrontSurfaceLane(base, 3u);
    surface.opacity = asfloat(lane.x);
    surface.roughness = asfloat(lane.y);
    surface.opticalControl = lane.z;
    lane = primeLoadWavefrontSurfaceLane(base, 4u);
    surface.shadingNormal = asfloat(lane.xyz);
    float adjacentX = asfloat(lane.w);
    lane = primeLoadWavefrontSurfaceLane(base, 5u);
    surface.adjacentBaseColor = float3(
            adjacentX, asfloat(lane.x), asfloat(lane.y));
    surface.adjacentInterfaceControl = lane.z;
    float positionX = asfloat(lane.w);
    lane = primeLoadWavefrontSurfaceLane(base, 6u);
    surface.position = float3(positionX, asfloat(lane.x), asfloat(lane.y));
    surface.motionZFlags = lane.z;
    return surface;
}
