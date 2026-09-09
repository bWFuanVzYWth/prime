// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/sun_shadow_hierarchy.slang";

public static const int PRIME_SUN_SHADOW_RESOLUTION = 512;
public int primeSunShadowHierarchyResolution(int level)
{
    return PRIME_SUN_SHADOW_RESOLUTION >> level;
}

public int2 primeSunShadowHierarchyOffset(int level)
{
    if (level == 0) return int2(0, 0);
    if (level == 1) return int2(512, 0);
    if (level == 2) return int2(512, 256);
    if (level == 3) return int2(640, 256);
    if (level == 4) return int2(640, 320);
    if (level == 5) return int2(672, 320);
    if (level == 6) return int2(672, 336);
    if (level == 7) return int2(680, 336);
    if (level == 8) return int2(680, 340);
    return int2(682, 340);
}

public float primeSunShadowLeafVisibleLength(
        float rayDepthAtZero,
        float rayDepthRate,
        float startDistance,
        float endDistance,
        float blockerDepth,
        float bias)
{
    float startDepth = rayDepthAtZero + rayDepthRate * startDistance + bias;
    float endDepth = rayDepthAtZero + rayDepthRate * endDistance + bias;
    if (startDepth >= blockerDepth && endDepth >= blockerDepth) {
        return endDistance - startDistance;
    }
    if (startDepth < blockerDepth && endDepth < blockerDepth) return 0.0;
    float crossing = (blockerDepth - bias - rayDepthAtZero) / rayDepthRate;
    return rayDepthRate > 0.0
            ? endDistance - clamp(crossing, startDistance, endDistance)
            : clamp(crossing, startDistance, endDistance) - startDistance;
}
