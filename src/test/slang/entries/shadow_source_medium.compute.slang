// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "transport/direct/medium.slang";
import "model/material/transparent.slang";
import "model/material/defaults.slang";
import "model/material/medium_boundary.slang";
import "prime_abi_types.slang";
import "contract/medium.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    uint kind = index % 6u;
    bool entering = (index / 6u & 1u) != 0u;
    bool reflection = (index / 12u & 1u) != 0u;
    uint distanceCase = index / 24u;
    float distance = distanceCase == 0u ? 0.0 : (distanceCase == 1u ? 2.0 : 1000000.0);
    SurfaceInteraction surface = {};
    surface.geometricNormal = float3(0.0, 1.0, 0.0);
    surface.shadingNormal = normalize(float3(0.9, 0.1, 0.0));
    surface.opacity = 1.0;
    surface.roughness = 0.5;
    surface.materialControl = PRIME_MATERIAL_FAMILY_DIELECTRIC;
    surface.mediumId = 17u;
    if (kind == 1u) {
        surface.materialControl |= PRIME_MATERIAL_MEDIUM_WATER;
        surface.mediumId = 1u;
    }
    if (kind == 2u || kind == 3u) {
        surface.adjacentInterfaceControl = PRIME_ADJACENT_INTERFACE_VALID
                | PRIME_MATERIAL_FAMILY_DIELECTRIC | PRIME_MATERIAL_MEDIUM_WATER;
        surface.adjacentMediumId = 1u;
        if (kind == 3u) surface.adjacentInterfaceControl |= PRIME_ADJACENT_MICRO_GAP_ELIGIBLE;
    }
    if (kind == 4u) surface.materialControl |= PRIME_MATERIAL_THIN_WALLED;
    if (kind == 5u) surface.materialControl = 0u;
    PrimeMedium incident = primeVacuumMedium();
    incident.extinction = float3(0.2, 0.5, 0.8);
    incident.ior = 1.5;
    incident.mediumId = 47u;
    float3 view = normalize(float3(0.2, entering ? 1.0 : -1.0, 0.0));
    float3 direction = reflection ? view : -view;
    PrimeMedium start = primeShadowStartingMedium(surface, view, direction, incident);
    float3 extinction = primeShadowCanonicalExtinction(start.extinction);
    // An unobstructed connection has no any-hit boundary after the excluded source surface.
    float3 filter = exp(-primeShadowOpticalDepth(float3(0.0), extinction, distance));
    outputData[2u * index] = float4(filter, float(start.mediumId));
    outputData[2u * index + 1u] = float4(extinction, start.ior);
}
