// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/contract/volume.slang";

import "bsdf/full/contract/flags.slang";

public struct PrimeFullOpenPbrVolume {
    public float3 extinction;
    public float3 albedo;
    public float anisotropy;
    public float ior;
    public uint mediumId;
    public uint iorEncoding;
};

// A ray carries exactly one resolved medium. Zero is the explicit vacuum endpoint.
public PrimeFullOpenPbrVolume primeFullOpenPbrVacuumVolume() {
    PrimeFullOpenPbrVolume medium = {};
    medium.ior = 1.0;
    return medium;
}
