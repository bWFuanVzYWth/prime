#language slang 2026
module "service/light/area_common.slang";

import "math/transport.slang";

public static const uint PRIME_LIGHT_CELL_SUBDIVISION = 16u;
public static const uint PRIME_LIGHT_CELL_COUNT = 256u;

public uint primeLightCellIndex(float2 parentBarycentric) {
    float2 bounded = max(parentBarycentric, float2(0.0));
    float sum = bounded.x + bounded.y;
    if (sum > 1.0) {
        bounded /= sum;
    }
    float2 scaled = min(
            bounded * float(PRIME_LIGHT_CELL_SUBDIVISION),
            float2(float(PRIME_LIGHT_CELL_SUBDIVISION) - 1.0e-5));
    uint2 coordinate = uint2(floor(scaled));
    if (coordinate.x + coordinate.y >= PRIME_LIGHT_CELL_SUBDIVISION) {
        if (coordinate.x > 0u) {
            coordinate.x--;
        } else {
            coordinate.y--;
        }
    }
    float2 local = scaled - float2(coordinate);
    bool upper = local.x + local.y > 1.0
            && coordinate.x + coordinate.y < PRIME_LIGHT_CELL_SUBDIVISION - 1u;
    uint rowBase = coordinate.y * (2u * PRIME_LIGHT_CELL_SUBDIVISION - coordinate.y);
    return rowBase + 2u * coordinate.x + (upper ? 1u : 0u);
}

public float primeAreaSolidAnglePdf(
        float3 origin,
        float3 lightPosition,
        float lightCosine,
        float areaPdf) {
    float3 delta = lightPosition - origin;
    float distanceSquared = dot(delta, delta);
    return primeAreaSolidAnglePdfValue(areaPdf, distanceSquared, lightCosine);
}
