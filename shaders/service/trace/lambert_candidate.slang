#language slang 2026
module "service/trace/lambert_candidate.slang";

import "service/trace/candidate.slang";
import "service/trace/candidate_record.slang";

public bool primeLambertRejectCandidate(uint2 source, float2 barycentrics) {
    if (primeCandidateIsSource(source)) return true;
    if (GeometryIndex() == 0u) return false;
    return primeCandidateCutout(primeCandidateSurface(barycentrics), barycentrics);
}
