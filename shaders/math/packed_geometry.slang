#language slang 2026
module "math/packed_geometry.slang";

import glsl;
import "prime_abi_types.slang";

public static const uint PRIME_HIT_KIND_MASK = 0xffu;
public static const uint PRIME_SURFACE_TRIANGLE_MASK = 0x7fffffffu;
public static const uint PRIME_SURFACE_MOTION_FLAG = 0x80000000u;

public uint primePackSurfaceMotion(uint triangleIndex, bool hasMotion)
{
    return (triangleIndex & PRIME_SURFACE_TRIANGLE_MASK)
            | (hasMotion ? PRIME_SURFACE_MOTION_FLAG : 0u);
}

public uint primePackedSurfaceTriangleIndex(uint packedHitKind)
{
    return packedHitKind & PRIME_SURFACE_TRIANGLE_MASK;
}

public uint primeSurfaceTriangleIndex(SurfaceInteraction surface)
{
    return primePackedSurfaceTriangleIndex(surface.motionZFlags);
}

public uint primeTriangleCount(uint primitiveCount, uint macroTriangleBase)
{
    return macroTriangleBase
            + 2u * (primitiveCount - macroTriangleBase);
}

public uint primeGlobalTriangleIndexValue(
        uint geometryIndex,
        uint primitiveIndex,
        uint opaquePrimitiveCount,
        uint cutoutPrimitiveCount,
        uint opaqueMacroTriangleBase,
        uint cutoutMacroTriangleBase)
{
    uint base = geometryIndex == 0u
            ? 0u
            : primeTriangleCount(
                    opaquePrimitiveCount, opaqueMacroTriangleBase);
    if (geometryIndex == 2u) {
        base += primeTriangleCount(
                cutoutPrimitiveCount, cutoutMacroTriangleBase);
    }
    return base + primitiveIndex;
}

public uint primeGlobalPrimitiveIndexFromTriangleValue(
        uint triangleIndex,
        uint opaquePrimitiveCount,
        uint cutoutPrimitiveCount,
        uint opaqueMacroTriangleBase,
        uint cutoutMacroTriangleBase,
        uint transmissiveMacroTriangleBase)
{
    uint opaqueTriangleCount = primeTriangleCount(
            opaquePrimitiveCount, opaqueMacroTriangleBase);
    uint cutoutTriangleCount = primeTriangleCount(
            cutoutPrimitiveCount, cutoutMacroTriangleBase);
    uint primitiveBase = 0u;
    uint localTriangle = triangleIndex;
    uint macroTriangleBase = opaqueMacroTriangleBase;
    if (triangleIndex >= opaqueTriangleCount) {
        primitiveBase = opaquePrimitiveCount;
        localTriangle -= opaqueTriangleCount;
        macroTriangleBase = cutoutMacroTriangleBase;
        if (localTriangle >= cutoutTriangleCount) {
            primitiveBase += cutoutPrimitiveCount;
            localTriangle -= cutoutTriangleCount;
            macroTriangleBase = transmissiveMacroTriangleBase;
        }
    }
    uint localPrimitive = localTriangle < macroTriangleBase
            ? localTriangle
            : macroTriangleBase + ((localTriangle - macroTriangleBase) >> 1u);
    return primitiveBase + localPrimitive;
}

public float2 primeUnpackUv(uint packedValue)
{
    uint2 fixedUv = uint2(packedValue & 0xffffu, packedValue >> 16u);
    float2 uv = float2(fixedUv) * (1.0 / 65536.0);
    return float2(
            fixedUv.x == 0xffffu ? 1.0 : uv.x,
            fixedUv.y == 0xffffu ? 1.0 : uv.y);
}

public float4 primeUnpackTint(uint packedValue)
{
    return unpackUnorm4x8ToFloat(packedValue);
}

public bool primeSurfaceHasMotion(SurfaceInteraction surface)
{
    return (surface.motionZFlags & PRIME_SURFACE_MOTION_FLAG) != 0u;
}

