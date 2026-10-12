// Only path lighting resources are visible to the realtime integration kernels.
import "atmosphere/lighting.slang";
import "render_lighting_controls.slang";
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> atmosphereFrames;
[[vk::binding(1, 1)]] Sampler2D<float4> atmosphereSky;
[[vk::binding(2, 1)]] Sampler2D<float4> atmosphereTransmittance;
[[vk::binding(5, 1)]] Sampler2D<float4> atmosphereStars;
[[vk::binding(6, 1)]] ConstantBuffer<RenderLightingControls> renderLightingControls;
AtmLighting atmosphereLighting() {
    return {atmosphereFrames, atmosphereSky, atmosphereTransmittance, atmosphereStars,
            renderLightingControls.stars};
}
