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

    private IntegratorSettings() {
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

}
