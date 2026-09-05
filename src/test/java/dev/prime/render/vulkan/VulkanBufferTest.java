package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

final class VulkanBufferTest {
    @Test
    @org.junit.jupiter.api.Tag("native")
    void nativeUploadsCopyOnlyRemainingBytesOfPositionedAndSlicedViews() {
        ByteBuffer source = MemoryUtil.memAlloc(12);
        ByteBuffer target = MemoryUtil.memAlloc(8);
        try {
            for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) i);
            source.position(2).limit(9);
            ByteBuffer[] views = {source, source.slice().position(1).limit(5).asReadOnlyBuffer()};
            for (ByteBuffer view : views) {
                MemoryUtil.memSet(MemoryUtil.memAddress(target), 0x7f, target.capacity());
                int position = view.position();
                int limit = view.limit();
                byte[] expected = new byte[view.remaining()];
                view.duplicate().get(expected);
                VulkanBuffer.copyRemaining(view, MemoryUtil.memAddress(target));
                byte[] actual = new byte[expected.length];
                target.duplicate().get(actual);
                assertArrayEquals(expected, actual);
                assertEquals((byte) 0x7f, target.get(expected.length));
                assertEquals(position, view.position());
                assertEquals(limit, view.limit());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> VulkanBuffer.copyRemaining(ByteBuffer.allocate(1), MemoryUtil.memAddress(target)));
        } finally {
            MemoryUtil.memFree(target);
            MemoryUtil.memFree(source);
        }
    }

    @Test
    void mappedRangesRejectOverflowWithoutTouchingNativeMemory() {
        VulkanBuffer buffer = new VulkanBuffer(1L, 2L, 3L, 4L, 5L, 16L);
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> buffer.put(Long.MAX_VALUE, ByteBuffer.allocateDirect(1)));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> buffer.put(15L, 1L, 2L));
    }

    @Test
    void hostOperationsRejectDeviceOnlyBuffers() {
        VulkanBuffer buffer = new VulkanBuffer(1L, 2L, 3L, 4L, 0L, 16L);
        assertThrows(IllegalStateException.class, () -> buffer.flush(0L, 16L));
        assertThrows(IllegalStateException.class, () -> buffer.invalidate(0L, 16L));
    }
}
