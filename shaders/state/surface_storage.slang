#language slang 2026
module "state/surface_storage.slang";

import "prime_abi_types.slang";

// Cross-dispatch surface storage keeps all continuous values as their exact f32 bit patterns.
// Only discrete values with producer-enforced domains share words: material control is u8,
// MediumId is u16, optical control uses bits 0..24, and adjacent-interface control uses 0..17.
public static const uint PRIME_SURFACE_STORAGE_HIT_MASK = 0xffu;
public static const uint PRIME_SURFACE_STORAGE_MATERIAL_MASK = 0xffu;
public static const uint PRIME_SURFACE_STORAGE_MEDIUM_MASK = 0xffffu;
public static const uint PRIME_SURFACE_STORAGE_OPTICAL_MASK = 0x01ffffffu;
public static const uint PRIME_SURFACE_STORAGE_ADJACENT_MASK = 0x0003ffffu;

public struct PrimeSurfaceStorageRecord {
    public uint4 geometry;
    public uint4 identityBaseX;
    public uint4 baseYzOpacityRoughness;
    public uint4 opticalShadingNormal;
    public uint4 adjacentBasePositionX;
    public uint4 positionYzMotionAdjacent;
};

uint primePackSurfaceHitMaterialMedium(SurfaceInteraction surface) {
    return (surface.hitKind & PRIME_SURFACE_STORAGE_HIT_MASK)
            | (surface.materialControl & PRIME_SURFACE_STORAGE_MATERIAL_MASK) << 8u
            | (surface.mediumId & PRIME_SURFACE_STORAGE_MEDIUM_MASK) << 16u;
}

uint primePackSurfaceOpticalAdjacentHigh(SurfaceInteraction surface) {
    return (surface.opticalControl & PRIME_SURFACE_STORAGE_OPTICAL_MASK)
            | ((surface.adjacentInterfaceControl >> 16u) & 0x3u) << 25u;
}

uint primePackSurfaceAdjacentMediumControl(SurfaceInteraction surface) {
    return (surface.adjacentMediumId & PRIME_SURFACE_STORAGE_MEDIUM_MASK)
            | (surface.adjacentInterfaceControl & 0xffffu) << 16u;
}

public PrimeSurfaceStorageRecord primePackSurfaceStorageRecord(
        SurfaceInteraction surface) {
    PrimeSurfaceStorageRecord record;
    record.geometry = uint4(
            asuint(surface.t),
            asuint(surface.geometricNormal.x),
            asuint(surface.geometricNormal.y),
            asuint(surface.geometricNormal.z));
    record.identityBaseX = uint4(
            primePackSurfaceHitMaterialMedium(surface),
            surface.sectionIndex,
            surface.emitterIndex,
            asuint(surface.baseColor.x));
    record.baseYzOpacityRoughness = uint4(
            asuint(surface.baseColor.y),
            asuint(surface.baseColor.z),
            asuint(surface.opacity),
            asuint(surface.roughness));
    record.opticalShadingNormal = uint4(
            primePackSurfaceOpticalAdjacentHigh(surface),
            asuint(surface.shadingNormal.x),
            asuint(surface.shadingNormal.y),
            asuint(surface.shadingNormal.z));
    record.adjacentBasePositionX = uint4(
            asuint(surface.adjacentBaseColor.x),
            asuint(surface.adjacentBaseColor.y),
            asuint(surface.adjacentBaseColor.z),
            asuint(surface.position.x));
    record.positionYzMotionAdjacent = uint4(
            asuint(surface.position.y),
            asuint(surface.position.z),
            surface.motionZFlags,
            primePackSurfaceAdjacentMediumControl(surface));
    return record;
}

public SurfaceInteraction primeUnpackSurfaceStorageRecord(
        PrimeSurfaceStorageRecord record) {
    SurfaceInteraction surface;
    surface.t = asfloat(record.geometry.x);
    surface.geometricNormal = asfloat(record.geometry.yzw);
    uint identity = record.identityBaseX.x;
    surface.hitKind = identity & PRIME_SURFACE_STORAGE_HIT_MASK;
    surface.materialControl = (identity >> 8u) & PRIME_SURFACE_STORAGE_MATERIAL_MASK;
    surface.mediumId = identity >> 16u;
    surface.sectionIndex = record.identityBaseX.y;
    surface.emitterIndex = record.identityBaseX.z;
    surface.baseColor = float3(
            asfloat(record.identityBaseX.w),
            asfloat(record.baseYzOpacityRoughness.x),
            asfloat(record.baseYzOpacityRoughness.y));
    surface.textureLod = 0.0;
    surface.opacity = asfloat(record.baseYzOpacityRoughness.z);
    surface.roughness = asfloat(record.baseYzOpacityRoughness.w);
    uint opticalAdjacent = record.opticalShadingNormal.x;
    surface.opticalControl = opticalAdjacent & PRIME_SURFACE_STORAGE_OPTICAL_MASK;
    surface.shadingNormal = asfloat(record.opticalShadingNormal.yzw);
    surface.adjacentBaseColor = asfloat(record.adjacentBasePositionX.xyz);
    surface.position = float3(
            asfloat(record.adjacentBasePositionX.w),
            asfloat(record.positionYzMotionAdjacent.x),
            asfloat(record.positionYzMotionAdjacent.y));
    surface.motionZFlags = record.positionYzMotionAdjacent.z;
    uint adjacent = record.positionYzMotionAdjacent.w;
    surface.adjacentMediumId = adjacent & PRIME_SURFACE_STORAGE_MEDIUM_MASK;
    surface.adjacentInterfaceControl = (adjacent >> 16u)
            | ((opticalAdjacent >> 25u) & 0x3u) << 16u;
    return surface;
}
