package dev.prime.render.fsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.data.RendererDataContracts;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.shader.ShaderAbi;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class FsrDispatchPlanTest {
    @Test
    void derivesTheCompleteNativeScalarContractWithoutDeviceState() {
        FsrDispatchPlan plan = FsrDispatchPlan.create(
                camera((float) Math.toRadians(70.0)),
                2560,
                1440,
                3840,
                2160,
                new SubpixelJitter(-0.25F, 1.0F / 6.0F),
                16.5F,
                true);

        assertEquals(0.25F, plan.jitterOffset().x());
        assertEquals(-1.0F / 6.0F, plan.jitterOffset().y());
        double[] canonicalProjectionJitter =
                RendererDataContracts.projectionJitterPixels(-0.25, 1.0 / 6.0);
        assertEquals(canonicalProjectionJitter[0], plan.jitterOffset().x(), 0.0);
        assertEquals(canonicalProjectionJitter[1], plan.jitterOffset().y(), 1.0e-8);
        assertEquals(2560.0F, plan.motionScaleX());
        assertEquals(1440.0F, plan.motionScaleY());
        assertTrue(plan.sharpening());
        assertEquals(FsrSettings.RCAS_SHARPNESS, plan.sharpness());
        assertEquals(FsrSettings.EXPOSURE, plan.preExposure());
        assertTrue(plan.reset());
        assertEquals(Float.MAX_VALUE, plan.cameraNear());
        assertEquals(ShaderAbi.FSR_NEAR_PLANE, plan.cameraFar());
        assertEquals(
                (float) Math.toRadians(70.0),
                plan.cameraFovAngleVertical(),
                1.0e-6F);
        assertEquals(
                ShaderAbi.FSR_VIEW_SPACE_TO_METERS_FACTOR,
                plan.viewSpaceToMetersFactor());
    }

    @Test
    void rejectsInvalidExtentsProjectionTimeAndValueObjects() {
        FrameCamera camera = camera((float) Math.toRadians(70.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> FsrDispatchPlan.create(
                        camera,
                        3841,
                        2160,
                        3840,
                        2160,
                        new SubpixelJitter(0.0F, 0.0F),
                        16.0F,
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> FsrDispatchPlan.create(
                        camera,
                        1920,
                        1080,
                        3840,
                        2160,
                        new SubpixelJitter(0.0F, 0.0F),
                        Float.NaN,
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> FsrDispatchPlan.create(
                        new FrameCamera(
                                new Matrix4f().m11(Float.NaN),
                                new Matrix4f(),
                                new Matrix4f(),
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                0.0),
                        1920,
                        1080,
                        3840,
                        2160,
                        new SubpixelJitter(0.0F, 0.0F),
                        16.0F,
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SubpixelJitter(0.75F, 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReconstructionExtent(0, 1));

        FsrDispatchPlan off = FsrDispatchPlan.create(
                camera,
                1920,
                1080,
                3840,
                2160,
                new SubpixelJitter(0.0F, 0.0F),
                0.0F,
                false);
        assertEquals(0.0F, off.frameTimeMilliseconds());
    }

    private static FrameCamera camera(float verticalFov) {
        Matrix4f projection = new Matrix4f().perspective(
                verticalFov,
                16.0F / 9.0F,
                512.0F,
                ShaderAbi.FSR_NEAR_PLANE,
                true);
        return new FrameCamera(
                projection,
                new Matrix4f(),
                new Matrix4f(projection).invert(),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0);
    }
}
