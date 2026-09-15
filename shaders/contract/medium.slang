// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/medium.slang";

// Production transport integrates absorption only. Scattering belongs to compact volume math.
public struct PrimeMedium {
    public float3 extinction;
    public float ior;
    public uint mediumId;
    public uint iorEncoding;
};

public PrimeMedium primeVacuumMedium() {
    PrimeMedium medium = {};
    medium.ior = 1.0;
    return medium;
}
