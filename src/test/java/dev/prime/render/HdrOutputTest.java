// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class HdrOutputTest {
    @AfterEach
    void resetGlobalState() {
        HdrOutput.setRequested(false);
        HdrOutput.setReferenceWhiteNits(HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS);
        HdrOutput.updateCapability(false, 0.0F, 0.0F);
    }

    @Test
    void hdrRequiresBothAUserRequestAndAUsableDisplay() {
        HdrOutput.setRequested(true);
        assertFalse(HdrOutput.capability().supported());
        assertEquals(1.0F, HdrOutput.activeHeadroom());

        HdrOutput.updateCapability(true, 1_000.0F, 100.0F);
        assertTrue(HdrOutput.capability().supported());
        assertEquals(10.0F, HdrOutput.activeHeadroom());
        assertEquals(100.0F, HdrOutput.activeCalibration().referenceWhiteNits());
        assertEquals(1.25F, HdrOutput.activeCalibration().scRgbScale());

        HdrOutput.setRequested(false);
        assertEquals(1.0F, HdrOutput.activeHeadroom());
        assertEquals(1.0F, HdrOutput.activeCalibration().scRgbScale());
    }

    @Test
    void manualReferenceWhiteSetsPhysicalScaleAndAvailableHeadroom() {
        HdrOutput.setRequested(true);
        HdrOutput.updateCapability(true, 1_000.0F, 100.0F);
        HdrOutput.setReferenceWhiteNits(400);

        HdrOutput.Calibration calibration = HdrOutput.activeCalibration();
        assertEquals(400.0F, calibration.referenceWhiteNits());
        assertEquals(2.5F, calibration.headroom());
        assertEquals(5.0F, calibration.scRgbScale());
        assertEquals(12.5F, calibration.headroom() * calibration.scRgbScale());

        HdrOutput.setReferenceWhiteNits(1_200);
        calibration = HdrOutput.activeCalibration();
        assertEquals(1_000.0F, calibration.referenceWhiteNits());
        assertEquals(1.0F, calibration.headroom());
        assertEquals(12.5F, calibration.scRgbScale());
    }

    @Test
    void capabilityPreservesAbsoluteLuminanceMeasurements() {
        HdrOutput.setRequested(true);
        HdrOutput.updateCapability(true, 1_000.0F, 10.0F);
        assertEquals(1_000.0F, HdrOutput.capability().maximumNits());
        assertEquals(10.0F, HdrOutput.capability().systemReferenceWhiteNits());
        assertEquals(100.0F, HdrOutput.activeHeadroom());
        assertEquals(1_000, HdrOutput.capability().maximumSelectableReferenceWhiteNits());
        assertThrows(
                IllegalArgumentException.class,
                () -> HdrOutput.updateCapability(true, Float.NaN, 100.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> HdrOutput.updateCapability(true, 1_000.0F, Float.NaN));
    }

    @Test
    void persistedReferenceWhiteUsesZeroAsAutomaticSentinel() {
        assertEquals(0, HdrOutput.validateReferenceWhiteNits(0));
        assertEquals(1, HdrOutput.validateReferenceWhiteNits(1));
        assertEquals(10_000, HdrOutput.validateReferenceWhiteNits(10_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> HdrOutput.validateReferenceWhiteNits(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> HdrOutput.validateReferenceWhiteNits(10_001));
    }
}
