// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "atmosphere/shadow_math.slang";

public static const int PRIME_SUN_SHADOW_RESOLUTION = 512;
public float primeSunShadowLeafVisibleLength(float rayDepthAtZero, float rayDepthRate,
                                             float startDistance, float endDistance,
                                             float blockerDepth, float bias) {
    float startDepth = rayDepthAtZero + rayDepthRate * startDistance + bias;
    float endDepth = rayDepthAtZero + rayDepthRate * endDistance + bias;
    if (startDepth >= blockerDepth && endDepth >= blockerDepth) {
        return endDistance - startDistance;
    }
    if (startDepth < blockerDepth && endDepth < blockerDepth)
        return 0.0;
    float crossing = (blockerDepth - bias - rayDepthAtZero) / rayDepthRate;
    return rayDepthRate > 0.0 ? endDistance - clamp(crossing, startDistance, endDistance)
                              : clamp(crossing, startDistance, endDistance) - startDistance;
}

public void sunShadowBasis(float3 directionToSun, out float3 basisU, out float3 basisV) {
    // Permuted Frisvad basis keeps its unavoidable seam outside the visible solar hemisphere.
    if (directionToSun.y < -0.9999999) {
        basisU = float3(1.0, 0.0, 0.0);
    } else {
        float reciprocal = 1.0 / (1.0 + directionToSun.y);
        basisU = normalize(float3(1.0 - directionToSun.x * directionToSun.x * reciprocal,
                                  -directionToSun.x,
                                  -directionToSun.x * directionToSun.z * reciprocal));
    }
    basisV = cross(directionToSun, basisU);
}

public float sunShadowTexelSize(int cascade) {
    return cascade == 0 ? 0.5
                        : (cascade == 1 ? 1.0 : (cascade == 2 ? 2.0 : (cascade == 3 ? 4.0 : 8.0)));
}
