// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import java.util.Objects;

/**
 * Pure sampling and reconstruction timeline for the interactive render path.
 *
 * <p>A plan exposes the Sobol identity, temporal camera and frame time used by one frame. The
 * returned state becomes current only after submission, so failed recording consumes neither
 * sampling nor reconstruction history.
 * Only whole-scene continuity lives here. Material, lighting and local scene changes retain the
 * sequence so temporal reconstruction can reject changed pixels without flashing the whole frame.
 */
public final class RealtimeSampleState {
    private static final int SOBOL_SEQUENCE_LENGTH = 1 << 16;
    private final FrameCamera camera;
    private final long resetRevision;
    private final int sampleIndex;
    private final int epoch;
    private final int frameIndex;
    private final long frameTimeNanos;
    private final boolean resetRequested;

    private RealtimeSampleState(
            FrameCamera camera,
            long resetRevision,
            int sampleIndex,
            int epoch,
            int frameIndex,
            long frameTimeNanos,
            boolean resetRequested) {
        this.camera = camera;
        this.resetRevision = resetRevision;
        this.sampleIndex = sampleIndex;
        this.epoch = epoch;
        this.frameIndex = frameIndex;
        this.frameTimeNanos = frameTimeNanos;
        this.resetRequested = resetRequested;
    }

    public static RealtimeSampleState initial() {
        return new RealtimeSampleState(
                null,
                Long.MIN_VALUE,
                0,
                0,
                0,
                0L,
                true);
    }

    public Plan plan(Input input) {
        Objects.requireNonNull(input, "input");
        // Motion vectors preserve ordinary camera motion. Restarting on every translated or
        // rotated frame destroys temporal Sobol stratification and raises 1 spp noise.
        boolean initialized = this.camera != null;
        boolean cameraCut = initialized
                && CameraDiscontinuity.isCut(this.camera, input.camera());
        boolean reset = this.resetRequested
                || input.forceReset()
                || !initialized
                || cameraCut
                || input.resetRevision() != this.resetRevision;
        int plannedSample = reset ? 0 : this.sampleIndex;
        int plannedEpoch = reset ? this.epoch + 1 : this.epoch;
        int plannedFrame = reset ? 0 : this.frameIndex;
        if (!reset && plannedSample >= SOBOL_SEQUENCE_LENGTH) {
            plannedSample = 0;
            plannedEpoch++;
        }
        FrameCamera historyCamera = reset ? input.camera() : this.camera;
        float deltaMilliseconds = FrameTime.deltaMilliseconds(
                initialized, input.frameTimeNanos(), this.frameTimeNanos);
        RealtimeSampleState committed = new RealtimeSampleState(
                input.camera(),
                input.resetRevision(),
                plannedSample + 1,
                plannedEpoch,
                Math.incrementExact(plannedFrame),
                input.frameTimeNanos(),
                false);
        return new Plan(
                input.camera(),
                historyCamera,
                plannedSample,
                plannedEpoch,
                plannedFrame,
                reset,
                deltaMilliseconds,
                committed);
    }

    public RealtimeSampleState invalidated() {
        if (this.resetRequested) {
            return this;
        }
        return new RealtimeSampleState(
                this.camera,
                this.resetRevision,
                this.sampleIndex,
                this.epoch,
                this.frameIndex,
                this.frameTimeNanos,
                true);
    }

    public int sampleIndex() {
        return this.sampleIndex;
    }

    public int epoch() {
        return this.epoch;
    }

    public record Input(
            FrameCamera camera,
            long frameTimeNanos,
            long resetRevision,
            boolean forceReset) {
        public Input {
            Objects.requireNonNull(camera, "camera");
        }
    }

    public record Plan(
            FrameCamera camera,
            FrameCamera historyCamera,
            int sampleIndex,
            int epoch,
            int frameIndex,
            boolean reset,
            float deltaMilliseconds,
            RealtimeSampleState committedState) {
        public Plan {
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(historyCamera, "historyCamera");
            if (sampleIndex < 0 || sampleIndex >= SOBOL_SEQUENCE_LENGTH) {
                throw new IllegalArgumentException("Sobol sample index is outside its sequence");
            }
            if (frameIndex < 0
                    || !Float.isFinite(deltaMilliseconds)
                    || deltaMilliseconds < 0.0F
                    || deltaMilliseconds > FrameTime.MAXIMUM_DELTA_MILLISECONDS) {
                throw new IllegalArgumentException("Invalid realtime temporal frame");
            }
            Objects.requireNonNull(committedState, "committedState");
        }
    }
}
