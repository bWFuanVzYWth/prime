// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import java.nio.ByteBuffer;

/** Deterministic ABI encoder for Prime's native NRD frame-settings bridge. */
final class NrdFrameSettingsConstants {
    static final int VIEW_TO_CLIP = 0;
    static final int VIEW_TO_CLIP_PREVIOUS = 64;
    static final int WORLD_TO_VIEW = 128;
    static final int WORLD_TO_VIEW_PREVIOUS = 192;
    static final int CAMERA_JITTER = 256;
    static final int PREVIOUS_CAMERA_JITTER = 264;
    static final int WIDTH = 272;
    static final int HEIGHT = 276;
    static final int PREVIOUS_WIDTH = 280;
    static final int PREVIOUS_HEIGHT = 284;
    static final int FRAME_INDEX = 288;
    static final int RESTART = 292;
    static final int TIME_DELTA = 296;
    static final int DENOISING_RANGE = 300;
    static final int ENABLE_VALIDATION = 304;
    static final int SUN_DIRECTION = 308;
    static final int SIZE = 320;

    private NrdFrameSettingsConstants() {
    }

    static void write(
            ByteBuffer target,
            NrdNative.FrameSettings settings) {
        if (target.capacity() < SIZE) {
            throw new IllegalArgumentException(
                    "NRD frame-settings buffer is too small");
        }
        settings.viewToClip().get(VIEW_TO_CLIP, target);
        settings.viewToClipPrevious().get(
                VIEW_TO_CLIP_PREVIOUS, target);
        settings.worldToView().get(WORLD_TO_VIEW, target);
        settings.worldToViewPrevious().get(
                WORLD_TO_VIEW_PREVIOUS, target);
        putVector2(
                target,
                CAMERA_JITTER,
                settings.cameraJitterX(),
                settings.cameraJitterY());
        putVector2(
                target,
                PREVIOUS_CAMERA_JITTER,
                settings.previousCameraJitterX(),
                settings.previousCameraJitterY());
        target.putInt(WIDTH, settings.width());
        target.putInt(HEIGHT, settings.height());
        target.putInt(PREVIOUS_WIDTH, settings.previousWidth());
        target.putInt(PREVIOUS_HEIGHT, settings.previousHeight());
        target.putInt(FRAME_INDEX, settings.frameIndex());
        target.putInt(RESTART, settings.restart() ? 1 : 0);
        target.putFloat(
                TIME_DELTA, settings.timeDeltaMilliseconds());
        target.putFloat(DENOISING_RANGE, settings.denoisingRange());
        target.putInt(
                ENABLE_VALIDATION,
                settings.enableValidation() ? 1 : 0);
        target.putFloat(
                SUN_DIRECTION, settings.sunDirectionX());
        target.putFloat(
                SUN_DIRECTION + Float.BYTES,
                settings.sunDirectionY());
        target.putFloat(
                SUN_DIRECTION + 2 * Float.BYTES,
                settings.sunDirectionZ());
    }

    private static void putVector2(
            ByteBuffer target,
            int offset,
            float x,
            float y) {
        target.putFloat(offset, x);
        target.putFloat(offset + Float.BYTES, y);
    }
}
