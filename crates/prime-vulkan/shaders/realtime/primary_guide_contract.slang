#language slang 2026
module "realtime/primary_guide_contract.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "pbr/vertex.slang";
import "realtime/primary_parameters.slang";
import "reconstruct/primary_psr.slang";

public interface IPrimaryGuides {
    bool enabled();
    float2 sampleOffset(ZSobol sampler);
    void initialize(uint2 pixel);
    void interfaceSpecular(uint2 pixel, PrimePbrVertex material, float3 view, float4 incident,
                           float4 transmitted);
    void surface(PrimaryParameters camera, uint2 pixel, float2 sampleUv, SurfacePoint surface,
                 PrimePbrVertex material, float3 direction, float4 incident, float4 transmitted,
                 bool staticGeometry, PrimePrimaryPsr psr, float3 previousPosition);
    void environment(PrimaryParameters camera, uint2 pixel, float2 sampleUv, float3 direction,
                     PrimePrimaryPsr psr);
    void unresolved(uint2 pixel);
    void specularDistance(uint2 pixel, float distance);
}

public struct NoPrimaryGuides : IPrimaryGuides {
    public bool enabled() {
        return false;
    }
    public float2 sampleOffset(ZSobol sampler) {
        return sampler.sample2D(0);
    }
    public void initialize(uint2 pixel) {}
    public void interfaceSpecular(uint2 pixel, PrimePbrVertex material, float3 view,
                                  float4 incident, float4 transmitted) {}
    public void surface(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                        SurfacePoint surface, PrimePbrVertex material, float3 direction,
                        float4 incident, float4 transmitted, bool staticGeometry,
                        PrimePrimaryPsr psr, float3 previousPosition) {}
    public void environment(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                            float3 direction, PrimePrimaryPsr psr) {}
    public void unresolved(uint2 pixel) {}
    public void specularDistance(uint2 pixel, float distance) {}
}
