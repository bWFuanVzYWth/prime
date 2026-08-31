package dev.prime.render;

import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.shader.ShaderAbi;

/**
 * Internal, deliberately small adapter between Minecraft's world and the path integrator.
 *
 * <p>All RGB radiance written here is linear Rec.2020 D65. That meaning is part of the shader ABI:
 * adapters may change the light model, but must not supply encoded sRGB or silently change the RGB
 * basis without migrating every material, path-state, accumulation, and presentation boundary.
 */
public final class IntegratorSettings {
    public static final int MAXIMUM_BOUNCES = ShaderAbi.MAXIMUM_BOUNCES;
    static final int RUSSIAN_ROULETTE_START = ShaderAbi.RUSSIAN_ROULETTE_START;
    static final int SAMPLE_EFFECT_CAMERA = 0;
    static final int SAMPLE_EFFECT_DIRECT_STARS = 1;
    static final int SAMPLE_EFFECT_DIRECT_SUN = 2;
    static final int SAMPLE_EFFECT_SCATTER_BSDF = 3;
    static final int SAMPLE_EFFECT_DIRECT_AREA_LIGHT = 5;

    private static final int SOBOL_INDEX_MASK = 0xffff_0000;
    private static final float UINT32_TO_FLOAT_EXCLUSIVE_SCALE = 1.0F / 4_294_967_808.0F;
    private static final int[] SOBOL_DIMENSION_ONE = new int[] {
        0x00000001, 0x00000003, 0x00000005, 0x0000000f,
        0x00000011, 0x00000033, 0x00000055, 0x000000ff,
        0x00000101, 0x00000303, 0x00000505, 0x00000f0f,
        0x00001111, 0x00003333, 0x00005555, 0x0000ffff,
        0x00010001, 0x00030003, 0x00050005, 0x000f000f,
        0x00110011, 0x00330033, 0x00550055, 0x00ff00ff,
        0x01010101, 0x03030303, 0x05050505, 0x0f0f0f0f,
        0x11111111, 0x33333333, 0x55555555, 0xffffffff
    };

    private IntegratorSettings() {
    }

    public static int packSampleControl(
            int sampleIndex,
            AstronomySettings astronomy,
            boolean seamlessGlass,
            boolean airGap) {
        return packSampleControl(
                sampleIndex,
                astronomy,
                seamlessGlass,
                airGap,
                MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS,
                TransparentNeeMode.DEFAULT);
    }

    public static int packSampleControl(
            int sampleIndex,
            AstronomySettings astronomy,
            boolean seamlessGlass,
            boolean airGap,
            boolean vanillaPbrPresets) {
        return packSampleControl(
                sampleIndex,
                astronomy,
                seamlessGlass,
                airGap,
                vanillaPbrPresets,
                TransparentNeeMode.DEFAULT);
    }

