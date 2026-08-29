package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class OpenPbrDistributionGpuTest {
    private static final int SAMPLE_COUNT = 262_144;
    private static final int GRID_Z = 256;
    private static final int GRID_PHI = 512;
    private static final int GRID_COUNT = GRID_Z * GRID_PHI;
    private static final int OUTPUT_WORDS = 3;
    private static final int HISTOGRAM_Z = 8;
    private static final int HISTOGRAM_PHI = 16;
    private static final int HISTOGRAM_BINS = HISTOGRAM_Z * HISTOGRAM_PHI;
    private static final int SAMPLE_MODE = 0;
    private static final int INTEGRATE_MODE = 1;
    private static final int LAMBERT = 0;
    private static final int CONDUCTOR = 1;
    private static final int TRANSMISSION = 2;
    private static final int SEED = 0x5985_E989;
    private static final double TWO_PI = 2.0 * Math.PI;

    private static final Configuration[] CONFIGURATIONS = {
        new Configuration(
                "Lambert",
                LAMBERT,
                0.0F,
                0.0F,
                0.5F,
                1.5F,
                new float[] {0.8F, 0.5F, 0.2F}),
        new Configuration(
                "isotropic conductor",
                CONDUCTOR,
                0.5F,
                0.0F,
                0.5F,
                1.5F,
                new float[] {0.8F, 0.6F, 0.3F}),
        new Configuration(
                "anisotropic conductor",
                CONDUCTOR,
                0.6F,
                0.75F,
                0.7F,
                1.5F,
                new float[] {0.7F, 0.4F, 0.2F}),
        new Configuration(
                "dielectric transmission",
                TRANSMISSION,
                0.5F,
                0.0F,
                0.5F,
                1.5F,
                new float[] {0.9F, 0.8F, 0.7F})
    };

    private static ShaderComputeRunner runner;
    private static Path[] shaders;

    @BeforeAll
    static void prepareResources() throws IOException {
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
        shaders = new Path[] {
            Path.of(
                    System.getProperty("prime.test.slangShaderDirectory"),
                    "openpbr_distribution_statistics.comp.spv")
        };
    }

    @Test
    void samplingMatchesPdfAndMonteCarloEnergyMatchesQuadrature() throws IOException {
        for (Path shader : shaders) {
            for (int index = 0; index < CONFIGURATIONS.length; index++) {
                verifyConfiguration(shader, CONFIGURATIONS[index], SEED + index * 0x9e37);
            }
        }
    }

    private static void verifyConfiguration(
            Path shader, Configuration configuration, int seed)
            throws IOException {
        ByteBuffer sampled = dispatch(shader, configuration, SAMPLE_MODE, SAMPLE_COUNT, seed);
        ByteBuffer integrated = dispatch(shader, configuration, INTEGRATE_MODE, GRID_COUNT, seed);
        double[] expectedProbability = new double[HISTOGRAM_BINS + 1];
        long[] actualCount = new long[HISTOGRAM_BINS + 1];
        double[] integratedEnergy = new double[3];
        double solidAngle = (configuration.suite() == TRANSMISSION ? 2.0 : 1.0)
                * TWO_PI / GRID_COUNT;

        for (int gridIndex = 0; gridIndex < GRID_COUNT; gridIndex++) {
            float responseX = value(integrated, gridIndex, 0, 0);
            float responseY = value(integrated, gridIndex, 0, 1);
            float responseZ = value(integrated, gridIndex, 0, 2);
            float pdf = value(integrated, gridIndex, 0, 3);
            assertFiniteNonnegative(configuration, "grid response.x", responseX);
            assertFiniteNonnegative(configuration, "grid response.y", responseY);
            assertFiniteNonnegative(configuration, "grid response.z", responseZ);
            assertFiniteNonnegative(configuration, "grid PDF", pdf);
            integratedEnergy[0] += responseX * solidAngle;
            integratedEnergy[1] += responseY * solidAngle;
            integratedEnergy[2] += responseZ * solidAngle;

            int zIndex = gridIndex / GRID_PHI;
            int phiIndex = gridIndex % GRID_PHI;
            int zBin = Math.min(
                    HISTOGRAM_Z - 1,
                    zIndex * HISTOGRAM_Z / GRID_Z);
            int phiBin = Math.min(
                    HISTOGRAM_PHI - 1,
                    phiIndex * HISTOGRAM_PHI / GRID_PHI);
            expectedProbability[zBin * HISTOGRAM_PHI + phiBin] += pdf * solidAngle;
        }

        double pdfMass = 0.0;
        for (int bin = 0; bin < HISTOGRAM_BINS; bin++) {
            pdfMass += expectedProbability[bin];
        }
        assertTrue(
                Double.isFinite(pdfMass) && pdfMass >= 0.0 && pdfMass <= 1.005,
                configuration.name() + " PDF mass is invalid: " + pdfMass);
        expectedProbability[HISTOGRAM_BINS] = Math.max(0.0, 1.0 - pdfMass);
        normalize(expectedProbability);

        double[][] moments = new double[3][2];
        double[][] evaluatedMoments = new double[3][2];
        for (int sampleIndex = 0; sampleIndex < SAMPLE_COUNT; sampleIndex++) {
            float directionX = value(sampled, sampleIndex, 0, 0);
            float directionY = value(sampled, sampleIndex, 0, 1);
            float directionZ = value(sampled, sampleIndex, 0, 2);
            float pdf = value(sampled, sampleIndex, 0, 3);
            int flags = ShaderTestBuffer.getInt(
                    sampled, sampleIndex, OUTPUT_WORDS, 1, 3);
            if (flags == 0 || pdf == 0.0F) {
                actualCount[HISTOGRAM_BINS]++;
                continue;
            }
            assertFiniteNonnegative(configuration, "sample PDF", pdf);
            double length = Math.sqrt(
                    directionX * directionX
                            + directionY * directionY
                            + directionZ * directionZ);
            assertTrue(
                    Double.isFinite(length) && Math.abs(length - 1.0) <= 8.0e-5,
                    configuration.name() + " sampled direction length is " + length);
            int bin = directionBin(configuration, directionX, directionY, directionZ);
            actualCount[bin]++;
            for (int channel = 0; channel < 3; channel++) {
                float response = value(sampled, sampleIndex, 1, channel);
                assertFiniteNonnegative(configuration, "sample response", response);
                double contribution = response / pdf;
                assertTrue(
                        Double.isFinite(contribution) && contribution >= 0.0,
                        configuration.name() + " invalid energy contribution " + contribution);
                moments[channel][0] += contribution;
                moments[channel][1] += contribution * contribution;

                float evaluatedResponse = value(sampled, sampleIndex, 2, channel);
                float evaluatedPdf = value(sampled, sampleIndex, 2, 3);
                assertFiniteNonnegative(
                        configuration, "sampled-direction evaluated response", evaluatedResponse);
                assertFiniteNonnegative(
                        configuration, "sampled-direction evaluated PDF", evaluatedPdf);
                if (evaluatedPdf > 0.0F) {
                    double evaluatedContribution = evaluatedResponse / evaluatedPdf;
                    assertTrue(
                            Double.isFinite(evaluatedContribution)
                                    && evaluatedContribution >= 0.0,
                            configuration.name()
                                    + " invalid evaluated contribution "
                                    + evaluatedContribution);
                    evaluatedMoments[channel][0] += evaluatedContribution;
                    evaluatedMoments[channel][1] +=
                            evaluatedContribution * evaluatedContribution;
                }
            }
        }

        double actualValidProbability =
                1.0 - (double) actualCount[HISTOGRAM_BINS] / SAMPLE_COUNT;
        double expectedValidProbability =
                1.0 - expectedProbability[HISTOGRAM_BINS];
        double probabilitySigma = Math.sqrt(
                Math.max(
                        1.0e-12,
                        expectedValidProbability
                                * (1.0 - expectedValidProbability)
                                / SAMPLE_COUNT));
        assertTrue(
                Math.abs(actualValidProbability - expectedValidProbability)
                        <= 6.0 * probabilitySigma + 2.0e-3,
                configuration.name()
                        + " valid sample probability differs: "
                        + actualValidProbability
                        + " vs "
                        + expectedValidProbability);
        assertChiSquare(configuration, actualCount, expectedProbability);

        for (int channel = 0; channel < 3; channel++) {
            double mean = moments[channel][0] / SAMPLE_COUNT;
            double secondMoment = moments[channel][1] / SAMPLE_COUNT;
            double variance = Math.max(0.0, secondMoment - mean * mean);
            double standardError = Math.sqrt(variance / SAMPLE_COUNT);
            double tolerance = 6.0 * standardError + 3.0e-3;
            double evaluatedMean = evaluatedMoments[channel][0] / SAMPLE_COUNT;
            double evaluatedSecondMoment =
                    evaluatedMoments[channel][1] / SAMPLE_COUNT;
            double evaluatedVariance = Math.max(
                    0.0, evaluatedSecondMoment - evaluatedMean * evaluatedMean);
            double evaluatedStandardError = Math.sqrt(
                    evaluatedVariance / SAMPLE_COUNT);
            double evaluatedTolerance =
                    6.0 * evaluatedStandardError + 3.0e-3;
            assertTrue(
                    Math.abs(evaluatedMean - integratedEnergy[channel])
                            <= evaluatedTolerance,
                    configuration.name()
                            + " channel "
                            + channel
                            + " re-evaluated MC energy differs: MC="
                            + evaluatedMean
                            + " quadrature="
                            + integratedEnergy[channel]
                            + " tolerance="
                            + evaluatedTolerance);
            assertTrue(
                    Math.abs(mean - integratedEnergy[channel]) <= tolerance,
                    configuration.name()
                            + " channel "
                            + channel
                            + " energy differs: MC="
                            + mean
                            + " quadrature="
                            + integratedEnergy[channel]
                            + " re-evaluated="
                            + evaluatedMean
                            + " tolerance="
                            + tolerance);
        }

        if (configuration.suite() != TRANSMISSION) {
            for (int channel = 0; channel < 3; channel++) {
                float fittedEnergy = value(integrated, 0, 1, channel);
                assertFiniteNonnegative(configuration, "directional energy", fittedEnergy);
                assertTrue(
                        fittedEnergy <= 1.001F
                                && Math.abs(fittedEnergy - integratedEnergy[channel]) <= 0.035,
                        configuration.name()
                                + " fitted energy differs in channel "
                                + channel
                                + ": "
                                + fittedEnergy
                                + " vs "
                                + integratedEnergy[channel]);
            }
        }
    }

    private static ByteBuffer dispatch(
            Path shader,
            Configuration configuration,
            int mode,
            int invocationCount,
            int seed)
            throws IOException {
        ByteBuffer input = ShaderTestBuffer.control(invocationCount, 3);
        ShaderTestBuffer.setOutputWords(input, OUTPUT_WORDS);
        ShaderTestBuffer.putControlInt(input, 0, 0, mode);
        ShaderTestBuffer.putControlInt(input, 0, 1, configuration.suite());
        ShaderTestBuffer.putControlInt(input, 0, 2, seed);
        ShaderTestBuffer.putControlFloat(input, 1, 0, configuration.roughness());
        ShaderTestBuffer.putControlFloat(input, 1, 1, configuration.anisotropy());
        ShaderTestBuffer.putControlFloat(input, 1, 2, configuration.viewCosine());
        ShaderTestBuffer.putControlFloat(input, 1, 3, configuration.ior());
        ShaderTestBuffer.putControlFloat(input, 2, 0, configuration.color()[0]);
        ShaderTestBuffer.putControlFloat(input, 2, 1, configuration.color()[1]);
        ShaderTestBuffer.putControlFloat(input, 2, 2, configuration.color()[2]);
        return runner.dispatch(
                shader,
                input,
                Math.multiplyExact(
                        Math.multiplyExact(invocationCount, OUTPUT_WORDS),
                        ShaderTestBuffer.WORD_BYTES),
                invocationCount);
    }

    private static int directionBin(
            Configuration configuration, float x, float y, float z) {
        double zUnit = configuration.suite() == TRANSMISSION
                ? 0.5 * (z + 1.0)
                : z;
        int zBin = Math.max(
                0,
                Math.min(HISTOGRAM_Z - 1, (int) (zUnit * HISTOGRAM_Z)));
        double phi = Math.atan2(y, x);
        if (phi < 0.0) {
            phi += TWO_PI;
        }
        int phiBin = Math.max(
                0,
                Math.min(HISTOGRAM_PHI - 1, (int) (phi / TWO_PI * HISTOGRAM_PHI)));
        return zBin * HISTOGRAM_PHI + phiBin;
    }

    private static void assertChiSquare(
            Configuration configuration,
            long[] observed,
            double[] probability) {
        List<Double> expectedGroups = new ArrayList<>();
        List<Long> observedGroups = new ArrayList<>();
        double pooledExpected = 0.0;
        long pooledObserved = 0L;
        for (int bin = 0; bin < probability.length; bin++) {
            double expected = probability[bin] * SAMPLE_COUNT;
            if (expected >= 10.0) {
                expectedGroups.add(expected);
                observedGroups.add(observed[bin]);
            } else {
                pooledExpected += expected;
                pooledObserved += observed[bin];
            }
        }
        if (pooledExpected > 0.0) {
            expectedGroups.add(pooledExpected);
            observedGroups.add(pooledObserved);
        }
        double chiSquare = 0.0;
        for (int group = 0; group < expectedGroups.size(); group++) {
            double expected = expectedGroups.get(group);
            double difference = observedGroups.get(group) - expected;
            chiSquare += difference * difference / expected;
        }
        int degreesOfFreedom = Math.max(1, expectedGroups.size() - 1);
        double zScore = 4.0;
        double base = 1.0
                - 2.0 / (9.0 * degreesOfFreedom)
                + zScore * Math.sqrt(2.0 / (9.0 * degreesOfFreedom));
        double upperBound = degreesOfFreedom * base * base * base;
        assertTrue(
                Double.isFinite(chiSquare) && chiSquare <= upperBound,
                configuration.name()
                        + " chi-square "
                        + chiSquare
                        + " exceeds "
                        + upperBound
                        + " with "
                        + degreesOfFreedom
                        + " degrees of freedom");
    }

    private static void normalize(double[] probabilities) {
        double sum = 0.0;
        for (double probability : probabilities) {
            sum += probability;
        }
        for (int index = 0; index < probabilities.length; index++) {
            probabilities[index] /= sum;
        }
    }

    private static float value(
            ByteBuffer output, int caseIndex, int word, int component) {
        return ShaderTestBuffer.getFloat(
                output, caseIndex, OUTPUT_WORDS, word, component);
    }

    private static void assertFiniteNonnegative(
            Configuration configuration, String name, float value) {
        assertTrue(
                Float.isFinite(value) && value >= 0.0F,
                configuration.name() + " " + name + " is invalid: " + value);
    }

    private record Configuration(
            String name,
            int suite,
            float roughness,
            float anisotropy,
            float viewCosine,
            float ior,
            float[] color) {
    }
}
