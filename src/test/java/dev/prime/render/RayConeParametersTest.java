package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.post.ReconstructionQualityMode;
import org.junit.jupiter.api.Test;

final class RayConeParametersTest {
    @Test
    void semanticValuesRemainF32UntilTheVulkanBoundary() {
        RayConeParameters value = ReconstructionQualityMode.QUALITY.rayConeParameters(
                1.0F, 1.0F, 1920, 1080);

        assertEquals(
                Float.floatToRawIntBits(2.0F / 1080.0F),
                Float.floatToRawIntBits(value.width()));
        assertEquals(
                Float.floatToRawIntBits(ReconstructionQualityMode.QUALITY.mipBias()),
                Float.floatToRawIntBits(value.mipBias()));
    }

    @Test
    void everyProductionQualityFitsTheAuditedBinary16LodError() {
        for (ReconstructionQualityMode quality : ReconstructionQualityMode.values()) {
            RayConeParameters value = quality.rayConeParameters(
                    1.25F,
                    1.5F,
                    quality.renderExtent(3840, 2160).width(),
                    quality.renderExtent(3840, 2160).height());
            assertTrue(value.binary16LodError()
                    <= RayConeParameters.MAXIMUM_BINARY16_LOD_ERROR);
        }
    }

    @Test
    void invalidOrInsufficientlyPreciseAbiValuesFailBeforeDispatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RayConeParameters(0.0F, 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RayConeParameters(Float.NaN, 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RayConeParameters(1.0e-8F, 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RayConeParameters(1.0F, 1000.1F));
        assertThrows(
                IllegalArgumentException.class,
                () -> RayConeParameters.fromProjection(1.0F, 1.0F, 0, 1, 0.0F));
    }
}
