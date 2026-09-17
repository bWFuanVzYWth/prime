// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class SurfaceSamplingGpuTest extends GpuShaderTest {
    @BeforeAll
    void bindFixture() throws IOException {
        runner.useIoBindings(100, 101);
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void guidesPreserveSamplesAndPrimaryAndCompleteNeeUseComplementaryMis() throws IOException {
        long seed = 0x5A4F_4143_4553_0001L;
        SplittableRandom random = new SplittableRandom(seed);
        int cases = 32_768;
        var input = ShaderTestBuffer.inputWriter(cases, 4);
        int[] materials = {0, 2, 1 | (1 << 2), 1 | (2 << 2), 1 | (3 << 2),
                1 | (1 << 2) | (1 << 4)};
        float[] roughness = {0, 0.0001f, 0.01f, 0.2f, 0.6f, 1};
        float[] cosine = {1e-6f, 1e-4f, 0.01f, 0.5f, 0.9f, 1};
        for (int i = 0; i < cases; ++i) {
            int material = materials[i % materials.length];
            int optical = i % 7 == 0 ? 231 : i % 5 == 0 ? 229 : 0;
            if ((i & 64) != 0) optical |= 128 << 8;
            if ((i & 128) != 0) optical |= 1 << 24;
            input.putInt(i, 0, 0, material);
            input.putInt(i, 0, 1, optical);
            input.putInt(i, 0, 2, (i >> 8) & 1);
            input.putInt(i, 0, 3, (i & 512) != 0 ? 1 | (2 << 2) : 0);
            input.putVec4(i, 1, (float) random.nextDouble(), (float) random.nextDouble(),
                    (float) random.nextDouble(), roughness[(i / materials.length) % roughness.length]);
            input.putVec4(i, 2, (float) random.nextDouble(), (float) random.nextDouble(),
                    (float) random.nextDouble(), 0);
            float c = cosine[(i / 36) % cosine.length] * ((i & 1024) == 0 ? 1 : -1);
            input.putVec4(i, 3, c, (i & 128) != 0 ? 2 : 0, (i >> 11) & 1,
                    (i & 2048) == 0 ? 0 : 0.8f);
        }
        ByteBuffer push = ByteBuffer.allocateDirect(ShaderAbi.PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        push.putInt(ShaderAbi.PUSH_PATH_OFFSET, ShaderAbi.PATH_BASE_COLOR_COMPENSATION_MASK);
        ShaderPropertyBatch.assertProperties(runner, "surface_sampling.comp.spv",
                input.buffer(), cases, 4, 5, seed, push);
    }
}
