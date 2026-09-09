// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class RealtimeSampleStateTest {
    @Test
    void stableCommittedFramesAdvanceWithoutReset() {
        RealtimeSampleState state = RealtimeSampleState.initial();
        FrameCamera camera = camera(1.0);
        RealtimeSampleState.Plan first = state.plan(input(camera, 3L, false));
        assertTrue(first.reset());
        assertEquals(0, first.sampleIndex());
        assertEquals(0, first.frameIndex());
        assertEquals(camera, first.historyCamera());
        int epoch = first.epoch();

        RealtimeSampleState.Plan second =
                first.committedState().plan(input(camera, 3L, false));
        assertFalse(second.reset());
        assertEquals(1, second.sampleIndex());
        assertEquals(1, second.frameIndex());
        assertEquals(camera, second.historyCamera());
        assertEquals(epoch, second.epoch());
    }

    @Test
    void unsubmittedPlansDoNotConsumeSamples() {
        RealtimeSampleState state = RealtimeSampleState.initial();
        FrameCamera camera = camera(1.0);
        RealtimeSampleState.Plan first = state.plan(input(camera, 1L, false));
        RealtimeSampleState.Plan retried = state.plan(input(camera, 1L, false));

        assertEquals(first.sampleIndex(), retried.sampleIndex());
        assertEquals(first.epoch(), retried.epoch());
        assertTrue(retried.reset());
        RealtimeSampleState.Plan firstContinuation =
                first.committedState().plan(input(camera, 1L, false));
        RealtimeSampleState.Plan retryContinuation =
                retried.committedState().plan(input(camera, 1L, false));
        assertEquals(firstContinuation.sampleIndex(), retryContinuation.sampleIndex());
        assertEquals(firstContinuation.epoch(), retryContinuation.epoch());
    }

    @Test
    void onlyWholeSceneTransitionsResetSequence() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = commit(
                RealtimeSampleState.initial(), input(camera, 1L, false));

        RealtimeSampleState.Plan world = state.plan(input(camera, 2L, false));
        assertTrue(world.reset());
        state = world.committedState();

        RealtimeSampleState.Plan ordinaryMotion =
                state.plan(input(camera(2.0), 2L, false));
        assertFalse(ordinaryMotion.reset());
        state = ordinaryMotion.committedState();
        assertEquals(2, state.sampleIndex());

        RealtimeSampleState.Plan cameraCut =
                state.plan(input(camera(35.0), 2L, false));
        assertTrue(cameraCut.reset());
        assertEquals(35.0, cameraCut.historyCamera().x());
        state = cameraCut.committedState();

        RealtimeSampleState.Plan forced =
                state.plan(input(camera(35.0), 2L, true));
        assertTrue(forced.reset());
        assertEquals(0, forced.sampleIndex());

        RealtimeSampleState invalidated = state.invalidated();
        assertEquals(state.sampleIndex(), invalidated.sampleIndex());
        assertEquals(state.epoch(), invalidated.epoch());
        RealtimeSampleState.Plan invalidation =
                invalidated.plan(input(camera(35.0), 2L, false));
        assertTrue(invalidation.reset());
        assertEquals(0, invalidation.sampleIndex());
        assertEquals(state.epoch() + 1, invalidation.epoch());
    }

    @Test
    void repeatedInvalidationIsConsumedOnceByTheNextPlan() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = commit(
                RealtimeSampleState.initial(), input(camera, 1L, false));

        RealtimeSampleState invalidated = state.invalidated().invalidated();
        RealtimeSampleState.Plan reset =
                invalidated.plan(input(camera, 1L, false));
        assertTrue(reset.reset());
        assertEquals(0, reset.sampleIndex());
        assertEquals(state.epoch() + 1, reset.epoch());

        RealtimeSampleState.Plan continuation =
                reset.committedState().plan(input(camera, 1L, false));
        assertFalse(continuation.reset());
        assertEquals(1, continuation.sampleIndex());
        assertEquals(reset.epoch(), continuation.epoch());
    }

    @Test
    void sobolSequenceStartsANewEpochBeforeItsSixteenBitIndexRepeats() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = RealtimeSampleState.initial();
        int initialEpoch = -1;
        for (int index = 0; index < (1 << 16); index++) {
            RealtimeSampleState.Plan plan = state.plan(input(camera, 1L, false));
            if (index == 0) {
                initialEpoch = plan.epoch();
            } else {
                assertFalse(plan.reset());
            }
            assertEquals(index, plan.sampleIndex());
            state = plan.committedState();
        }

        RealtimeSampleState.Plan wrapped = state.plan(input(camera, 1L, false));
        assertFalse(wrapped.reset());
        assertEquals(0, wrapped.sampleIndex());
        assertEquals(1 << 16, wrapped.frameIndex());
        assertEquals(initialEpoch + 1, wrapped.epoch());
    }

    @Test
    void frameTimeUsesOnlyCommittedHistory() {
        FrameCamera firstCamera = camera(1.0);
        RealtimeSampleState.Plan first = RealtimeSampleState.initial().plan(
                new RealtimeSampleState.Input(firstCamera, 1_000_000L, 1L, false));
        FrameCamera secondCamera = camera(2.0);
        RealtimeSampleState.Plan second = first.committedState().plan(
                new RealtimeSampleState.Input(secondCamera, 11_000_000L, 1L, false));

        assertEquals(firstCamera, second.historyCamera());
        assertEquals(10.0F, second.deltaMilliseconds(), 1.0e-5F);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> first.committedState().plan(
                        new RealtimeSampleState.Input(secondCamera, 1L, 1L, false)));
    }

    private static RealtimeSampleState commit(
            RealtimeSampleState state, RealtimeSampleState.Input input) {
        return state.plan(input).committedState();
    }

    private static RealtimeSampleState.Input input(
            FrameCamera camera, long revision, boolean forceReset) {
        return new RealtimeSampleState.Input(camera, 1L, revision, forceReset);
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(new Matrix4f(), x, 2.0, 3.0);
    }
}
