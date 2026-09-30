// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/defaults.slang";

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
// Endpoints of the canonical dielectric codebook, independent of compact OpenPBR mathematics.
public static const float PRIME_COMMON_DIELECTRIC_F0_MINIMUM = 0.02;
public static const float PRIME_COMMON_DIELECTRIC_F0_MAXIMUM = 0.17;
public static const float PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS = 0.9;
