// Only path lighting resources are visible to the realtime integration kernels.
import "atmosphere/lighting.slang";
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> atmosphereFrames;
[[vk::binding(1, 1)]] Sampler2D<float4> atmosphereSky;
[[vk::binding(2, 1)]] Sampler2D<float4> atmosphereTransmittance;
AtmLighting atmosphereLighting() {
    return {atmosphereFrames, atmosphereSky, atmosphereTransmittance};
}
