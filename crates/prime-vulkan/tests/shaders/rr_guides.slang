#language slang 2026
module rr_guides_test;
import "reconstruct/rr_guides.slang";
import "math/color.slang";
import "../../shaders/pbr.slang";
import "service/bsdf/contract/albedo.slang";
import "bsdf/full/math/microfacet_energy.slang";

StructuredBuffer<float4> inputs;
RWStructuredBuffer<float4> outputs;

public float2 rrCpuMotion(float4 target, float2 sampleUv, float4 previousPosition,
                          float4 previousForward, float3 previousRight, float3 previousUp,
                          bool history, bool known) {
    RrCamera camera = {previousPosition, previousForward, float4(previousRight, 0),
                       float4(previousUp, 0), float4(0.23, -0.37, history ? 1.0 : 0.0, 0)};
    return primeRrMotion(camera, sampleUv, target.xyz, target.w != 0, known);
}

public float3 rrCpuColor(float3 value, bool roundtrip) {
    return roundtrip ? workingToLinear709(linear709ToWorking(value))
                     : primeRrFiniteHalfColor(value);
}

// An explicit constant response isolates adapter arithmetic. This is not a replacement or
// correctness oracle for the production LUT; the real LUT has a separate BSDF test suite.
public struct RrCpuConstantEnergy : IPrimeFullOpenPbrTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw) {
        return float4(0, 0, 0.5, 0);
    }
};

public PrimeDenoiseAlbedos rrCpuAlbedos(float3 base, float3 optical, float roughness,
                                        bool dielectric, bool thin, float cosine) {
    float3 view = float3(sqrt(max(0.0, 1.0 - cosine * cosine)), 0, cosine);
    PrimePbrVertex vertex =
            primePbrVertex(base, float3(0, 0, 1), 0, optical, false, true, thin, dielectric, view);
    vertex.roughness = roughness;
    return primePbrGuideAlbedos(RrCpuConstantEnergy(), vertex, view, float4(1, 0, 0, 0),
                                float4(1.5, 0, 0, 0));
}

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputs[id.x];
    outputs[3 * id.x] = float4(rrCpuMotion(a, float2(0.5), float4(0, 0, 0, 1), float4(0, 0, 1, 1),
                                           float3(1, 0, 0), float3(0, 1, 0), true, true),
                               0, 0);
    outputs[3 * id.x + 1] = float4(rrCpuColor(a.xyz, a.w != 0), 1);
    PrimeDenoiseAlbedos albedo =
            rrCpuAlbedos(a.xyz, float3(0.5, 4.0 / 255.0, 0), 0.5, false, false, 1);
    outputs[3 * id.x + 2] = float4(albedo.diffuse + albedo.specular, 1);
}
