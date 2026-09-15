// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/scatter/contract.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/albedo.slang";
import "contract/medium.slang";

public struct PrimePathScatter {
    public BsdfSample bsdf;
    public PrimeMedium medium;
};

public struct PrimeGuidedPathScatter {
    public PrimePathScatter scatter;
    public PrimeDenoiseAlbedos albedos;
};
