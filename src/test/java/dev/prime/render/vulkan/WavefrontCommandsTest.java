// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class WavefrontCommandsTest {
    @Test
    void queueZeroFourthWordCarriesPersistentAdditionalSpecularBounces() {
        int queues = 6;
        int stride = 16;
        ByteBuffer commands = ByteBuffer.allocateDirect(queues * stride)
                .order(ByteOrder.nativeOrder());

        WavefrontCommands.initializeQueueCommands(commands, queues, stride, 64);

        for (int queue = 0; queue < queues; queue++) {
            int offset = queue * stride;
            assertEquals(0, commands.getInt(offset));
            assertEquals(1, commands.getInt(offset + Integer.BYTES));
            assertEquals(1, commands.getInt(offset + 2 * Integer.BYTES));
            assertEquals(queue == 0 ? 64 : 0,
                    commands.getInt(offset + 3 * Integer.BYTES));
        }
        // Shader queue resets exchange only the width word.
        commands.putInt(0, 0);
        assertEquals(64, commands.getInt(3 * Integer.BYTES));
    }

    @Test
    void queueCommandInitializationRejectsTruncatedLayouts() {
        assertThrows(IllegalArgumentException.class, () ->
                WavefrontCommands.initializeQueueCommands(
                        ByteBuffer.allocate(15), 1, 16, 8));
        assertThrows(IllegalArgumentException.class, () ->
                WavefrontCommands.initializeQueueCommands(
                        ByteBuffer.allocate(16), 1, 12, 8));
    }
}
