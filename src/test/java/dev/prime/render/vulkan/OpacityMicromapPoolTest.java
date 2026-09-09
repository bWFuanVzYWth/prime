// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.prime.render.terrain.OpacityMicromapData;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OpacityMicromapPoolTest {
    @Test
    void contentCanonicalizesOrderAndDuplicateBlocks() {
        OpacityMicromapPool.Block high = block(0x7f);
        OpacityMicromapPool.Block low = block(0x01);

        OpacityMicromapPool.Content content =
                OpacityMicromapPool.Content.from(List.of(high, low, high));

        assertEquals(2, content.blockCount());
        assertEquals(content.indexOf(high), content.remap(
                OpacityMicromapPool.Content.from(List.of(high)))[0]);
        assertEquals(content.indexOf(low), content.remap(
                OpacityMicromapPool.Content.from(List.of(low)))[0]);
    }

    @Test
    void supersetContentProvidesExactSubsetRemap() {
        OpacityMicromapPool.Block first = block(0x11);
        OpacityMicromapPool.Block second = block(0x22);
        OpacityMicromapPool.Content superset =
                OpacityMicromapPool.Content.from(List.of(first, second));
        OpacityMicromapPool.Content subset =
                OpacityMicromapPool.Content.from(List.of(second));

        assertArrayEquals(
                new int[] {superset.indexOf(second)},
                superset.remap(subset));
        assertNull(subset.remap(superset));
    }

    private static OpacityMicromapPool.Block block(int states) {
        return new OpacityMicromapPool.Block(
                OpacityMicromapData.TWO_STATE_FORMAT,
                0,
                new byte[] {(byte) states});
    }
}
