// Only path lighting resources are visible to the realtime integration kernels.
import "atmosphere/lighting.slang";
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> atmosphereFrames;
[[vk::binding(1, 1)]] Sampler2D<float4> atmosphereSky;
[[vk::binding(2, 1)]] Sampler2D<float4> atmosphereTransmittance;
[[vk::binding(5, 1)]] Sampler2D<float4> atmosphereStars;
[[vk::binding(6, 1)]] ConstantBuffer<float4> atmosphereStarControls;
AtmLighting atmosphereLighting() {
    return {atmosphereFrames, atmosphereSky, atmosphereTransmittance, atmosphereStars,
            atmosphereStarControls};
}
