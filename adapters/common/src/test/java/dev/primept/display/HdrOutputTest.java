package dev.primept.display;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class HdrOutputTest {
    @AfterEach
    void reset() {
        HdrOutput.surfaceConfigured(false);
        HdrOutput.setRequested(false);
        HdrOutput.setReferenceWhiteNits(0);
        HdrOutput.updateCapability(false, 0, 0);
    }

    @Test
    void calibrationPreservesAbsoluteNitsAndScRgbUnits() {
        HdrOutput.setRequested(true);
        HdrOutput.updateCapability(true, 1000, 200);
        assertTrue(HdrOutput.requestedCalibration().active());
        assertFalse(HdrOutput.activeCalibration().active());
        HdrOutput.surfaceConfigured(true);
        var calibration = HdrOutput.activeCalibration();
        assertTrue(calibration.active());
        assertEquals(200, calibration.referenceWhiteNits());
        assertEquals(1000, calibration.maximumNits());
        assertEquals(5, calibration.headroom());
        assertEquals(2.5f, calibration.scRgbScale());
        HdrOutput.setReferenceWhiteNits(1000);
        HdrOutput.updateCapability(true, 600, 200);
        calibration = HdrOutput.activeCalibration();
        assertEquals(600, calibration.referenceWhiteNits());
        assertEquals(1, calibration.headroom());
        assertEquals(7.5f, calibration.scRgbScale());
        assertEquals(1000, HdrOutput.referenceWhiteNits());
    }

    @Test
    void unknownOrDisabledCapabilityUsesSdrAndInvalidNitsFail() {
        HdrOutput.setRequested(true);
        assertFalse(HdrOutput.activeCalibration().active());
        HdrOutput.updateCapability(true, 1000, 200);
        HdrOutput.surfaceConfigured(true);
        HdrOutput.setRequested(false);
        assertFalse(HdrOutput.requestedCalibration().active());
        // The currently configured surface retains its units until reconfiguration succeeds.
        assertTrue(HdrOutput.activeCalibration().active());
        HdrOutput.surfaceConfigured(false);
        assertFalse(HdrOutput.activeCalibration().active());
        assertThrows(IllegalArgumentException.class,
                     () -> HdrOutput.updateCapability(true, Float.NaN, 200));
        assertThrows(IllegalArgumentException.class,
                     () -> HdrOutput.updateCapability(true, 1000, 0));
        assertThrows(IllegalArgumentException.class, () -> HdrOutput.setReferenceWhiteNits(-1));
        assertThrows(IllegalArgumentException.class, () -> HdrOutput.setReferenceWhiteNits(10001));
    }
}
