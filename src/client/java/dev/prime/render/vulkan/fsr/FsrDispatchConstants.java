// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.fsr;

import dev.prime.render.fsr.FsrDispatchPlan;
import java.nio.ByteBuffer;

/** Deterministic scalar ABI encoder for {@code FfxApiDispatchDescUpscale}. */
final class FsrDispatchConstants {
    static final int JITTER = 360;
    static final int MOTION_SCALE = 368;
    static final int RENDER_SIZE = 376;
    static final int DISPLAY_SIZE = 384;
    static final int ENABLE_SHARPENING = 392;
    static final int SHARPNESS = 396;
    static final int FRAME_TIME = 400;
    static final int PRE_EXPOSURE = 404;
    static final int RESET = 408;
    static final int CAMERA_NEAR = 412;
    static final int CAMERA_FAR = 416;
    static final int CAMERA_FOV_VERTICAL = 420;
    static final int VIEW_SPACE_TO_METERS = 424;
    static final int FLAGS = 428;
    static final int END = 432;


    private FsrDispatchConstants() {
    }

    static void write(ByteBuffer target, FsrDispatchPlan plan) {
        if (target.capacity() < END) {
            throw new IllegalArgumentException(
                    "FidelityFX dispatch description buffer is too small");
        }
        putVector2(
                target,
                JITTER,
                plan.jitterOffset().x(),
                plan.jitterOffset().y());
        putVector2(
                target,
                MOTION_SCALE,
                plan.motionScaleX(),
                plan.motionScaleY());
        putExtent(
                target,
                RENDER_SIZE,
                plan.renderWidth(),
                plan.renderHeight());
        putExtent(
                target,
                DISPLAY_SIZE,
                plan.displayWidth(),
                plan.displayHeight());
        target.putInt(
                ENABLE_SHARPENING,
                plan.sharpening() ? 1 : 0);
        target.putFloat(SHARPNESS, plan.sharpness());
        target.putFloat(FRAME_TIME, plan.frameTimeMilliseconds());
        target.putFloat(PRE_EXPOSURE, plan.preExposure());
        target.putInt(RESET, plan.reset() ? 1 : 0);
        target.putFloat(CAMERA_NEAR, plan.cameraNear());
        target.putFloat(CAMERA_FAR, plan.cameraFar());
        target.putFloat(
                CAMERA_FOV_VERTICAL,
                plan.cameraFovAngleVertical());
        target.putFloat(
                VIEW_SPACE_TO_METERS,
                plan.viewSpaceToMetersFactor());
        target.putInt(FLAGS, 0);
    }

    private static void putExtent(
            ByteBuffer buffer,
            int offset,
            int width,
            int height) {
        buffer.putInt(offset, width);
        buffer.putInt(offset + Integer.BYTES, height);
    }

    private static void putVector2(
            ByteBuffer buffer,
            int offset,
            float x,
            float y) {
        buffer.putFloat(offset, x);
        buffer.putFloat(offset + Float.BYTES, y);
    }
}
