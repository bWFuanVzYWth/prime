// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module transport_physics_math_test;
import "trace/visibility.slang";
import "trace/closest.slang";
import "pbr/delta.slang";
import "../../shaders/pbr.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "model/material/medium.slang";
import "service/bsdf/contract/sample.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::push_constant]] ConstantBuffer<uint4> dispatch;

public struct PhysicsTestEnergy : IPrimeFullOpenPbrTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw) {
        return float4(.2 * uvw.x, -.4 * uvw.y, clamp(uvw.z, 0., 1.), .25 * uvw.x);
    }
};

// Locked pre-refactor arithmetic, independent of production prepared metadata helpers.
float4 legacyTexel(uint address) {
    uint packed = texels[address];
    return float4(packed & 255, (packed >> 8) & 255, (packed >> 16) & 255, packed >> 24) / 255.0;
}
float4 legacyPoint(float2 uv) {
    uint4 image = textures[0];
    bool sprite = (image.w & 0x80000000u) != 0;
    float2 local = sprite ? saturate(uv) : frac(uv);
    uint2 pixel = min(uint2(local * float2(image.yz)), image.yz - 1);
    uint stride = sprite ? image.w & 0x7fffffffu : image.y;
    uint at = pixel.y * stride + pixel.x;
    float4 color = legacyTexel(image.x + at);
    if (sprite) {
        uint4 extra = textures[1];
        float blend = asfloat(extra.w);
        if (blend > 0.0)
            color = floor(lerp(color, legacyTexel(extra.x + at), blend) * 255.0 + 0.5) / 255.0;
    }
    return color;
}
float4 legacyLinear(float2 uv) {
    float2 dimensions = float2(textures[0].yz);
    float2 position = uv * dimensions - 0.5;
    float2 lo = floor(position), f = frac(position);
    float4 a = legacyPoint((lo + float2(.5, .5)) / dimensions);
    float4 b = legacyPoint((lo + float2(1.5, .5)) / dimensions);
    float4 c = legacyPoint((lo + float2(.5, 1.5)) / dimensions);
    float4 d = legacyPoint((lo + float2(1.5, 1.5)) / dimensions);
    return lerp(lerp(a, b, f.x), lerp(c, d, f.x), f.y);
}

[numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint index = id.x;
    if (index >= dispatch.y)
        return;
    uint4 ua = inputs[2 * index];
    float4 a = asfloat(ua), b = asfloat(inputs[2 * index + 1]);
    uint4 first = uint4(0), second = uint4(0);
    if (dispatch.x == 0) {
        first = asuint(float4(primeVisibleEmitterRadiance(a.xyz, b.xyz), 0));
    } else if (dispatch.x == 1) {
        first = asuint(
                float4(primeThinVisibility(a.x, float4(a.y, 0, 0, 0), float4(a.z, b.xyz)), 0));
    } else if (dispatch.x == 2) {
        SurfaceHit hit = {};
        hit.physicalFront = dispatch.z != 0;
        hit.incident = a;
        hit.transmitted = b;
        first = asuint(primeThinInsideMedium(hit));
        second = asuint(primeThinOutsideMedium(hit));
    } else if (dispatch.x == 3) {
        float4 air = float4(1, 0, 0, 0), water = float4(1.333, .2916, .04444, .010182);
        float4 terminal = air;
        float3 moment = float3(0);
        float last = -1;
        for (uint i = 0; i < 3; ++i) {
            uint boundary = (ua.x >> (2 * i)) & 3u;
            float distance = boundary == 0 ? 2 : (boundary == 1 ? 5 : 7);
            float4 from = boundary == 1 ? water : air;
            float4 to = boundary == 0 ? water : (boundary == 1 ? air : b);
            primeVisibilityBoundaryMoment(distance, from, to, moment, last, terminal);
        }
        first = asuint(float4(exp(-max(float3(0), moment + terminal.yzw * a.y)), last));
        second = asuint(terminal);
    } else if (dispatch.x == 4) {
        PrimePbrVertex vertex = {};
        vertex.roughness = a.x;
        vertex.control = ua.y;
        bool discrete =
                primePbrVertexIsDiscreteOnly(vertex, float4(a.z, 0, 0, 0), float4(a.w, 0, 0, 0));
        bool support = primeGeometricEvaluationSupport(true, float3(0, 0, 1), b.xyz,
                                                       float3(0, 0, 1), float3(1, 0, b.w),
                                                       (ua.y & PRIME_PBR_VERTEX_DIELECTRIC) != 0);
        first = uint4(discrete ? 1 : 0, support ? 1 : 0, 0, 0);
    } else if (dispatch.x == 6) {
        PrimePbrVertex vertex = {};
        vertex.baseColor = float3(.3, .7, .2);
        vertex.normal = float3(0, 0, 1);
        vertex.roughness = a.x;
        vertex.control = ua.y;
        float4 incident = float4(a.z, 0, 0, 0), transmitted = float4(a.w, .29, 1., 1.1);
        BsdfEvaluation evaluated =
                primeEvaluatePbrVertex(PhysicsTestEnergy(), vertex, normalize(float3(.2, 0, 1)),
                                       normalize(float3(.37, .11, b.w)), incident, transmitted);
        first = asuint(float4(evaluated.response, evaluated.pdf));
        second.x = primePbrVertexIsDiscreteOnly(vertex, incident, transmitted) ? 1 : 0;
    } else {
        first = asuint(primeLinearMaterialLevel(textures, texels, 0, a.xy));
        second = asuint(legacyLinear(a.xy));
    }
    outputs[2 * index] = first;
    outputs[2 * index + 1] = second;
}
