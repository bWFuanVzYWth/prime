// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import glsl;
import "prime_abi_types.slang";
import "service/material/static_color.slang";
import "service/material/access.slang";
import "service/trace/surface_records.slang";
import "model/material/transparent.slang";

struct ReferencePush { uint64_t requests; uint64_t output; uint count; };
[[vk::push_constant]] ReferencePush referencePush;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    if (thread.x >= referencePush.count) return;
    uint2 request = ((uint2*)referencePush.requests)[thread.x];
    PrimitiveRecord surface = primeSurfaceRecords[request.x];
    float opacity;
    float3 color = primeStaticBaseColor(request.y, surface.tint,
            primePrimitiveMaterialReferenceUv(surface), 0.0, opacity);
    // The old per-hit path's exact canonicalization and branch, before sidecar encoding.
    float3 extinction = primeShadowCanonicalExtinction(primeGlassShadowExtinction(color, opacity));
    ((uint4*)referencePush.output)[request.x] = uint4(asuint(extinction),
            primeGlassReferenceIsStained(opacity) ? 1u : 0u);
}
