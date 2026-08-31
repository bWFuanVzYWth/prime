package dev.prime.render.fsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.RayConeParameters;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class FsrSettingsTest {
    @Test
    void defaultUsesPerformanceUpscalingWithoutFrameGeneration() {
        assertEquals("3.1.4", FsrSettings.UPSCALER_VERSION);
        assertTrue(FsrSettings.DEFAULT_ENABLED);
        assertFalse(FsrSettings.FRAME_GENERATION_ENABLED);
        assertEquals(new ReconstructionExtent(1920, 1080),
                ReconstructionQualityMode.DEFAULT.renderExtent(3840, 2160));
        assertEquals(0.2F, FsrSettings.RCAS_SHARPNESS);
        assertEquals(1.0F, FsrSettings.EXPOSURE);
    }

    @Test
    void everyVideoPresetOwnsItsResolutionAndTemporalContract() {
        Map<ReconstructionQualityMode, ReconstructionExtent> expectedExtents = Map.of(
                ReconstructionQualityMode.NATIVE_AA, new ReconstructionExtent(3840, 2160),
                ReconstructionQualityMode.QUALITY, new ReconstructionExtent(2560, 1440),
                ReconstructionQualityMode.BALANCED, new ReconstructionExtent(2258, 1270),
                ReconstructionQualityMode.PERFORMANCE, new ReconstructionExtent(1920, 1080),
                ReconstructionQualityMode.ULTRA_PERFORMANCE,
                        new ReconstructionExtent(1280, 720));
        Map<ReconstructionQualityMode, Integer> expectedPhases = Map.of(
                ReconstructionQualityMode.NATIVE_AA, 8,
                ReconstructionQualityMode.QUALITY, 18,
                ReconstructionQualityMode.BALANCED, 23,
                ReconstructionQualityMode.PERFORMANCE, 32,
                ReconstructionQualityMode.ULTRA_PERFORMANCE, 72);

        for (ReconstructionQualityMode mode : ReconstructionQualityMode.values()) {
            assertEquals(expectedExtents.get(mode), mode.renderExtent(3840, 2160));
            assertEquals(expectedPhases.get(mode), mode.jitterPhaseCount());
            assertEquals(
                    (float) (Math.log(1.0 / mode.upscaleRatio()) / Math.log(2.0) - 1.0),
                    mode.mipBias(),
                    1.0e-6F);
        }
    }

    @Test
    void jitterUsesTheCanonicalHaltonPhaseForEachMode() {
        ReconstructionQualityMode mode = ReconstructionQualityMode.QUALITY;
        assertEquals(0.0F, mode.jitter(0).x(), 1.0e-7F);
        assertEquals(-1.0F / 6.0F, mode.jitter(0).y(), 1.0e-7F);
        assertEquals(-0.25F, mode.jitter(1).x(), 1.0e-7F);
        assertEquals(1.0F / 6.0F, mode.jitter(1).y(), 1.0e-7F);
        assertEquals(0.25F, mode.jitter(1).forFsrDispatch().x(), 1.0e-7F);
        assertEquals(-1.0F / 6.0F, mode.jitter(1).forFsrDispatch().y(), 1.0e-7F);
        assertEquals(
                mode.jitter(0),
                mode.jitter(mode.jitterPhaseCount()));
        assertEquals(1, mode.jitterPhase(0));
        assertEquals(2, mode.jitterPhase(1));
        assertEquals(1, mode.jitterPhase(mode.jitterPhaseCount()));
    }

    @Test
    void rayConeCarriesProjectionFootprintAndModeMipBias() {
        ReconstructionQualityMode mode = ReconstructionQualityMode.QUALITY;
        RayConeParameters rayCone = mode.rayConeParameters(1.0F, 1.0F, 1920, 1080);
        assertEquals(2.0F / 1080.0F, rayCone.width());
        assertEquals(mode.mipBias(), rayCone.mipBias());
        assertTrue(rayCone.binary16LodError()
                <= RayConeParameters.MAXIMUM_BINARY16_LOD_ERROR);
    }

    @Test
    void persistedIdsRoundTripAndUnknownValuesUseDefault() {
        for (ReconstructionQualityMode mode : ReconstructionQualityMode.values()) {
            assertEquals(mode, ReconstructionQualityMode.fromId(mode.id()));
        }
        assertEquals(
                ReconstructionQualityMode.PERFORMANCE,
                ReconstructionQualityMode.fromId("future_mode"));
    }

    @Test
    void valueTypesRejectOutOfContractExtentsAndJitter() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReconstructionExtent(0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SubpixelJitter(Float.NaN, 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SubpixelJitter(0.0F, -0.5001F));
    }
}
