// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class SuccessorQueueGpuTest extends GpuShaderTest {
    @Test
    void scalarAndSubgroupPublishEverySurvivorOnceAcrossEmptyMixedAndPartialGroups() throws Exception {
        for (int count : new int[] {1, 31, 65, 4093}) {
            for (int pattern = 0; pattern < 4; ++pattern) {
                for (int queue = 0; queue < 2; ++queue) {
                    ByteBuffer input = ByteBuffer.allocateDirect(8 + count * 4).order(ByteOrder.LITTLE_ENDIAN);
                    input.putInt(count).putInt(queue);
                    int expected = 0;
                    for (int i = 0; i < count; ++i) {
                        int alive = alive(pattern, i) ? 1 : 0;
                        input.putInt(alive); expected += alive;
                    }
                    input.flip();
                    for (String artifact : new String[] {"successor_queue", "successor_queue_subgroup", "successor_queue_ser",
                            "wavefront_queue", "wavefront_queue_subgroup"}) {
                        ByteBuffer output = runner.dispatch(artifact + ".comp.spv",
                                input, (8 + 2 * count) * 4, count);
                        assertEquals(expected, output.getInt(queue * 16));
                        assertEquals(0, output.getInt((queue ^ 1) * 16));
                        int reservations = output.getInt(queue * 16 + 4);
                        assertEquals(0, output.getInt(queue * 16 + 8), "No overflow");
                        if (!artifact.endsWith("_subgroup")) assertEquals(expected, reservations);
                        else {
                            assertTrue(reservations <= expected);
                            if (expected == 0) assertEquals(0, reservations);
                            if (pattern == 1 && count > 64) assertTrue(reservations < expected);
                        }
                        boolean[] seen = new boolean[count];
                        for (int entry = 0; entry < expected; ++entry) {
                            int index = output.getInt((8 + queue * count + entry) * 4) - 1;
                            assertTrue(index >= 0 && index < count);
                            assertTrue(alive(pattern, index));
                            assertFalse(seen[index], "Duplicate successor");
                            seen[index] = true;
                        }
                        for (int i = 0; i < count; ++i) assertEquals(alive(pattern, i), seen[i]);
                    }
                }
            }
        }
    }

    private static boolean alive(int pattern, int index) {
        return switch (pattern) {
            case 0 -> false;
            case 1 -> true;
            case 2 -> index % 3 == 0;
            default -> index / 64 % 2 == 1 && index % 17 == 0;
        };
    }
}
