#language slang 2026
module "service/reconstruct/primary_guide.slang";

import "contract/realtime/transparent.slang";
import "service/reconstruct/nrd/common.slang";

// A guide-only ray observes geometry but contributes no radiance. Rough primaries keep their
// current guide and need a first BSDF distance; delta chains still need a replacement surface.
public void primeRecordPrimaryGuideSegment(
        inout PrimeTransparentBranchResult result, bool hasGuide, bool diffuse, bool hit, float distance) {
    result.firstHitDistance = hit ? primeNrdSanitizeHitDistance(distance) : PRIME_NRD_FP16_MAX;
    if (hasGuide) {
        if (diffuse) result.guides.diffuseHitDistance = result.firstHitDistance;
        else result.guides.specularHitDistance = result.firstHitDistance;
    }
}
