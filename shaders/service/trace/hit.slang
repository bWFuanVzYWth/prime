#language slang 2026
module "service/trace/hit.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "model/material/defaults.slang";
import "model/material/medium_boundary.slang";
import "service/material/access.slang";

import "contract/path.slang";


public struct PrimeSurfaceRelation {
    public uint control;
    public uint referenceUv;
    public uint tint;
    public uint mediumId;
    public uint materialId;
    public PrimitiveRecord material;
};

public static const uint PRIME_SURFACE_RELATION_KIND_MASK = 0xfu;
public static const uint PRIME_SURFACE_RELATION_BOUNDARY = 1u;
static const uint PRIME_SURFACE_RELATION_OVERLAY = 2u;
static const uint PRIME_SURFACE_RELATION_BILATERAL = 3u;
public static const uint PRIME_SURFACE_RELATION_MICRO_GAP_ELIGIBLE = 1u << 4u;
static const uint PRIME_SURFACE_RELATION_POSITIVE_ONLY = 1u << 4u;

public SectionRecord primeSection() {
    return ((SectionRecord*)primePush.sectionTableAddress)[InstanceID()];
}

uint primeLocalPrimitiveIndex(SectionRecord section) {
    // BLAS geometries and the primitive buffer share one semantic order:
    // opaque, alpha-tested cutout, then transmissive. Macro rectangles occupy adjacent triangle
    // pairs at each geometry tail, where both triangles share one projected-UV primitive record.
    uint macroBase = GeometryIndex() == 0
            ? section.opaqueMacroTriangleBase
            : (GeometryIndex() == 1
                    ? section.cutoutMacroTriangleBase
                    : section.transmissiveMacroTriangleBase);
    return PrimitiveIndex() < macroBase
            ? PrimitiveIndex()
            : macroBase + ((PrimitiveIndex() - macroBase) >> 1u);
}

public PrimitiveRecord primePrimitive(SectionRecord section) {
    uint base = GeometryIndex() == 0
            ? 0u
            : (GeometryIndex() == 1 ? section.cutoutBase : section.transmissiveBase);
    uint primitiveIndex = primeLocalPrimitiveIndex(section);
    PrimitiveRecord primitive =
            ((PrimitiveRecord*)section.primitiveAddress)[base + primitiveIndex];
    // Baked material primitives may lerp an untinted base with a tinted overlay in one BLAS.
    // Their instance tint is applied after per-primitive color selection in material.slang.
    if ((section.instanceTint & 0x80000000u) != 0u
            && !primeUsesBakedMaterial(primitive)) {
        primitive.tint = (primitive.tint & 0xff000000u)
                | (section.instanceTint & 0x00ffffffu);
    }
    return primitive;
}

public uint primeGlobalPrimitiveIndex(SectionRecord section) {
    uint base = GeometryIndex() == 0
            ? 0u
            : (GeometryIndex() == 1 ? section.cutoutBase : section.transmissiveBase);
    return base + primeLocalPrimitiveIndex(section);
}

public uint primeGlobalPrimitiveIndexFromTriangle(
        SectionRecord section, uint triangleIndex) {
    uint opaquePrimitiveCount = section.cutoutBase;
    uint cutoutPrimitiveCount = section.transmissiveBase - section.cutoutBase;
    return primeGlobalPrimitiveIndexFromTriangleValue(
            triangleIndex,
            opaquePrimitiveCount,
            cutoutPrimitiveCount,
            section.opaqueMacroTriangleBase,
            section.cutoutMacroTriangleBase,
            section.transmissiveMacroTriangleBase);
}

public uint primeGlobalTriangleIndex(SectionRecord section) {
    uint opaquePrimitiveCount = section.cutoutBase;
    uint cutoutPrimitiveCount = section.transmissiveBase - section.cutoutBase;
    return primeGlobalTriangleIndexValue(
            GeometryIndex(),
            PrimitiveIndex(),
            opaquePrimitiveCount,
            cutoutPrimitiveCount,
            section.opaqueMacroTriangleBase,
            section.cutoutMacroTriangleBase);
}

