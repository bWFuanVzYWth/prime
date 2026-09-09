// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.dlss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.AerialEpipolarMapping;
import dev.prime.render.post.SubpixelJitter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class DlssRrPrepareConstantsTest {
    @Test
    void serializesMatricesSunAndSampleJitterAtTheShaderAbiOffsets() {
        Matrix4f current = sequence(1.0F);
        Matrix4f previous = sequence(17.0F);
        Matrix4f rotation = sequence(33.0F);
        SubpixelJitter jitter = new SubpixelJitter(0.25F, -0.375F);
        ByteBuffer bytes = ByteBuffer.allocateDirect(DlssRrPrepareConstants.SIZE)
                .order(ByteOrder.nativeOrder());

        DlssRrPrepareConstants.write(
                bytes,
                current,
                previous,
                rotation,
                12.5F,
                -0.75F,
                new AerialEpipolarMapping.Epipole(2.0F, -3.0F),
                jitter);

        assertMatrix(bytes, DlssRrPrepareConstants.CURRENT_CLIP_TO_WORLD, 1.0F);
        assertMatrix(bytes, DlssRrPrepareConstants.PREVIOUS_WORLD_TO_CLIP, 17.0F);
        assertMatrix(bytes, DlssRrPrepareConstants.VIEW_ROTATION, 33.0F);
        assertEquals(12.5F, bytes.getFloat(DlssRrPrepareConstants.SUN_RADIANCE));
        assertEquals(-0.75F, bytes.getFloat(DlssRrPrepareConstants.RESPONSIVITY));
        assertEquals(2.0F, bytes.getFloat(DlssRrPrepareConstants.EPIPOLE_X));
        assertEquals(-3.0F, bytes.getFloat(DlssRrPrepareConstants.EPIPOLE_Y));
        assertEquals(0.25F, bytes.getFloat(DlssRrPrepareConstants.JITTER_X));
        assertEquals(-0.375F, bytes.getFloat(DlssRrPrepareConstants.JITTER_Y));
    }

    private static Matrix4f sequence(float first) {
        return new Matrix4f()
                .m00(first).m01(first + 1.0F).m02(first + 2.0F).m03(first + 3.0F)
                .m10(first + 4.0F).m11(first + 5.0F).m12(first + 6.0F).m13(first + 7.0F)
                .m20(first + 8.0F).m21(first + 9.0F).m22(first + 10.0F).m23(first + 11.0F)
                .m30(first + 12.0F).m31(first + 13.0F).m32(first + 14.0F).m33(first + 15.0F);
    }

    private static void assertMatrix(ByteBuffer bytes, int offset, float first) {
        for (int index = 0; index < 16; index++) {
            assertEquals(first + index, bytes.getFloat(offset + index * Float.BYTES));
        }
    }
}
