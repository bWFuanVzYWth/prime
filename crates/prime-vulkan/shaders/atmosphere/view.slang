// Original Prime chart, phase and solar calibration; only the consumer interface changes.
#language slang 2026
module "atmosphere/view.slang";
import "atmosphere/constants.slang";
import "atmosphere/sky_view.slang";
import "atmosphere/epipolar.slang";

public struct AtmChart {
    public float4 bounds;
    public float4 centers;
    public float4 weights;
    public float4 widths;
};
public struct AtmFrame {
    public float4 sun;
    public float4 tangent;
    public float4 bitangent;
    public float4 geometry; // height, horizon cosine, solar elevation, horizontal sun length
    public float4 camera;   // relative position, eye radius
    public float4 forward;  // forward, tan(fov/2)
    public float4 right;    // right, aspect
    public float4 up;
    public float4 epipole;
    public AtmChart charts[3];
    public float4 range;
    public float4 rangeExtra;
    public float4 shadowU;
    public float4 shadowV;
};
public struct AtmEnvironment {
    public StructuredBuffer<AtmFrame> frames;
    public Sampler2D<float4> sky;
    public Sampler2D<float4> transmittance;
    public Sampler3D<float4> aerialRadiance;
    public Sampler3D<float4> aerialTransmittance;
};

public float atmPreparedElevation(float e, AtmChart c) {
    float x = clamp(e, c.bounds.x, c.bounds.y);
    float y = c.bounds.z * (x - c.bounds.x) + c.weights.w;
    for (uint j = 0; j < 3; j++) {
        float d = x - c.centers[j];
        if (c.centers[j] < c.bounds.x || c.centers[j] > c.bounds.y) {
            y += c.weights[j] * (x - c.bounds.x) / (abs(d) + c.widths[j]);
        } else {
            y += c.weights[j] * (d / (abs(d) + c.widths[j]));
        }
    }
    return clamp(y, 0.0, 1.0);
}

public float3 atmSky(AtmEnvironment environment, float3 ray) {
    AtmFrame f = environment.frames[0];
    float horizontal = length(ray.xz) * f.geometry.w;
    float cp = horizontal > 1e-10 ? clamp(dot(ray.xz, f.sun.xz) / horizontal, -1.0, 1.0) : 1.0;
    float x = sqrt(sqrt(max((1.0 - cp) * 0.5, 0.0))) * 255.0;
    bool ground = ray.y < 0 && ray.y <= f.geometry.y;
    uint3 layout = ground ? uint3(2, 192, 64) : ray.y >= 0 ? uint3(1, 96, 96) : uint3(0, 0, 96);
    float y = atmPreparedElevation(asin(clamp(ray.y, -1.0, 1.0)), f.charts[layout.x]);
    float row = float(layout.y) + y * float(layout.z - 1);
    float3 logarithm = environment.sky.SampleLevel((float2(x, row) + 0.5) / 256.0, 0).xyz;
    return max(exp(logarithm) - float3(1e-30), float3(0.0));
}

public float3 atmDistantTransmittance(AtmEnvironment environment, float3 direction) {
    if (direction.y < 0 && direction.y <= environment.frames[0].geometry.y)
        return float3(0.0);
    float coordinate = 0.5 + 0.5 * sign(direction.y) * sqrt(abs(clamp(direction.y, -1.0, 1.0)));
    return environment.transmittance
            .SampleLevel(float2((coordinate * 8192.0 + 0.5) / 8193.0, 0.5), 0)
            .rgb;
}

public float atmSunSolidAngle() {
    float s = sin(0.5 * ATM_SUN_ANGULAR_RADIUS_RADIANS);
    return 4.0 * ATM_PI * s * s;
}
public float atmSunPdf() {
    return 1.0 / atmSunSolidAngle();
}
public bool atmSunContains(AtmEnvironment environment, float3 direction) {
    return dot(direction, environment.frames[0].sun.xyz) >= cos(ATM_SUN_ANGULAR_RADIUS_RADIANS);
}
public float3 atmSampleSun(AtmEnvironment environment, float2 sample) {
    float cosine = lerp(cos(ATM_SUN_ANGULAR_RADIUS_RADIANS), 1.0, sample.x);
    float sine = sqrt(1.0 - cosine * cosine);
    float azimuth = 2.0 * ATM_PI * sample.y;
    AtmFrame f = environment.frames[0];
    return normalize(f.tangent.xyz * (sine * cos(azimuth)) +
                     f.bitangent.xyz * (sine * sin(azimuth)) + f.sun.xyz * cosine);
}
public float3 atmSunRadiance(AtmEnvironment environment, float3 direction) {
    return (ATM_SPACE_SUN_INTENSITY / atmSunSolidAngle()) *
           atmDistantTransmittance(environment, direction);
}
public AtmEpipolarRange atmPreparedRange(AtmFrame f) {
    return {f.range.x, f.range.y, f.range.zw, f.rangeExtra.x, f.rangeExtra.y > 0.5};
}
public float3 atmAerialDirection(AtmFrame f, int2 coordinate) {
    AtmEpipolarSegment segment =
            atmEpipolarSliceSegment(f.epipole.xy, atmPreparedRange(f), coordinate.y, 256);
    float2 ndc = segment.valid
                         ? lerp(segment.entry, segment.exit, (float(coordinate.x) + 0.5) / 128.0)
                         : float2(2);
    return normalize(f.forward.xyz + f.right.xyz * (ndc.x * f.right.w * f.forward.w) +
                     f.up.xyz * (ndc.y * f.forward.w));
}
public void atmAerial(AtmEnvironment environment, float2 uv, float distanceMeters,
                      out float3 radiance, out float3 transmittance) {
    float distanceKm = max(distanceMeters, 0.0) * ATM_WORLD_UNIT_SCALE_KM;
    float depth = max(0.5 / 128.0, sqrt(clamp(distanceKm / ATM_AERIAL_MAX_DISTANCE_KM, 0.0, 1.0)));
    float weight = clamp(distanceKm / (ATM_AERIAL_MAX_DISTANCE_KM * (0.5 / 128.0) * (0.5 / 128.0)),
                         0.0, 1.0);
    AtmFrame f = environment.frames[0];
    float3 s = atmSampleEpipolarWithRange(environment.aerialRadiance, uv, depth, f.epipole.xy,
                                          atmPreparedRange(f))
                       .rgb;
    float3 t = environment.aerialTransmittance.SampleLevel(float3(uv, depth), 0).rgb;
    radiance = max(s, float3(0)) * weight;
    transmittance = lerp(float3(1), clamp(t, float3(0), float3(1)), weight);
}
