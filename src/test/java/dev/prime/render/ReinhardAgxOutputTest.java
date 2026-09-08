package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ReinhardAgxOutputTest {
    @Test
    void sdrPreservesBlackAndSceneLinearMiddleGray() {
        ReinhardAgxOutput.Parameters parameters = ReinhardAgxOutput.parameters(1.0F);

        assertEquals(1.0F, parameters.outputPeak());
        assertEquals(0.0, evaluate(parameters, 0.0));
        assertEquals(0.09, evaluate(parameters, 0.09));
        assertEquals(ReinhardAgxOutput.MIDDLE_GRAY,
                evaluate(parameters, ReinhardAgxOutput.MIDDLE_GRAY));
        assertTrue(evaluate(parameters, 0.5) < 0.5);
    }

    @Test
    void curveIsValueAndSlopeContinuousAtCompressionStart() {
        for (float headroom : new float[] {1.0F, 4.0F, HdrOutput.MAXIMUM_HEADROOM}) {
            ReinhardAgxOutput.Parameters parameters = ReinhardAgxOutput.parameters(headroom);
            assertContinuousJoin(parameters);
        }
    }

    private static void assertContinuousJoin(ReinhardAgxOutput.Parameters parameters) {
        double start = ReinhardAgxOutput.COMPRESSION_START;
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
            ReinhardAgxOutput.Parameters parameters =
                    ReinhardAgxOutput.parameters(headroom);
            double effectiveReach = ReinhardAgxOutput.HIGHLIGHT_REACH_EV
                    + Math.log(headroom) / Math.log(2.0);
            double reachInput = ReinhardAgxOutput.MIDDLE_GRAY
                    * Math.pow(2.0, effectiveReach);
            double tolerance = Math.max(2.0E-5, headroom * 3.0E-6);

            assertEquals(headroom, parameters.outputPeak());
            assertEquals(headroom, evaluate(parameters, reachInput), tolerance);
            assertTrue(asymptote(parameters) > parameters.outputPeak());
            double previous = 0.0;
            for (int ev = -20; ev <= 100; ev++) {
                double mapped = evaluate(parameters, 0.18 * Math.pow(2.0, ev) * headroom);
                assertTrue(Double.isFinite(mapped));
                assertTrue(mapped >= previous);
                assertTrue(mapped <= asymptote(parameters));
                previous = mapped;
            }
        }
    }

    @Test
    void headroomIsFiniteAndClampedToTheHdrContract() {
        assertEquals(
                ReinhardAgxOutput.parameters(HdrOutput.MINIMUM_HEADROOM),
                ReinhardAgxOutput.parameters(-10.0F));
        assertEquals(
                ReinhardAgxOutput.parameters(HdrOutput.MAXIMUM_HEADROOM),
                ReinhardAgxOutput.parameters(HdrOutput.MAXIMUM_HEADROOM + 1.0F));
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> ReinhardAgxOutput.parameters(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> new ReinhardAgxOutput.Parameters(1.0F, invalid));
        }
        for (float invalid : new float[] {0.0F, -1.0F, 1.0F, 2.0F}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ReinhardAgxOutput.Parameters(1.0F, invalid));
        }
    }

    private static double evaluate(
            ReinhardAgxOutput.Parameters parameters,
            double color) {
        double start = ReinhardAgxOutput.COMPRESSION_START;
        if (color <= start) return color;

        double extent = parameters.outputPeak() - start;
        double logDistance = Math.log1p((color - start) / extent);
        // Equivalent inverse-power form independently checks the derived coefficient.
        return start + extent / Math.pow(
                Math.pow(logDistance, -ReinhardAgxOutput.SHOULDER_POWER)
                        + parameters.shoulderCoefficient(),
                1.0 / ReinhardAgxOutput.SHOULDER_POWER);
    }

    private static double asymptote(ReinhardAgxOutput.Parameters parameters) {
        double start = ReinhardAgxOutput.COMPRESSION_START;
        return start + (parameters.outputPeak() - start)
                * Math.pow(parameters.shoulderCoefficient(), -1.0 / ReinhardAgxOutput.SHOULDER_POWER);
    }
}
