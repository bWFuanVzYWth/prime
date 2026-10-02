// Postprocessing has no sky-light sampling or path-lighting resource view.
import "atmosphere/aerial.slang";
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> atmosphereFrames;
[[vk::binding(3, 1)]] Sampler3D<float4> atmosphereAerialRadiance;
[[vk::binding(4, 1)]] Sampler3D<float4> atmosphereAerialTransmittance;
AtmAerial atmosphereAerial() {
    return {atmosphereFrames, atmosphereAerialRadiance, atmosphereAerialTransmittance};
}
