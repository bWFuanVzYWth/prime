package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class AtmosphereEpipolarShadowAlignmentGpuTest {
    private static final int CASE_BYTES = 96;
    private static ShaderComputeRunner runner;

    @Test
    void cachedShadowDirectionMustOwnTheEpipolarGrid()
            throws IOException {
        Matrix4f inverseViewProjection = new Matrix4f()
                .perspective(
                        (float) (Math.PI * 0.5),
                        16.0F / 9.0F,
                        0.1F,
                        1_000.0F)
                .invert();
        Vector3f center = new Vector3f(0.0F, 0.0F, -1.0F);
        Vector3f offscreen =
                new Vector3f(0.9F, 0.2F, -0.4F).normalize();
        Vector3f parallel = new Vector3f(1.0F, 0.0F, 0.0F);
        Vector3f downwardSun =
                new Vector3f(0.45F, -0.15F, -0.88F).normalize();
        float lag = 1.0e-3F;
        Vector3f advanced =
                new Vector3f((float) Math.sin(lag), 0.0F, -(float) Math.cos(lag));
        Vector3f[][] cases = {
            {center, center},
            {offscreen, offscreen},
            {parallel, parallel},
            {downwardSun, downwardSun},
            {advanced, center}
        };
        Matrix4f downwardViewProjection = new Matrix4f()
                .perspective(
                        (float) (Math.PI * 0.5),
                        16.0F / 9.0F,
                        0.1F,
                        1_000.0F)
                .lookAt(
                        0.0F, 0.0F, 0.0F,
                        0.0F, -0.97F, -0.24F,
                        0.0F, 1.0F, 0.0F)
                .invert();
        ByteBuffer input = ByteBuffer.allocateDirect(
                        cases.length * CASE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < cases.length; index++) {
            int base = index * CASE_BYTES;
            (index == 3 ? downwardViewProjection : inverseViewProjection)
                    .get(base, input);
            putDirection(input, base + 64, cases[index][0]);
            putDirection(input, base + 80, cases[index][1]);
        }
        input.position(input.capacity()).flip();
        ByteBuffer push = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cases.length)
                .flip();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "atmosphere_epipolar_shadow_alignment.comp.spv");

        ByteBuffer output = runner.dispatch(
                shader,
                input,
                cases.length * Integer.BYTES,
                new ShaderComputeRunner.Workgroups(
                        1,
                        256,
                        cases.length),
                push);
        for (int caseIndex = 0; caseIndex < 4; caseIndex++) {
            assertEquals(
                    0,
                    output.getInt(caseIndex * Integer.BYTES),
                    "aligned cache direction case " + caseIndex);
        }
        assertTrue(
                output.getInt(4 * Integer.BYTES) > 0,
                "a lagged cache direction must reproduce the rejected rays");
    }

    private static void putDirection(
            ByteBuffer target,
            int offset,
            Vector3f value) {
        target.putFloat(offset, value.x);
        target.putFloat(offset + Float.BYTES, value.y);
        target.putFloat(offset + 2 * Float.BYTES, value.z);
        target.putFloat(offset + 3 * Float.BYTES, 0.0F);
    }
}
