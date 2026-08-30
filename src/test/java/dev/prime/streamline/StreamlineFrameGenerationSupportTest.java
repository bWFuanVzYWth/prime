package dev.prime.streamline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.binding.streamline.DlssgStatus;
import org.junit.jupiter.api.Test;

final class StreamlineFrameGenerationSupportTest {
    @Test
    void everyTaggedInputMustMeetTheRuntimeMinimum() {
        StreamlineFrameGenerationSupport support =
                new StreamlineFrameGenerationSupport(0, 3, 480);

        assertTrue(support.supportsFrame(960, 540, 960, 540, 1920, 1080));
        assertFalse(support.supportsFrame(960, 479, 960, 540, 1920, 1080));
        assertFalse(support.supportsFrame(960, 540, 479, 540, 1920, 1080));
        assertFalse(support.supportsFrame(960, 540, 960, 540, 1920, 479));
        assertFalse(new StreamlineFrameGenerationSupport(0, 1, 0)
                .supportsFrame(0, 1, 1, 1, 1, 1));
    }

    @Test
    void resolutionStatusCanRecoverButFatalRuntimeStatusCannot() {
        StreamlineFrameGenerationSupport resolution = new StreamlineFrameGenerationSupport(
                DlssgStatus.FAIL_RESOLUTION_TOO_LOW.mask, 1, 480);
        assertTrue(resolution.runtimeCanRetry());
        assertTrue(resolution.supportsFrame(960, 540, 960, 540, 1920, 1080));
        assertFalse(resolution.supportsFrame(400, 400, 400, 400, 400, 400));

        StreamlineFrameGenerationSupport constants = new StreamlineFrameGenerationSupport(
                DlssgStatus.FAIL_COMMON_CONSTANTS_INVALID.mask, 1, 0);
        assertFalse(constants.runtimeCanRetry());
        assertFalse(constants.supportsFrame(960, 540, 960, 540, 1920, 1080));
    }

    @Test
    void missingGenerationCapacityIsUnavailable() {
        assertFalse(new StreamlineFrameGenerationSupport(0, 0, 0).featureAvailable());
        assertFalse(new StreamlineFrameGenerationSupport(0, -5, -1)
                .supportsFrame(1, 1, 1, 1, 1, 1));
    }
}
