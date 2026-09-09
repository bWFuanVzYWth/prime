// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class ScatterHitDistanceGpuTest extends GpuShaderTest {
    @Test void guideDistancesRetainTheirPhaseAndAbsorptionSemantics() throws Exception {
        // transparent, segment survives, bounce, guide bounce, landing writes, secondary writes.
        // Bit 0 is the first transparent hit; bit 1 is the guide's next hit.
        int[][] cases = {
            {0,1,1,0,2,0}, {0,1,0,0,0,2}, {0,1,7,6,2,0}, {0,1,8,6,0,0},
            {0,0,1,0,0,0}, {0,0,0,0,0,0}, {1,1,1,0,3,2}, {1,1,0,0,0,1},
            {1,0,1,0,3,2}, {1,0,0,0,0,1}, {1,1,7,6,2,2}, {1,0,7,6,2,2},
            {1,1,8,6,0,0}, {1,1,64,63,2,2}, {0,1,64,63,2,0}
        };
        ByteBuffer input = ByteBuffer.allocateDirect((cases.length + 1) * 16).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(cases.length).putInt(0).putLong(0);
        for (int[] c : cases) for (int i = 0; i < 4; ++i) input.putInt(c[i]);
        ByteBuffer output = runner.dispatch("scatter_hit_distance.comp.spv", input.flip(), cases.length * 16, cases.length);
        for (int i = 0; i < cases.length; ++i) {
            assertEquals(cases[i][4], output.getInt(i * 16), "Landing case " + i);
            assertEquals(cases[i][5], output.getInt(i * 16 + 4), "Secondary case " + i);
        }
    }
}
