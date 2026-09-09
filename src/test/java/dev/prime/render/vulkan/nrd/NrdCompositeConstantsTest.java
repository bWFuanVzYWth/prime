// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class NrdCompositeConstantsTest {
    @Test
    void writesAlignedShaderLayoutAndClearsPadding() {
        ByteBuffer target = ByteBuffer.allocate(NrdCompositeConstants.SIZE)
                .order(ByteOrder.nativeOrder());
        Arrays.fill(target.array(), (byte) 0x5a);

        NrdCompositeConstants.write(
                target, 1920, 1080, 3.25F, -0.25F, 0.5F, -0.75F, 0.875F);

        assertEquals(1920, target.getInt(NrdCompositeConstants.OUTPUT_EXTENT));
        assertEquals(1080, target.getInt(
                NrdCompositeConstants.OUTPUT_EXTENT + Integer.BYTES));
        assertEquals(3.25F, target.getFloat(
                NrdCompositeConstants.SUN_RADIANCE_MULTIPLIER));
        assertEquals(0, target.getInt(12));
        assertEquals(-0.25F, target.getFloat(
                NrdCompositeConstants.CURRENT_JITTER_PIXELS));
        assertEquals(0.5F, target.getFloat(
                NrdCompositeConstants.CURRENT_JITTER_PIXELS + Float.BYTES));
        assertEquals(-0.75F, target.getFloat(NrdCompositeConstants.EPIPOLE_NDC));
        assertEquals(0.875F, target.getFloat(
                NrdCompositeConstants.EPIPOLE_NDC + Float.BYTES));
    }

    @Test
    void rejectsUndersizedBuffer() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NrdCompositeConstants.write(
                        ByteBuffer.allocate(NrdCompositeConstants.SIZE - 1),
                        1,
                        1,
                        1.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F));
    }
}
