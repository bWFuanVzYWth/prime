// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/atmosphere/sky.slang";

__exported import "model/atmosphere/constants.slang";
import "model/atmosphere/geometry.slang";

// Camera geometry and LUT addressing are visible to rendering entries. Spectral integration
// and Rec.2020 conversion live in spectrum.slang, visible only to LUT producers.
// Integral of exp(-extinction * distance) over one homogeneous segment. The Taylor branch
// avoids cancellation at small optical depth without replacing zero extinction by absorption.
public float atmHomogeneousSegmentIntegral(float extinction, float length) {
    float segmentLength = max(length, 0.0);
    float x = extinction * segmentLength;
    if (x <= 0.05) {
        float factor = 1.0 + x * (-0.5 + x * (1.0 / 6.0 + x * (-1.0 / 24.0 + x * (1.0 / 120.0))));
        return segmentLength * factor;
    }
    return segmentLength * (1.0 - exp(-x)) / x;
}

public float4 atmHomogeneousSegmentIntegral(float4 extinction, float length) {
    return float4(atmHomogeneousSegmentIntegral(extinction.x, length),
                  atmHomogeneousSegmentIntegral(extinction.y, length),
                  atmHomogeneousSegmentIntegral(extinction.z, length),
                  atmHomogeneousSegmentIntegral(extinction.w, length));
}

public bool atmDistantDirectionVisible(float eyeRadiusKm, float3 direction) {
    return !atmGround(max(eyeRadiusKm - ATM_BOTTOM_RADIUS_KM, 0.0), direction.y);
}

public float atmRayleighPhase(float cosine) { return ATM_RAYLEIGH_PHASE_SCALE * (1.0 + cosine * cosine); }

public float4 atmSample2D(readonly RWTexture2D<float4> source, float2 uv) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    int2 dimensions = int2(width, height);
    float2 coordinate = clamp(uv, float2(0.0), float2(1.0)) * float2(dimensions) - float2(0.5);
    int2 lower = int2(floor(coordinate));
    float2 fraction = frac(coordinate);
    int2 maximum = dimensions - int2(1);
    int2 p00 = clamp(lower, int2(0), maximum);
    int2 p10 = clamp(lower + int2(1, 0), int2(0), maximum);
    int2 p01 = clamp(lower + int2(0, 1), int2(0), maximum);
    int2 p11 = clamp(lower + int2(1), int2(0), maximum);
    float4 row0 = lerp(source[p00], source[p10], fraction.x);
    float4 row1 = lerp(source[p01], source[p11], fraction.x);
    return lerp(row0, row1, fraction.y);
}

public float4 atmSample3D(readonly RWTexture3D<float4> source, float3 uvw) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    int3 dimensions = int3(width, height, depth);
    float3 coordinate = clamp(uvw, float3(0.0), float3(1.0)) * float3(dimensions) - float3(0.5);
    int3 lower = int3(floor(coordinate));
    float3 fraction = frac(coordinate);
    int3 maximum = dimensions - int3(1);
    int3 p000 = clamp(lower, int3(0), maximum);
    int3 p100 = clamp(lower + int3(1, 0, 0), int3(0), maximum);
    int3 p010 = clamp(lower + int3(0, 1, 0), int3(0), maximum);
    int3 p110 = clamp(lower + int3(1, 1, 0), int3(0), maximum);
    int3 p001 = clamp(lower + int3(0, 0, 1), int3(0), maximum);
    int3 p101 = clamp(lower + int3(1, 0, 1), int3(0), maximum);
    int3 p011 = clamp(lower + int3(0, 1, 1), int3(0), maximum);
    int3 p111 = clamp(lower + int3(1), int3(0), maximum);
    float4 z0y0 = lerp(source[p000], source[p100], fraction.x);
    float4 z0y1 = lerp(source[p010], source[p110], fraction.x);
    float4 z1y0 = lerp(source[p001], source[p101], fraction.x);
    float4 z1y1 = lerp(source[p011], source[p111], fraction.x);
    return lerp(lerp(z0y0, z0y1, fraction.y), lerp(z1y0, z1y1, fraction.y), fraction.z);
}
