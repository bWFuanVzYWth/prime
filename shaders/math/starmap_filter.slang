// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "math/starmap_filter.slang";

public float2 primeStarmapWrappedDelta(float2 a, float2 b) {
    float2 delta = a - b;
    delta.x -= floor(delta.x + 0.5);
    return delta;
}

public struct PrimeStarmapFootprint {
    public float2 stepUv;
    public float lod;
    public uint taps;
};

// Ellipse axes from the UV Jacobian. Bound anisotropy rather than underfiltering the major axis.
public PrimeStarmapFootprint primeStarmapFootprint(float2 dx, float2 dy, float2 extent) {
    float2 x = dx * extent;
    float2 y = dy * extent;
    float a = x.x*x.x + y.x*y.x;
    float b = x.x*x.y + y.x*y.y;
    float c = x.y*x.y + y.y*y.y;
    float discriminant = sqrt(max((a-c)*(a-c) + 4.0*b*b, 0.0));
    float major = sqrt(max(0.5*(a+c+discriminant), 0.0));
    float minor = sqrt(max(0.5*(a+c-discriminant), 0.0));
    minor = max(1.0, max(minor, major / 8.0));
    float2 axis = a >= c ? float2(0.5*(a-c+discriminant), b)
            : float2(b, 0.5*(c-a+discriminant));
    float axisScale = max(abs(axis.x), abs(axis.y));
    // Rescale before normalization so nearly zero Jacobians cannot underflow the length.
    axis = axisScale > 0.0 ? normalize(axis / axisScale) : float2(1.0, 0.0);
    PrimeStarmapFootprint result;
    result.taps = uint(clamp(ceil(major / minor), 1.0, 8.0));
    result.stepUv = axis * (major / float(result.taps)) / extent;
    result.lod = log2(minor);
    return result;
}

public float3 primeCompositeStars(float3 scene, float3 stars, float foregroundCoverage) {
    // NGX alpha is an external result; invalid coverage must not reveal stars through geometry.
    float visible = isfinite(foregroundCoverage) ? 1.0 - saturate(foregroundCoverage) : 0.0;
    return scene + stars * visible;
}
