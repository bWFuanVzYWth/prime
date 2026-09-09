// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/contract/albedo.slang";

public static const uint PRIME_DENOISE_CLOSURE_OPAQUE = 0u;
public static const uint PRIME_DENOISE_CLOSURE_FOLIAGE = 1u;
public static const uint PRIME_DENOISE_CLOSURE_TRANSMISSIVE = 2u;

public struct PrimeDenoiseAlbedos {
    public float3 diffuse;
    public float3 specular;
};
