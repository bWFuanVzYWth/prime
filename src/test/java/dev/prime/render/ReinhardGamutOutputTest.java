package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ReinhardGamutOutputTest {
    @Test
    void sdrPreservesBlackAndSceneLinearMiddleGray() {
        ReinhardGamutOutput.Parameters parameters = ReinhardGamutOutput.parameters(1.0F);

        assertEquals(1.0F, parameters.outputPeak());
        assertTrue(parameters.curvePeak() > 1.001F);
        assertTrue(parameters.curvePeak() < 1.002F);
        assertEquals(0.0, evaluate(parameters, 0.0));
        assertEquals(0.09, evaluate(parameters, 0.09));
        assertEquals(ReinhardGamutOutput.MIDDLE_GRAY,
                evaluate(parameters, ReinhardGamutOutput.MIDDLE_GRAY));
        assertEquals(0.5, evaluate(parameters, 0.5));
    }

    @Test
    void curveIsValueAndSlopeContinuousAtCompressionStart() {
        ReinhardGamutOutput.Parameters parameters = ReinhardGamutOutput.parameters(4.0F);
        double start = ReinhardGamutOutput.COMPRESSION_START;
        double step = 1.0E-5;
        double atStart = evaluate(parameters, start);
        double leftSlope = (atStart - evaluate(parameters, start - step)) / step;
        double rightSlope = (evaluate(parameters, start + step) - atStart) / step;

        assertEquals(start, atStart);
        assertEquals(1.0, leftSlope, 1.0E-11);
        assertEquals(1.0, rightSlope, 2.0E-4);
    }

    @Test
    void requestedReachHitsEverySupportedDisplayPeak() {
        for (float headroom : new float[] {1.0F, 4.0F, HdrOutput.MAXIMUM_HEADROOM}) {
            ReinhardGamutOutput.Parameters parameters =
                    ReinhardGamutOutput.parameters(headroom);
            double effectiveReach = ReinhardGamutOutput.HIGHLIGHT_REACH_EV
                    + Math.log(headroom) / Math.log(2.0);
            double reachInput = ReinhardGamutOutput.MIDDLE_GRAY
                    * Math.pow(2.0, effectiveReach);
            double tolerance = Math.max(2.0E-5, headroom * 3.0E-6);

            assertEquals(headroom, parameters.outputPeak());
            assertEquals(headroom, evaluate(parameters, reachInput), tolerance);
            assertTrue(parameters.curvePeak() > parameters.outputPeak());
            assertTrue(Float.isFinite(parameters.curvePeak()));
        }
    }

    @Test
    void headroomIsFiniteAndClampedToTheHdrContract() {
        assertEquals(
                ReinhardGamutOutput.parameters(HdrOutput.MINIMUM_HEADROOM),
                ReinhardGamutOutput.parameters(-10.0F));
        assertEquals(
                ReinhardGamutOutput.parameters(HdrOutput.MAXIMUM_HEADROOM),
                ReinhardGamutOutput.parameters(HdrOutput.MAXIMUM_HEADROOM + 1.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReinhardGamutOutput.parameters(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReinhardGamutOutput.parameters(Float.POSITIVE_INFINITY));
    }

    private static double evaluate(
            ReinhardGamutOutput.Parameters parameters,
            double color) {
        double start = ReinhardGamutOutput.COMPRESSION_START;
        if (color <= start) return color;

        double shoulderExtent = parameters.curvePeak() - start;
        double distance = color - start;
        return start + distance / (1.0 + distance / shoulderExtent);
    }
}
