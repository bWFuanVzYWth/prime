// Compatibility facade for Offline and atmosphere preparation/test consumers.
#language slang 2026
module "atmosphere/view.slang";
__exported import "atmosphere/lighting.slang";
__exported import "atmosphere/aerial.slang";
public struct AtmEnvironment {
    public StructuredBuffer<AtmFrame> frames;
    public Sampler2D<float4> sky;
    public Sampler2D<float4> transmittance;
    public Sampler3D<float4> aerialRadiance;
    public Sampler3D<float4> aerialTransmittance;
    public Sampler2D<float4> stars;
    public float4 starControls;
};
public AtmLighting atmLighting(AtmEnvironment environment) {
    return {environment.frames, environment.sky, environment.transmittance, environment.stars,
            environment.starControls};
}
public float3 atmSky(AtmEnvironment environment, float3 direction) {
    return atmSky(atmLighting(environment), direction);
}
public float3 atmSky(AtmEnvironment environment, float3 direction, float skyIntensity) {
    return atmSky(atmLighting(environment), direction, skyIntensity);
}
public float3 atmDistantTransmittance(AtmEnvironment environment, float3 direction) {
    return atmDistantTransmittance(atmLighting(environment), direction);
}
public bool atmSunContains(AtmEnvironment environment, float3 direction) {
    return atmSunContains(atmLighting(environment), direction);
}
public float3 atmSampleSun(AtmEnvironment environment, float2 sample) {
    return atmSampleSun(atmLighting(environment), sample);
}
public float3 atmSunRadiance(AtmEnvironment environment, float3 direction) {
    return atmSunRadiance(atmLighting(environment), direction);
}
public void atmAerial(AtmEnvironment environment, float2 uv, float distanceMeters,
                      out float3 radiance, out float3 transmittance) {
    AtmAerial aerial = {environment.frames, environment.aerialRadiance,
                        environment.aerialTransmittance};
    atmAerial(aerial, uv, distanceMeters, radiance, transmittance);
}
