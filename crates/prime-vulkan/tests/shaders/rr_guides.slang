#language slang 2026
module rr_guides_test;
import "reconstruct/rr_guides.slang";
import "reconstruct/rr_status.slang";
import "reconstruct/spatial_denoise.slang";
import "math/rigid_motion.slang";
import "reconstruct/visible_guides.slang";
import "math/color.slang";
import "../../shaders/pbr.slang";
import "service/bsdf/contract/albedo.slang";
import "bsdf/full/math/microfacet_energy.slang";

StructuredBuffer<float4> inputs;
RWStructuredBuffer<float4> outputs;

public float rrCpuMaterialWeight(float3 centerDiffuse, float3 sampleDiffuse, float3 centerSpecular,
                                 float3 sampleSpecular, float roughnessDifference = 0.0) {
    return primeSpatialMaterialWeight(centerDiffuse, sampleDiffuse, centerSpecular, sampleSpecular,
                                      roughnessDifference);
}

public float rrCpuSpatialWeight(float2 offset, float centerDepth, float sampleDepth,
                                float3 centerNormal, float3 sampleNormal, bool centerForeground,
                                bool sampleForeground) {
    return primeSpatialDenoiseWeight(offset, centerDepth, sampleDepth, centerNormal, sampleNormal,
                                     centerForeground, sampleForeground);
}

public float3 rrCpuRigid(float3 v0, float3 v1, float3 v2, float2 bary, float4 row0, float4 row1,
                         float4 row2) {
    return primeRigidPreviousPosition(v0, v1, v2, bary, float3x4(row0, row1, row2));
}

public bool rrCpuForeground(float packed) {
    return primeRrForeground(packed);
}

public float rrCpuDeviceDepth(float viewZ) {
    return primeVisibleDeviceDepth(viewZ);
}

public float2 rrCpuMotion(float4 target, float2 sampleUv, float4 previousPosition,
                          float4 previousForward, float3 previousRight, float3 previousUp,
                          bool history, bool known) {
    RrCamera camera = {previousPosition,
                       previousForward,
                       float4(previousRight, 0),
                       float4(previousUp, 0),
                       float4(0.23, -0.37, history ? 1.0 : 0.0, 0),
                       float4(0),
                       float4(0),
                       float4(0),
                       float4(0)};
    return primeRrMotion(camera, sampleUv, target.xyz, target.w != 0, known);
}

public float3 rrCpuPixelMotion(float4 target, float2 sampleUv, float4 previousPosition,
                               float4 previousForward, float3 previousRight, float3 previousUp,
                               bool history, bool known, uint2 extent) {
    RrCamera camera = {previousPosition,
                       previousForward,
                       float4(previousRight, 0),
                       float4(previousUp, 0),
                       float4(0.23, -0.37, history ? 1.0 : 0.0, 0),
                       float4(0),
                       float4(0),
                       float4(0),
                       float4(0)};
    float2 motion;
    bool valid =
            primeRrMotionPixels(camera, sampleUv, target.xyz, target.w != 0, known, extent, motion);
    return float3(motion, valid ? 1.0 : 0.0);
}

public float3 rrCpuReflectionMotion(float2 sampleUv, float depth, float distance,
                                    float2 primaryMotion, float4 currentPosition,
                                    float4 currentForward, float3 currentRight, float3 currentUp,
                                    float4 previousPosition, float4 previousForward,
                                    float3 previousRight, float3 previousUp, bool history,
                                    uint2 extent) {
    RrCamera camera = {previousPosition,
                       previousForward,
                       float4(previousRight, 0),
                       float4(previousUp, 0),
                       float4(0.23, -0.37, history ? 1.0 : 0.0, 0),
                       currentPosition,
                       currentForward,
                       float4(currentRight, 0),
                       float4(currentUp, 0)};
    float2 motion;
    bool valid =
            primeRrDistanceMotion(camera, sampleUv, depth, distance, primaryMotion, extent, motion);
    return float3(motion, valid ? 1.0 : 0.0);
}

public float3 rrCpuDistanceMotion(float2 sampleUv, float depth, float distance,
                                  float4 currentPosition, float4 currentForward,
                                  float3 currentRight, float3 currentUp, float4 previousPosition,
                                  float4 previousForward, float3 previousRight, float3 previousUp,
                                  bool history, uint2 extent) {
    // Existing static-primary distance cases supply the real primary projection. The separate
    // wrapper accepts moving-primary motion directly, without inferring its previous depth.
    float2 screen = sampleUv * 2.0 - 1.0;
    float3 primary = currentPosition.xyz + currentForward.xyz * depth +
                     currentRight * (screen.x * currentForward.w * currentPosition.w * depth) -
                     currentUp * (screen.y * currentPosition.w * depth);
    float3 pixels =
            rrCpuPixelMotion(float4(primary, 0), sampleUv, previousPosition, previousForward,
                             previousRight, previousUp, history, true, extent);
    return rrCpuReflectionMotion(
            sampleUv, depth, distance, pixels.z != 0 ? pixels.xy : float2(PRIME_RR_INVALID_MOTION),
            currentPosition, currentForward, currentRight, currentUp, previousPosition,
            previousForward, previousRight, previousUp, history, extent);
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
    outputs[7 * id.x] = float4(rrCpuMotion(a, float2(0.5), float4(0, 0, 0, 1), float4(0, 0, 1, 1),
                                           float3(1, 0, 0), float3(0, 1, 0), true, true),
                               0, 0);
    outputs[7 * id.x + 1] = float4(rrCpuColor(a.xyz, a.w != 0), 1);
    PrimeDenoiseAlbedos albedo =
            rrCpuAlbedos(a.xyz, float3(0.5, 4.0 / 255.0, 0), 0.5, false, false, 1);
    outputs[7 * id.x + 2] = float4(albedo.diffuse + albedo.specular, 1);
    outputs[7 * id.x + 3] =
            float4(rrCpuPixelMotion(a, float2(0.5), float4(0, 0, 0, 1), float4(0, 0, 1, 1),
                                    float3(1, 0, 0), float3(0, 1, 0), true, true, uint2(960, 540)),
                   1);
    outputs[7 * id.x + 4] =
            float4(rrCpuDistanceMotion(float2(0.5), a.z, a.w, float4(0, 0, 0, 1),
                                       float4(0, 0, 1, 1), float3(1, 0, 0), float3(0, 1, 0),
                                       float4(0, 0, 0, 1), float4(0, 0, 1, 1), float3(1, 0, 0),
                                       float3(0, 1, 0), true, uint2(960, 540)),
                   1);
    outputs[7 * id.x + 5] =
            float4(rrCpuRigid(a.xyz, float3(1, 0, 0), float3(0, 1, 0), a.xy, float4(1, 0, 0, 0),
                              float4(0, 1, 0, 0), float4(0, 0, 1, 0)),
                   1);
    outputs[7 * id.x + 6] =
            float4(rrCpuForeground(a.w) ? 1.0 : 0.0, rrCpuDeviceDepth(a.z),
                   rrCpuSpatialWeight(a.xy, a.z, a.w, float3(0, 0, 1), float3(0, 0, 1), true, true),
                   rrCpuMaterialWeight(a.xyz, float3(0.5), float3(0), float3(0)));
}
