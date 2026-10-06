#language slang 2026
module "reconstruct/spatial_denoise.slang";
import "reconstruct/rr_format.slang";

public float primeSpatialMaterialWeight(float3 centerDiffuse, float3 sampleDiffuse,
                                        float3 centerSpecular, float3 sampleSpecular,
                                        float roughnessDifference = 0.0) {
    float3 diffuse = centerDiffuse - sampleDiffuse;
    float3 specular = centerSpecular - sampleSpecular;
    return exp(-64.0 * (dot(diffuse, diffuse) + dot(specular, specular) +
                        roughnessDifference * roughnessDifference));
}

// A single-frame fallback for unavailable RR. Geometry defines the kernel, so a noisy
// center color cannot prevent its neighbours from reducing variance.
public float primeSpatialDenoiseWeight(float2 offset, float centerDepth, float sampleDepth,
                                       float3 centerNormal, float3 sampleNormal,
                                       bool centerForeground, bool sampleForeground) {
    if (centerForeground != sampleForeground)
        return 0.0;
    bool centerSky = centerDepth == PRIME_RR_SKY_DEPTH;
    bool sampleSky = sampleDepth == PRIME_RR_SKY_DEPTH;
    if (centerSky != sampleSky)
        return 0.0;
    float weight = exp(-dot(offset, offset) / 4.0);
    if (centerSky)
        return weight;
    if (!isfinite(centerDepth) || !isfinite(sampleDepth) || centerDepth <= 0.0 ||
        sampleDepth <= 0.0)
        return 0.0;
    float relative = (sampleDepth - centerDepth) / max(0.01, 0.02 * centerDepth);
    weight *= exp(-relative * relative);
    float normalLength = dot(centerNormal, centerNormal) * dot(sampleNormal, sampleNormal);
    if (isfinite(normalLength) && normalLength > 1e-12)
        weight *= pow(saturate(dot(centerNormal, sampleNormal) * rsqrt(normalLength)), 32.0);
    return weight;
}
