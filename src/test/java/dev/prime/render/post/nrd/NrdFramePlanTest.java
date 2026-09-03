package dev.prime.render.post.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TemporalReconstructionState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdFramePlanTest {
    private static final ReconstructionQualityMode QUALITY =
            ReconstructionQualityMode.PERFORMANCE;
    private static final SunDirection SUN = new SunDirection(0.0F, 1.0F, 0.0F);

    @Test
    void derivesNrdHistoryFromTheSubmittedReconstructionTimeline() {
        FrameCamera firstCamera = camera(0.0);
        TemporalReconstructionState.Plan first = TemporalReconstructionState.initial().plan(
                new TemporalReconstructionState.Input(
                        firstCamera, 1_000_000L, 1L, false));
        SubpixelJitter firstJitter = QUALITY.jitter(first.frameIndex());
        NrdFramePlan reset = NrdFramePlan.from(first, firstJitter, QUALITY, SUN);

        assertSame(firstCamera, reset.camera());
        assertSame(firstCamera, reset.historyCamera());
        assertSame(firstJitter, reset.jitter());
        assertSame(firstJitter, reset.historyJitter());

        FrameCamera secondCamera = camera(1.0);
        TemporalReconstructionState.Plan second = first.committedState().plan(
                new TemporalReconstructionState.Input(
                        secondCamera, 11_000_000L, 1L, false));
        NrdFramePlan continued = NrdFramePlan.from(
                second, QUALITY.jitter(second.frameIndex()), QUALITY, SUN);

        assertSame(secondCamera, continued.camera());
        assertSame(firstCamera, continued.historyCamera());
        assertEquals(QUALITY.jitter(0), continued.historyJitter());
        assertEquals(1, continued.frameIndex());
        assertEquals(10.0F, continued.deltaMilliseconds(), 1.0e-5F);
        assertSame(SUN, continued.sunDirection());
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(
                new Matrix4f(), new Matrix4f(), new Matrix4f(), x, 0.0, 0.0, x, 0.0, 0.0);
    }
}
