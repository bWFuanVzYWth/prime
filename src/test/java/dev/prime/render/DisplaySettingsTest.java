// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DisplaySettingsTest {
    @Test
    void finalExposureUsesQuarterEvStepsAcrossTheUserRange() {
        assertEquals(1.0F, DisplaySettings.finalExposureMultiplier(0));
        assertEquals(2.0F, DisplaySettings.finalExposureMultiplier(4));
        assertEquals(0.5F, DisplaySettings.finalExposureMultiplier(-4));
        assertEquals(
                (float) Math.pow(2.0, 0.25),
                DisplaySettings.finalExposureMultiplier(1));
        assertEquals(256.0F, DisplaySettings.finalExposureMultiplier(32));
        assertEquals(1.0F / 256.0F, DisplaySettings.finalExposureMultiplier(-32));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.finalExposureMultiplier(33));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.finalExposureMultiplier(-33));
    }

    @Test
    void autoExposureCompensationCoversDisabledThroughFullMetering() {
        assertEquals(0.0F, DisplaySettings.autoExposureCompensation(0));
        assertEquals(0.5F, DisplaySettings.autoExposureCompensation(50));
        assertEquals(1.0F, DisplaySettings.autoExposureCompensation(100));
        assertEquals(60, DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS);
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.autoExposureCompensation(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.autoExposureCompensation(101));
    }
}
