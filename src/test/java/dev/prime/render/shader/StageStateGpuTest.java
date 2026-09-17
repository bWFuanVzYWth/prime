// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class StageStateGpuTest extends GpuShaderTest {
    @Test void narrowReceiverAndSecondaryTransitionPreserveTheirSemanticDomains() throws Exception {
        int count = 8192;
        var random = new SplittableRandom(0x53544147454cL);
        ByteBuffer input = ByteBuffer.allocateDirect(16 + count * 128).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(0, count);
        for (int i = 0; i < count; ++i) {
            int base = 16 + i * 128;
            for (int word = 0; word < 32; ++word) input.putInt(base + word * 4, random.nextInt());
            int material = i % 3;
            int hit = i % 7 == 0 ? 0 : 1;
            input.putInt(base + 16, random.nextInt() & 0xffff0000 | material << 8 | hit);
            // Finite normals exercise both face orientations; other f32 fields preserve arbitrary bits.
            for (int word : new int[] {1, 2, 3, 13, 14, 15, 28, 29, 30})
                input.putFloat(base + word * 4, (float) random.nextDouble(-1, 1));
        }
        ByteBuffer output = runner.dispatch("stage_state.comp.spv", input, count * 128, count);
        for (int i = 0; i < count; ++i) {
            int base = i * 128;
            int source = 16 + base;
            assertEquals(1, output.getInt(base + 96), "Receiver case " + i);
            // Packed ABI: only PSR/fallback, local bounce, and guide bounce expire at this boundary.
            assertEquals(input.getInt(source + 96) & ~7, output.getInt(base + 100));
            assertEquals(input.getInt(source + 100) & ~255, output.getInt(base + 104));
            assertEquals(input.getInt(source + 104) & ~0xff00, output.getInt(base + 108));
            assertEquals(1, output.getInt(base + 112), "Transition must be idempotent");
        }
    }
}
