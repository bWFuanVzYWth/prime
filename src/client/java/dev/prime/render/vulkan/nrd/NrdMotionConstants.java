// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import dev.prime.render.shader.ShaderAbi;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.joml.Matrix4fc;

/** Deterministic ABI encoder for {@code PrimeMotionPushConstants}. */
final class NrdMotionConstants {
    private NrdMotionConstants() {
    }

    static void write(
            ByteBuffer target,
            Matrix4fc currentClipToWorld,
            Matrix4fc previousWorldToClip,
            float cameraJitterX,
            float cameraJitterY) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(
                currentClipToWorld, "currentClipToWorld");
        Objects.requireNonNull(
                previousWorldToClip, "previousWorldToClip");
        if (target.capacity()
                < ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException(
                    "NRD motion push-constant buffer is too small");
        }
        if (!currentClipToWorld.isFinite()
                || !previousWorldToClip.isFinite()) {
            throw new IllegalArgumentException(
                    "NRD motion matrices must be finite");
        }
        if (!validJitter(cameraJitterX)
                || !validJitter(cameraJitterY)) {
            throw new IllegalArgumentException(
                    "NRD motion jitter is invalid");
        }
        currentClipToWorld.get(
                ShaderAbi.NRD_MOTION_PUSH_CURRENT_CLIP_TO_WORLD_OFFSET,
                target);
        previousWorldToClip.get(
                ShaderAbi.NRD_MOTION_PUSH_PREVIOUS_WORLD_TO_CLIP_OFFSET,
                target);
        int jitter =
                ShaderAbi.NRD_MOTION_PUSH_CURRENT_JITTER_PIXELS_OFFSET;
        target.putFloat(jitter, cameraJitterX);
        target.putFloat(jitter + Float.BYTES, cameraJitterY);
    }

    private static boolean validJitter(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 0.5F;
    }
}
