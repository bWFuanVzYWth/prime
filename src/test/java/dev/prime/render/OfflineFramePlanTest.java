package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.post.PostProcessingMode;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class OfflineFramePlanTest {
    @Test
    void sobolIndexAndEpochAreDerivedBeforeDeviceExecution() {
        OfflineFramePlan lastFirstEpoch = input(0xffffL).plan();
        OfflineFramePlan firstSecondEpoch = input(0x1_0000L).plan();

        assertEquals(0xffff, lastFirstEpoch.integrator().sampleIndex());
        assertEquals(0, lastFirstEpoch.integrator().sampleEpoch());
        assertEquals(0, firstSecondEpoch.integrator().sampleIndex());
        assertEquals(1, firstSecondEpoch.integrator().sampleEpoch());
        assertEquals(0x1_0001L, firstSecondEpoch.nextSampleCount());
    }

    @Test
    void offlinePlanPreservesNativeUnfilteredIntegratorContract() {
        OfflineFrameInput input = input(7L);
        OfflineFramePlan first = input.plan();
        OfflineFramePlan second = input.plan();

        assertEquals(first, second);
        assertEquals(PostProcessingMode.DISABLED,
                first.integrator().postProcessingMode());
        assertEquals(0, first.integrator().jitterPhase());
        assertEquals(0.0F, first.integrator().rayCone().mipBias());
        assertFalse(first.integrator().shInput());
        first.requireSceneRevision(input.sceneRevision());
        assertThrows(
                IllegalStateException.class,
                () -> first.requireSceneRevision(input.sceneRevision() + 1L));
        first.requireTextureRevision(input.textureRevision());
        assertThrows(
                IllegalStateException.class,
                () -> first.requireTextureRevision(input.textureRevision() + 1L));
    }

    @Test
    void invalidOrUnrepresentableSampleCountsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> input(-1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(1L << 47).plan());
    }

    @Test
    void finalExposureChangesPreserveAccumulationIdentityAndSampleCount() {
        OfflineFrameInput initial = input(37L);
        OfflineFrameInput adjusted = new OfflineFrameInput(
                initial.camera(),
                initial.width(),
                initial.height(),
                initial.sceneRevision(),
                initial.textureRevision(),
                initial.astronomy(),
                initial.cameraInWater(),
                initial.lighting(),
                initial.material(),
                initial.sampleCount(),
                new DisplaySettings.Snapshot(
                        8,
                        initial.display().autoExposureCompensationSteps()));

        assertEquals(initial.plan().integrator(), adjusted.plan().integrator());
        assertEquals(initial.plan().nextSampleCount(), adjusted.plan().nextSampleCount());
        assertEquals(8, adjusted.display().finalExposureQuarterSteps());
    }

    private static OfflineFrameInput input(long sampleCount) {
        return new OfflineFrameInput(
                new FrameCamera(new Matrix4f(), 1.0, 2.0, 3.0),
                64,
                48,
                7L,
                11L,
                AstronomyState.atSolarHourAngle(
                        0.0F, AstronomySettings.defaults()),
                false,
                new LightingSettings.Snapshot(
                        0, 0, 0, 1L),
                new MaterialSettings.Snapshot(90, 1L),
                sampleCount,
                new DisplaySettings.Snapshot(0, 50));
    }
}
