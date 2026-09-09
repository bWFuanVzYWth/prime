// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdFrameSettingsConstantsTest {
    @Test
    void serializesEveryNativeFieldAtTheBridgeAbiOffset() {
        NrdNative.FrameSettings settings = new NrdNative.FrameSettings(
                sequence(1.0F),
                sequence(17.0F),
                sequence(33.0F),
                sequence(49.0F),
                0.25F,
                -0.375F,
                -0.125F,
                0.5F,
                1920,
                1080,
                1280,
                720,
                37,
                true,
                16.5F,
                65_504.0F,
                true,
                0.0F,
                1.0F,
                0.0F);
        ByteBuffer target = ByteBuffer.allocateDirect(
                        NrdFrameSettingsConstants.SIZE)
                .order(ByteOrder.nativeOrder());

        NrdFrameSettingsConstants.write(target, settings);

        assertMatrix(
                target,
                NrdFrameSettingsConstants.VIEW_TO_CLIP,
                1.0F);
        assertMatrix(
                target,
                NrdFrameSettingsConstants.VIEW_TO_CLIP_PREVIOUS,
                17.0F);
        assertMatrix(
                target,
                NrdFrameSettingsConstants.WORLD_TO_VIEW,
                33.0F);
        assertMatrix(
                target,
                NrdFrameSettingsConstants.WORLD_TO_VIEW_PREVIOUS,
                49.0F);
        assertEquals(
                settings.cameraJitterX(),
                target.getFloat(
                        NrdFrameSettingsConstants.CAMERA_JITTER));
        assertEquals(
                settings.cameraJitterY(),
                target.getFloat(
                        NrdFrameSettingsConstants.CAMERA_JITTER
                                + Float.BYTES));
        assertEquals(
                settings.previousCameraJitterX(),
                target.getFloat(
                        NrdFrameSettingsConstants
                                .PREVIOUS_CAMERA_JITTER));
        assertEquals(
                settings.previousCameraJitterY(),
                target.getFloat(
                        NrdFrameSettingsConstants
                                        .PREVIOUS_CAMERA_JITTER
                                + Float.BYTES));
        assertEquals(
                settings.width(),
                target.getInt(NrdFrameSettingsConstants.WIDTH));
        assertEquals(
                settings.height(),
                target.getInt(NrdFrameSettingsConstants.HEIGHT));
        assertEquals(
                settings.previousWidth(),
                target.getInt(
                        NrdFrameSettingsConstants.PREVIOUS_WIDTH));
        assertEquals(
                settings.previousHeight(),
                target.getInt(
                        NrdFrameSettingsConstants.PREVIOUS_HEIGHT));
        assertEquals(
                settings.frameIndex(),
                target.getInt(NrdFrameSettingsConstants.FRAME_INDEX));
        assertEquals(
                1, target.getInt(NrdFrameSettingsConstants.RESTART));
        assertEquals(
                settings.timeDeltaMilliseconds(),
                target.getFloat(NrdFrameSettingsConstants.TIME_DELTA));
        assertEquals(
                settings.denoisingRange(),
                target.getFloat(
                        NrdFrameSettingsConstants.DENOISING_RANGE));
        assertEquals(
                1,
                target.getInt(
                        NrdFrameSettingsConstants.ENABLE_VALIDATION));
        assertEquals(
                settings.sunDirectionX(),
                target.getFloat(
                        NrdFrameSettingsConstants.SUN_DIRECTION));
        assertEquals(
                settings.sunDirectionY(),
                target.getFloat(
                        NrdFrameSettingsConstants.SUN_DIRECTION
                                + Float.BYTES));
        assertEquals(
                settings.sunDirectionZ(),
                target.getFloat(
                        NrdFrameSettingsConstants.SUN_DIRECTION
                                + 2 * Float.BYTES));
    }

    @Test
    void rejectsInvalidSemanticValuesBeforeTheNativeCall() {
        Matrix4f identity = new Matrix4f();
        assertThrows(
                IllegalArgumentException.class,
                () -> new NrdNative.FrameSettings(
                        identity,
                        identity,
                        identity,
                        identity,
                        0.75F,
                        0.0F,
                        0.0F,
                        0.0F,
                        1,
                        1,
                        1,
                        1,
                        0,
                        false,
                        16.0F,
                        65_504.0F,
                        false,
                        0.0F,
                        1.0F,
                        0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NrdNative.FrameSettings(
                        identity,
                        identity,
                        identity,
                        identity,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        1,
                        1,
                        1,
                        1,
                        0,
                        false,
                        16.0F,
                        65_504.0F,
                        false,
                        0.0F,
                        0.0F,
                        0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> NrdFrameSettingsConstants.write(
                        ByteBuffer.allocate(
                                NrdFrameSettingsConstants.SIZE - 1),
                        null));
    }

    private static Matrix4f sequence(float first) {
        return new Matrix4f()
                .m00(first)
                .m01(first + 1.0F)
                .m02(first + 2.0F)
                .m03(first + 3.0F)
                .m10(first + 4.0F)
                .m11(first + 5.0F)
                .m12(first + 6.0F)
                .m13(first + 7.0F)
                .m20(first + 8.0F)
                .m21(first + 9.0F)
                .m22(first + 10.0F)
                .m23(first + 11.0F)
                .m30(first + 12.0F)
                .m31(first + 13.0F)
                .m32(first + 14.0F)
                .m33(first + 15.0F);
    }

    private static void assertMatrix(
            ByteBuffer target, int offset, float first) {
        for (int index = 0; index < 16; index++) {
            assertEquals(
                    first + index,
                    target.getFloat(
                            offset + index * Float.BYTES));
        }
    }
}
