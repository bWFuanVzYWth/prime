// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/material/primary_color.slang";

import "math/color_space.slang";
import "model/material/recipe.slang";
import "math/packed_geometry.slang";
import "prime_abi_types.slang";
import "service/material/access.slang";
import "service/material/tint.slang";
import "service/trace/hit.slang";

/** Resolves only Minecraft base color and coverage; no lighting or PBR state is constructed. */
public float4 primePrimaryColor(
        SectionRecord section,
        PrimitiveRecord primitive,
        uint textureId,
        float2 uv,
        float textureLodValue) {
    bool dynamicPrimitive =
            (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u;
    if (primeUsesBakedMaterial(primitive)) {
        float4 color = dynamicPrimitive
                ? float4(
                        primeInlineSrgbTintColor(
                                primeUnpackTint(primitive.tint).rgb),
                        1.0)
                : primeStaticTintColor(primitive.tint);
        bool ownsTint = (primitive.uv2 & PRIME_CONSTANT_UV_OWN_TINT) != 0u;
        if (!ownsTint && (section.instanceTint & 0x80000000u) != 0u) {
            color = primeApplyStaticTint(color, section.instanceTint);
        }
        return color;
    }
    if (dynamicPrimitive) {
        float4 textureSample =
                primeSamplePrimitiveTexture(primitive, textureId, uv, textureLodValue);
        if (primeUsesDynamicRedAlpha(primitive)) {
            return float4(
                    primeInlineSrgbTintColor(
                            primeUnpackTint(primitive.tint).rgb),
                    textureSample.r);
        }
        return float4(
                primeLinearSrgbToLinearRec2020(
                        textureSample.rgb
                                * primeDecodeSrgb(
                                        primeUnpackTint(primitive.tint).rgb)),
                textureSample.a);
    }
    return primeApplyStaticTint(
            primeSampleStaticAlbedo(textureId, uv, textureLodValue),
            primePrimitiveTintId(primitive));
}
