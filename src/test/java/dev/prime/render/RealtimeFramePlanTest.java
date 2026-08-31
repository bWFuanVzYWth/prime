package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrame;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class RealtimeFramePlanTest {
    private static final SubpixelJitter JITTER = new SubpixelJitter(-0.25F, 1.0F / 6.0F);
    private static final RayConeParameters RAY_CONE = new RayConeParameters(0.1F, 0.0F);

    @Test
    void everyProductModeProducesOneBackendNeutralPlan() {
        for (PostProcessingMode mode : PostProcessingMode.values()) {
            RealtimeFrameInput input = input(mode);
            RealtimeSampleState.Plan sample =
                    RealtimeSampleState.initial().plan(input.sampleStateInput());
            ReconstructionFrameParameters reconstruction =
                    input.reconstructionInput(sample.reset());
            RealtimeFramePlan plan = RealtimeFramePlan.complete(
                    input,
                    sample,
                    reconstruction,
                    new ReconstructionFrame(0, JITTER, true),
                    JITTER,
                    7,
                    RAY_CONE);

            assertEquals(mode, plan.integrator().postProcessingMode());
            assertEquals(input.transparentGuideMode(), plan.integrator().transparentGuideMode());
            assertEquals(RAY_CONE, plan.integrator().rayCone());
            assertEquals(input.maximumBounces(), plan.integrator().maximumBounces());
            assertEquals(
                    input.minimumBounces(),
                    plan.integrator().minimumBounces());
            assertEquals(7, plan.integrator().jitterPhase());
            assertEquals(JITTER, plan.jitter());
            assertTrue(plan.reconstructionReset());
            assertFalse(plan.integrator().historyValid());
        }
    }

    @Test
    void committedFrameAdvancesWithoutRestartAndPreservesCapturedValues() {
        RealtimeFrameInput firstInput = input(PostProcessingMode.NRD_FSR);
        RealtimeSampleState.Plan first =
                RealtimeSampleState.initial().plan(firstInput.sampleStateInput());
        RealtimeFrameInput secondInput = new RealtimeFrameInput(
                firstInput.camera(),
                99L,
                firstInput.sceneRevision(),
                firstInput.residentSceneRevision(),
                firstInput.textureRevision(),
                firstInput.width(),
                firstInput.height(),
                firstInput.displayWidth(),
                firstInput.displayHeight(),
                firstInput.astronomy(),
                firstInput.cameraInWater(),
                firstInput.postProcessingMode(),
                firstInput.quality(),
                firstInput.transparentGuideMode(),
                firstInput.maximumBounces(),
                firstInput.lighting(),
                firstInput.material(),
                firstInput.shInput(),
                firstInput.display(),
                false);
        RealtimeSampleState.Plan second =
                first.committedState().plan(secondInput.sampleStateInput());
        RealtimeFramePlan plan = RealtimeFramePlan.complete(
                secondInput,
                second,
                secondInput.reconstructionInput(second.reset()),
                new ReconstructionFrame(1, JITTER, false),
                JITTER,
                8,
                RAY_CONE);

        assertFalse(second.reset());
        assertTrue(plan.integrator().historyValid());
        assertEquals(1, plan.integrator().sampleIndex());
        assertEquals(99L, plan.reconstruction().frameTimeNanos());
        plan.requireSceneRevision(secondInput.residentSceneRevision());
        plan.requireTextureRevision(secondInput.textureRevision());
        assertThrows(
                IllegalStateException.class,
                () -> plan.requireSceneRevision(secondInput.residentSceneRevision() + 1L));
        assertThrows(
                IllegalStateException.class,
                () -> plan.requireTextureRevision(secondInput.textureRevision() + 1L));
    }

    @Test
    void backendCannotDivergeFromResetJitterOrCapturedParameters() {
        RealtimeFrameInput input = input(PostProcessingMode.DLSS_RR);
        RealtimeSampleState.Plan sample =
                RealtimeSampleState.initial().plan(input.sampleStateInput());
        ReconstructionFrameParameters parameters = input.reconstructionInput(sample.reset());

        assertThrows(
                IllegalStateException.class,
                () -> RealtimeFramePlan.complete(
                        input,
                        sample,
                        parameters,
                        new ReconstructionFrame(0, JITTER, false),
                        JITTER,
                        1,
                        RAY_CONE));
        assertThrows(
                IllegalStateException.class,
                () -> RealtimeFramePlan.complete(
                        input,
                        sample,
                        parameters,
                        new ReconstructionFrame(0, new SubpixelJitter(0.0F, 0.0F), true),
                        JITTER,
                        1,
                        RAY_CONE));
        ReconstructionFrameParameters wrongTexture = new ReconstructionFrameParameters(
                parameters.camera(),
                parameters.frameTimeNanos(),
                parameters.sceneRevision(),
                parameters.textureRevision() + 1L,
                parameters.forceRestart(),
                parameters.sunDirection(),
                parameters.lighting(),
                parameters.display());
        assertThrows(
                IllegalStateException.class,
                () -> RealtimeFramePlan.complete(
                        input,
                        sample,
                        wrongTexture,
                        new ReconstructionFrame(0, JITTER, true),
                        JITTER,
                        1,
                        RAY_CONE));
    }

    private static RealtimeFrameInput input(PostProcessingMode mode) {
        TransparentGuideMode guide = switch (mode) {
            case NRD_FSR -> TransparentGuideMode.REFLECTION_AND_TRANSMISSION;
            case DLSS_RR -> TransparentGuideMode.TRANSMISSION_ONLY;
            case DISABLED -> TransparentGuideMode.DISABLED;
        };
        return new RealtimeFrameInput(
                new FrameCamera(new Matrix4f(), 1.0, 2.0, 3.0),
                42L,
                7L,
                9L,
                11L,
                64,
                48,
                128,
                96,
                AstronomyState.atSolarHourAngle(0.0F, AstronomySettings.defaults()),
                false,
                mode,
                ReconstructionQualityMode.QUALITY,
                guide,
                MaximumBounceSettings.MAXIMUM_COUNT,
                new LightingSettings.Snapshot(0, 0, 0, 13L),
                new MaterialSettings.Snapshot(90, 17L),
                true,
                new DisplaySettings.Snapshot(0, 50),
                false);
    }
}
