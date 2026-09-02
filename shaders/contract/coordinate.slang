#language slang 2026
module "contract/coordinate.slang";

public static const float2 PRIME_PIXEL_CENTER_OFFSET = float2(0.5, 0.5);

public float2 primeCanonicalUvToClip(float2 uv)
{
    return float2(2.0 * uv.x - 1.0, 1.0 - 2.0 * uv.y);
}

public float2 primeCanonicalClipToUv(float2 clip)
{
    return float2(0.5 * clip.x + 0.5, -0.5 * clip.y + 0.5);
}

public float2 primeCanonicalVisibleMotionUv(float2 previousUv, float2 currentSampleUv)
{
    return previousUv - currentSampleUv;
}
