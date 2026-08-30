package dev.prime.config;

import dev.prime.binding.streamline.ReflexMode;
import dev.prime.render.HdrOutput;
import dev.prime.render.MaximumBounceSettings;
import dev.prime.render.MinimumBounceSettings;
import dev.prime.render.SpecularBounceSettings;
import dev.prime.render.terrain.TerrainWorkerSettings;
import java.util.Objects;

/** Immutable data transferred between the properties codec and the live config owner. */
record PrimeConfigData(
        PrimeSettings settings,
        int additionalSpecularBounces,
        int minimumBounces,
        int maximumBounces,
        int terrainWorkerPercentage,
        boolean hdrEnabled,
        int referenceWhiteNits,
        ReflexMode reflexMode,
        boolean dlssFrameGenerationEnabled,
        int dlssFrameGenerationMultiplier,
        boolean dlssFrameGenerationUiRecomposition) {
    PrimeConfigData {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(reflexMode, "reflexMode");
        additionalSpecularBounces = SpecularBounceSettings.validateCount(additionalSpecularBounces);
        minimumBounces = MinimumBounceSettings.validateCount(minimumBounces);
        maximumBounces = MaximumBounceSettings.validateCount(maximumBounces);
        terrainWorkerPercentage =
                TerrainWorkerSettings.validatePercentage(terrainWorkerPercentage);
        referenceWhiteNits = HdrOutput.validateReferenceWhiteNits(referenceWhiteNits);
        if (dlssFrameGenerationMultiplier < 2) {
            throw new IllegalArgumentException(
                    "DLSS frame generation multiplier must be at least 2");
        }
    }

    static PrimeConfigData defaults() {
        return new PrimeConfigData(
                PrimeSettings.defaults(),
                SpecularBounceSettings.DEFAULT_COUNT,
                MinimumBounceSettings.DEFAULT_COUNT,
                MaximumBounceSettings.DEFAULT_COUNT,
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                false,
                HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS,
                ReflexMode.OFF,
                false,
                2,
                true);
    }
}
