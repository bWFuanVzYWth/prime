// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.fsr;

import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;

/**
 * Pure, device-free scalar payload for one FidelityFX upscaler dispatch.
 *
 * <p>The Vulkan shell binds images and a command buffer separately. Keeping all scalar convention
 * conversion here makes jitter sign, motion scale, projection and exposure directly testable
 * without loading the native SDK.
 */
public record FsrDispatchPlan(
        int renderWidth,
        int renderHeight,
        int displayWidth,
        int displayHeight,
        SubpixelJitter jitterOffset,
        float motionScaleX,
        float motionScaleY,
        boolean sharpening,
        float sharpness,
        float frameTimeMilliseconds,
        float preExposure,
        boolean reset,
        float cameraNear,
        float cameraFar,
        float cameraFovAngleVertical,
        float viewSpaceToMetersFactor) {
    public FsrDispatchPlan {
        Objects.requireNonNull(jitterOffset, "jitterOffset");
        if (renderWidth <= 0
                || renderHeight <= 0
                || displayWidth <= 0
                || displayHeight <= 0
                || renderWidth > displayWidth
                || renderHeight > displayHeight) {
            throw new IllegalArgumentException(
                    "FSR render extent must be positive and not exceed the display extent");
        }
        if (!Float.isFinite(motionScaleX)
                || !Float.isFinite(motionScaleY)
                || motionScaleX != (float) renderWidth
                || motionScaleY != (float) renderHeight) {
            throw new IllegalArgumentException(
                    "Normalized UV motion requires the FSR host scale to equal the render extent");
        }
        if (!Float.isFinite(sharpness)
                || sharpness < 0.0F
                || sharpness > 1.0F
                || !Float.isFinite(frameTimeMilliseconds)
                || frameTimeMilliseconds < 0.0F
                || frameTimeMilliseconds > FrameTime.MAXIMUM_DELTA_MILLISECONDS
                || Float.floatToRawIntBits(preExposure)
                        != Float.floatToRawIntBits(FsrSettings.EXPOSURE)) {
            throw new IllegalArgumentException(
                    "FSR sharpness, frame time or fixed exposure is outside Prime's contract");
        }
        if (Float.floatToRawIntBits(cameraNear)
                        != Float.floatToRawIntBits(Float.MAX_VALUE)
                || Float.floatToRawIntBits(cameraFar)
                        != Float.floatToRawIntBits(ShaderAbi.FSR_NEAR_PLANE)
                || !Float.isFinite(cameraFovAngleVertical)
                || cameraFovAngleVertical <= 0.0F
                || cameraFovAngleVertical > Math.PI
                || Float.floatToRawIntBits(viewSpaceToMetersFactor)
                        != Float.floatToRawIntBits(
                                ShaderAbi.FSR_VIEW_SPACE_TO_METERS_FACTOR)) {
            throw new IllegalArgumentException(
                    "FSR reversed-infinite projection parameters are invalid");
        }
    }

    public static FsrDispatchPlan create(
            FrameCamera camera,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            SubpixelJitter sampleJitter,
            float frameTimeMilliseconds,
            boolean reset) {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(sampleJitter, "sampleJitter");
        float inverseCotangent =
                Math.abs(1.0F / camera.projection().m11());
        float fieldOfView =
                2.0F * (float) Math.atan(inverseCotangent);
        return new FsrDispatchPlan(
                renderWidth,
                renderHeight,
                displayWidth,
                displayHeight,
                sampleJitter.forFsrDispatch(),
                (float) renderWidth,
                (float) renderHeight,
                true,
                FsrSettings.RCAS_SHARPNESS,
                frameTimeMilliseconds,
                FsrSettings.EXPOSURE,
                reset,
                Float.MAX_VALUE,
                ShaderAbi.FSR_NEAR_PLANE,
                fieldOfView,
                ShaderAbi.FSR_VIEW_SPACE_TO_METERS_FACTOR);
    }
}
