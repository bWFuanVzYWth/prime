// Included only by shader entries. Library functions receive AtmEnvironment explicitly.
import "atmosphere/view.slang";
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> atmosphereFrames;
[[vk::binding(1, 1)]] Sampler2D<float4> atmosphereSky;
[[vk::binding(2, 1)]] Sampler2D<float4> atmosphereTransmittance;
[[vk::binding(3, 1)]] Sampler3D<float4> atmosphereAerialRadiance;
[[vk::binding(4, 1)]] Sampler3D<float4> atmosphereAerialTransmittance;
AtmEnvironment atmosphereEnvironment() {
    return {atmosphereFrames, atmosphereSky, atmosphereTransmittance, atmosphereAerialRadiance,
            atmosphereAerialTransmittance};
}
