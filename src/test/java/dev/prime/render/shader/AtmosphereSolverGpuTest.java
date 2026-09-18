// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.vulkan.AtmospherePrecomputation;
import dev.prime.render.vulkan.AtmosphereMedium;
import dev.prime.render.AtmosphereSettings;
import dev.prime.render.AtmosphereCoordinates;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeAll;

/** Executes the production startup passes, then compares transport with pinned WGSL outputs. */
final class AtmosphereSolverGpuTest extends GpuShaderTest {
    private static final ShaderComputeRunner.ImageFormat HALF = ShaderComputeRunner.ImageFormat.R16G16B16A16_SFLOAT;
    private static final ShaderComputeRunner.ImageFormat FLOAT = ShaderComputeRunner.ImageFormat.R32G32B32A32_SFLOAT;

    @BeforeAll
    void prepareResources() throws Exception {
        runner.useIoBindings(33, 34);
        runner.enableGpuTiming();
        byte[] medium = AtmosphereMedium.load(AtmosphereSettings.DEFAULT_STEPS);
        runner.bindStorageBuffer(7, buffer(medium.length).put(medium).flip());
        for (int binding = 29; binding <= 31; binding++) {
            runner.bindStorageBuffer(binding, buffer(4 * 160 * (binding == 31 ? 7 : 1536) * 16));
        }
        runner.bindStorageImage(32, HALF, 512, 128, 1);
        runner.aliasSampledImage(32, 0);
        runner.bindStorageImage(35, HALF, 3200, 240, 1);
        runner.aliasSampledImage(35, 1);
        runner.bindStorageImage(2, FLOAT, 160, 40, 1);
        runner.bindStorageImage(3, FLOAT, 160, 1, 1);
        runner.bindStorageImage(24, FLOAT, 800, 21, 1);
        runner.bindStorageImage(25, HALF, 3200, 240, 1);
        runner.bindStorageImage(26, FLOAT, 160, 40, 1);
        runner.bindStorageImage(27, FLOAT, 160, 1, 1);
        runner.bindStorageImage(28, FLOAT, 800, 21, 1);
        runner.bindStorageImage(4, FLOAT, 256, 256, 1);
        runner.bindStorageImage(23, HALF, ShaderAbi.ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH, 1, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 50, 100, 1600})
    void directionalSolverAndSkyMatchSkyTracerAcrossDayTwilightAndAltitude(int densitySteps) throws Exception {
        String fixture = "/prime/atmosphere/" + (densitySteps == 100 ? "" : "aerosol-" + densitySteps + "/");
        byte[] medium = AtmosphereMedium.load(densitySteps);
        runner.writeStorageBuffer(7, buffer(medium.length).put(medium).flip());
        ByteBuffer push = buffer(16);
        long start = System.nanoTime();
        int bank = 0;
        double solveMillis = 0;
        for (var step : AtmospherePrecomputation.plan()) {
            if (bank != step.bank()) { swap(); bank = step.bank(); }
            push.putInt(0,step.firstHeight()).putInt(4,step.heightCount()).putInt(8,step.iteration());
            runner.dispatchProduction(step.stage().artifact(),
                    new ShaderComputeRunner.Workgroups(step.x(),step.y(),1),push);
            solveMillis += runner.lastGpuMillis();
        }
        if (bank != AtmospherePrecomputation.finalBank()) swap();
        System.out.println("Atmosphere test solve wall time (includes per-dispatch pipeline creation): "
                + (System.nanoTime() - start) * 1e-6 + " ms");
        System.out.println("Atmosphere startup GPU kernels: " + solveMillis + " ms");
        ByteBuffer reference = resource(fixture + "transport-reference.bin.gz.b64");
        int count = reference.getInt();
        ByteBuffer input = buffer(count * 32);
        float[][] expected = new float[count][8];
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < 8; k++) input.putFloat(reference.getFloat());
            for (int k = 0; k < 8; k++) expected[i][k] = reference.getFloat();
        }
        input.flip();
        ByteBuffer result = runner.dispatch("atmosphere_transport.comp.spv", input, count * 32, count);
        float worstLight = 0;
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < 8; k++) {
                float value = result.getFloat(i * 32 + k * 4);
                assertTrue(Float.isFinite(value) && value >= 0, "finite nonnegative transport " + i);
                float tolerance = k < 4 ? Math.max(2e-8F, expected[i][k] * 0.008F) : 3e-4F;
                assertEquals(expected[i][k], value, tolerance, "WGSL transport " + i + " lane " + k);
                if (k < 4 && expected[i][k] > 1e-7F) {
                    worstLight = Math.max(worstLight, Math.abs(value / expected[i][k] - 1));
                }
                if (k >= 4) assertTrue(value <= 1, "bounded transmittance");
            }
        }
        System.out.println("Atmosphere WGSL maximum spectral relative error: " + worstLight);

        ByteBuffer sky = resource(fixture + "sky-reference.bin.gz.b64");
        ByteBuffer projection = resource(fixture + "projection-reference.bin.gz.b64");
        int cases = sky.getInt(), samples = sky.getInt();
        float worstSky = 0;
        for (int c = 0; c < cases; c++) {
            float height = sky.getFloat(), elevation = sky.getFloat();
            float radians = (float) Math.toRadians(elevation);
            ByteBuffer skyPush = buffer(128).putFloat(64, 6360F + height)
                    .putFloat(84, (float) Math.sin(radians)).putFloat(88, (float) Math.cos(radians));
            stage("sky", 1, 256, skyPush);
            System.out.println("Atmosphere SkyView GPU h=" + height + " sun=" + elevation + ": " + runner.lastGpuMillis() + " ms");
            ByteBuffer pixels = buffer(samples * 16);
            float[][] rgb = new float[samples][3];
            for (int i = 0; i < samples; i++) {
                pixels.putInt(sky.getInt()).putInt(sky.getInt()).putLong(0L);
                for (int k = 0; k < 3; k++) rgb[i][k] = sky.getFloat();
            }
            pixels.flip();
            ByteBuffer actual = runner.dispatch("atmosphere_sky_read.comp.spv", pixels, samples * 16, samples);
            for (int i = 0; i < samples; i++) {
                for (int k = 0; k < 3; k++) {
                    float value = actual.getFloat(i * 16 + k * 4);
                    assertTrue(Float.isFinite(value) && value >= 0, "finite sky");
                    assertEquals(rgb[i][k], value, Math.max(2e-6F, rgb[i][k] * 0.012F),
                            "WGSL SkyView case " + c + " pixel " + i + " channel " + k);
                    if (rgb[i][k] > 1e-5F) worstSky = Math.max(worstSky, Math.abs(value / rgb[i][k] - 1));
                }
            }
            ByteBuffer rays = buffer(192 * 32);
            float[][] projected = new float[192][3];
            for (int i = 0; i < 192; i++) {
                for (int k = 0; k < 8; k++) rays.putFloat(projection.getFloat());
                for (int k = 0; k < 3; k++) projected[i][k] = projection.getFloat();
            }
            rays.flip();
            ByteBuffer view = runner.dispatch("atmosphere_sky_sample.comp.spv", rays, 192*16, 192);
            for (int i = 0; i < 192; i++) {
                for (int k = 0; k < 3; k++) {
                    float value = view.getFloat(i*16+k*4);
                    assertTrue(Float.isFinite(value) && value >= 0);
                    assertEquals(projected[i][k], value, Math.max(2e-6F, projected[i][k]*0.004F),
                            "WGSL sky lookup case " + c + " pixel " + i + " channel " + k);
                }
            }
        }
        System.out.println("Atmosphere WGSL maximum SkyView relative error: " + worstSky);
        checkFiniteSegments();
        checkVirtualAltitudes();
    }

    private void checkVirtualAltitudes() throws Exception {
        // Include the exact surface: its below-horizon sky chart collapses to zero width.
        for (int offset : new int[] {0, 300, 10_000}) {
            float radius = AtmosphereCoordinates.eyeRadiusKm(-64, new AtmosphereSettings(100, offset));
            ByteBuffer push = buffer(128).putFloat(64, radius).putFloat(84, 0.6F).putFloat(88, 0.8F);
            stage("sky", 1, 256, push);
            ByteBuffer pixels = buffer(256 * 16);
            for (int row = 0; row < 256; row++) {
                pixels.putInt(128).putInt(row).putLong(0);
            }
            pixels.flip();
            ByteBuffer result = runner.dispatch("atmosphere_sky_read.comp.spv", pixels, 256 * 16, 256);
            for (int row = 0; row < 256; row++) for (int channel = 0; channel < 3; channel++) {
                float value = result.getFloat(row * 16 + channel * 4);
                assertTrue(Float.isFinite(value) && value >= 0, "sky at virtual altitude " + offset);
            }
        }
    }

    private void checkFiniteSegments() throws Exception {
        float[][] cases = {
            {0.064F, 0F, 0F, 2.048F}, {0.064F, 0.7F, 0.9F, 1F},
            {2F, -0.1F, -0.06F, 4F}, {12F, 0F, -0.1F, 8F},
            {34.9F, 0.5F, 0.02F, 2F}, {108F, -0.03F, -0.08F, 20F}
        };
        ByteBuffer input = buffer(cases.length * 32);
        for (float[] c : cases) {
            input.putFloat(0).putFloat(c[1]).putFloat((float) Math.sqrt(1-c[1]*c[1])).putFloat(c[0]);
            input.putFloat(0).putFloat(c[2]).putFloat((float) Math.sqrt(1-c[2]*c[2])).putFloat(c[3]);
        }
        input.flip();
        ByteBuffer result = runner.dispatch("atmosphere_segment_properties.comp.spv", input, cases.length * 64, cases.length);
        for (int i = 0; i < cases.length; i++) {
            for (int k = 0; k < 4; k++) {
                float full = result.getFloat(i*64+k*4);
                float split = result.getFloat(i*64+16+k*4);
                assertTrue(Float.isFinite(full) && full >= 0 && Float.isFinite(split) && split >= 0);
                assertEquals(full, split, Math.max(2e-9F, full*0.002F), "segment radiance composition " + i);
                assertEquals(result.getFloat(i*64+32+k*4), result.getFloat(i*64+48+k*4), 2e-5F,
                        "segment transmittance composition " + i);
            }
        }
    }

    private void stage(String name, int x, int y, ByteBuffer push) throws Exception {
        runner.dispatchProduction("atmosphere_" + name, new ShaderComputeRunner.Workgroups(x, y, 1), push);
    }

    private void swap() {
        runner.swapImages(1, 25);
        runner.swapImages(2, 26);
        runner.swapImages(3, 27);
        runner.swapImages(24, 28);
    }

    static ByteBuffer resource(String path) throws Exception {
        try (InputStream encoded = AtmosphereSolverGpuTest.class.getResourceAsStream(path);
                InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
            byte[] bytes = decompressed.readAllBytes();
            return buffer(bytes.length).put(bytes).flip();
        }
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
