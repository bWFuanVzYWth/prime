// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class AtmosphereCameraGpuTest extends GpuShaderTest {
    @Test
    void cameraDirectionTableBoundsInterpolationAndHalfStorageError() throws Exception {
        runner.bindStorageBuffer(7, AtmosphereSolverGpuTest.resource("/prime/atmosphere/medium.bin.gz.b64"));
        ByteBuffer spectral = runner.dispatch("atmosphere_transmittance_table.comp.spv",
                ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN),
                512 * 128 * 16, 512 * 128);
        int count = 32_769;
        float maximum = 0.0F;
        for (float altitude : new float[] {0.0F, 0.001F, 0.008F, 0.016F, 0.064F, 0.256F, 0.3F, 0.428F, 10.128F,
                0.448F, 2.0F, 10.0F, 50.0F, 119.999F}) {
            ByteBuffer push = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(6360.0F + altitude).putInt(count).flip();
            ByteBuffer result = runner.dispatch("atmosphere_camera_error.comp.spv", spectral,
                    count * 32, new ShaderComputeRunner.Workgroups((count + 63) / 64, 1, 1), push);
            for (int i = 0; i < count; i++) {
                assertEquals(result.getFloat(i * 32 + 12), result.getFloat(i * 32 + 28),
                        3.0e-7F, "direction round trip");
                for (int c = 0; c < 3; c++) {
                    float expected = result.getFloat(i * 32 + c * 4);
                    float actual = result.getFloat(i * 32 + 16 + c * 4);
                    assertTrue(Float.isFinite(actual) && actual >= 0.0F && actual <= 1.0F);
                    maximum = Math.max(maximum, Math.abs(expected - actual));
                    assertEquals(expected, actual, 0.002F,
                            "camera LUT altitude=" + altitude + " direction=" + i + " channel=" + c);
                }
            }
        }
        System.out.println("Camera transmittance maximum absolute RGB error: " + maximum);
    }
}
