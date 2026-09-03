package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class AtmosphereGpuTest {
    private static ShaderComputeRunner runner;

    @Test
    void profilesEveryShadowLeafAndKeepsInvalidDirectionsShadowed() throws IOException {
        int caseFloats = 12;
        int outputFloats = 7;
        float diagonal = (float) (1.0 / Math.sqrt(2.0));
        float[][] cases = {
            {0.25F, -0.25F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 5.0F, 0.0F, 10.0F, 0.0F, 1.0F},
            {0.0F, 0.0F, diagonal, diagonal, diagonal, diagonal, 0.0F, -1.0e20F, 0.0F, 10.0F, 0.0F, 1.0F},
            {0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.001F, 1.0F, -1.0e20F, 0.0F, 10.0F, 0.0F, 1.0F},
            {0.0F, 0.0F, 1.0F, 0.0F, -1.0F, 0.0F, 1.0F, -1.0e20F, 0.0F, 10.0F, 0.0F, 1.0F},
            {0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 0.0F, 5.0F, 0.0F, 10.0F, 0.0F, 1.0F},
            {4_096.0F, -4_096.0F, -1.0F, 0.0F, -1.0F, 0.0F, 1.0F, -1.0e20F, 0.0F, 10.0F, 0.0F, 1.0F},
            {-4_096.0F, 4_096.0F, -diagonal, -diagonal, -diagonal, -diagonal, 0.0F, -1.0e20F, 0.0F, 10.0F, 0.0F, 1.0F},
            {1_000_000.0F, -1_000_000.0F, diagonal, -diagonal, diagonal, -diagonal, 0.0F, -1.0e20F, 0.0F, 10.0F, 0.0F, 1.0F}
        };
        ByteBuffer output = runner.dispatch(
                "atmosphere_epipolar_shadow_profile.comp.spv",
                floats(cases),
                cases.length * outputFloats * Float.BYTES,
                new ShaderComputeRunner.Workgroups(cases.length, 1, 1),
                null);
        for (int caseIndex = 0; caseIndex < cases.length; ++caseIndex) {
            int base = caseIndex * outputFloats * Float.BYTES;
            for (int cascade = 0; cascade < 5; ++cascade) {
                assertTrue(
                        output.getFloat(base + cascade * Float.BYTES) > 0.0F,
                        "profile overflow case " + caseIndex + " cascade " + cascade);
            }
            float directionMax = Math.max(
                    Math.abs(cases[caseIndex][2]), Math.abs(cases[caseIndex][3]));
            assertEquals(
                    127.0F / directionMax,
                    output.getFloat(base + 5 * Float.BYTES),
                    2.0e-4F,
                    "near profile extent case " + caseIndex);
        }
        assertEquals(255.0F, output.getFloat(0), 0.0F, "axis-aligned leaf count");
        assertEquals(
                254.0F,
                output.getFloat(outputFloats * Float.BYTES),
                0.0F,
                "diagonal leaf count");
        assertEquals(0.502F, output.getFloat(6 * Float.BYTES), 1.0e-5F, "analytic blocker crossing");
        for (int caseIndex = 1; caseIndex < 5; ++caseIndex) {
            assertEquals(
                    caseIndex == 1 ? 1.0F : 0.0F,
                    output.getFloat((caseIndex * outputFloats + 6) * Float.BYTES),
                    1.0e-6F,
                    "visibility case " + caseIndex);
        }
        for (int caseIndex = 5; caseIndex < cases.length; ++caseIndex) {
            assertEquals(
                    1.0F,
                    output.getFloat((caseIndex * outputFloats + 6) * Float.BYTES),
                    1.0e-6F,
                    "large-coordinate traversal case " + caseIndex);
        }
    }

    @Test
    void cachedShadowDirectionMustOwnTheEpipolarGrid() throws IOException {
        int caseBytes = 96;
        Matrix4f inverseViewProjection = new Matrix4f()
                .perspective((float) (Math.PI * 0.5), 16.0F / 9.0F, 0.1F, 1_000.0F)
                .invert();
        Vector3f center = new Vector3f(0.0F, 0.0F, -1.0F);
        Vector3f offscreen = new Vector3f(0.9F, 0.2F, -0.4F).normalize();
        Vector3f parallel = new Vector3f(1.0F, 0.0F, 0.0F);
        Vector3f downwardSun = new Vector3f(0.45F, -0.15F, -0.88F).normalize();
        float lag = 1.0e-3F;
        Vector3f advanced = new Vector3f(
                (float) Math.sin(lag), 0.0F, -(float) Math.cos(lag));
        Vector3f[][] cases = {
            {center, center},
            {offscreen, offscreen},
            {parallel, parallel},
            {downwardSun, downwardSun},
            {advanced, center}
        };
        Matrix4f downwardViewProjection = new Matrix4f()
                .perspective((float) (Math.PI * 0.5), 16.0F / 9.0F, 0.1F, 1_000.0F)
                .lookAt(0.0F, 0.0F, 0.0F, 0.0F, -0.97F, -0.24F, 0.0F, 1.0F, 0.0F)
                .invert();
        ByteBuffer input = buffer(cases.length * caseBytes);
        for (int index = 0; index < cases.length; index++) {
            int base = index * caseBytes;
            (index == 3 ? downwardViewProjection : inverseViewProjection).get(base, input);
            putDirection(input, base + 64, cases[index][0]);
            putDirection(input, base + 80, cases[index][1]);
        }
        input.position(input.capacity()).flip();
        ByteBuffer output = runner.dispatch(
                "atmosphere_epipolar_shadow_alignment.comp.spv",
                input,
                cases.length * Integer.BYTES,
                new ShaderComputeRunner.Workgroups(1, 256, cases.length),
                count(cases.length));
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

    @Test
    void parallelPrefixMatchesOrderedAerialComposition() throws IOException {
        int groupCount = 2;
        int valueCount = 4;
        int channelCount = 4;
        int voxelBytes = valueCount * channelCount * Float.BYTES;
        int depth = ShaderAbi.ATMOSPHERE_AERIAL_DEPTH;
        ByteBuffer input = buffer(depth * voxelBytes);
        double[][][] radiance = new double[groupCount][depth][channelCount];
        double[][][] transmittance = new double[groupCount][depth][channelCount];
        for (int slice = 0; slice < depth; slice++) {
            for (int group = 0; group < groupCount; group++) {
                for (int channel = 0; channel < channelCount; channel++) {
                    radiance[group][slice][channel] = 0.00025
                            * (1 + ((slice * 17 + group * 31 + channel * 7) % 29));
                    transmittance[group][slice][channel] = 0.972
                            + 0.00035 * ((slice * 11 + group * 13 + channel * 5) % 71);
                }
            }
            putGroup(input, radiance[0][slice], transmittance[0][slice]);
            putGroup(input, radiance[1][slice], transmittance[1][slice]);
        }
        input.flip();
        ByteBuffer output = runner.dispatch(
                "atmosphere_aerial_prefix.comp.spv",
                input,
                depth * voxelBytes,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                null);
        double[][] cumulativeRadiance = new double[groupCount][channelCount];
        double[][] cumulativeTransmittance = new double[groupCount][channelCount];
        for (int group = 0; group < groupCount; group++) {
            for (int channel = 0; channel < channelCount; channel++) {
                cumulativeTransmittance[group][channel] = 1.0;
            }
        }
        for (int slice = 0; slice < depth; slice++) {
            for (int group = 0; group < groupCount; group++) {
                for (int channel = 0; channel < channelCount; channel++) {
                    cumulativeRadiance[group][channel] += cumulativeTransmittance[group][channel]
                            * radiance[group][slice][channel];
                    cumulativeTransmittance[group][channel] *= transmittance[group][slice][channel];
                    int value = slice * valueCount + group * 2;
                    int radianceOffset = (value * channelCount + channel) * Float.BYTES;
                    int transmittanceOffset = ((value + 1) * channelCount + channel) * Float.BYTES;
                    assertEquals(
                            cumulativeRadiance[group][channel],
                            output.getFloat(radianceOffset),
                            2.0e-5,
                            "radiance group " + group + ", slice " + slice + ", channel " + channel);
                    assertEquals(
                            cumulativeTransmittance[group][channel],
                            output.getFloat(transmittanceOffset),
                            2.0e-5,
                            "transmittance group " + group + ", slice " + slice + ", channel " + channel);
                }
            }
        }
    }

    @Test
    void homogeneousSegmentIntegralMatchesDoubleOracleAcrossItsBranches() throws IOException {
        int components = 4;
        float[][] cases = {
            {0.0F, 1.0e-12F, 1.0e-6F, 37.0F},
            {1.0e-12F, 1.0e-8F, 1.0e-4F, 1.0F},
            {0.049999F, 0.05F, 0.050001F, 1.0F},
            {0.5F, 5.0F, 50.0F, 1.0F},
            {1.0e-4F, 0.01F, 2.0F, 25.0F},
            {100.0F, 1_000.0F, 10_000.0F, 0.5F}
        };
        ByteBuffer output = runner.dispatch(
                "atmosphere_segment_integral.comp.spv",
                floats(cases),
                cases.length * components * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                count(cases.length));
        for (int caseIndex = 0; caseIndex < cases.length; caseIndex++) {
            float previous = Float.POSITIVE_INFINITY;
            for (int component = 0; component < 3; component++) {
                float actual = output.getFloat(
                        (caseIndex * components + component) * Float.BYTES);
                double expected = segmentIntegral(cases[caseIndex][component], cases[caseIndex][3]);
                assertTrue(Float.isFinite(actual) && actual >= 0.0F);
                assertEquals(
                        expected,
                        actual,
                        Math.max(2.0e-6, Math.abs(expected) * 3.0e-6),
                        "case " + caseIndex + " component " + component);
                assertTrue(actual <= previous + 2.0e-6F);
                previous = actual;
            }
            assertEquals(
                    output.getFloat(caseIndex * components * Float.BYTES),
                    output.getFloat((caseIndex * components + 3) * Float.BYTES),
                    0.0F);
        }
        int thresholdOffset = 2 * components * Float.BYTES;
        assertTrue(Math.abs(output.getFloat(thresholdOffset)
                - output.getFloat(thresholdOffset + 2 * Float.BYTES)) < 3.0e-6F);
    }

    @Test
    void screenPointsRoundTripForVisibleAndOffscreenEpipoles() throws IOException {
        int outputFloats = 4;
        float[][] cases = {
            {0.0F, 0.0F, 0.75F, 0.25F},
            {0.2F, -0.4F, -0.8F, 0.9F},
            {-3.0F, 0.1F, -0.5F, -0.7F},
            {4.0F, 3.0F, 0.9F, -0.9F},
            {0.3F, 5.0F, -0.9F, 0.9F},
            {-2000.0F, 0.0F, 0.4F, -0.6F},
            {-16_384.0F, 0.0F, 0.4F, 0.6F}
        };
        ByteBuffer output = runner.dispatch(
                "atmosphere_epipolar_properties.comp.spv",
                floats(cases),
                cases.length * outputFloats * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                count(cases.length));
        for (int index = 0; index < cases.length; index++) {
            int offset = index * outputFloats * Float.BYTES;
            assertEquals(cases[index][2], output.getFloat(offset), 2.0e-3F, "x case " + index);
            assertEquals(cases[index][3], output.getFloat(offset + Float.BYTES), 2.0e-3F, "y case " + index);
            assertEquals(0.0F, output.getFloat(offset + 2 * Float.BYTES), 2.0e-4F, "boundary case " + index);
            assertEquals(1.0F, output.getFloat(offset + 3 * Float.BYTES), 0.0F, "valid case " + index);
        }
    }

    @Test
    void coordinateScalePreservesAtmosphereAndMultipliesWorldOpticalDepth() throws IOException {
        int caseFloats = 4;
        float altitude = 10.0F;
        float horizonRadius = 6_360.0F + altitude - 0.01F;
        float tangent = (float) -Math.sqrt(
                1.0 - 6_360.0F * 6_360.0F / (horizonRadius * horizonRadius));
        float[][] cases = {
            {0.0F, 0.0F, 1.0F, 1.0F},
            {0.5F, 1.0F, 0.2F, 16.0F},
            {1.5F, 0.0F, 0.0F, 64.0F},
            {10.0F, 1.0F, -0.2F, 256.0F},
            {altitude, 0.0F, tangent + 1.0e-4F, 256.0F},
            {altitude, 1.0F, tangent - 1.0e-4F, 256.0F},
            {50.0F, 0.0F, 0.7F, 1_024.0F},
            {99.0F, 1.0F, -0.7F, 2_048.0F}
        };
        ByteBuffer output = runner.dispatch(
                "atmosphere_scale_properties.comp.spv",
                floats(cases),
                cases.length * caseFloats * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                count(cases.length));
        for (int caseIndex = 0; caseIndex < cases.length; ++caseIndex) {
            int base = caseIndex * caseFloats * Float.BYTES;
            for (int component = 0; component < caseFloats; ++component) {
                float error = output.getFloat(base + component * Float.BYTES);
                assertTrue(
                        error <= 5.0e-4F,
                        "coordinate-scale case " + caseIndex + " component " + component + ": " + error);
            }
        }
    }

    @Test
    void shadowLeafIntegrationResolvesBlockerCrossingsAnalytically() throws IOException {
        float[][] cases = {
            {10.0F, 0.0F, 0.0F, 4.0F, 5.0F, 0.02F},
            {0.0F, 0.0F, 0.0F, 4.0F, 5.0F, 0.02F},
            {0.0F, 2.0F, 0.0F, 4.0F, 4.0F, 0.0F},
            {8.0F, -2.0F, 0.0F, 4.0F, 4.0F, 0.0F},
            {0.0F, 1.0F, 2.0F, 6.0F, 4.0F, 0.0F},
            {0.0F, 0.0F, 1.0F, 3.0F, -1.0e20F, 0.02F},
            {0.0F, 0.0F, 1.0F, 3.0F, 1.0e20F, 0.02F},
            {4.99F, 0.0F, 2.0F, 5.0F, 5.0F, 0.02F}
        };
        float[] expected = {4.0F, 0.0F, 2.0F, 2.0F, 2.0F, 2.0F, 0.0F, 3.0F};
        String shader = "sun_shadow_hierarchy_properties.comp.spv";
        ByteBuffer output = runner.dispatch(
                shader,
                floats(cases),
                cases.length * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                count(cases.length));
        for (int index = 0; index < cases.length; index++) {
            assertEquals(
                    expected[index],
                    output.getFloat(index * Float.BYTES),
                    1.0e-5F,
                    shader + " case " + index);
        }
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer floats(float[][] values) {
        int components = values[0].length;
        ByteBuffer result = buffer(values.length * components * Float.BYTES);
        for (float[] value : values) {
            if (value.length != components) throw new IllegalArgumentException("Ragged float cases");
            for (float component : value) result.putFloat(component);
        }
        return result.flip();
    }

    private static ByteBuffer count(int value) {
        return buffer(Integer.BYTES).putInt(value).flip();
    }

    private static void putDirection(ByteBuffer target, int offset, Vector3f value) {
        target.putFloat(offset, value.x);
        target.putFloat(offset + Float.BYTES, value.y);
        target.putFloat(offset + 2 * Float.BYTES, value.z);
        target.putFloat(offset + 3 * Float.BYTES, 0.0F);
    }

    private static void putGroup(ByteBuffer input, double[] radiance, double[] transmittance) {
        for (double value : radiance) input.putFloat((float) value);
        for (double value : transmittance) input.putFloat((float) value);
    }

    private static double segmentIntegral(double extinction, double length) {
        return extinction == 0.0 ? length : -Math.expm1(-extinction * length) / extinction;
    }
}
