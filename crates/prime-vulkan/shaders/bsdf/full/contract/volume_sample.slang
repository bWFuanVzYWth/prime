// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/contract/volume_sample.slang";

import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/volume.slang";

public struct PrimeFullOpenPbrSampleResult {
    public PrimeFullOpenPbrSample bsdfSample;
    public PrimeFullOpenPbrVolume medium;
    public float rayT;
};
