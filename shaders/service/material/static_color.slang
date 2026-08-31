#language slang 2026
module "service/material/static_color.slang";

import "math/color_space.slang";
import "service/material/access.slang";
import "service/material/tint.slang";

public float3 primeStaticBaseColor(
        uint textureId,
        uint tintId,
        float2 uv,
        float textureLodValue,
        out float opacity) {
    float4 textureSample = primeSampleStaticAlbedo(textureId, uv, textureLodValue);
    opacity = textureSample.a;
    return primeApplyStaticTint(
            primeLinearSrgbToLinearRec2020(textureSample.rgb), tintId);
}
