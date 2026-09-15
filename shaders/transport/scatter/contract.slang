// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/scatter/contract.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/albedo.slang";
import "bsdf/compact/contract/volume.slang";

public struct PrimePathScatter {
    public BsdfSample bsdf;
    public PrimeOpenPbrVolume medium;
};

public struct PrimeGuidedPathScatter {
    public PrimePathScatter scatter;
    public PrimeDenoiseAlbedos albedos;
};