public struct PrimeHitTriangle {
    public float3 localPosition;
    public float3 position;
    public float3 geometricNormal;
    public uint triangleIndex;
};

public PrimeHitTriangle primeHitTriangle(
        SectionRecord section,
        float2 barycentrics) {
    PrimeHitTriangle result;
    result.triangleIndex = primeGlobalTriangleIndex(section);
    float* positions = (float*)section.positionAddress;
    uint vertexBase = result.triangleIndex * 9u;
    float3 first = float3(
            positions[vertexBase],
            positions[vertexBase + 1u],
            positions[vertexBase + 2u]);
    float3 edgeOne = float3(
            positions[vertexBase + 3u],
            positions[vertexBase + 4u],
            positions[vertexBase + 5u]) - first;
    float3 edgeTwo = float3(
            positions[vertexBase + 6u],
            positions[vertexBase + 7u],
            positions[vertexBase + 8u]) - first;
    // Add the base vertex last so its rounding error is not amplified by both edge terms.
    result.localPosition = barycentrics.x * edgeOne
            + barycentrics.y * edgeTwo
            + first;
    result.position = mul(
            ObjectToWorld3x4(), float4(result.localPosition, 1.0));
    float3 normal = cross(edgeOne, edgeTwo);
    // Translation omits exact degenerates. Scaling by the nonzero maximum avoids squared-length
    // underflow without introducing an area threshold that would erase valid small triangles.
    float normalScale = max(abs(normal.x), max(abs(normal.y), abs(normal.z)));
    normal /= normalScale;
    result.geometricNormal = normalize(normal);
    return result;
}

public uint primePrimitiveEmitterIndex(PrimitiveRecord primitive) {
    uint encodedEmitter = (primitive.flagsEmitter & 4u) != 0u
            ? primitive.flagsEmitter >> 3u & 0x00ffffffu
            : 0u;
    return encodedEmitter == 0u
            ? PRIME_NO_PRIMITIVE_INDEX
            : encodedEmitter - 1u;
}

