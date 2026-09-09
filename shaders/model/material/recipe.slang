// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/recipe.slang";

public static const uint PRIME_CONSTANT_UV_DENSITY = 0x80000000u;
public static const uint PRIME_CONSTANT_UV_OWN_TINT = 1u;
public static const uint PRIME_CONSTANT_UV_BAKED_MATERIAL = 2u;
public static const uint PRIME_RECIPE_ALPHA_CUTOUT = 1u;
public static const uint PRIME_RECIPE_ANIMATED = 1u << 1u;
public static const uint PRIME_RECIPE_SCATTERING_SHIFT = 2u;
public static const uint PRIME_RECIPE_SCATTERING_MASK =
        3u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_DIELECTRIC_SOLID =
        1u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_DIELECTRIC_THIN =
        2u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_FOLIAGE_THIN =
        3u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_WATER = 1u << 4u;
public static const uint PRIME_RECIPE_TANGENT_NEGATIVE = 1u << 7u;
public static const uint PRIME_RECIPE_BUILTIN_SHIFT = 11u;
public static const uint PRIME_RECIPE_BUILTIN_MASK =
        15u << PRIME_RECIPE_BUILTIN_SHIFT;
public static const uint PRIME_RECIPE_MATERIAL_MASK =
        0xffu | PRIME_RECIPE_BUILTIN_MASK;
public static const uint PRIME_DYNAMIC_TEXTURE_FLAG = 0x80000000u;
public static const uint PRIME_VISIBLE_EMISSION_FLAG = 1u << 10u;
public bool primeRecipeIsTransmissive(uint control) {
    uint scattering = control & PRIME_RECIPE_SCATTERING_MASK;
    return scattering == PRIME_RECIPE_DIELECTRIC_SOLID
            || scattering == PRIME_RECIPE_DIELECTRIC_THIN;
}
public bool primeRecipeIsThinWalled(uint control) {
    uint scattering = control & PRIME_RECIPE_SCATTERING_MASK;
    return scattering == PRIME_RECIPE_DIELECTRIC_THIN
            || scattering == PRIME_RECIPE_FOLIAGE_THIN;
}
