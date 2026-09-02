#language slang 2026
module "service/material/static_color.slang";

import "service/material/access.slang";
import "service/material/tint.slang";

public float3 primeStaticBaseColor(
        uint textureId,
        uint tintId,
        float2 uv,
        float textureLodValue,
        out float opacity,
        out uint packedFrameExtent) {
    PrimeBaseTextureRecord record = primeLoadBaseTextureRecord(textureId);
    float4 textureSample = primeSampleStaticAlbedo(record, uv, textureLodValue);
    float4 tinted = primeApplyStaticTint(textureSample, tintId);
    opacity = tinted.a;
    packedFrameExtent = record.words.y;
    return tinted.rgb;
}

public float3 primeStaticBaseColor(
        uint textureId,
        uint tintId,
        float2 uv,
        float textureLodValue,
        out float opacity) {
    uint packedFrameExtent;
    return primeStaticBaseColor(
            textureId,
            tintId,
            uv,
            textureLodValue,
            opacity,
            packedFrameExtent);
}