    public static int packSampleControl(
            int sampleIndex,
            AstronomySettings astronomy,
            boolean seamlessGlass,
            boolean airGap,
            boolean vanillaPbrPresets,
            TransparentNeeMode transparentNeeMode) {
        if (sampleIndex < 0
                || (sampleIndex & ~ShaderAbi.PATH_SAMPLE_INDEX_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Sample index does not fit the Sobol sequence");
        }
        java.util.Objects.requireNonNull(astronomy, "astronomy");
        int solarLongitude = astronomy.solarLongitudeDegrees();
        if ((solarLongitude & ~ShaderAbi.PATH_SOLAR_LONGITUDE_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Solar longitude does not fit the path-control ABI");
        }
        java.util.Objects.requireNonNull(transparentNeeMode, "transparentNeeMode");
        return sampleIndex
                | solarLongitude << ShaderAbi.PATH_SOLAR_LONGITUDE_SHIFT
                | (seamlessGlass ? ShaderAbi.PATH_SEAMLESS_GLASS_MASK : 0)
                | (airGap ? ShaderAbi.PATH_AIR_GAP_MASK : 0)
                | (vanillaPbrPresets
                        ? ShaderAbi.PATH_VANILLA_PBR_PRESETS_MASK
                        : 0)
                | (transparentNeeMode == TransparentNeeMode.UNBIASED_BSDF_ONLY
                        ? ShaderAbi.PATH_TRANSPARENT_NEE_UNBIASED_MASK
                        : 0);
    }

    public static int packSampleEpoch(int sampleEpoch) {
        return packSampleEpoch(sampleEpoch, false);
    }

    public static int packSampleEpoch(int sampleEpoch, boolean historyValid) {
        if ((sampleEpoch & ~ShaderAbi.PATH_SAMPLE_EPOCH_MASK) != 0) {
            throw new IllegalArgumentException("Sample epoch does not fit in 31 bits");
        }
        return sampleEpoch | (historyValid ? ShaderAbi.PATH_HISTORY_VALID_MASK : 0);
    }

    public static int packPathControl(
            int maximumBounces,
            int jitterPhase,
            AstronomySettings astronomy,
            boolean cameraInWater,
            TransparentGuideMode transparentGuideMode) {
        if (maximumBounces < 0 || maximumBounces > MAXIMUM_BOUNCES) {
            throw new IllegalArgumentException(
                    "Maximum bounce count exceeds the integrator limit");
        }
        // Zero selects the offline pixel filter. Realtime reconstruction uses the exact
        // one-based jitter phase supplied by FSR or RR.
        if (jitterPhase < 0 || jitterPhase > ShaderAbi.PATH_JITTER_PHASE_MASK) {
            throw new IllegalArgumentException("Jitter phase does not fit in 13 bits");
        }
        java.util.Objects.requireNonNull(astronomy, "astronomy");
        int encodedLatitude =
                astronomy.latitudeDegrees() + ShaderAbi.PATH_LATITUDE_BIAS;
        if ((encodedLatitude & ~ShaderAbi.PATH_LATITUDE_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Observer latitude does not fit the path-control ABI");
        }
        java.util.Objects.requireNonNull(transparentGuideMode, "transparentGuideMode");
        return (cameraInWater ? ShaderAbi.PATH_CAMERA_IN_WATER_MASK : 0)
                | transparentGuideMode.abiValue()
                        << ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_SHIFT
                | (jitterPhase << 16)
                | encodedLatitude << ShaderAbi.PATH_LATITUDE_SHIFT
                | maximumBounces;
    }

    public static int packMaterialLightingControl(
            int sunQuarterSteps,
            int starQuarterSteps,
            int blockLightQuarterSteps,
            int materialRoughnessSteps,
            boolean shInput) {
        LightingSettings.starLinearMultiplier(starQuarterSteps);
        if (materialRoughnessSteps < MaterialSettings.MINIMUM_ROUGHNESS_STEPS
                || materialRoughnessSteps > MaterialSettings.MAXIMUM_ROUGHNESS_STEPS
                || (materialRoughnessSteps & ~ShaderAbi.PATH_MATERIAL_ROUGHNESS_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Default material roughness does not fit in the path-control ABI");
        }
        return (shInput ? ShaderAbi.PATH_SH_INPUT_MASK : 0)
                | packEvQuarterSteps(sunQuarterSteps, ShaderAbi.PATH_SUN_EV_QUARTER_SHIFT)
                | packStarEvQuarterSteps(starQuarterSteps)
                | packEvQuarterSteps(
                        blockLightQuarterSteps,
                        ShaderAbi.PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT)
                | materialRoughnessSteps << ShaderAbi.PATH_MATERIAL_ROUGHNESS_SHIFT;
    }

    private static int packEvQuarterSteps(int quarterSteps, int shift) {
        int encoded = quarterSteps + ShaderAbi.PATH_EV_QUARTER_BIAS;
        if ((encoded & ~ShaderAbi.PATH_EV_QUARTER_MASK) != 0) {
            throw new IllegalArgumentException("Lighting EV does not fit in the path-control ABI");
        }
        return encoded << shift;
    }

    private static int packStarEvQuarterSteps(int quarterSteps) {
        int encoded = quarterSteps + ShaderAbi.PATH_STAR_EV_QUARTER_BIAS;
        if ((encoded & ~ShaderAbi.PATH_STAR_EV_QUARTER_MASK) != 0) {
            throw new IllegalArgumentException("Star EV does not fit in the path-control ABI");
        }
        return encoded << ShaderAbi.PATH_STAR_EV_QUARTER_SHIFT;
    }

    static float powerHeuristic(float firstPdf, float secondPdf) {
        if (Float.isNaN(firstPdf) || !(firstPdf > 0.0F)) {
            return 0.0F;
        }
        if (Float.isNaN(secondPdf) || !(secondPdf > 0.0F)) {
            return 1.0F;
        }
        if (Float.isInfinite(firstPdf)) {
            return Float.isInfinite(secondPdf) ? 0.5F : 1.0F;
        }
        if (Float.isInfinite(secondPdf)) {
            return 0.0F;
        }
        if (firstPdf >= secondPdf) {
            float ratio = secondPdf / firstPdf;
            return 1.0F / (1.0F + ratio * ratio);
        }
        float ratio = firstPdf / secondPdf;
        float ratioSquared = ratio * ratio;
        return ratioSquared / (1.0F + ratioSquared);
    }

    static float rouletteSurvival(float maximumThroughput, float etaScale) {
        return (float) Math.min(
                1.0,
                Math.sqrt(Math.max(0.0F, maximumThroughput * etaScale)));
    }

    static float updateMean(float previousMean, float sample, int sampleIndex) {
        if (sampleIndex < 0) {
            throw new IllegalArgumentException("Sample index must not be negative");
        }
        return sampleIndex == 0
                ? sample
                : previousMean + (sample - previousMean) / (sampleIndex + 1.0F);
    }

    /** CPU reference for the grouped two-dimensional shader sequence. */
    static float[] sobolSample2D(
            int pixelX,
            int pixelY,
            int sampleIndex,
            int sampleEpoch,
            int vertexIndex,
            int effect,
            int dimensionSet) {
        int seed = hash32(pixelX);
        seed = hashCombine(seed, pixelY);
        seed = hashCombine(seed, sampleEpoch);
        seed = hashCombine(seed, vertexIndex);
        int mixedSeed = hashCombine(seed, effect) ^ highQualityHash(dimensionSet);
        int shuffledIndex = reversedBitOwen(
                Integer.reverse(sampleIndex), mixedSeed ^ 0xf8ad_e99a) & SOBOL_INDEX_MASK;
        return new float[] {
            sobolBurley(shuffledIndex, 0, mixedSeed ^ 0xe0aa_af76),
            sobolBurley(shuffledIndex, 1, mixedSeed ^ 0x9496_4d4e)
        };
    }

    static float diffusePdf(float cosine) {
        return Math.max(cosine, 0.0F) / (float) Math.PI;
    }

    private static float sobolBurley(int reversedBitIndex, int dimension, int seed) {
        int result = 0;
        if (dimension == 0) {
            result = Integer.reverse(reversedBitIndex);
        } else {
            int index = reversedBitIndex;
            int tableIndex = 0;
            while (index != 0) {
                int leadingZeroes = Integer.numberOfLeadingZeros(index);
                result ^= SOBOL_DIMENSION_ONE[tableIndex + leadingZeroes];
                tableIndex += leadingZeroes + 1;
                index <<= leadingZeroes;
                index <<= 1;
            }
        }
        long unsigned = Integer.toUnsignedLong(
                Integer.reverse(reversedBitOwen(result, seed)));
        return (float) unsigned * UINT32_TO_FLOAT_EXCLUSIVE_SCALE;
    }

    private static int reversedBitOwen(int value, int seed) {
        value ^= value * 0x3d20_adea;
        value += seed;
        value *= (seed >>> 16) | 1;
        value ^= value * 0x0552_6c56;
        value ^= value * 0x53a2_2864;
        return value;
    }

    private static int highQualityHash(int value) {
        value ^= value >>> 16;
        value *= 0x21f0_aaad;
        value ^= value >>> 15;
        value *= 0xd35a_2d97;
        value ^= value >>> 15;
        return value ^ 0xe6fe_3beb;
    }

    private static int hash32(int value) {
        value ^= value >>> 16;
        value *= 0x21f0_aaad;
        value ^= value >>> 15;
        value *= 0xf35a_2d97;
        return value ^ value >>> 15;
    }

    private static int hashCombine(int seed, int value) {
        return seed ^ (hash32(value) + 0x9e37_79b9 + (seed << 6) + (seed >>> 2));
    }
}
