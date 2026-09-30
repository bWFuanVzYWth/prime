// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/contract/components.slang";

public struct PrimeBsdfComponents {
    public float3 diffuseResponse;
    public float3 specularResponse;
    public float pdf;
};
