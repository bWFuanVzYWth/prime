// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "service/bsdf/opaque/material.slang";
import "model/material/defaults.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/traits.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/support/transmission_energy.slang";
import "service/bsdf/opaque/state.slang";
import "service/bsdf/opaque/albedo.slang";
import "service/bsdf/foliage/state.slang";
import "bsdf/compact/opaque/energy.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0].x) return;
    float4 source = asfloat(inputData[1u + index * 2u]);
    uint4 control = inputData[2u + index * 2u];
    float3 normal = float3(0.0, 0.0, 1.0);
    float minimumRoughness = asfloat(control.z);
    PrimeOpenPbrMaterial material = primeMaterialIsFoliage(control.y)
            ? primeMinecraftFoliageMaterialWithMinimumRoughness(
                    source.rgb, normal, source.a, control.x, control.y, minimumRoughness)
            : primeOpaqueMaterialWithMinimumRoughness(
                    source.rgb, normal, source.a, control.x, control.y, minimumRoughness);
    uint output = index * 6u;
    outputData[output] = float4(material.base.color, material.specular.roughness);
    outputData[output + 1u] = float4(material.subsurface.color, material.weight.subsurface);
    outputData[output + 2u] = float4(material.transmission.color, material.weight.transmission);
    outputData[output + 3u] = float4(0.0);
    outputData[output + 4u] = float4(0.0);
    outputData[output + 5u] = float4(0.0);
    if (primeMaterialIsFoliage(control.y)) return;

    PrimeOpenPbrVolume medium = primeVacuumMedium();
    PrimeCompactOpaqueState state = primeOpaqueStateWithMinimumRoughness(
            source.rgb, normal, source.a, control.x, control.y, normal, 0.0, medium,
            minimumRoughness);
    PrimeCompactOpaqueEnergy energy = primeCompactOpaqueEnergy(normal, state);
    outputData[output + 1u] = float4(energy.diffuse + energy.specular, energy.specular.x);

    // Restore the authored input to measure the old material using the same OpenPBR math.
    material.base.color = source.rgb;
    PrimeCompactOpaqueState previous = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(), material, normal, 1.0, 0.0);
    PrimeCompactOpaqueEnergy oldEnergy = primeCompactOpaqueEnergy(normal, previous);
    outputData[output + 2u] = float4(oldEnergy.diffuse + oldEnergy.specular, 0.0);

    float cosine = asfloat(control.w);
    float3 view = float3(sqrt(1.0 - cosine * cosine), 0.0, cosine);
    state = primeOpaqueStateWithMinimumRoughness(
            source.rgb, normal, source.a, control.x, control.y, view, 0.0, medium,
            minimumRoughness);
    energy = primeCompactOpaqueEnergy(view, state);
    PrimeDenoiseAlbedos guide = primeDenoiseAlbedosFromState(
            state, view, PRIME_DENOISE_CLOSURE_OPAQUE);
    outputData[output + 3u] = float4(guide.diffuse, state.subsurfaceWeight);
    outputData[output + 4u] = float4(guide.specular, state.kind);
    outputData[output + 5u] = float4(energy.diffuse + energy.specular, 0.0);
}
