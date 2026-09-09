// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/realtime/radiance.slang";

// Mutually exclusive signal partitions reconstruct the beauty estimate exactly. Guides never
// feed back into these values.
public struct PrimePathRadiance {
    public float3 diffuse;
    public float3 specular;
    public float3 stable;
    public float3 unshadowedSun;
    public float sunVisibility;
};

public PrimePathRadiance primeEmptyPathRadiance() {
    PrimePathRadiance value;
    value.diffuse = float3(0.0);
    value.specular = float3(0.0);
    value.stable = float3(0.0);
    value.unshadowedSun = float3(0.0);
    value.sunVisibility = 0.0;
    return value;
}
