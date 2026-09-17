// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/material/shadow_interaction.compute.slang";

import "service/material/static_color.slang";
import "service/trace/surface_records.slang";
import "model/material/transparent.slang";
import "model/material/shadow_interaction.slang";
import "math/packed_geometry.slang";
import "model/material/recipe.slang";
import "prime_abi_types.slang";
import "prime_shadow_interaction_abi.slang";

struct ShadowInteractionPush {
    uint64_t requests;
    uint64_t output;
    uint count;
};
[[vk::push_constant]] ShadowInteractionPush shadowPush;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    if (thread.x >= shadowPush.count) return;
    uint2 request = ((uint2*)shadowPush.requests)[thread.x];
    uint4 uv = uint4(primeSurfaceRecords[request.x].uv0, primeSurfaceRecords[request.x].uv1,
            primeSurfaceRecords[request.x].uv2, primeSurfaceRecords[request.x].uvDensity);
    float2 referenceUv;
    if (uv.w == PRIME_CONSTANT_UV_DENSITY) {
        referenceUv = float2(asfloat(uv.x), asfloat(uv.y));
    } else {
        float2 first = primeUnpackUv(uv.x);
        float2 second = primeUnpackUv(uv.y);
        float2 third = primeUnpackUv(uv.z);
        referenceUv = 0.5 * (min(first, min(second, third)) + max(first, max(second, third)));
    }
    float opacity;
    float3 color = primeStaticBaseColor(request.y, primeSurfaceRecords[request.x].tint,
            referenceUv, 0.0, opacity);
    ((uint4*)shadowPush.output)[request.x] = primeEncodeShadowInteraction(
            primeShadowCanonicalExtinction(primeGlassShadowExtinction(color, opacity)),
            primeGlassReferenceIsStained(opacity));
}
