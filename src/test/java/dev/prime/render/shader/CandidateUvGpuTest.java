package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class CandidateUvGpuTest extends GpuShaderTest {
    @Test
    void repeatedCoverageKeepsNegativeCoordinatesAxisTiesAndSmallNormals() throws Exception {
        float[][] normals = {{1,0,0}, {0,1,0}, {0,0,1}, {-1,-1,-1}, {1,1,0}, {1,0,1},
                {0,1,1}, {1,2,3}, {3,2,1}, {1,3,2}};
        float[] scales = {1.0F, 1.0e-30F, 1.0e30F};
        int count = normals.length * scales.length;
        ByteBuffer input = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (float scale : scales) for (float[] n : normals) {
            input.putFloat(n[0] * scale).putFloat(n[1] * scale).putFloat(n[2] * scale).putFloat(0);
        }
        input.flip();
        ByteBuffer output = runner.dispatch("candidate_uv.comp.spv", input, count * 32,
                new ShaderComputeRunner.Workgroups(count, 1, 1), null);
        for (int i = 0; i < count; i++) {
            float[] n = normals[i % normals.length];
            float x = Math.abs(n[0]), y = Math.abs(n[1]), z = Math.abs(n[2]);
            boolean yz = x > y && x > z;
            boolean xz = !yz && y > z;
            assertEquals(0.25F, output.getFloat(i * 32), 0.0F);
            assertEquals(0.5F, output.getFloat(i * 32 + 4), 0.0F);
            assertEquals(yz ? 0.5F : 0.75F, output.getFloat(i * 32 + 8), 0.0F);
            assertEquals(yz || xz ? 0.25F : 0.5F, output.getFloat(i * 32 + 12), 0.0F);
            float lengthSquared = 0;
            for (int j = 0; j < 3; j++) {
                float value = output.getFloat(i * 32 + 16 + j * 4);
                assertTrue(Float.isFinite(value));
                lengthSquared += value * value;
            }
            assertEquals(1.0F, lengthSquared, 3.0e-7F);
            assertEquals(0.0F, output.getFloat(i * 32 + 28), 0.0F, "opaque source identity");
        }
    }
}
