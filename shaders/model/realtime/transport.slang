// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/realtime/transport.slang";

public struct PrimeDenoiserState {
    public bool hasPrimarySurface;
    public bool reachedSolidAngle;
    public float3 diffuseAlbedoProduct;
    public float3 specularAlbedoProduct;
    public bool diffusePath;
    public uint primaryBounce;
};

public PrimeDenoiserState primeInitialDenoiserState() {
    PrimeDenoiserState state;
    state.hasPrimarySurface = false;
    state.reachedSolidAngle = false;
    state.diffuseAlbedoProduct = float3(1.0);
    state.specularAlbedoProduct = float3(0.0);
    state.diffusePath = false;
    state.primaryBounce = 0u;
    return state;
}
