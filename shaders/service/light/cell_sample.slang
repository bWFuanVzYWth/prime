// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/light/cell_sample.slang";

import "service/light/area_common.slang";

// aliasValue is in [0, 256); low eight geometry bits store the alias cell index.
public uint primeSelectLightCell(float aliasValue, float aliasProbability, uint aliasGeometry) {
    uint column = uint(aliasValue);
    return aliasValue - float(column) < aliasProbability
            ? column : aliasGeometry & 0xffu;
}

void primeLightCellVertices(
        uint geometry, out float2 first, out float2 second, out float2 third) {
    uint column = geometry & 0xfu;
    uint row = (geometry >> 4u) & 0xfu;
    bool upper = (geometry & 0x100u) != 0u;
    float inverseSubdivision = 1.0 / float(PRIME_LIGHT_CELL_SUBDIVISION);
    float x = float(column) * inverseSubdivision;
    float y = float(row) * inverseSubdivision;
    if (upper) {
        first = float2(x + inverseSubdivision, y);
        second = float2(x + inverseSubdivision, y + inverseSubdivision);
        third = float2(x, y + inverseSubdivision);
    } else {
        first = float2(x, y);
        second = float2(x + inverseSubdivision, y);
        third = float2(x, y + inverseSubdivision);
    }
}

public float2 primeSampleLightCell(uint geometry, float2 positionSample) {
    float2 first;
    float2 second;
    float2 third;
    primeLightCellVertices(geometry, first, second, third);
    float squareRoot = sqrt(positionSample.x);
    float3 barycentric = float3(
            1.0 - squareRoot,
            squareRoot * (1.0 - positionSample.y),
            squareRoot * positionSample.y);
    return first * barycentric.x + second * barycentric.y + third * barycentric.z;
}
