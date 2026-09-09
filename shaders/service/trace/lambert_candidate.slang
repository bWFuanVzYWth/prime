// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/lambert_candidate.slang";

import "service/trace/candidate.slang";
import "service/trace/candidate_record.slang";

public bool primeLambertRejectCandidate(uint2 source, float2 barycentrics) {
    if (primeCandidateIsSource(source)) return true;
    if (GeometryIndex() == 0u) return false;
    // Class 2 cannot have coverage without a translated surface relation.
    if (GeometryIndex() == 2u && primeCandidateSection()->surfaceRelationAddress == uint64_t(0)) return false;
    PrimeCandidateRecord record = primeCandidateRecord();
    uint3 header = primeCandidateHeader(record);
    uint64_t relation = primeCandidateRelation(header);
    if (GeometryIndex() == 2u && (relation == uint64_t(0) || ((uint*)relation)[0] == 0u)) return false;
    return primeCandidateCutout(primeCandidateSurface(record, header, relation, barycentrics), barycentrics);
}
