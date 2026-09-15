// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class ShadowSourceMediumGpuTest extends GpuShaderTest {
    @Test
    void directConnectionsUseTheOutgoingEndpointAndPreserveReflectionAndThinMedia() throws Exception {
        int count = 72;
        ByteBuffer input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        input.putInt(count).putInt(0).putLong(0).flip();
        ByteBuffer output = runner.dispatch("shadow_source_medium.comp.spv", input, count * 32, count);
        for (int index = 0; index < count; index++) {
            int kind = index % 6;
            boolean entering = (index / 6 & 1) != 0;
            boolean unchanged = (index / 12 & 1) != 0 || kind >= 4;
            boolean water = entering ? kind == 1 : kind == 2 || kind == 3;
            int expectedId = unchanged ? 47 : water ? 1 : entering ? 17 : 0;
            double distance = switch (index / 24) { case 0 -> 0; case 1 -> 2; default -> 1_000_000; };
            assertEquals(expectedId, output.getFloat(index * 32 + 12), "case " + index);
            float[] absorption = unchanged ? new float[] {0.2f, 0.5f, 0.8f}
                    : water ? new float[] {0.2916f, 0.04444f, 0.010182f}
                    : entering ? new float[] {glassAbsorption(), glassAbsorption(), glassAbsorption()}
                    : new float[3];
            for (int channel = 0; channel < 3; channel++) {
                float expected = absorption[channel];
                // The existing shadow encoding can round to binary16; allow half an ulp there.
                double tolerance = 5e-4 * expected + 2e-6;
                assertEquals(expected, output.getFloat(index * 32 + 16 + channel * 4), tolerance, "case " + index);
                assertEquals(Math.exp(-expected * distance), output.getFloat(index * 32 + channel * 4),
                        distance > 2 ? 2e-6 : 2 * tolerance, "case " + index);
            }
        }
    }

    private static float glassAbsorption() {
        return (float) -Math.log(Math.pow((0.6 + 0.055) / 1.055, 2.4));
    }
}
