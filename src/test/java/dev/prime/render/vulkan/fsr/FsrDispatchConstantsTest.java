// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.fsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.FrameCamera;
import dev.prime.render.fsr.FsrDispatchPlan;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.shader.ShaderAbi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class FsrDispatchConstantsTest {
    @Test
    void overwritesTheCompleteNativeScalarAbiFromOnePurePlan() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0),
                16.0F / 9.0F,
                512.0F,
                ShaderAbi.FSR_NEAR_PLANE,
                true);
        FsrDispatchPlan plan = FsrDispatchPlan.create(
                new FrameCamera(
                        projection,
                        new Matrix4f(),
                        new Matrix4f(projection).invert(),
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
                new SubpixelJitter(-0.25F, 0.375F),
                12.5F,
                true);
        ByteBuffer target = ByteBuffer.allocateDirect(
                        FsrDispatchConstants.END)
                .order(ByteOrder.nativeOrder());

        FsrDispatchConstants.write(target, plan);

        assertEquals(
                plan.jitterOffset().x(),
                target.getFloat(FsrDispatchConstants.JITTER));
        assertEquals(
                plan.jitterOffset().y(),
                target.getFloat(
                        FsrDispatchConstants.JITTER + Float.BYTES));
        assertEquals(
                plan.motionScaleX(),
                target.getFloat(FsrDispatchConstants.MOTION_SCALE));
        assertEquals(
                plan.motionScaleY(),
                target.getFloat(
                        FsrDispatchConstants.MOTION_SCALE + Float.BYTES));
        assertEquals(
                plan.renderWidth(),
                target.getInt(FsrDispatchConstants.RENDER_SIZE));
        assertEquals(
                plan.renderHeight(),
                target.getInt(
                        FsrDispatchConstants.RENDER_SIZE + Integer.BYTES));
        assertEquals(
                plan.displayWidth(),
                target.getInt(FsrDispatchConstants.DISPLAY_SIZE));
        assertEquals(
                plan.displayHeight(),
                target.getInt(
                        FsrDispatchConstants.DISPLAY_SIZE + Integer.BYTES));
        assertEquals(1, target.getInt(
                FsrDispatchConstants.ENABLE_SHARPENING));
        assertEquals(
                plan.sharpness(),
                target.getFloat(FsrDispatchConstants.SHARPNESS));
        assertEquals(
                plan.frameTimeMilliseconds(),
                target.getFloat(FsrDispatchConstants.FRAME_TIME));
        assertEquals(
                plan.preExposure(),
                target.getFloat(FsrDispatchConstants.PRE_EXPOSURE));
        assertEquals(1, target.getInt(FsrDispatchConstants.RESET));
        assertEquals(
                plan.cameraNear(),
                target.getFloat(FsrDispatchConstants.CAMERA_NEAR));
        assertEquals(
                plan.cameraFar(),
                target.getFloat(FsrDispatchConstants.CAMERA_FAR));
        assertEquals(
                plan.cameraFovAngleVertical(),
                target.getFloat(
                        FsrDispatchConstants.CAMERA_FOV_VERTICAL));
        assertEquals(
                plan.viewSpaceToMetersFactor(),
                target.getFloat(
                        FsrDispatchConstants.VIEW_SPACE_TO_METERS));
        assertEquals(0, target.getInt(FsrDispatchConstants.FLAGS));
    }

    @Test
    void rejectsAnUndersizedNativeDescription() {
        ByteBuffer target = ByteBuffer.allocate(
                FsrDispatchConstants.END - 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> FsrDispatchConstants.write(target, null));
    }
}
