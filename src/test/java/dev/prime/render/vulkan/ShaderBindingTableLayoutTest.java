// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ShaderBindingTableLayoutTest {
    @Test
    void alignsEveryRegionEvenWhenTheBufferAddressIsNotBaseAligned() {
        long bufferAddress = 0x1008L;
        ShaderBindingTableLayout layout = ShaderBindingTableLayout.create(
                32, 32, 64, 4, 3, 2, 2, bufferAddress);
        assertEquals(32L, layout.recordStride());
        assertEquals(64L, layout.raygenRecordStride());
        assertEquals(56L, layout.raygenOffset());
        assertEquals(248L, layout.missOffset());
        assertEquals(312L, layout.hitOffset());
        assertEquals(376L, layout.totalSize());
        assertEquals(0L, (bufferAddress + layout.raygenOffset()) % 64L);
        assertEquals(
                0L,
                (bufferAddress + layout.raygenOffset() + 2L * layout.raygenRecordStride()) % 64L);
        assertEquals(0L, (bufferAddress + layout.missOffset()) % 64L);
        assertEquals(0L, (bufferAddress + layout.hitOffset()) % 64L);
        assertTrue(layout.hitOffset() >= layout.missOffset() + 2L * layout.recordStride());
        assertTrue(layout.totalSize()
                <= ShaderBindingTableLayout.minimumBufferSize(
                        32, 32, 64, 4, 3, 2, 2));
    }

    @Test
    void raygenInlineDataCannotOverlapAFullSizeHandle() {
        ShaderBindingTableLayout layout = ShaderBindingTableLayout.create(
                64, 64, 64, 4, 3, 2, 2, 0L);

        assertEquals(128L, layout.raygenRecordStride());
    }

    @Test
    void rejectsNonPowerOfTwoAlignments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderBindingTableLayout.create(32, 24, 64, 4, 3, 2, 2, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderBindingTableLayout.create(32, 32, 48, 4, 3, 2, 2, 0L));
    }

    @Test
    void rejectsEmptyRaygenMissOrHitRegions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderBindingTableLayout.create(32, 32, 64, 4, 0, 2, 2, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderBindingTableLayout.create(32, 32, 64, 4, 3, 0, 2, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderBindingTableLayout.create(32, 32, 64, 4, 3, 2, 0, 0L));
    }
}
