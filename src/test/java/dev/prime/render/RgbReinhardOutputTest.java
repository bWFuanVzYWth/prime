// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RgbReinhardOutputTest {
    @Test
    void sdrUsesEightStopReach() {
        var parameters = RgbReinhardOutput.parameters(1.0F);
        assertEquals(1.0F, parameters.outputPeak());
        assertEquals(1.0149157054126, parameters.curvePeak(), 1.0E-7);
        assertEquals(1.0, evaluate(parameters, 0.18 * 256.0), 1.0E-7);
    }

    @Test
    void everySupportedPeakPreservesTheLinearJoinAndReachesTheRequestedHeadroom() {
        for (float headroom : new float[] {1.0F, Math.nextUp(1.0F), 4.0F, 64.0F, 10_000.0F}) {
            var parameters = RgbReinhardOutput.parameters(headroom);
            assertEquals(headroom, parameters.outputPeak());
            assertTrue(parameters.curvePeak() > headroom);
            assertEquals(headroom, evaluate(parameters, 0.18 * 256.0 * headroom), headroom * 2.0E-7);
            assertEquals(0.0, evaluate(parameters, 0.0));
            assertEquals(1.0E-30, evaluate(parameters, 1.0E-30));
            assertEquals(0.09, evaluate(parameters, 0.09));
            assertEquals(0.18, evaluate(parameters, 0.18));
            double step = 1.0E-6;
            assertEquals(1.0, (evaluate(parameters, 0.18 + step) - 0.18) / step, 2.0E-6);
            double previous = 0.0;
            for (int ev = -100; ev <= 128; ev++) {
                double output = evaluate(parameters, 0.18 * Math.pow(2.0, ev));
                assertTrue(Double.isFinite(output) && output >= previous);
                assertTrue(output <= parameters.curvePeak() * (1.0 + 1.0E-15));
                previous = output;
            }
        }
    }

    @Test
    void headroomIsFiniteAndClampedToTheHdrContract() {
        assertEquals(RgbReinhardOutput.parameters(1.0F), RgbReinhardOutput.parameters(-10.0F));
        assertEquals(RgbReinhardOutput.parameters(10_000.0F), RgbReinhardOutput.parameters(10_001.0F));
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> RgbReinhardOutput.parameters(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> new RgbReinhardOutput.Parameters(1.0F, invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> new RgbReinhardOutput.Parameters(invalid, 2.0F));
        }
        for (float invalid : new float[] {0.0F, -1.0F, 1.0F}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RgbReinhardOutput.Parameters(1.0F, invalid));
        }
        for (float invalid : new float[] {0.99F, 10_001.0F}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RgbReinhardOutput.Parameters(invalid, invalid + 1.0F));
        }
    }

    private static double evaluate(RgbReinhardOutput.Parameters parameters, double value) {
        if (value <= 0.18) return value;
        double distance = value - 0.18;
        return 0.18 + distance / (1.0 + distance / (parameters.curvePeak() - 0.18));
    }
}
