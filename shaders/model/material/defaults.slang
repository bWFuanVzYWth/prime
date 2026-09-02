#language slang 2026
module "model/material/defaults.slang";

public static const uint PRIME_MATERIAL_FAMILY_MASK = 3;
public static const uint PRIME_MATERIAL_FAMILY_OPAQUE = 0;
public static const uint PRIME_MATERIAL_FAMILY_DIELECTRIC = 1;
public static const uint PRIME_MATERIAL_FAMILY_FOLIAGE = 2;
public static const uint PRIME_MATERIAL_MEDIUM_SHIFT = 2;
public static const uint PRIME_MATERIAL_MEDIUM_MASK = 3 << PRIME_MATERIAL_MEDIUM_SHIFT;
public static const uint PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS = 1 << PRIME_MATERIAL_MEDIUM_SHIFT;
public static const uint PRIME_MATERIAL_MEDIUM_STAINED_GLASS = 2 << PRIME_MATERIAL_MEDIUM_SHIFT;
public static const uint PRIME_MATERIAL_MEDIUM_WATER = 3 << PRIME_MATERIAL_MEDIUM_SHIFT;
public static const uint PRIME_MATERIAL_THIN_WALLED = 1 << 4;
public static const uint PRIME_MATERIAL_ANIMATED = 1 << 5;
public static const uint PRIME_MATERIAL_VISIBLE_EMISSION = 1 << 6;
public static const uint PRIME_MATERIAL_DECORATIVE_INTERFACE = 1 << 7;

public static const uint PRIME_OPTICAL_FRESNEL_MASK = 0xff;
public static const uint PRIME_OPTICAL_SUBSURFACE_SHIFT = 8;
public static const uint PRIME_OPTICAL_POROSITY_SHIFT = 16;
public static const uint PRIME_FRESNEL_DEFAULT_DIELECTRIC = 0;
public static const uint PRIME_FRESNEL_IRON = 231;
public static const uint PRIME_FRESNEL_GOLD = 232;
public static const uint PRIME_FRESNEL_ALUMINIUM = 233;
public static const uint PRIME_FRESNEL_CHROME = 234;
public static const uint PRIME_FRESNEL_COPPER = 235;
public static const uint PRIME_FRESNEL_LEAD = 236;
public static const uint PRIME_FRESNEL_PLATINUM = 237;
public static const uint PRIME_FRESNEL_SILVER = 238;
public static const uint PRIME_FRESNEL_CUSTOM_CONDUCTOR = 239;

public static const float PRIME_DEFAULT_DIELECTRIC_F0 = 0.04;
// LabPBR's byte range includes invalid/extreme dielectrics. These bounds are deliberate input
// cleaning at the translation boundary and are not part of compact OpenPBR mathematics.
public static const float PRIME_COMMON_DIELECTRIC_F0_MINIMUM = 0.02;
public static const float PRIME_COMMON_DIELECTRIC_F0_MAXIMUM = 0.17;
public static const float PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS = 0.9;

public static const float PRIME_BUILTIN_ROUGHNESS[13] = {
    0.9, 0.82, 0.48, 0.96, 0.72, 0.98, 0.62,
    0.28, 0.90, 0.38, 0.30, 0.34, 0.78
};

public float primeBuiltinRoughness(
        uint materialClass,
        float defaultLinearRoughness)
{
    return materialClass < 13
            ? PRIME_BUILTIN_ROUGHNESS[materialClass]
            : clamp(defaultLinearRoughness, 0.0, 1.0);
}

public float primeBuiltinRoughness(uint materialClass)
{
    return primeBuiltinRoughness(
            materialClass,
            PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS);
}

public uint primeBuiltinFresnelCode(uint materialClass)
{
    return materialClass == 9
            ? PRIME_FRESNEL_IRON
            : materialClass == 10
                    ? PRIME_FRESNEL_GOLD
                    : materialClass == 11 ? PRIME_FRESNEL_COPPER : 0;
}

public uint primeMaterialFamily(uint control) { return control & PRIME_MATERIAL_FAMILY_MASK; }
public uint primeMaterialMedium(uint control) { return control & PRIME_MATERIAL_MEDIUM_MASK; }
public bool primeMaterialIsTransmissive(uint control)
{
    return primeMaterialFamily(control) == PRIME_MATERIAL_FAMILY_DIELECTRIC;
}
public bool primeMaterialIsFoliage(uint control)
{
    return primeMaterialFamily(control) == PRIME_MATERIAL_FAMILY_FOLIAGE;
}
public bool primeMaterialIsColorlessGlass(uint control)
{
    return primeMaterialMedium(control) == PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS;
}
public bool primeMaterialIsWater(uint control)
{
    return primeMaterialMedium(control) == PRIME_MATERIAL_MEDIUM_WATER;
}
public bool primeMaterialIsRoughGlass(uint control)
{
    return (control & PRIME_MATERIAL_DECORATIVE_INTERFACE) != 0;
}
public float primeMaterialEffectiveLinearRoughness(uint control, float roughness)
{
    // Smooth dielectric bodies, including water, ignore authored roughness in the compact closure.
    // Reconstruction must use that same value or RR will reject their deterministic reflection MV.
    return primeMaterialIsTransmissive(control) && !primeMaterialIsRoughGlass(control)
            ? 0.0
            : clamp(roughness, 0.0, 1.0);
}
public bool primeFresnelIsConductor(uint fresnelCode)
{
    return fresnelCode >= PRIME_FRESNEL_IRON
            && fresnelCode <= PRIME_FRESNEL_CUSTOM_CONDUCTOR;
}
public float primeDecodeDielectricF0(uint fresnelCode)
{
    return fresnelCode == PRIME_FRESNEL_DEFAULT_DIELECTRIC
            ? PRIME_DEFAULT_DIELECTRIC_F0
            : clamp(
                    float(fresnelCode - 1) / 255.0,
                    PRIME_COMMON_DIELECTRIC_F0_MINIMUM,
                    PRIME_COMMON_DIELECTRIC_F0_MAXIMUM);
}

public struct PrimeFsrMasks
{
    public float reactive;
    public float transparencyAndComposition;
};

public PrimeFsrMasks primeFsrMasks(uint control)
{
    PrimeFsrMasks masks;
    if (primeMaterialIsTransmissive(control)) {
        masks.reactive = 0.0;
        masks.transparencyAndComposition = 1.0;
        return masks;
    }
    masks.reactive = 0.0;
    masks.transparencyAndComposition =
            (control & PRIME_MATERIAL_ANIMATED) != 0 ? 0.75 : 0.0;
    return masks;
}
