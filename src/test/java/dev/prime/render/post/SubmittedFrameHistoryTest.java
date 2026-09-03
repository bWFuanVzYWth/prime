package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.post.nrd.NrdFrameHistory;
import dev.prime.render.post.nrd.NrdFrameInput;
import dev.prime.render.post.nrd.NrdFramePlan;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class SubmittedFrameHistoryTest {
    @Test
    void ownsOneFrameAndCommitsItOnlyAfterExecution() {
        SubmittedFrameHistory<Integer, String, String> history = counterHistory();
        SubmittedFrame<String> frame = history.plan("first");

        assertEquals("0:first", frame.plan());
        assertThrows(IllegalStateException.class, () -> history.plan("blocked"));
        assertThrows(IllegalStateException.class, () -> history.reset(state -> 0));
        assertThrows(IllegalArgumentException.class, () -> history.submitted(frame));
        assertThrows(
                IllegalArgumentException.class,
                () -> counterHistory().submitted(frame));

        assertSame(frame.plan(), frame.claimForExecution());
        history.submitted(frame);
        assertThrows(IllegalArgumentException.class, frame::claimForExecution);
        assertThrows(IllegalArgumentException.class, () -> history.submitted(frame));
        assertEquals("1:second", history.plan("second").plan());
    }

    @Test
    void resetAndAbandonDoNotCommitAPlannedTransition() {
        SubmittedFrameHistory<Integer, String, String> history = counterHistory();
        history.reset(state -> state + 10);
        SubmittedFrame<String> abandoned = history.plan("abandoned");
        abandoned.claimForExecution();
        history.abandon(abandoned);

        assertThrows(IllegalArgumentException.class, abandoned::claimForExecution);
        assertThrows(IllegalArgumentException.class, () -> history.abandon(abandoned));
        assertEquals("10:retry", history.plan("retry").plan());
    }

    @Test
    void nrdWrapperMapsTheCommittedTemporalPlan() {
        NrdFrameHistory history = new NrdFrameHistory();
        FrameCamera firstCamera = camera(0.0);
        SubmittedFrame<NrdFramePlan> first =
                history.plan(nrdInput(firstCamera, 1_000_000L));
        assertTrue(first.plan().restart());
        first.claimForExecution();
        history.submitted(first);

        NrdFramePlan second = history.plan(nrdInput(camera(1.0), 11_000_000L)).plan();
        assertFalse(second.restart());
        assertSame(firstCamera, second.historyCamera());
        assertEquals(1, second.frameIndex());
        assertEquals(10.0F, second.deltaMilliseconds(), 1.0e-5F);
    }

    @Test
    void reconstructionWrapperAppliesExplicitReset() {
        ReconstructionFrameHistory history = new ReconstructionFrameHistory();
        SubmittedFrame<TemporalReconstructionState.Plan> first =
                history.plan(reconstructionInput(camera(0.0), 1L));
        first.claimForExecution();
        history.submitted(first);

        SubmittedFrame<TemporalReconstructionState.Plan> second =
                history.plan(reconstructionInput(camera(1.0), 2L));
        second.claimForExecution();
        history.submitted(second);
        history.requestReset();

        TemporalReconstructionState.Plan reset =
                history.plan(reconstructionInput(camera(2.0), 3L)).plan();
        assertTrue(reset.restart());
        assertEquals(0, reset.frameIndex());
        assertSame(reset.camera(), reset.historyCamera());
    }

    private static SubmittedFrameHistory<Integer, String, String> counterHistory() {
        return new SubmittedFrameHistory<>(
                0,
                (state, input) -> new SubmittedFrameHistory.Transition<>(
                        state + ":" + input, state + 1));
    }

    private static NrdFrameInput nrdInput(FrameCamera camera, long time) {
        return new NrdFrameInput(
                camera,
                time,
                1L,
                2L,
                new SunDirection(0.0F, 1.0F, 0.0F),
                0.25F,
                -0.25F,
                false);
    }

    private static TemporalReconstructionState.Input reconstructionInput(
            FrameCamera camera, long time) {
        return new TemporalReconstructionState.Input(camera, time, 1L, false);
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(
                new Matrix4f(), new Matrix4f(), new Matrix4f(), x, 0.0, 0.0, x, 0.0, 0.0);
    }
}
