#language slang 2026
module "state/offline/surface_record.slang";

import "prime_abi_types.slang";

// Raw uint lanes make retained f32 fields and the packed source identity bit-exact across stages.
public struct PrimeOfflineSurfaceScratch {
    public uint4 geometry;
    public uint4 identity;
    public uint4 baseOpacity;
    public uint4 shadingRoughness;
    public uint4 materialAdjacent;
    public uint4 positionControl;
    public uint motion;
    public uint2 mediumIds;
};

public PrimeOfflineSurfaceScratch primePackOfflineSurfaceScratch(
        SurfaceInteraction surface) {
    PrimeOfflineSurfaceScratch scratch;
    scratch.geometry = uint4(
            asuint(surface.t),
            asuint(surface.geometricNormal.x),
            asuint(surface.geometricNormal.y),
            asuint(surface.geometricNormal.z));
    scratch.identity = uint4(
            surface.hitKind,
            surface.materialControl,
            surface.sectionIndex,
            surface.emitterIndex);
    scratch.baseOpacity = uint4(
            asuint(surface.baseColor.x),
            asuint(surface.baseColor.y),
            asuint(surface.baseColor.z),
            asuint(surface.opacity));
    scratch.shadingRoughness = uint4(
            asuint(surface.shadingNormal.x),
            asuint(surface.shadingNormal.y),
            asuint(surface.shadingNormal.z),
            asuint(surface.roughness));
    scratch.materialAdjacent = uint4(
            surface.opticalControl,
            asuint(surface.adjacentBaseColor.x),
            asuint(surface.adjacentBaseColor.y),
            asuint(surface.adjacentBaseColor.z));
    scratch.positionControl = uint4(
            surface.adjacentInterfaceControl,
            asuint(surface.position.x),
            asuint(surface.position.y),
            asuint(surface.position.z));
    scratch.motion = surface.motionZFlags;
    scratch.mediumIds = uint2(surface.mediumId, surface.adjacentMediumId);
    return scratch;
}

public SurfaceInteraction primeUnpackOfflineSurfaceScratch(
        PrimeOfflineSurfaceScratch scratch) {
    SurfaceInteraction surface;
    surface.t = asfloat(scratch.geometry.x);
    surface.geometricNormal = asfloat(scratch.geometry.yzw);
    surface.hitKind = scratch.identity.x;
    surface.materialControl = scratch.identity.y;
    surface.sectionIndex = scratch.identity.z;
    surface.emitterIndex = scratch.identity.w;
    surface.baseColor = asfloat(scratch.baseOpacity.xyz);
    surface.textureLod = 0.0;
    surface.opacity = asfloat(scratch.baseOpacity.w);
    surface.shadingNormal = asfloat(scratch.shadingRoughness.xyz);
    surface.roughness = asfloat(scratch.shadingRoughness.w);
    surface.opticalControl = scratch.materialAdjacent.x;
    surface.motionZFlags = scratch.motion;
    surface.mediumId = scratch.mediumIds.x;
    surface.adjacentMediumId = scratch.mediumIds.y;
    surface.adjacentBaseColor = asfloat(scratch.materialAdjacent.yzw);
    surface.adjacentInterfaceControl = scratch.positionControl.x;
    surface.position = asfloat(scratch.positionControl.yzw);
    return surface;
}
