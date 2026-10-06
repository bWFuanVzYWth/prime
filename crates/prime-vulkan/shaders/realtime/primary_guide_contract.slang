#language slang 2026
module "realtime/primary_guide_contract.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "pbr/vertex.slang";
import "realtime/primary_parameters.slang";
import "reconstruct/primary_psr.slang";

public static const uint PRIME_PRIMARY_GUIDE_REFLECTION = 1u << 12;
public static const uint PRIME_PRIMARY_GUIDE_COMPANION = 1u << 13;

public interface IPrimaryGuides {
    bool enabled();
    float2 sampleOffset(ZSobol sampler);
    void initialize(uint2 pixel, uint2 inputExtent);
    void foreground(PrimaryParameters camera, uint2 pixel, float2 sampleUv, float3 position,
                    float3 previousPosition, bool previousPositionKnown);
    void interfaceSpecular(uint2 pixel, PrimePbrVertex material, float3 view, float4 incident,
                           float4 transmitted);
    void reflectionPending(uint2 pixel);
    void candidate(PrimaryParameters camera, uint2 pixel, float2 sampleUv, SurfacePoint surface,
                   PrimePbrVertex material, float3 direction, float4 incident, float4 transmitted,
                   float3 previousPosition, bool previousPositionKnown, PrimePrimaryPsr psr);
    void surface(PrimaryParameters camera, uint2 pixel, float2 sampleUv, SurfacePoint surface,
                 PrimePbrVertex material, float3 direction, float4 incident, float4 transmitted,
                 float3 previousPosition, bool previousPositionKnown, PrimePrimaryPsr psr);
    void environment(PrimaryParameters camera, uint2 pixel, float2 sampleUv, float3 direction,
                     PrimePrimaryPsr psr);
    void unresolved(uint2 pixel, uint flags);
}

public struct NoPrimaryGuides : IPrimaryGuides {
    public bool enabled() {
        return false;
    }
    public float2 sampleOffset(ZSobol sampler) {
        return sampler.sample2D(0);
    }
    public void initialize(uint2 pixel, uint2 inputExtent) {}
    public void foreground(PrimaryParameters camera, uint2 pixel, float2 sampleUv, float3 position,
                           float3 previousPosition, bool previousPositionKnown) {}
    public void interfaceSpecular(uint2 pixel, PrimePbrVertex material, float3 view,
                                  float4 incident, float4 transmitted) {}
    public void reflectionPending(uint2 pixel) {}
    public void candidate(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                          SurfacePoint surface, PrimePbrVertex material, float3 direction,
                          float4 incident, float4 transmitted, float3 previousPosition,
                          bool previousPositionKnown, PrimePrimaryPsr psr) {}
    public void surface(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                        SurfacePoint surface, PrimePbrVertex material, float3 direction,
                        float4 incident, float4 transmitted, float3 previousPosition,
                        bool previousPositionKnown, PrimePrimaryPsr psr) {}
    public void environment(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                            float3 direction, PrimePrimaryPsr psr) {}
    public void unresolved(uint2 pixel, uint flags) {}
}
