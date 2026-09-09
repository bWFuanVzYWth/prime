// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.dlss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK12;

final class DlssRrNativeContractTest {
    @Test
    void ngxReceivesTheDeclaredLinearHdrGuideFormats() {
        assertEquals(15, DlssRrPreparePass.IMAGE_COUNT);
        assertEquals(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, DlssRrTargets.COLOR_FORMAT);
        assertEquals(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, DlssRrTargets.ALBEDO_FORMAT);
        assertEquals(
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                DlssRrTargets.NORMAL_ROUGHNESS_FORMAT);
        assertEquals(VK12.VK_FORMAT_R32_SFLOAT, DlssRrTargets.LINEAR_DEPTH_FORMAT);
        assertEquals(VK12.VK_FORMAT_R32G32_SFLOAT, DlssRrTargets.MOTION_FORMAT);
        assertEquals(
                VK12.VK_FORMAT_R16_SFLOAT,
                DlssRrTargets.SPECULAR_HIT_DISTANCE_FORMAT);
        assertEquals(VK12.VK_FORMAT_R16_SFLOAT, DlssRrTargets.RESPONSIVITY_FORMAT);
    }

    @Test
    void jomlColumnMajorBytesAreTheRequiredNgxRowVectorTranspose() {
        Matrix4f matrix = new Matrix4f()
                .m00(1.0F).m01(2.0F).m02(3.0F).m03(4.0F)
                .m10(5.0F).m11(6.0F).m12(7.0F).m13(8.0F)
                .m20(9.0F).m21(10.0F).m22(11.0F).m23(12.0F)
                .m30(13.0F).m31(14.0F).m32(15.0F).m33(16.0F);
        ByteBuffer bytes = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        DlssRrNative.putMatrixForNgx(bytes, 0, matrix);

        for (int index = 0; index < 16; index++) {
            assertEquals(index + 1.0F, bytes.getFloat(index * Float.BYTES));
        }
    }

}
