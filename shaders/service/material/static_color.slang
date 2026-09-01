#language slang 2026
module "service/material/static_color.slang";

import "service/material/access.slang";
import "service/material/tint.slang";

public float3 primeStaticBaseColor(
        uint textureId,
        uint tintId,
        float2 uv,
        float textureLodValue,
        out float opacity) {
    float4 textureSample = primeSampleStaticAlbedo(textureId, uv, textureLodValue);
    float4 tinted = primeApplyStaticTint(textureSample, tintId);
    opacity = tinted.a;
    return tinted.rgb;
}
