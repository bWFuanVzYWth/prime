// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/contract/volume_sample.slang";

import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/contract/volume.slang";

public struct PrimeOpenPbrSampleResult {
    public PrimeOpenPbrSample bsdfSample;
    public PrimeOpenPbrVolume medium;
    public float rayT;
};