public uint primePrimitiveTextureId(
        SectionRecord section, PrimitiveRecord primitive) {
    uint materialId = primePrimitiveMaterialId(primitive);
    if (materialId != 0u) {
        return primeMaterialTextureId(materialId);
    }
    if ((primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u) {
        return 0u;
    }
    if ((primitive.flagsEmitter & 4u) == 0u) {
        return primePrimitiveStaticTextureId(primitive);
    }
    uint emitterIndex = primePrimitiveEmitterIndex(primitive);
    if (section.lightAddress == uint64_t(0)
            || emitterIndex == PRIME_NO_PRIMITIVE_INDEX) {
        return 0u;
    }
    SectionLightHeader* header = (SectionLightHeader*)section.lightAddress;
    if (emitterIndex >= header[0].emitterCount) {
        return 0u;
    }
    LightEmitter* emitters = (LightEmitter*)header[0].emitterAddress;
    return emitters[emitterIndex].metadata.w;
}

public PrimeSurfaceRelation primeSurfaceRelation(
        SectionRecord section, PrimitiveRecord primitive) {
    PrimeSurfaceRelation result;
    result.control = 0u;
    result.referenceUv = 0u;
    result.tint = 0u;
    result.mediumId = 0u;
    result.materialId = 0u;
    result.material.uv0 = 0u;
    result.material.uv1 = 0u;
    result.material.uv2 = 0u;
    result.material.tint = 0u;
    result.material.mediumMaterialIds = 0u;
    result.material.flagsEmitter = 0u;
    result.material.uvDensity = 0u;
    result.material.tangent = 0u;
    if (section.surfaceRelationAddress == uint64_t(0)) {
        return result;
    }
    if (primePrimitiveMaterialId(primitive) == 0u) {
        return result;
    }
    uint encodedOffset = 0u;
    uint emitterIndex = primePrimitiveEmitterIndex(primitive);
    if (emitterIndex != PRIME_NO_PRIMITIVE_INDEX) {
        if (section.lightAddress == uint64_t(0)) {
            return result;
        }
        SectionLightHeader* header = (SectionLightHeader*)section.lightAddress;
        if (emitterIndex >= header[0].emitterCount) {
            return result;
        }
        LightEmitter* emitters = (LightEmitter*)header[0].emitterAddress;
        encodedOffset = emitters[emitterIndex].relationOffset;
    } else {
        encodedOffset = primitive.flagsEmitter >> 3u & 0x00ffffffu;
    }
    if (encodedOffset == 0u) {
        return result;
    }
    uint* relations = (uint*)section.surfaceRelationAddress;
    uint offset = encodedOffset - 1u;
    result.control = relations[offset];
    uint kind = result.control & PRIME_SURFACE_RELATION_KIND_MASK;
    if (kind == PRIME_SURFACE_RELATION_BOUNDARY) {
        result.referenceUv = relations[offset + 1u];
        result.tint = relations[offset + 2u];
        result.mediumId = primeMediumId(relations[offset + 3u]);
        result.materialId = primeMaterialId(relations[offset + 3u]);
    } else {
        result.material.uv0 = relations[offset + 1u];
        result.material.uv1 = relations[offset + 2u];
        result.material.uv2 = relations[offset + 3u];
        result.material.tint = relations[offset + 4u];
        result.material.mediumMaterialIds = relations[offset + 5u];
        result.material.flagsEmitter = relations[offset + 6u];
        result.material.uvDensity = relations[offset + 7u];
        result.material.tangent = relations[offset + 8u];
    }
    return result;
}

PrimitiveRecord primePrimitiveWithMaterialRecipe(
        PrimitiveRecord primitive, uint recipe) {
    uint control = (primePrimitiveControl(primitive) & ~PRIME_RECIPE_MATERIAL_MASK)
            | (recipe & PRIME_RECIPE_MATERIAL_MASK);
    primitive.tint = (primitive.tint & 0x00ffffffu) | ((control & 0xffu) << 24u);
    primitive.flagsEmitter = (primitive.flagsEmitter & ~0x78000003u)
            | ((control >> 8u) & 3u)
            | ((control & PRIME_RECIPE_BUILTIN_MASK) << 16u);
    return primitive;
}

public float2 primeInterpolateUv(
        SectionRecord section,
        PrimitiveRecord primitive,
        float2 barycentrics,
        float3 localPosition,
        float3 geometricNormal);

public PrimitiveRecord primeResolveSurfacePrimitive(
        SectionRecord section,
        PrimitiveRecord primary,
        PrimeSurfaceRelation relation,
        float2 barycentrics,
        float3 localPosition,
        float3 geometricNormal) {
    uint kind = relation.control & PRIME_SURFACE_RELATION_KIND_MASK;
    bool backFacing = HitKind() == HIT_KIND_TRIANGLE_BACK_FACE;
    if (kind == PRIME_SURFACE_RELATION_BILATERAL && backFacing) {
        return relation.material;
    }
    if (kind != PRIME_SURFACE_RELATION_OVERLAY) {
        return primary;
    }
    bool positiveOnly =
            (relation.control & PRIME_SURFACE_RELATION_POSITIVE_ONLY) != 0u;
    bool overlayCovered = !(positiveOnly && backFacing)
            && primeEvaluateOpacity(
                    primary,
                    primePrimitiveTextureId(section, primary),
                    primeInterpolateUv(
                            section,
                            primary,
                            barycentrics,
                            localPosition,
                            geometricNormal)) >= PRIME_CUTOUT_ALPHA_THRESHOLD;
    return overlayCovered
            ? primePrimitiveWithMaterialRecipe(primary, relation.control >> 8u)
            : relation.material;
}

public PrimitiveRecord primePrimitive() {
    return primePrimitive(primeSection());
}

bool primeUsesRepeatedUv(PrimitiveRecord primitive) {
    return asfloat(primitive.uvDensity) < 0.0;
}

bool primeUsesConstantFloatUv(PrimitiveRecord primitive) {
    // Negative zero is distinct at the ABI level while remaining outside the negative-density
    // macro-face encoding. Voxel texel centers need float32 precision on large logical textures.
    return primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY;
}

public float2 primeInterpolateUv(
        SectionRecord section,
        PrimitiveRecord primitive,
        float2 barycentrics,
        float3 localPosition,
        float3 geometricNormal) {
    if (primeUsesConstantFloatUv(primitive)) {
        return float2(asfloat(primitive.uv0), asfloat(primitive.uv1));
    }
    if (primeUsesRepeatedUv(primitive)) {
        float3 normal = abs(geometricNormal);
        float2 projectedPosition = normal.x > normal.y && normal.x > normal.z
                ? localPosition.yz
                : (normal.y > normal.z ? localPosition.xz : localPosition.xy);
        float2 repeatedPosition = frac(projectedPosition);
        float2 uv0 = primeUnpackUv(primitive.uv0);
        float2 uv1 = primeUnpackUv(primitive.uv1);
        float2 uv2 = primeUnpackUv(primitive.uv2);
        return uv0
                + repeatedPosition.x * (uv1 - uv0)
                + repeatedPosition.y * (uv2 - uv0);
    }
    float3 barycentric = float3(
            1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x,
            barycentrics.y);
    return primeUnpackUv(primitive.uv0) * barycentric.x
            + primeUnpackUv(primitive.uv1) * barycentric.y
            + primeUnpackUv(primitive.uv2) * barycentric.z;
}

public float primeRayConeTextureLod(
        PrimitiveRecord primitive,
        uint textureId,
        float3 geometricNormal,
        float3 hitPosition) {
    float2 rayCone = unpackHalf2x16ToFloat(primePush.rayCone);
    float normalizedUvDensity;
    normalizedUvDensity = abs(asfloat(primitive.uvDensity));
    bool dynamic = (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u;
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    PrimeSceneTextureDimensions dimensions;
    if (dynamic) {
        dimensions = primeSceneTextureDimensions(textureIndex);
    } else {
        dimensions.extent = primeTextureFrameExtent(textureId);
        dimensions.mipLevels = primeTextureMaximumMip(textureId) + 1;
    }
    if (!(rayCone.x > 0.0)
            || !(normalizedUvDensity > 0.0)
            || dimensions.mipLevels <= 1) {
        return 0.0;
    }

    // The CPU stores the largest world-to-normalized-UV singular value. Multiplying by the
    // logical frame extent turns it into texels per world unit without baking resource-pack size
    // into section meshes. max() is conservative for non-square logical textures.
    float texelsPerWorldUnit = normalizedUvDensity
            * float(max(dimensions.extent.x, dimensions.extent.y));
    float distanceFromCamera = length(hitPosition - primePush.cameraPosition);
    float incidence = max(abs(dot(
            normalize(geometricNormal), normalize(WorldRayDirection()))), 0.05);
    float texelFootprint = distanceFromCamera * rayCone.x
            * texelsPerWorldUnit / incidence;

    // rayCone.y is FSR's quality-mode mip bias. It must be applied after deriving the native
    // screen-space footprint; applying a fixed mip directly would make distant shimmer look like
    // detail and cause FSR's luminance-instability stage to erase it over time.
    float lod = log2(max(texelFootprint, 1.0e-8)) + rayCone.y;
    return clamp(lod, 0.0, float(dimensions.mipLevels - 1));
}
