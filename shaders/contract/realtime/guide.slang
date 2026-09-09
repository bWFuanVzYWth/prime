// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/realtime/guide.slang";

// Reconstruction observations describe the sampled path but cannot change its radiance estimate.
public struct PrimeDenoiserGuides {
    public float primaryDistance;
    public float specularHitDistance;
    public float diffuseHitDistance;
    public float sunPenumbra;
    public float3 primaryAlbedo;
    public uint primaryHitKind;
    public float3 primaryNormal;
    public uint primaryMaterialFlags;
    public float3 primarySpecularAlbedo;
    public float primaryLinearRoughness;
    public float3 primaryPosition;
    public float3 primaryPreviousPosition;
    public bool primaryHasMotion;
    public float3 diffuseDirection;
    public float3 specularDirection;
    public float3 primaryAreaDiffuse;
    public float3 primaryAreaSpecular;
    public float3 primaryAreaDirection;
};
