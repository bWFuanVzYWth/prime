// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/contract/flags.slang";

public static const uint PRIME_OPENPBR_FLAG_NONE = 0u;
public static const uint PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION = 1u << 0u;
public static const uint PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION = 1u << 1u;
public static const uint PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION = 1u << 2u;
public static const uint PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION = 1u << 3u;
public static const uint PRIME_OPENPBR_FLAG_DELTA_REFLECTION = 1u << 4u;
public static const uint PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION = 1u << 5u;

public static const uint PRIME_OPENPBR_FLAG_DIFFUSE =
        PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION
        | PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
public static const uint PRIME_OPENPBR_FLAG_SPECULAR =
        PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION
        | PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
public static const uint PRIME_OPENPBR_FLAG_DELTA =
        PRIME_OPENPBR_FLAG_DELTA_REFLECTION
        | PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;
public static const uint PRIME_OPENPBR_FLAG_REFLECTION =
        PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION
        | PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION
        | PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
public static const uint PRIME_OPENPBR_FLAG_TRANSMISSION =
        PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION
        | PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION
        | PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;
public static const uint PRIME_OPENPBR_FLAG_ALL =
        PRIME_OPENPBR_FLAG_REFLECTION | PRIME_OPENPBR_FLAG_TRANSMISSION;

public static const uint PRIME_OPENPBR_DIFFRACTION_RECTANGULAR = 1u;

public bool primeOpenPbrIsReflective(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_REFLECTION) != 0u;
}

public bool primeOpenPbrIsTransmissive(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_TRANSMISSION) != 0u;
}

public bool primeOpenPbrIsDiffuse(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_DIFFUSE) != 0u;
}

public bool primeOpenPbrIsSpecular(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_SPECULAR) != 0u;
}

public bool primeOpenPbrIsDelta(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_DELTA) != 0u;
}

public bool primeOpenPbrIsNonDelta(uint flags) {
    return (flags & (PRIME_OPENPBR_FLAG_DIFFUSE
            | PRIME_OPENPBR_FLAG_SPECULAR)) != 0u;
}
