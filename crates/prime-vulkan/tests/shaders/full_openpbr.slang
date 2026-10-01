// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module full_openpbr;
import "bsdf/full/energy.slang";
import "bsdf/full/pt.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/volume.slang";
import "bsdf/full/model/material.slang";
import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/traits.slang";
import "bsdf/full/opaque/evaluate.slang";
import "bsdf/full/opaque/sample.slang";
import "bsdf/full/dielectric/state.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/refract.slang";
import "bsdf/full/dielectric/evaluate.slang";
import "bsdf/full/math/microfacet_energy.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint4> cases;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> results;
[[vk::binding(9, 0)]] Sampler3D<float4> transmissionEnergy;
[[vk::push_constant]] ConstantBuffer<uint4> control;
void fullCase(uint3 id, PrimeFullTransmissionGgxEnergy energy, bool narrow) {
    uint o = id.x * 6;
    uint r = id.x * 8 + (narrow ? 4 : 0);
    float3 color = asfloat(cases[o]).xyz;
    float roughness = asfloat(cases[o]).w;
    float3 wi = asfloat(cases[o + 1]).xyz;
    float ior = asfloat(cases[o + 1]).w;
    float3 wo = asfloat(cases[o + 2]).xyz;
    float sss = asfloat(cases[o + 2]).w;
    float3 random = asfloat(cases[o + 3]).xyz;
    uint kind = cases[o + 3].w;
    float3 f0 = asfloat(cases[o + 4]).xyz;
    float3 f82 = asfloat(cases[o + 5]).xyz;
    PrimeFullOpenPbrSample sampled;
    PrimeFullOpenPbrThroughput throughput;
    float pdf;
    if (kind < 2) {
        PrimeFullCompactOpaqueState state;
        if (narrow) {
            state = primeFullPtOpaqueState(energy, color, roughness, ior, sss, kind == 1, f0, f82,
                                           wi);
        } else {
            PrimeFullOpenPbrMaterial material = primeFullCompactMaterialFromMetallic(
                    color, roughness, kind == 1 ? 1.0 : 0.0, float3(0, 0, 1));
            material.specular.ior = ior;
            material.weight.subsurface = sss;
            material.geometry.thinWalled = 1;
            material.subsurface.color = color;
            if (kind == 1) {
                material.base.color = f0;
                material.specular.color = f82;
            }
            state = primeFullCompactOpaqueStateInit(energy, material, wi, 1.0);
        }
        PrimeFullCompactOpaqueEvaluation eval = primeFullCompactOpaqueEvaluate(wi, wo, state);
        throughput = eval.throughput;
        pdf = eval.pdf;
        sampled = primeFullCompactOpaqueSample(wi, random, state);
        if (kind != 1 && sampled.throughput.flags != 0 && sampled.pdf > 0 &&
            all(isfinite(sampled.wo)) && all(isfinite(sampled.throughput.value)) &&
            all(sampled.throughput.value >= 0) && abs(dot(sampled.wo, sampled.wo) - 1.0) <= 1e-3 &&
            isfinite(sampled.pdf) && !primeFullOpenPbrIsDelta(sampled.throughput.flags)) {
            eval = primeFullCompactOpaqueEvaluate(wi, sampled.wo, state);
            sampled.throughput.value = eval.throughput.value;
            sampled.pdf = eval.pdf;
        }
    } else {
        PrimeFullCompactTransmissionState state;
        if (narrow) {
            state = primeFullPtTransmissionState(energy, roughness, ior, kind == 3,
                                                 float3(.01, .02, .03), wi);
        } else {
            PrimeFullOpenPbrMaterial material =
                    primeFullCompactMaterialFromMetallic(float3(1), roughness, 0, float3(0, 0, 1));
            material.specular.ior = ior;
            material.weight.transmission = 1;
            material.geometry.thinWalled = kind == 3 ? 1 : 0;
            material.transmission.color = float3(1);
            material.transmission.depth = 1;
            state = primeFullCompactTransmissionStateInit(energy, material, wi,
                                                          primeFullOpenPbrVacuumVolume(), 0, wi.z);
            state.transmissionVolume.extinction = float3(.01, .02, .03);
        }
        PrimeFullCompactTransmissionEvaluation eval =
                primeFullCompactTransmissionEvaluate(wi, wo, state, PRIME_FULL_OPENPBR_FLAG_ALL);
        throughput = eval.throughput;
        pdf = eval.pdf;
        sampled = primeFullCompactTransmissionSampleRaw(
                wi, random, primeFullCompactTransmissionClosureState(state),
                PRIME_FULL_OPENPBR_FLAG_ALL);
    }
    results[r] = asuint(float4(sampled.wo, sampled.eta));
    results[r + 1] = asuint(float4(sampled.throughput.value, sampled.pdf));
    results[r + 2] = uint4(sampled.throughput.flags, throughput.flags, 0, 0);
    results[r + 3] = asuint(float4(throughput.value, pdf));
}

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= control.y)
        return;
    PrimeFullTransmissionGgxEnergy energy = {transmissionEnergy};
    fullCase(id, energy, false);
    fullCase(id, energy, true);
}
