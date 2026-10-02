#language slang 2026
module "atmosphere/aerial.slang";
__exported import "atmosphere/view_types.slang";
import "atmosphere/constants.slang";
import "atmosphere/epipolar.slang";
public struct AtmAerial {
    public StructuredBuffer<AtmFrame> frames;
    public Sampler3D<float4> aerialRadiance;
    public Sampler3D<float4> aerialTransmittance;
};
public AtmEpipolarRange atmPreparedRange(AtmFrame f) {
    return {f.range.x, f.range.y, f.range.zw, f.rangeExtra.x, f.rangeExtra.y > 0.5};
}
public float3 atmAerialDirection(AtmFrame f, int2 coordinate) {
    AtmEpipolarSegment segment = atmEpipolarSliceSegment(f.epipole.xy, atmPreparedRange(f),
                                                         coordinate.y, ATM_AERIAL_RADIANCE_SLICES);
    float2 ndc = segment.valid
                         ? lerp(segment.entry, segment.exit, (float(coordinate.x) + 0.5) / 128.0)
                         : float2(2);
    return normalize(f.forward.xyz + f.right.xyz * (ndc.x * f.right.w * f.forward.w) +
                     f.up.xyz * (ndc.y * f.forward.w));
}
public void atmAerial(AtmAerial environment, float2 uv, float distanceMeters, out float3 radiance,
                      out float3 transmittance) {
    float distanceKm = max(distanceMeters, 0.0) * ATM_WORLD_UNIT_SCALE_KM;
    float depth = max(0.5 / 128.0, sqrt(clamp(distanceKm / ATM_AERIAL_MAX_DISTANCE_KM, 0.0, 1.0)));
    float weight = clamp(distanceKm / (ATM_AERIAL_MAX_DISTANCE_KM * (0.5 / 128.0) * (0.5 / 128.0)),
                         0.0, 1.0);
    AtmFrame f = environment.frames[0];
    float3 s = atmSampleEpipolarWithRange(environment.aerialRadiance, uv, depth, f.epipole.xy,
                                          atmPreparedRange(f), ATM_AERIAL_RADIANCE_SLICES)
                       .rgb;
    float3 t = environment.aerialTransmittance.SampleLevel(float3(uv, depth), 0).rgb;
    radiance = max(s, float3(0)) * weight;
    transmittance = lerp(float3(1), clamp(t, float3(0), float3(1)), weight);
}
