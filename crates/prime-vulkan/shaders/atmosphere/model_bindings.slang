// Entry-only binding declarations.
import "atmosphere/resources.slang";

[[vk::binding(0, 0)]] Sampler2D<float4> opticalInput;
[[vk::binding(1, 0)]] Sampler2D<float4> shapeInput;
[[vk::binding(2, 0)]] Texture2D<float4> meanInput;
[[vk::binding(3, 0)]] Texture2D<float4> groundInput;
[[vk::binding(4, 0)]] Texture2D<float4> rayleighInput;
[[vk::binding(5, 0)]] StructuredBuffer<float4> mediumInput;
AtmModel atmosphereModel() {
    return {mediumInput, opticalInput, shapeInput, meanInput, groundInput, rayleighInput};
}
