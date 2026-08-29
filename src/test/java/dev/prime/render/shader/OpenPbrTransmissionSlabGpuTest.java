package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class OpenPbrTransmissionSlabGpuTest {
    private static final int CASE_COUNT = 8_192;
    private static final int INPUT_WORDS = 1;
    private static final int WITNESS_WORDS = 8;
    private static final long SEED = 0x51AB_1FACE_0000_001L;

    private static ShaderComputeRunner runner;

    @BeforeAll
    static void bindTransmissionGgxEnergy() throws IOException {
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void parallelInterfacesPreserveReciprocalEtaDirectionAndTir() throws IOException {
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "prime_dielectric_slab_properties.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                createCases(),
                CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    private static ByteBuffer createCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        float[] iors = {1.01F, 1.1F, 1.333F, 1.5F, 2.4F, 2.5F};
        float[] sines = {0.0F, 0.01F, 0.25F, 0.5F, 0.75F, 0.9F, 0.99F};
        int boundaryCases = iors.length * sines.length;
        for (int caseIndex = 0; caseIndex < CASE_COUNT; caseIndex++) {
            float ior;
            float sine;
            if (caseIndex < boundaryCases) {
                ior = iors[caseIndex % iors.length];
                sine = sines[(caseIndex / iors.length) % sines.length];
            } else {
                ior = 1.01F + random.nextFloat() * 1.49F;
                sine = random.nextFloat() * 0.99F;
            }
            ShaderTestBuffer.putFloat(input, caseIndex, INPUT_WORDS, 0, 0, ior);
            ShaderTestBuffer.putFloat(input, caseIndex, INPUT_WORDS, 0, 1, sine);
        }
        return input;
    }
}
