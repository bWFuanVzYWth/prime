// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.post.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.prime.render.FrameCamera;
import dev.prime.render.DisplaySettings;
import dev.prime.render.RealtimeSampleState;
import dev.prime.render.SunDirection;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdFramePlanTest {
    private static final ReconstructionQualityMode QUALITY =
            ReconstructionQualityMode.PERFORMANCE;
    private static final SunDirection SUN = new SunDirection(0.0F, 1.0F, 0.0F);

    @Test
    void derivesNrdHistoryFromTheSubmittedReconstructionTimeline() {
        FrameCamera firstCamera = camera(0.0);
        RealtimeSampleState.Plan first = RealtimeSampleState.initial().plan(
                new RealtimeSampleState.Input(firstCamera, 1_000_000L, 1L, false));
        SubpixelJitter firstJitter = QUALITY.jitter(first.frameIndex());
        NrdFramePlan reset = NrdFramePlan.from(parameters(first, firstJitter), QUALITY);

        assertSame(firstCamera, reset.camera());
        assertSame(firstCamera, reset.historyCamera());
        assertSame(firstJitter, reset.jitter());
        assertSame(firstJitter, reset.historyJitter());

        FrameCamera secondCamera = camera(1.0);
        RealtimeSampleState.Plan second = first.committedState().plan(
                new RealtimeSampleState.Input(secondCamera, 11_000_000L, 1L, false));
        NrdFramePlan continued = NrdFramePlan.from(
                parameters(second, QUALITY.jitter(second.frameIndex())), QUALITY);

        assertSame(secondCamera, continued.camera());
        assertSame(firstCamera, continued.historyCamera());
        assertEquals(QUALITY.jitter(0), continued.historyJitter());
        assertEquals(1, continued.frameIndex());
        assertEquals(10.0F, continued.deltaMilliseconds(), 1.0e-5F);
        assertSame(SUN, continued.sunDirection());
    }

    private static ReconstructionFrameParameters parameters(
            RealtimeSampleState.Plan frame, SubpixelJitter jitter) {
        return new ReconstructionFrameParameters(
                frame.camera(),
                frame.historyCamera(),
                frame.frameIndex(),
                jitter,
                frame.reset(),
                frame.deltaMilliseconds(),
                SUN,
                1.0F,
                new dev.prime.render.post.StarsFrameParameters(dev.prime.render.AstronomySettings.defaults(),
                        dev.prime.render.AtmosphereSettings.defaults(), 1.0F, false),
                new DisplaySettings.Snapshot(0, 0));
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(
                new Matrix4f(), new Matrix4f(), new Matrix4f(), x, 0.0, 0.0, x, 0.0, 0.0);
    }
}
