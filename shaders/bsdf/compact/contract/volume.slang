// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/contract/volume.slang";

import "bsdf/compact/contract/flags.slang";

public struct PrimeOpenPbrVolume {
    public float3 extinction;
    public float3 albedo;
    public float anisotropy;
    public float ior;
    public uint mediumId;
    public uint iorEncoding;
};

// A ray carries exactly one resolved medium. Zero is the explicit vacuum endpoint.
public PrimeOpenPbrVolume primeVacuumMedium() {
    PrimeOpenPbrVolume medium = {};
    medium.ior = 1.0;
    return medium;
}
