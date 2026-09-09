// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/realtime/transparent.slang";

import "contract/realtime/guide.slang";

public struct PrimeTransparentBranchResult {
    public float3 diffuseRadiance;
    public float3 specularRadiance;
    public PrimeDenoiserGuides guides;
    public float firstHitDistance;
    public float anchorDistance;
    public bool directionalGuide;
};
