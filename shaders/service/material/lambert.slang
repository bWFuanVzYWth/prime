#language slang 2026
module "service/material/lambert.slang";
import "math/packed_geometry.slang";
import "model/material/recipe.slang";
import "service/material/coverage.slang";
import "service/material/access.slang";
import "prime_material_core_abi.slang";
import "prime_abi_types.slang";
import "contract/lambert.slang";
import "service/trace/hit.slang";
import "service/material/primary_color.slang";
import "service/material/static_color.slang";
import "service/material/medium_access.slang";
import "model/material/transparent.slang";
import "policy/runtime/config.slang";

public struct PrimeLambertMaterial {
    public float3 color; // Lambert albedo or delta extinction / thin optical depth
    public float3 adjacent;
    public uint media;
    public uint flags;
};

float3 primeLambertReferenceExtinction(uint materialId, uint tint, uint uv) {
    uint core = primeMaterialCoreWord(materialId);
    uint recipe = (core >> PRIME_MATERIAL_CORE_RECIPE_CONTROL_SHIFT) & PRIME_MATERIAL_CORE_RECIPE_CONTROL_MASK;
    if ((recipe & PRIME_RECIPE_WATER) != 0u) return PRIME_PURE_WATER_ABSORPTION_M_INV;
    float opacity;
    float3 color = primeStaticBaseColor(core & PRIME_MATERIAL_CORE_TEXTURE_ID_MASK,
            tint, primeUnpackUv(uv), 0.0, opacity);
    return primeGlassShadowExtinction(color, opacity);
}

// Classification and absorption deliberately do not load optical maps, normals or BSDF material.
public PrimeLambertMaterial primeLambertMaterial(SectionRecord section, PrimitiveRecord primitive,
        uint recipe, uint textureId, float2 uv, float lod, float cosine, PrimeSurfaceRelation relation) {
    PrimeLambertMaterial result = {};
    if (!primeRecipeIsTransmissive(recipe)) {
        result.color = clamp(primePrimaryColor(section, primitive, textureId, uv, lod).rgb, float3(0.0), float3(1.0));
        return result;
    }
    bool water = (recipe & PRIME_RECIPE_WATER) != 0u;
    if (water) result.color = PRIME_PURE_WATER_ABSORPTION_M_INV;
    else if (primeUsesBakedMaterial(primitive)) {
        // Baked records own color, not a canonical TextureId / reference texel.
        float4 color = primePrimaryColor(section, primitive, textureId, uv, lod);
        result.color = primeGlassShadowExtinction(color.rgb, color.a);
    } else {
        float opacity;
        float3 reference = primeStaticBaseColor(textureId, primePrimitiveTintId(primitive),
                primePrimitiveMaterialReferenceUv(primitive), 0.0, opacity);
        if (!primeGlassReferenceIsStained(opacity) && primeGlassTexelIsRough(
                primeEvaluateOpacity(primitive, textureId, uv), primeUsesSeamlessGlass())) {
            result.color = clamp(primePrimaryColor(section, primitive, textureId, uv, lod).rgb, float3(0.0), float3(1.0));
            return result;
        }
        result.color = primeGlassShadowExtinction(reference, opacity);
    }
    result.flags = PRIME_LAMBERT_DELTA | (water ? PRIME_LAMBERT_WATER : 0u)
            | (cosine < 0.0 ? PRIME_LAMBERT_ENTERING : 0u);
    result.media = primePrimitiveMediumId(primitive);
    if (primeRecipeIsThinWalled(recipe)) {
        result.flags |= PRIME_LAMBERT_THIN;
        // Same explicit reference thickness as the material translation; no geometric epsilon.
        result.color *= PRIME_THIN_WALLED_THICKNESS_M / max(abs(cosine), 1.0e-3);
    } else if ((relation.control & PRIME_SURFACE_RELATION_KIND_MASK) == PRIME_SURFACE_RELATION_BOUNDARY) {
        result.media |= primeMaterialMediumId(relation.materialId) << 16u;
        result.adjacent = primeLambertReferenceExtinction(relation.materialId, relation.tint, relation.referenceUv);
    }
    return result;
}
