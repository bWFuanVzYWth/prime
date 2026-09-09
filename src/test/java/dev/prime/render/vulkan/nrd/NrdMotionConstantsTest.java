// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.shader.ShaderAbi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdMotionConstantsTest {
    @Test
    void writesEveryDeclaredFieldAndClearsLayoutPadding() {
        ByteBuffer target = ByteBuffer.allocate(
                        ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        Arrays.fill(target.array(), (byte) 0x5a);

        NrdMotionConstants.write(
                target,
                matrix(1.0F),
                matrix(21.0F),
                -0.5F,
                0.25F);

        assertMatrix(
                target,
                ShaderAbi.NRD_MOTION_PUSH_CURRENT_CLIP_TO_WORLD_OFFSET,
                1.0F);
        assertMatrix(
                target,
                ShaderAbi.NRD_MOTION_PUSH_PREVIOUS_WORLD_TO_CLIP_OFFSET,
                21.0F);
        int jitter = ShaderAbi.NRD_MOTION_PUSH_CURRENT_JITTER_PIXELS_OFFSET;
        assertEquals(-0.5F, target.getFloat(jitter));
        assertEquals(0.25F, target.getFloat(jitter + Float.BYTES));
    }

    @Test
    void rejectsInvalidAbiInputsBeforeEncoding() {
        ByteBuffer target = ByteBuffer.allocate(
                        ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        Matrix4f identity = new Matrix4f();

        assertThrows(
                IllegalArgumentException.class,
                () -> NrdMotionConstants.write(
                        ByteBuffer.allocate(ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE - 1),
                        identity,
                        identity,
                        0.0F,
                        0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> NrdMotionConstants.write(
                        target,
                        new Matrix4f().m00(Float.NaN),
                        identity,
                        0.0F,
                        0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> NrdMotionConstants.write(
                        target,
                        identity,
                        identity,
                        0.5001F,
                        0.0F));
    }

    private static Matrix4f matrix(float first) {
        float[] values = new float[16];
        for (int index = 0; index < values.length; index++) {
            values[index] = first + index;
        }
        return new Matrix4f().set(values);
    }

    private static void assertMatrix(ByteBuffer target, int offset, float first) {
        for (int index = 0; index < 16; index++) {
            assertEquals(first + index, target.getFloat(offset + index * Float.BYTES));
        }
    }
}
