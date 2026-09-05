#language slang 2026
module "math/lambert.slang";

import "math/basis.slang";

public static const float PRIME_LAMBERT_PI = 3.141592653589793;

public float primeLambertPdf(float cosine) {
    return max(cosine, 0.0) / PRIME_LAMBERT_PI;
}

// The sampled hemisphere is the geometric hemisphere; there is no shading-normal correction.
public float3 primeLambertDirection(float3 normal, float2 sampleValue) {
    float radius = sqrt(sampleValue.x);
    float angle = 2.0 * PRIME_LAMBERT_PI * sampleValue.y;
    return primeLocalToWorld(float3(radius * cos(angle), radius * sin(angle),
            sqrt(max(1.0 - sampleValue.x, 0.0))), normal);
}

public float3 primeLambertDirect(float3 albedo, float cosine, float3 radiance, float lightPdf) {
    return albedo * radiance * (primeLambertPdf(cosine) / lightPdf);
}
