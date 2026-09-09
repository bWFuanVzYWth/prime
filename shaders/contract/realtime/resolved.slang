// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/realtime/resolved.slang";

import "contract/realtime/guide.slang";
import "contract/realtime/radiance.slang";

// This complete reconstruction state is restricted to resolve and output modules.
public struct PrimeResolvedSample {
    public PrimePathRadiance radiance;
    public PrimeDenoiserGuides guides;
    public float3 reflectionDiffuseRadiance;
    public float3 reflectionSpecularRadiance;
    public PrimeDenoiserGuides transmissionGuides;
    public PrimeDenoiserGuides reflectionGuides;
    public float transmissionAnchorDistance;
    public bool reflectionDirectionalGuide;
    public bool transparentPrimary;
    public bool transparentReflectionOnly;
};
