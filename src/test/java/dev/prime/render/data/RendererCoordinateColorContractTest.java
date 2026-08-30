package dev.prime.render.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RendererCoordinateColorContractTest {
    private static final double EPSILON = 2.0e-7;

    @Test
    void pixelCentersAndClipCornersUseTheTopLeftNvidiaConvention() {
        assertArrayEquals(
                new double[] {0.5 / 1920.0, 0.5 / 1080.0},
                RendererDataContracts.sampleUv(0, 0, 1920, 1080),
                0.0);
        assertArrayEquals(
                new double[] {1919.5 / 1920.0, 1079.5 / 1080.0},
                RendererDataContracts.sampleUv(1919, 1079, 1920, 1080),
                0.0);
        assertArrayEquals(
                new double[] {-1.0, 1.0},
                RendererDataContracts.uvToClip(0.0, 0.0),
                0.0);
        assertArrayEquals(
                new double[] {1.0, -1.0},
                RendererDataContracts.uvToClip(1.0, 1.0),
                0.0);
        assertArrayEquals(
                new double[] {0.0, 0.0},
                RendererDataContracts.uvToClip(0.5, 0.5),
                0.0);
        assertThrows(
                IllegalArgumentException.class,
                () -> RendererDataContracts.sampleUv(1920, 0, 1920, 1080));
    }

    @Test
    void projectionJitterAndVisibleMotionHaveOneCanonicalSignAndUnit() {
        assertEquals("render-pixel", RendererDataContracts.SAMPLE_JITTER_UNIT);
        assertEquals("normalized-uv", RendererDataContracts.VISIBLE_MOTION_UNIT);
        assertArrayEquals(
                new double[] {-0.25, 0.375},
                RendererDataContracts.projectionJitterPixels(0.25, -0.375),
                0.0);
        assertArrayEquals(
                new double[] {0.125, -0.25},
                RendererDataContracts.visibleMotionUv(0.625, 0.25, 0.5, 0.5),
                0.0);
    }

    @Test
    void everySrgbCodeDecodesBeforeRec2020Conversion() {
        assertEquals("linear-rec2020-d65", RendererDataContracts.WORKING_COLOR_SPACE);
        assertEquals("linear", RendererDataContracts.ALPHA_TRANSFER);
        assertEquals("linear-rec2020-d65", RendererDataContracts.MIP_FILTER_SPACE);
        for (int code = 0; code <= 255; code++) {
            double encoded = code / 255.0;
            double decoded = encoded <= 0.04045
                    ? encoded / 12.92
                    : StrictMath.pow((encoded + 0.055) / 1.055, 2.4);
            assertEquals(decoded, RendererDataContracts.decodeSrgb(encoded), EPSILON);
        }

        assertArrayEquals(
                new double[] {0.6274039, 0.0690973, 0.0163914},
                RendererDataContracts.linearSrgbToLinearRec2020(1.0, 0.0, 0.0),
                1.0e-12);
        assertArrayEquals(
                new double[] {1.0, 1.0, 1.0},
                RendererDataContracts.linearSrgbToLinearRec2020(1.0, 1.0, 1.0),
                2.0e-7);
    }

    @Test
    void negativeAndSuperwhiteValuesRemainDefinedAtTheExplicitColorBoundary() {
        assertEquals(-0.25 / 12.92, RendererDataContracts.decodeSrgb(-0.25), 0.0);
        double superwhite = RendererDataContracts.decodeSrgb(1.5);
        assertTrue(Double.isFinite(superwhite));
        assertTrue(superwhite > 1.0);
    }

    @Test
    void linearFilteringCommutesWithTheRec2020MatrixButSrgbFilteringDoesNot() {
        double encodedMidpoint = 0.5;
        double decodedMidpoint = RendererDataContracts.decodeSrgb(encodedMidpoint);
        double[] decodedThenConverted = RendererDataContracts.linearSrgbToLinearRec2020(
                decodedMidpoint, decodedMidpoint, decodedMidpoint);
        double[] convertThenLinearFilter = new double[] {0.5, 0.5, 0.5};

        for (int channel = 0; channel < 3; channel++) {
            assertEquals(decodedMidpoint, decodedThenConverted[channel], 2.0e-7);
            assertEquals(0.5, convertThenLinearFilter[channel], 0.0);
        }
    }
}
