#language slang 2026
module "atmosphere/resources.slang";
public struct AtmModel {
    public StructuredBuffer<float4> data;
    public Sampler2D<float4> opticalDepth;
    public Sampler2D<float4> scatteringSource;
    public Texture2D<float4> incidentMean;
    public Texture2D<float4> groundRadiance;
    public Texture2D<float4> rayleighSource;
};