public uint primeSurfaceEmitterIndex(SurfaceInteraction surface)
{
    // Dynamic geometry cannot be a light-tree emitter. Its emitter lane carries the triangle id
    // needed to reconstruct the previous surface instead.
    return primeSurfaceHasMotion(surface) ? 0xffffffffu : surface.emitterIndex;
}

public struct PrimePreviousDynamicSurface
{
    public float3 position;
    public float3 geometricNormal;
};

public PrimePreviousDynamicSurface primePreviousDynamicSurfaceValue(
        float3 first,
        float3 second,
        float3 third,
        float3 translation,
        float2 barycentrics)
{
    float3 barycentric = float3(
            1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x,
            barycentrics.y);
    PrimePreviousDynamicSurface previous;
    previous.position = first * barycentric.x
            + second * barycentric.y
            + third * barycentric.z
            + translation;
    float3 normal = cross(second - first, third - first);
    float normalLengthSquared = dot(normal, normal);
    previous.geometricNormal = normalLengthSquared > 0.0
            ? normal * rsqrt(normalLengthSquared)
            : float3(0.0, 1.0, 0.0);
    return previous;
}

public PrimePreviousDynamicSurface primePreviousDynamicSurface(
        SectionRecord section, uint triangleIndex, float2 barycentrics)
{
    float* positions = (float*)section.lightAddress;
    uint vertexBase = triangleIndex * 9u;
    float3 first = float3(
            positions[vertexBase],
            positions[vertexBase + 1u],
            positions[vertexBase + 2u]);
    float3 second = float3(
            positions[vertexBase + 3u],
            positions[vertexBase + 4u],
            positions[vertexBase + 5u]);
    float3 third = float3(
            positions[vertexBase + 6u],
            positions[vertexBase + 7u],
            positions[vertexBase + 8u]);
    return primePreviousDynamicSurfaceValue(
            first, second, third, section.translation, barycentrics);
}

public PrimePreviousDynamicSurface primeSurfacePreviousDynamicSurface(
        SurfaceInteraction surface, uint64_t sectionTableAddress)
{
    SectionRecord section = ((SectionRecord*)sectionTableAddress)[
            surface.sectionIndex];
    return primePreviousDynamicSurface(
            section,
            primeSurfaceTriangleIndex(surface),
            float2(asfloat(surface.emitterIndex), surface.textureLod));
}

public PrimePreviousDynamicSurface primeSurfacePrevious(
        SurfaceInteraction surface, uint64_t sectionTableAddress)
{
    if (primeSurfaceHasMotion(surface)) {
        PrimePreviousDynamicSurface previous = primeSurfacePreviousDynamicSurface(
                surface, sectionTableAddress);
        if (dot(previous.geometricNormal, surface.geometricNormal) < 0.0) {
            previous.geometricNormal = -previous.geometricNormal;
        }
        return previous;
    }
    PrimePreviousDynamicSurface previous;
    previous.position = surface.position;
    previous.geometricNormal = surface.geometricNormal;
    return previous;
}

public float3 primeSurfacePreviousPosition(
        SurfaceInteraction surface, uint64_t sectionTableAddress)
{
    return primeSurfacePrevious(surface, sectionTableAddress).position;
}

float2 primeSignNotZero(float2 value)
{
    return float2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
}

public float3 primeUnpackOctahedralUnitVector(uint packedValue)
{
    float2 encoded = unpackSnorm2x16ToFloat(packedValue);
    float3 normal = float3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return normalize(normal);
}

public uint primePackOctahedralUnitVector(float3 value)
{
    float3 normal = normalize(value);
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-20);
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return packSnorm2x16(clamp(normal.xy, float2(-1.0), float2(1.0)));
}

public bool primeDecodeTexturePageExtent(uint extentCode, out int2 extent)
{
    if (extentCode == 0xffffu) {
        extent = int2(0);
        return false;
    }
    uint width = (extentCode & 0xfffu) + 1u;
    uint heightCode = extentCode >> 12u & 0xfu;
    extent = int2(width, heightCode == 0xfu ? width : 1u << heightCode);
    return true;
}

public uint primeMediumId(uint packedIdentity)
{
    return packedIdentity & 0xffffu;
}

public uint primeMaterialId(uint packedIdentity)
{
    return packedIdentity >> 16u;
}
