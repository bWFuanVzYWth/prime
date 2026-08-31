package dev.prime.render.post;

import dev.prime.render.RayConeParameters;
import dev.prime.render.StableIds;
import java.util.Optional;

/** Shared render-resolution, sampling, mip and ray-cone policy for reconstruction backends. */
public enum ReconstructionQualityMode {
    NATIVE_AA("native_aa", 1.0F),
    QUALITY("quality", 1.5F),
    BALANCED("balanced", 1.7F),
    PERFORMANCE("performance", 2.0F),
    ULTRA_PERFORMANCE("ultra_performance", 3.0F);

    public static final ReconstructionQualityMode DEFAULT = PERFORMANCE;

    private final String id;
    private final float upscaleRatio;
    private final int jitterPhaseCount;
    private final float mipBias;
    private final SubpixelJitter[] jitterSequence;

    ReconstructionQualityMode(String id, float upscaleRatio) {
        this.id = id;
        this.upscaleRatio = upscaleRatio;
        this.jitterPhaseCount = Math.max(1, (int) (8.0F * upscaleRatio * upscaleRatio));
        this.mipBias = (float) (Math.log(1.0 / upscaleRatio) / Math.log(2.0) - 1.0);
        this.jitterSequence = new SubpixelJitter[this.jitterPhaseCount];
        for (int index = 0; index < this.jitterSequence.length; index++) {
            this.jitterSequence[index] = SubpixelJitter.halton(index + 1);
        }
    }

    public String id() {
        return this.id;
    }

    public float upscaleRatio() {
        return this.upscaleRatio;
    }

    public int jitterPhaseCount() {
        return this.jitterPhaseCount;
    }

    public float mipBias() {
        return this.mipBias;
    }

    public ReconstructionExtent renderExtent(int displayWidth, int displayHeight) {
        return new ReconstructionExtent(
                renderDimension(displayWidth), renderDimension(displayHeight));
    }

    public SubpixelJitter jitter(int frameIndex) {
        return this.jitterSequence[jitterPhase(frameIndex) - 1];
    }

    /** Returns the one-based Halton phase consumed by both ray-generation passes. */
    public int jitterPhase(int frameIndex) {
        return Math.floorMod(frameIndex, this.jitterPhaseCount) + 1;
    }

    public RayConeParameters rayConeParameters(
            float projectionM00, float projectionM11, int width, int height) {
        return RayConeParameters.fromProjection(
                projectionM00, projectionM11, width, height, this.mipBias);
    }

    public static Optional<ReconstructionQualityMode> findById(String id) {
        return StableIds.find(values(), id, ReconstructionQualityMode::id);
    }

    public static ReconstructionQualityMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }

    private int renderDimension(int displayDimension) {
        if (displayDimension <= 0) {
            throw new IllegalArgumentException("Display dimensions must be positive");
        }
        return Math.max(1, (int) (displayDimension / this.upscaleRatio));
    }
}
