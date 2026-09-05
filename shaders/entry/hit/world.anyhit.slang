#language slang 2026
module "entry/hit/world.anyhit.slang";

import glsl;
import "model/material/recipe.slang";
import "prime_abi_types.slang";
import "service/trace/candidate.slang";
import "service/trace/candidate_record.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    // Slang's GLSL front end otherwise diagnoses this input-only aggregate as uninitialized.
    // t is already reset to zero before traversal and closest-hit overwrites it on acceptance.
    primePayload.t = 0.0;
    if (primeCandidateIsSource(uint2(primePayload.sectionIndex, primePayload.positionReserved))) {
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
