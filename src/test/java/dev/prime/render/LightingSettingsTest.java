package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

final class LightingSettingsTest {
    @Test
    void quarterEvStepsConvertToExactPowerOfTwoMultipliers() {
        assertEquals(-32, LightingSettings.MINIMUM_QUARTER_STEPS);
        assertEquals(32, LightingSettings.MAXIMUM_QUARTER_STEPS);
        assertEquals(1.0F, LightingSettings.linearMultiplier(0));
        assertEquals(2.0F, LightingSettings.linearMultiplier(4));
        assertEquals(0.5F, LightingSettings.linearMultiplier(-4));
        assertEquals(256.0F, LightingSettings.linearMultiplier(32));
        assertEquals(1.0F / 256.0F, LightingSettings.linearMultiplier(-32));
        assertEquals((float) Math.pow(2.0, 0.25), LightingSettings.linearMultiplier(1));
        assertEquals(1.0F, LightingSettings.starLinearMultiplier(0));
        assertEquals(
                256.0F,
                LightingSettings.starLinearMultiplier(32));
        assertThrows(IllegalArgumentException.class,
                () -> LightingSettings.linearMultiplier(
                        LightingSettings.MAXIMUM_QUARTER_STEPS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> LightingSettings.starLinearMultiplier(33));
    }

    @Test
    void snapshotDerivesLinearValuesFromItsCanonicalSteps() {
        LightingSettings.Snapshot snapshot =
                new LightingSettings.Snapshot(4, -8, 12);

        assertEquals(2.0F, snapshot.sunMultiplier());
        assertEquals(0.25F, snapshot.starMultiplier());
        assertEquals(8.0F, snapshot.blockLightMultiplier());
        assertThrows(
                IllegalArgumentException.class,
                () -> new LightingSettings.Snapshot(33, 0, 0));
    }

}
