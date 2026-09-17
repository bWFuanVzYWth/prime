// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/light_receiver.slang";

// Exact inputs for closure classification and light-tree weighting; no shading/guide outputs.
public struct PrimeSurfaceClassification {
    public uint materialControl;
    public float roughness;
    public uint opticalControl;
    public uint adjacentInterfaceControl;
};

public struct PrimeLightReceiver {
    public uint hitKind;
    public PrimeSurfaceClassification classification;
    public float3 position;
    public float3 geometricNormal;
    public float3 shadingNormal;
};
