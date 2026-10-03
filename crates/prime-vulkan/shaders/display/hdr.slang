// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "display/hdr.slang";

public float primeExtendedSrgbEotf(float encoded) {
    float magnitude = abs(encoded);
    float linear = magnitude <= 0.04045 ? magnitude / 12.92 : pow((magnitude + 0.055) / 1.055, 2.4);
    return copysign(linear, encoded);
}
public float3 primeExtendedSrgbEotf(float3 encoded) {
    return float3(primeExtendedSrgbEotf(encoded.x), primeExtendedSrgbEotf(encoded.y),
                  primeExtendedSrgbEotf(encoded.z));
}

public float primeUiCoverage(float4 uiComposite) {
    return clamp(uiComposite.a, 0.0, 1.0);
}

// Preserve the old source-over UI reconstruction approximation. Arbitrary blend modes cannot
// be recovered from the final RGBA8 image. HDR world never comes from that clipped image.
public float3 primeHdrCompositeLinear(float3 linearHdr, float3 encodedBaseline, float4 uiComposite,
                                      float scale) {
    float coverage = primeUiCoverage(uiComposite);
    float3 encodedUi = max(uiComposite.rgb - encodedBaseline * (1.0 - coverage), float3(0.0));
    float3 ui =
            coverage > 0.0 ? primeExtendedSrgbEotf(encodedUi / coverage) * coverage : float3(0.0);
    return (ui + linearHdr * (1.0 - coverage)) * scale;
}

public float3 primeHdrComposite(float3 encodedHdr, float3 encodedBaseline, float4 uiComposite,
                                float scale) {
    return primeHdrCompositeLinear(primeExtendedSrgbEotf(encodedHdr), encodedBaseline, uiComposite,
                                   scale);
}
