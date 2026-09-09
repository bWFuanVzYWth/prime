// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/runtime/config.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";

public uint primeSampleIndex()
{
    return primePush.path.x & PRIME_PATH_SAMPLE_INDEX_MASK;
}

public float primeSolarLongitudeRadians()
{
    uint encoded = (primePush.path.x >> PRIME_PATH_SOLAR_LONGITUDE_SHIFT)
            & PRIME_PATH_SOLAR_LONGITUDE_MASK;
    return radians(float(encoded));
}

public bool primeUsesSeamlessGlass()
{
    return (primePush.path.x & PRIME_PATH_SEAMLESS_GLASS_MASK) != 0u;
}

public bool primeUsesAirGap()
{
    return (primePush.path.x & PRIME_PATH_AIR_GAP_MASK) != 0u;
}

public bool primeUsesVanillaPbrPresets()
{
    return (primePush.path.x & PRIME_PATH_VANILLA_PBR_PRESETS_MASK) != 0u;
}

public bool primeUsesUnbiasedTransparentNee()
{
    return (primePush.path.x & PRIME_PATH_TRANSPARENT_NEE_UNBIASED_MASK) != 0u;
}

public float primeObserverLatitudeRadians()
{
    uint encoded = (primePush.path.z >> PRIME_PATH_LATITUDE_SHIFT)
            & PRIME_PATH_LATITUDE_MASK;
    return radians(float(int(encoded) - PRIME_PATH_LATITUDE_BIAS));
}

public uint primeMaximumBounces()
{
    return min(
            primePush.path.z & PRIME_PATH_MAXIMUM_BOUNCES_MASK,
            PRIME_MAXIMUM_BOUNCES);
}

int primeDecodeEvQuarterSteps(uint shift)
{
    uint encoded = (primePush.path.w >> shift) & PRIME_PATH_EV_QUARTER_MASK;
    return int(encoded) - PRIME_PATH_EV_QUARTER_BIAS;
}

public float primeSunRadianceMultiplier()
{
    return exp2(0.25 * float(primeDecodeEvQuarterSteps(PRIME_PATH_SUN_EV_QUARTER_SHIFT)));
}

public float primeBlockLightRadianceMultiplier()
{
    return exp2(0.25 * float(primeDecodeEvQuarterSteps(
            PRIME_PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT)));
}

public float primeStarRadianceMultiplier()
{
    uint encoded = (primePush.path.w >> PRIME_PATH_STAR_EV_QUARTER_SHIFT)
            & PRIME_PATH_STAR_EV_QUARTER_MASK;
    int quarterSteps = int(encoded) - PRIME_PATH_STAR_EV_QUARTER_BIAS;
    return exp2(0.25 * float(quarterSteps));
}

public bool primeWritesNrdShInputs()
{
    return (primePush.path.w & PRIME_PATH_SH_INPUT_MASK) != 0u;
}

public uint primeTransparentGuideMode()
{
    return (primePush.path.z >> PRIME_PATH_TRANSPARENT_GUIDE_MODE_SHIFT)
            & PRIME_PATH_TRANSPARENT_GUIDE_MODE_MASK;
}

public uint primeSampleEpoch()
{
    return primePush.path.y & PRIME_PATH_SAMPLE_EPOCH_MASK;
}

public float primeConfiguredDefaultLinearRoughness()
{
    uint encoded = (primePush.path.w >> PRIME_PATH_MATERIAL_ROUGHNESS_SHIFT)
            & PRIME_PATH_MATERIAL_ROUGHNESS_MASK;
    return float(encoded) / PRIME_PATH_MATERIAL_ROUGHNESS_STEPS_PER_UNIT;
}
