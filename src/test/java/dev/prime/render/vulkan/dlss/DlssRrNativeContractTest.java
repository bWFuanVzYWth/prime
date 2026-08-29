package dev.prime.render.vulkan.dlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK12;

final class DlssRrNativeContractTest {
    @Test
    void fixedWidthJavaAbiMatchesTheNativeBridge() {
        assertEquals(8, DlssRrNative.ABI_VERSION);
        assertEquals(6, DlssRrNative.RENDER_PRESET_F);
        assertEquals(56, DlssRrNative.EXTENSION_QUERY_SIZE);
        assertEquals(56, DlssRrNative.INIT_DESCRIPTION_SIZE);
        assertEquals(32, DlssRrNative.OPTIMAL_SETTINGS_SIZE);
        assertEquals(48, DlssRrNative.FEATURE_DESCRIPTION_SIZE);
        assertEquals(32, DlssRrNative.IMAGE_SIZE);
        assertEquals(10, DlssRrNative.IMAGE_COUNT);
        assertEquals(496, DlssRrNative.EVALUATE_DESCRIPTION_SIZE);
    }

    @Test
    void ngxReceivesTheDeclaredLinearHdrGuideFormats() {
        assertEquals(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, DlssRrTargets.COLOR_FORMAT);
        assertEquals(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, DlssRrTargets.ALBEDO_FORMAT);
        assertEquals(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                DlssRrTargets.NORMAL_ROUGHNESS_FORMAT);
        assertEquals(VK12.VK_FORMAT_R32_SFLOAT, DlssRrTargets.LINEAR_DEPTH_FORMAT);
        assertEquals(VK12.VK_FORMAT_R16G16_SFLOAT, DlssRrTargets.MOTION_FORMAT);
        assertEquals(VK12.VK_FORMAT_R32G32_SFLOAT, DlssRrTargets.SPECULAR_MOTION_FORMAT);
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

    @Test
    void onlyX64WindowsIsAccepted() {
        assertTrue(DlssRrNative.isSupportedPlatform("Windows 11", "amd64"));
        assertTrue(DlssRrNative.isSupportedPlatform("WINDOWS 10", "x86_64"));
        assertFalse(DlssRrNative.isSupportedPlatform("Windows 11", "aarch64"));
        assertFalse(DlssRrNative.isSupportedPlatform("Linux", "amd64"));
    }

}
