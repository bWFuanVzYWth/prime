#language slang 2026
module "service/material/surface.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/material/normal.slang";
import "math/color_space.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "model/material/defaults.slang";
import "model/material/labpbr.slang";
import "model/material/translation.slang";
import "model/material/transparent.slang";
import "service/material/access.slang";
import "service/material/auxiliary_access.slang";
import "service/material/static_color.slang";
import "service/material/tint.slang";

// Minimal translated-texture adapter. The integrator consumes only this result, so backing-page
// changes cannot affect path scheduling or traversal.
public struct PrimeMaterialSample {
    public float3 baseColor;
    public float opacity;
    public float3 shadingNormal;
    public float roughness;
    public uint materialControl;
    public uint opticalControl;
};

static const uint PRIME_EMITTER_FLAG_TWO_SIDED = 1u;
static const uint PRIME_EMITTER_FLAG_LABPBR_EMISSION = 2u;
public PrimeMaterialSample primeEvaluateMaterial(
        PrimitiveRecord primitive,
        uint recipeControl,
        uint textureId,
        float2 uv,
        float textureLodValue,
        uint instanceTint,
        float3 geometricNormal) {
    PrimeMaterialSample result;
    bool bakedMaterial = primeUsesBakedMaterial(primitive);
    // Static source RGB8 tints are exact renderer TintIds. Dynamic attachments retain their
    // explicit source-color adapter until they gain a canonical resource path.
    if (bakedMaterial) {
        float3 baseColor = primeStaticTintColor(primitive.tint);
        bool ownsTint = (primitive.uv2 & PRIME_CONSTANT_UV_OWN_TINT) != 0u;
        if (!ownsTint && (instanceTint & 0x80000000u) != 0u) {
            baseColor = primeApplyStaticTint(baseColor, instanceTint);
        }
        result.baseColor = baseColor;
        result.opacity = 1.0;
    } else {
        float4 textureSample =
                primeSamplePrimitiveTexture(primitive, textureId, uv, textureLodValue);
        if (primeUsesDynamicRedAlpha(primitive)) {
            result.opacity = textureSample.r;
            result.baseColor = primeLinearSrgbToLinearRec2020(
                    primeDecodeSrgb(primeUnpackTint(primitive.tint).rgb));
        } else if ((primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u) {
            result.opacity = textureSample.a;
            result.baseColor = primeLinearSrgbToLinearRec2020(
                    textureSample.rgb
                            * primeDecodeSrgb(primeUnpackTint(primitive.tint).rgb));
        } else {
            result.opacity = textureSample.a;
            result.baseColor = primeApplyStaticTint(textureSample.rgb, primitive.tint);
        }
    }
    uint recipeScattering = recipeControl & PRIME_RECIPE_SCATTERING_MASK;
    bool recipeTransmissive = recipeScattering == PRIME_RECIPE_DIELECTRIC_SOLID
            || recipeScattering == PRIME_RECIPE_DIELECTRIC_THIN;
    uint materialControl = recipeScattering == PRIME_RECIPE_FOLIAGE_THIN
            ? PRIME_MATERIAL_FAMILY_FOLIAGE | PRIME_MATERIAL_THIN_WALLED
            : recipeTransmissive
                    ? PRIME_MATERIAL_FAMILY_DIELECTRIC
                            | (recipeScattering == PRIME_RECIPE_DIELECTRIC_THIN
                                    ? PRIME_MATERIAL_THIN_WALLED
                                    : 0u)
                    : PRIME_MATERIAL_FAMILY_OPAQUE;
    materialControl |= (recipeControl & PRIME_RECIPE_ANIMATED) != 0u
            ? PRIME_MATERIAL_ANIMATED
            : 0u;
    uint builtinClass = recipeControl >> PRIME_RECIPE_BUILTIN_SHIFT & 15u;
    result.roughness = primeUsesVanillaPbrPresets() && builtinClass != 0u
            ? primeBuiltinRoughness(
                    builtinClass,
                    primeConfiguredDefaultLinearRoughness())
            : primeConfiguredDefaultLinearRoughness();
    result.opticalControl = primeUsesVanillaPbrPresets()
            ? primeBuiltinFresnelCode(builtinClass)
            : 0u;
    if (bakedMaterial && (recipeControl & PRIME_RECIPE_OPTICAL_TEXTURE) != 0u) {
        PrimeCanonicalOptics optics = primeAdaptLabPbrSpecular(
                unpackUnorm4x8ToFloat(primitive.uv1));
        result.roughness = optics.roughness;
        result.opticalControl = optics.opticalControl;
    } else if (!bakedMaterial
            && (recipeControl & PRIME_RECIPE_OPTICAL_TEXTURE) != 0u) {
        PrimeCanonicalOptics optics = primeAdaptLabPbrSpecular(
                primeSampleMaterialOptical(textureId, uv, textureLodValue));
        result.roughness = optics.roughness;
        result.opticalControl = optics.opticalControl;
    }
    bool usesNormalMap = !bakedMaterial
            && (recipeControl & PRIME_RECIPE_NORMAL_TEXTURE) != 0u;
    PrimeLabPbrNormalAtlasSample normalSample;
    normalSample.tangentNormal = float3(0.0, 0.0, 1.0);
    normalSample.distributionRoughness = 0.0;
    if (usesNormalMap) {
        normalSample = primeAdaptLabPbrNormalAtlas(
                primeSampleMaterialNormal(textureId, uv, textureLodValue));
        result.roughness = primeCombineLabPbrNormalRoughness(
                result.roughness,
                normalSample.distributionRoughness);
        result.opticalControl |= PRIME_OPTICAL_NORMAL_MAPPED;
    }
    if (recipeTransmissive
            && (recipeControl & PRIME_RECIPE_WATER) == 0u
            && !bakedMaterial) {
        float referenceOpacity;
        float3 referenceBaseColor = primeStaticBaseColor(
                textureId,
                primitive.tint,
                primePrimitiveMaterialReferenceUv(primitive),
                0.0,
                referenceOpacity);
        bool stained = primeGlassReferenceIsStained(referenceOpacity);
        bool rough = primeGlassTexelIsRough(
                result.opacity, primeUsesSeamlessGlass());
        if (stained) {
            // Both boundaries of a closed medium must agree on its color even when they hit
            // different decorative texels. Shadow rays use this same stable material reference.
            result.baseColor = referenceBaseColor;
            materialControl |= PRIME_MATERIAL_MEDIUM_STAINED_GLASS;
        } else {
            materialControl |= PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS;
        }
        if (rough) {
            materialControl |= PRIME_MATERIAL_DECORATIVE_INTERFACE;
        } else {
            result.roughness = 0.0;
        }
        if (!stained && rough) {
            // Vanilla clear-glass borders are opaque blue-grey paint, not a second dielectric
            // volume. Keep the primitive in the transmissive BLAS but shade this texel as rough
            // diffuse so the transparent body remains a closed colorless medium.
            materialControl = (materialControl & ~PRIME_MATERIAL_FAMILY_MASK)
                    | PRIME_MATERIAL_FAMILY_OPAQUE;
        }
    } else if ((recipeControl & PRIME_RECIPE_WATER) != 0u) {
        materialControl |= PRIME_MATERIAL_MEDIUM_WATER;
    }
    if ((primitive.flagsEmitter
            & (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG))
            == (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG)) {
        materialControl |= PRIME_MATERIAL_VISIBLE_EMISSION;
    }
    uint subsurfaceCode = result.opticalControl
            >> PRIME_OPTICAL_SUBSURFACE_SHIFT & 0xffu;
    if ((recipeControl & PRIME_RECIPE_ALPHA_CUTOUT) != 0u
            && subsurfaceCode != 0u) {
        materialControl |= PRIME_MATERIAL_THIN_WALLED;
    }
    result.materialControl = materialControl;
    if (usesNormalMap) {
        float3 tangent = primeUnpackOctahedralUnitVector(primitive.tangent);
        tangent = normalize(tangent - geometricNormal * dot(tangent, geometricNormal));
        float3 bitangent = cross(geometricNormal, tangent);
        bitangent *= (recipeControl & PRIME_RECIPE_TANGENT_NEGATIVE) != 0u
                ? -1.0
                : 1.0;
        result.shadingNormal = normalize(
                tangent * normalSample.tangentNormal.x
                        + bitangent * normalSample.tangentNormal.y
                        + geometricNormal * normalSample.tangentNormal.z);
    } else {
        result.shadingNormal = geometricNormal;
    }
    return result;
}

public PrimeMaterialSample primeEvaluateMaterialReference(
        uint recipeControl, uint tintId, uint textureId, float2 uv) {
    PrimitiveRecord primitive;
    primitive.uv0 = asuint(uv.x);
    primitive.uv1 = asuint(uv.y);
    primitive.uv2 = 0u;
    uint referenceControl = recipeControl & ~PRIME_RECIPE_NORMAL_TEXTURE;
    primitive.tint = tintId & 0x00ffffffu;
    primitive.mediumMaterialIds = 0u;
    primitive.flagsEmitter = 0u;
    primitive.uvDensity = PRIME_CONSTANT_UV_DENSITY;
    primitive.tangent = 0u;
    return primeEvaluateMaterial(
            primitive,
            referenceControl,
            textureId,
            uv,
            0.0,
            0u,
            float3(0.0, 0.0, 1.0));
}

public float3 primeEvaluateEmitterRadiance(
        uint textureId,
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        float2 uv,
        float textureLodValue) {
    float opacity;
    float3 color = primeStaticBaseColor(
            textureId, atlasTint, uv, textureLodValue, opacity);
    bool cutout = (emitterFlags & PRIME_EMITTER_FLAG_TWO_SIDED) != 0u;
    if (cutout && opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        return float3(0.0);
    }
    // Minecraft's 0..15 block-light value is an ordinal influence radius, not radiometry. Prime's
    // documented fallback maps its squared normalized level to the ABI source calibration, so a
    // white level-15 texel has radiance PRIME_LEVEL_15_BLOCK_INTENSITY. The
    // path geometry term supplies the actual inverse-square falloff; never add it here as well.
    // This global radiometric scale is uniform over every emitter. It therefore changes neither
    // light-tree selection probabilities nor their PDFs and does not require rebuilding the tree.
    float authoredEmission = 0.0;
    if ((emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u) {
        authoredEmission = primeDecodeLabPbrEmission(
                primeSampleMaterialOptical(textureId, uv, textureLodValue).a);
    }
    // LabPBR defines only a normalized emissiveness, not absolute radiometry. Prime maps 100%
    // to the same calibrated radiance as a white vanilla level-15 source. Once authored, this
    // channel replaces Minecraft's block-light value; alpha 0 can intentionally turn it off.
    float radianceScale = (emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u
            ? authoredEmission * PRIME_LEVEL_15_BLOCK_INTENSITY
            : max(fallbackRadiance, 0.0);
    return color * radianceScale * primeBlockLightRadianceMultiplier();
}

public float3 primeEvaluateEmitterRadiance(
        uint textureId,
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        float2 uv) {
    // A sampled light point has no screen-space ray footprint. The distribution was constructed
    // from mip 0, so evaluating that same radiometric function preserves its PDF contract.
    return primeEvaluateEmitterRadiance(
            textureId, atlasTint, emitterFlags, fallbackRadiance, uv, 0.0);
}
