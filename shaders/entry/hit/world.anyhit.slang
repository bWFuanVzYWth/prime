// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/hit/world.anyhit.slang";

import glsl;
import "model/material/recipe.slang";
import "prime_abi_types.slang";
import "service/trace/candidate.slang";
import "service/trace/candidate_record.slang";
import "contract/trace.slang";

#if defined(PRIME_ENABLE_SER)
layout(location = 2) rayPayloadInEXT PrimeTraceRequest primeTraceRequest;
#else
layout(location = 0) rayPayloadInEXT TracePayload primePayload;
#endif

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    // Slang requires an aggregate write before reading the incoming payload.
#if defined(PRIME_ENABLE_SER)
    primeTraceRequest.reserved = 0u;
    uint2 source = primeTraceRequest.source;
#else
    primePayload.t = 0.0;
    uint2 source = uint2(primePayload.sectionIndex, primePayload.positionReserved);
#endif
    if (primeCandidateIsSource(source)) {
        IgnoreHit();
        return;
    }
    if (GeometryIndex() == 0u) return;
    // This exact translation class has no cutout without a surface relation.
    if (GeometryIndex() == 2u && primeCandidateSection()->surfaceRelationAddress == uint64_t(0)) return;
    PrimeCandidateRecord record = primeCandidateRecord();
    uint3 header = primeCandidateHeader(record);
    uint64_t relation = primeCandidateRelation(header);
    if (GeometryIndex() == 2u && (relation == uint64_t(0) || ((uint*)relation)[0] == 0u)) return;
    PrimeCandidateSurface surface = primeCandidateSurface(record, header, relation, attributes.barycentrics);
    // Radiance rays keep transmissive geometry as explicit path vertices.
    if (primeRecipeIsTransmissive(surface.recipe)) return;
    if (primeCandidateCutout(surface, attributes.barycentrics)) IgnoreHit();
}
