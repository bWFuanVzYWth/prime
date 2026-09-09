// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/medium_boundary.slang";

public static const uint PRIME_ADJACENT_MATERIAL_MASK = 0xff;
public static const uint PRIME_ADJACENT_FRESNEL_SHIFT = 8;
public static const uint PRIME_ADJACENT_FRESNEL_MASK = 0xff << PRIME_ADJACENT_FRESNEL_SHIFT;
public static const uint PRIME_ADJACENT_INTERFACE_VALID = 1 << 16;
public static const uint PRIME_ADJACENT_MICRO_GAP_ELIGIBLE = 1 << 17;

public uint primeAdjacentMaterialControl(uint control)
{
    return control & PRIME_ADJACENT_MATERIAL_MASK;
}

public uint primeAdjacentFresnelCode(uint control)
{
    return (control >> PRIME_ADJACENT_FRESNEL_SHIFT) & 0xff;
}
