package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

final class ZSobolSamplerTest {
    private static final int CASE_COUNT = 4_096;
    private static final int INPUT_WORDS = 2;
    private static final int OUTPUT_WORDS = 2;
    private static final int LOG4_SAMPLES_PER_PIXEL = 8;
    private static final double MAX_LOW_TO_HIGH_POWER = 1.55;
    private static final double MAX_ANNULAR_PEAK_TO_MEAN = 1.20;
    private static final double MAX_ANGULAR_MAX_TO_MIN = 1.60;
    private static final long RANDOM_SEED = 0x5a53_4f42_4f4c_0001L;
    private static final int[] DIMENSION_ONE = {
        0x8000_0000, 0xc000_0000, 0xa000_0000, 0xf000_0000,
        0x8800_0000, 0xcc00_0000, 0xaa00_0000, 0xff00_0000,
        0x8080_0000, 0xc0c0_0000, 0xa0a0_0000, 0xf0f0_0000,
        0x8888_0000, 0xcccc_0000, 0xaaaa_0000, 0xffff_0000,
        0x8000_8000, 0xc000_c000, 0xa000_a000, 0xf000_f000,
        0x8800_8800, 0xcc00_cc00, 0xaa00_aa00, 0xff00_ff00,
        0x8080_8080, 0xc0c0_c0c0, 0xa0a0_a0a0, 0xf0f0_f0f0,
        0x8888_8888, 0xcccc_cccc, 0xaaaa_aaaa, 0xffff_ffff,
        0x8000_0000, 0xc000_0000, 0xa000_0000, 0xf000_0000,
        0x8800_0000, 0xcc00_0000, 0xaa00_0000, 0xff00_0000,
        0x8080_0000, 0xc0c0_0000, 0xa0a0_0000, 0xf0f0_0000,
        0x8888_0000, 0xcccc_0000, 0xaaaa_0000, 0xffff_0000,
        0x8000_8000, 0xc000_c000, 0xa000_a000, 0xf000_f000
    };
    @Nested
    @Tag("gpu-shader")
    @ExtendWith(ShaderComputeExtension.class)
    final class Parity {
        private static ShaderComputeRunner runner;

        @Test
        void shaderMatchesPrimeZOrderFastOwenReference() throws IOException {
            ByteBuffer input = cases();
            ByteBuffer output = runner.dispatch(
                    shader("prime_zsobol_parity.comp.spv"),
                    input,
                    CASE_COUNT * OUTPUT_WORDS * ShaderTestBuffer.WORD_BYTES,
                    CASE_COUNT);
            for (int caseIndex = 0; caseIndex < CASE_COUNT; caseIndex++) {
                int pixelX = input(caseIndex, 0, 0, input);
                int pixelY = input(caseIndex, 0, 1, input);
                int sampleIndex = input(caseIndex, 0, 2, input);
                int sampleEpoch = input(caseIndex, 0, 3, input);
                int extentX = input(caseIndex, 1, 0, input);
                int extentY = input(caseIndex, 1, 1, input);
                int vertexIndex = input(caseIndex, 1, 2, input);
                int pathBranch = input(caseIndex, 1, 3, input);
                float[] expected = sample(
                        pixelX,
                        pixelY,
                        extentX,
                        extentY,
                        sampleIndex,
                        sampleEpoch,
                        vertexIndex,
                        pathBranch);
                for (int component = 0; component < expected.length; component++) {
                    int word = component / 4;
                    int lane = component % 4;
                    assertEquals(
                            Float.floatToRawIntBits(expected[component]),
                            ShaderTestBuffer.getInt(
                                    output, caseIndex, OUTPUT_WORDS, word, lane),
                            "case=" + caseIndex + " component=" + component);
                }
            }
        }
    }

    @Test
    void affineDigitMappingCoversEveryPermutation() {
        Set<Integer> permutations = new HashSet<>();
        for (int matrixIndex = 0; matrixIndex < 6; matrixIndex++) {
            long matrixSelector = ((2L * matrixIndex + 1) << 30) / 12;
            for (int translation = 0; translation < 4; translation++) {
                int selector = (int) ((matrixSelector << 2) | translation);
                boolean[] occupied = new boolean[4];
                int packed = 0;
                for (int digit = 0; digit < 4; digit++) {
                    int mapped = permuteDigit(selector, digit);
                    assertFalse(occupied[mapped], "selector=" + selector);
                    occupied[mapped] = true;
                    packed |= mapped << (digit * 2);
                }
                assertTrue(permutations.add(packed), "selector=" + selector);
            }
        }
        assertEquals(24, permutations.size());
    }

    @Test
    void selectorUsesAllPermutationsWithoutLargeBias() {
        int[] counts = new int[24];
        for (int dimension = 0; dimension < 16; dimension++) {
            int dimensionHash = 0x5555_5555 * dimension;
            for (long prefix = 0; prefix < 65_536; prefix++) {
                int value = selector(prefix, dimensionHash);
                int matrixSelector = value >>> 2;
                int matrixIndex = (matrixSelector + (matrixSelector << 1)) >>> 29;
                counts[matrixIndex * 4 + (value & 3)]++;
            }
        }
        double expected = 16.0 * 65_536.0 / counts.length;
        for (int permutation = 0; permutation < counts.length; permutation++) {
            double relativeError = Math.abs(counts[permutation] - expected) / expected;
            assertTrue(
                    relativeError < 0.025,
                    "permutation=" + permutation
                            + " count=" + counts[permutation]
                            + " relativeError=" + relativeError);
        }
    }

    @Test
    void mappedIndicesDoNotRepeatAcrossPixelsOrFrames() {
        int extent = 16;
        int digitCount = Integer.numberOfTrailingZeros(extent)
                + LOG4_SAMPLES_PER_PIXEL;
        for (int dimension : new int[] {0, 7, 191}) {
            Set<Long> jointIndices = new HashSet<>();
            for (int sampleIndex = 0; sampleIndex < 256; sampleIndex++) {
                Set<Long> screenIndices = new HashSet<>();
                for (int pixelY = 0; pixelY < extent; pixelY++) {
                    for (int pixelX = 0; pixelX < extent; pixelX++) {
                        long mapped = mappedIndex(
                                (morton(pixelX, pixelY) << 16) | sampleIndex,
                                digitCount,
                                dimension);
                        assertTrue(screenIndices.add(mapped),
                                "dimension=" + dimension
                                        + " pixel=" + pixelX + "," + pixelY
                                        + " sample=" + sampleIndex);
                        assertTrue(jointIndices.add(mapped),
                                "Sobol index reused across pixels or frames: dimension="
                                        + dimension
                                        + " pixel=" + pixelX + "," + pixelY
                                        + " sample=" + sampleIndex
                                        + " mapped=" + mapped);
                    }
                }
                assertEquals(extent * extent, screenIndices.size());
            }
            assertEquals(extent * extent * 256, jointIndices.size());

            for (int[] pixel : new int[][] {{0, 0}, {7, 11}, {15, 15}}) {
                boolean[] occupied = new boolean[1 << 16];
                long interval = -1;
                for (int sampleIndex = 0; sampleIndex < occupied.length; sampleIndex++) {
                    long mapped = mappedIndex(
                            (morton(pixel[0], pixel[1]) << 16) | sampleIndex,
                            digitCount,
                            dimension);
                    if (sampleIndex == 0) {
                        interval = mapped >>> 16;
                    } else {
                        assertEquals(interval, mapped >>> 16);
                    }
                    int low = (int) mapped & 0xffff;
                    assertFalse(occupied[low]);
                    occupied[low] = true;
                }
            }
        }
    }

    @Test
    void alignedSampleBlocksRemainAlignedIndexIntervals() {
        int extent = 64;
        int digitCount = Integer.numberOfTrailingZeros(extent)
                + LOG4_SAMPLES_PER_PIXEL;
        for (int dimension : new int[] {0, 7, 191}) {
            for (int[] pixel : new int[][] {{0, 0}, {17, 29}, {63, 63}}) {
                for (int lowBits = 0; lowBits <= 16; lowBits += 2) {
                    int blockSize = 1 << lowBits;
                    for (int origin : new int[] {0, (1 << 16) - blockSize}) {
                        long interval = -1;
                        boolean[] occupied = new boolean[1 << lowBits];
                        for (int sampleIndex = origin;
                                sampleIndex < origin + blockSize;
                                sampleIndex++) {
                            long mapped = mappedIndex(
                                    (morton(pixel[0], pixel[1]) << 16) | sampleIndex,
                                    digitCount,
                                    dimension);
                            long mappedInterval = mapped >>> lowBits;
                            if (interval < 0) {
                                interval = mappedInterval;
                            } else {
                                assertEquals(interval, mappedInterval);
                            }
                            int low = lowBits == 0
                                    ? 0
                                    : (int) (mapped & ((1L << lowBits) - 1));
                            assertFalse(occupied[low]);
                            occupied[low] = true;
                        }
                    }
                }
            }
        }
    }

    @Test
    void sampleHashSeparatesDimensionsAndEpochs() {
        Set<Long> hashes = new HashSet<>();
        for (int dimension = 0; dimension < 256; dimension++) {
            for (int epoch = 0; epoch < 64; epoch++) {
                assertTrue(
                        hashes.add(sampleHash(dimension, epoch)),
                        "dimension=" + dimension + " epoch=" + epoch);
            }
        }
    }

    @Test
    void everyPixelRetainsDyadicTemporalStratification() {
        int[][] cases = {
            {0, 0, 1, 1},
            {17, 29, 1920, 1080},
            {1919, 1079, 1920, 1080},
            {2731, 1535, 2732, 1536}
        };
        for (int[] sampleCase : cases) {
            for (int stream = 0; stream < 3; stream++) {
                for (int component = 0; component < 2; component++) {
                    boolean[] occupied = new boolean[256];
                    for (int sampleIndex = 0; sampleIndex < occupied.length; sampleIndex++) {
                        int value = sampleBits(
                                sampleCase[0],
                                sampleCase[1],
                                sampleCase[2],
                                sampleCase[3],
                                sampleIndex,
                                11,
                                1,
                                0)[stream * 2 + component];
                        int bin = value >>> 24;
                        assertFalse(
                                occupied[bin],
                                "pixel=" + sampleCase[0] + "," + sampleCase[1]
                                        + " stream=" + stream
                                        + " component=" + component
                                        + " bin=" + bin);
                        occupied[bin] = true;
                    }
                }
            }
        }
    }

    @Test
    void temporalPrefixesRetainBaseFourSobolNetBounds() {
        int[][] cases = {
            {0, 0, 1, 1},
            {17, 29, 1920, 1080},
            {2731, 1535, 2732, 1536}
        };
        for (int[] sampleCase : cases) {
            for (int epoch : new int[] {0, 12_345}) {
                for (int stream = 0; stream < 3; stream++) {
                    int[] x = new int[1 << 16];
                    int[] y = new int[1 << 16];
                    for (int sampleIndex = 0; sampleIndex < x.length; sampleIndex++) {
                        int[] bits = sampleBits(
                                sampleCase[0],
                                sampleCase[1],
                                sampleCase[2],
                                sampleCase[3],
                                sampleIndex,
                                epoch,
                                1,
                                0);
                        x[sampleIndex] = bits[stream * 2];
                        y[sampleIndex] = bits[stream * 2 + 1];
                    }
                    String context = "pixel=" + sampleCase[0] + "," + sampleCase[1]
                            + " epoch=" + epoch
                            + " stream=" + stream;
                    // A complete base-4 digit is a (0,m,2)-net. A half digit keeps
                    // the corresponding (1,m,2) bound at odd binary prefixes.
                    for (int logSamples = 1; logSamples <= 16; logSamples++) {
                        if ((logSamples & 1) == 0) {
                            assertElementaryIntervalCounts(
                                    x, y, logSamples, logSamples, 1, context);
                        } else {
                            assertElementaryIntervalCounts(
                                    x, y, logSamples, logSamples - 1, 2, context);
                        }
                    }
                }
            }
        }
    }

    @Test
    void foldedDimensionOneMatchesPinnedJoeKuoColumns() {
        for (int column = 0; column < DIMENSION_ONE.length; column++) {
            assertEquals(
                    DIMENSION_ONE[column],
                    dimensionOne(1L << column),
                    "column=" + column);
        }
        SplittableRandom random = new SplittableRandom(RANDOM_SEED);
        long mask = (1L << DIMENSION_ONE.length) - 1;
        for (int caseIndex = 0; caseIndex < 4_096; caseIndex++) {
            long index = random.nextLong() & mask;
            int expected = 0;
            for (int column = 0; column < DIMENSION_ONE.length; column++) {
                if ((index & (1L << column)) != 0) {
                    expected ^= DIMENSION_ONE[column];
                }
            }
            assertEquals(expected, dimensionOne(index), "index=" + index);
        }
    }

    @Nested
    @Tag("gpu-shader")
    @ExtendWith(ShaderComputeExtension.class)
    final class Distribution {
        private static ShaderComputeRunner runner;

        @Test
        void shaderFloatTemporalPrefixesStayWithinOneBoundaryRoundingEvent()
                throws IOException {
            // The raw u32 test above proves exact elementary intervals. The production
            // f32 conversion may move a boundary-adjacent value to a neighboring bin,
            // so test its material distribution error instead of demanding bit exactness.
            int logSampleCount = 16;
            int sampleCount = 1 << logSampleCount;
            ByteBuffer output = runner.dispatch(
                    shader("prime_zsobol_parity.comp.spv"),
                    temporalCases(sampleCount),
                    sampleCount * OUTPUT_WORDS * ShaderTestBuffer.WORD_BYTES,
                    sampleCount);
            for (int stream = 0; stream < 3; stream++) {
                int[] x = new int[sampleCount];
                int[] y = new int[sampleCount];
                for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                    x[sampleIndex] = ((int) (outputSample(
                            output, sampleIndex, stream * 2) * 0x1p24)) << 8;
                    y[sampleIndex] = ((int) (outputSample(
                            output, sampleIndex, stream * 2 + 1) * 0x1p24)) << 8;
                }
                for (int logSamples : new int[] {8, 12, 16}) {
                    assertRoundedElementaryDistribution(
                            x, y, logSamples, "shader stream=" + stream);
                }
            }
        }

        @Test
        void screenSpaceErrorSpectrumAvoidsConcentratedPatterns() throws IOException {
            int[][] frames = {
                {0, 0},
                {1, 0},
                {17, 0},
                {255, 17},
                {65_535, 12_345}
            };
            for (int[] frame : frames) {
                ByteBuffer output = runner.dispatch(
                        shader("prime_zsobol_parity.comp.spv"),
                        screenCases(frame[0], frame[1]),
                        CASE_COUNT * OUTPUT_WORDS * ShaderTestBuffer.WORD_BYTES,
                        CASE_COUNT);
                for (int stream = 0; stream < 3; stream++) {
                    double[][] error = new double[4][CASE_COUNT];
                    for (int pixel = 0; pixel < CASE_COUNT; pixel++) {
                        double x = outputSample(output, pixel, stream * 2);
                        double y = outputSample(output, pixel, stream * 2 + 1);
                        error[0][pixel] = x - 0.5;
                        error[1][pixel] = y - 0.5;
                        error[2][pixel] = x * y - 0.25;
                        error[3][pixel] = (x + y < 1.0 ? 1.0 : 0.0) - 0.5;
                    }
                    for (int integrand = 0; integrand < error.length; integrand++) {
                        SpectrumMetrics metrics = spectrumMetrics(error[integrand], 64);
                        assertTrue(
                                metrics.lowToHigh() < MAX_LOW_TO_HIGH_POWER
                                        && metrics.annularPeakToMean()
                                                < MAX_ANNULAR_PEAK_TO_MEAN
                                        && metrics.angularMaxToMin()
                                                < MAX_ANGULAR_MAX_TO_MIN,
                                "sample=" + frame[0]
                                        + " epoch=" + frame[1]
                                        + " stream=" + stream
                                        + " integrand=" + integrand
                                        + " metrics=" + metrics);
                    }
                }
            }
        }
    }

    private static ByteBuffer cases() {
        int[][] extents = {
            {1, 1},
            {1280, 720},
            {1920, 1080},
            {2732, 1536},
            {3840, 2160},
            {8192, 4320},
            {1 << 18, 1 << 18}
        };
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        ShaderTestBuffer.setOutputWords(input, OUTPUT_WORDS);
        SplittableRandom random = new SplittableRandom(RANDOM_SEED);
        for (int caseIndex = 0; caseIndex < CASE_COUNT; caseIndex++) {
            int[] extent = extents[caseIndex % extents.length];
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 0, random.nextInt(extent[0]));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 1, random.nextInt(extent[1]));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 2, random.nextInt(1 << 16));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 3, random.nextInt(1 << 30));
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 0, extent[0]);
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 1, extent[1]);
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 1, 2, random.nextInt(1, 129));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 1, 3, random.nextInt(2));
        }
        return input;
    }

    private static ByteBuffer screenCases(int sampleIndex, int sampleEpoch) {
        int extent = 64;
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        ShaderTestBuffer.setOutputWords(input, OUTPUT_WORDS);
        for (int caseIndex = 0; caseIndex < CASE_COUNT; caseIndex++) {
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 0, caseIndex % extent);
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 1, caseIndex / extent);
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 2, sampleIndex);
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 3, sampleEpoch);
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 0, extent);
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 1, extent);
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 2, 0);
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 3, 0);
        }
        return input;
    }

    private static ByteBuffer temporalCases(int sampleCount) {
        ByteBuffer input = ShaderTestBuffer.inputs(sampleCount, INPUT_WORDS);
        ShaderTestBuffer.setOutputWords(input, OUTPUT_WORDS);
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            ShaderTestBuffer.putInt(input, sampleIndex, INPUT_WORDS, 0, 0, 17);
            ShaderTestBuffer.putInt(input, sampleIndex, INPUT_WORDS, 0, 1, 29);
            ShaderTestBuffer.putInt(
                    input, sampleIndex, INPUT_WORDS, 0, 2, sampleIndex);
            ShaderTestBuffer.putInt(input, sampleIndex, INPUT_WORDS, 0, 3, 12_345);
            ShaderTestBuffer.putInt(input, sampleIndex, INPUT_WORDS, 1, 0, 1920);
            ShaderTestBuffer.putInt(input, sampleIndex, INPUT_WORDS, 1, 1, 1080);
            ShaderTestBuffer.putInt(input, sampleIndex, INPUT_WORDS, 1, 2, 1);
            ShaderTestBuffer.putInt(input, sampleIndex, INPUT_WORDS, 1, 3, 0);
        }
        return input;
    }

    private static double outputSample(ByteBuffer output, int caseIndex, int component) {
        int word = component / 4;
        int lane = component % 4;
        float sample = Float.intBitsToFloat(ShaderTestBuffer.getInt(
                output, caseIndex, OUTPUT_WORDS, word, lane));
        assertTrue(Float.isFinite(sample));
        assertTrue(sample >= 0.0F && sample < 1.0F);
        return sample;
    }

    private static SpectrumMetrics spectrumMetrics(double[] field, int size) {
        double[] real = field.clone();
        double[] imaginary = new double[field.length];
        double[] lineReal = new double[size];
        double[] lineImaginary = new double[size];
        for (int y = 0; y < size; y++) {
            System.arraycopy(real, y * size, lineReal, 0, size);
            System.arraycopy(imaginary, y * size, lineImaginary, 0, size);
            fft(lineReal, lineImaginary);
            System.arraycopy(lineReal, 0, real, y * size, size);
            System.arraycopy(lineImaginary, 0, imaginary, y * size, size);
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                lineReal[y] = real[y * size + x];
                lineImaginary[y] = imaginary[y * size + x];
            }
            fft(lineReal, lineImaginary);
            for (int y = 0; y < size; y++) {
                real[y * size + x] = lineReal[y];
                imaginary[y * size + x] = lineImaginary[y];
            }
        }

        double lowPower = 0.0;
        double highPower = 0.0;
        int lowCount = 0;
        int highCount = 0;
        double[] annularPower = new double[6];
        int[] annularCount = new int[annularPower.length];
        double[] angularPower = new double[8];
        int[] angularCount = new int[angularPower.length];
        for (int y = 0; y < size; y++) {
            int signedFrequencyY = y <= size / 2 ? y : y - size;
            int frequencyY = Math.abs(signedFrequencyY);
            for (int x = 0; x < size; x++) {
                int signedFrequencyX = x <= size / 2 ? x : x - size;
                int frequencyX = Math.abs(signedFrequencyX);
                int radiusSquared = frequencyX * frequencyX
                        + frequencyY * frequencyY;
                if (radiusSquared == 0) {
                    continue;
                }
                double power = real[y * size + x] * real[y * size + x]
                        + imaginary[y * size + x] * imaginary[y * size + x];
                if (radiusSquared <= 36) {
                    lowPower += power;
                    lowCount++;
                } else if (radiusSquared >= 256) {
                    highPower += power;
                    highCount++;
                }
                double radius = Math.sqrt(radiusSquared);
                if (radius >= 4.0 && radius < 28.0) {
                    int annulus = (int) ((radius - 4.0) / 4.0);
                    annularPower[annulus] += power;
                    annularCount[annulus]++;
                    double angle = Math.atan2(signedFrequencyY, signedFrequencyX);
                    if (angle < 0.0) {
                        angle += Math.PI;
                    } else if (angle >= Math.PI) {
                        angle -= Math.PI;
                    }
                    int sector = Math.min(
                            angularPower.length - 1,
                            (int) (angle * angularPower.length / Math.PI));
                    angularPower[sector] += power;
                    angularCount[sector]++;
                }
            }
        }
        double annularSum = 0.0;
        double annularPeak = 0.0;
        for (int index = 0; index < annularPower.length; index++) {
            double mean = annularPower[index] / annularCount[index];
            annularSum += mean;
            annularPeak = Math.max(annularPeak, mean);
        }
        double angularMinimum = Double.POSITIVE_INFINITY;
        double angularMaximum = 0.0;
        for (int index = 0; index < angularPower.length; index++) {
            double mean = angularPower[index] / angularCount[index];
            angularMinimum = Math.min(angularMinimum, mean);
            angularMaximum = Math.max(angularMaximum, mean);
        }
        return new SpectrumMetrics(
                (lowPower / lowCount) / (highPower / highCount),
                annularPeak / (annularSum / annularPower.length),
                angularMaximum / angularMinimum);
    }

    private record SpectrumMetrics(
            double lowToHigh,
            double annularPeakToMean,
            double angularMaxToMin) {}

    private static void fft(double[] real, double[] imaginary) {
        int size = real.length;
        for (int index = 1, reversed = 0; index < size; index++) {
            int bit = size >>> 1;
            while ((reversed & bit) != 0) {
                reversed ^= bit;
                bit >>>= 1;
            }
            reversed ^= bit;
            if (index < reversed) {
                double swap = real[index];
                real[index] = real[reversed];
                real[reversed] = swap;
                swap = imaginary[index];
                imaginary[index] = imaginary[reversed];
                imaginary[reversed] = swap;
            }
        }
        for (int length = 2; length <= size; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double stepReal = Math.cos(angle);
            double stepImaginary = Math.sin(angle);
            for (int offset = 0; offset < size; offset += length) {
                double twiddleReal = 1.0;
                double twiddleImaginary = 0.0;
                for (int index = 0; index < length / 2; index++) {
                    int first = offset + index;
                    int second = first + length / 2;
                    double productReal = twiddleReal * real[second]
                            - twiddleImaginary * imaginary[second];
                    double productImaginary = twiddleReal * imaginary[second]
                            + twiddleImaginary * real[second];
                    real[second] = real[first] - productReal;
                    imaginary[second] = imaginary[first] - productImaginary;
                    real[first] += productReal;
                    imaginary[first] += productImaginary;
                    double nextReal = twiddleReal * stepReal
                            - twiddleImaginary * stepImaginary;
                    twiddleImaginary = twiddleReal * stepImaginary
                            + twiddleImaginary * stepReal;
                    twiddleReal = nextReal;
                }
            }
        }
    }

    private static int input(
            int caseIndex, int word, int component, ByteBuffer input) {
        return ShaderTestBuffer.getInputInt(
                input, caseIndex, INPUT_WORDS, word, component);
    }

    private static float[] sample(
            int pixelX,
            int pixelY,
            int extentX,
            int extentY,
            int sampleIndex,
            int sampleEpoch,
            int vertexIndex,
            int pathBranch) {
        int[] bits = sampleBits(
                pixelX,
                pixelY,
                extentX,
                extentY,
                sampleIndex,
                sampleEpoch,
                vertexIndex,
                pathBranch);
        float[] result = new float[bits.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = sampleFloat(bits[index]);
        }
        return result;
    }

    private static int[] sampleBits(
            int pixelX,
            int pixelY,
            int extentX,
            int extentY,
            int sampleIndex,
            int sampleEpoch,
            int vertexIndex,
            int pathBranch) {
        long mortonIndex = (morton(pixelX, pixelY) << 16)
                | Integer.toUnsignedLong(sampleIndex);
        int dimensionBase = (vertexIndex * 2 + pathBranch) * 6;
        int digitCount = 32 - Integer.numberOfLeadingZeros(
                Math.max(extentX, extentY) - 1) + LOG4_SAMPLES_PER_PIXEL;
        int[] result = new int[6];
        for (int stream = 0; stream < 3; stream++) {
            int dimension = dimensionBase + stream * 2;
            long mappedIndex = mappedIndex(mortonIndex, digitCount, dimension);
            long hash = sampleHash(dimension + 2, sampleEpoch);
            result[stream * 2] = fastOwen(
                    Integer.reverse((int) mappedIndex), (int) hash);
            result[stream * 2 + 1] = fastOwen(
                    dimensionOne(mappedIndex), (int) (hash >>> 32));
        }
        return result;
    }

    private static long mappedIndex(long mortonIndex, int digitCount, int dimension) {
        long source = mortonIndex << (64 - 2 * digitCount);
        long higherDigits = 0;
        long sampleIndex = 0;
        for (int remainingDigits = digitCount;
                remainingDigits != 0;
                remainingDigits--) {
            int digit = (int) (source >>> 62);
            int dimensionHash = 0x5555_5555 * dimension;
            int selector = selector(higherDigits, dimensionHash);
            digit = permuteDigit(selector, digit);
            sampleIndex = (sampleIndex << 2) | digit;
            higherDigits = (higherDigits << 2) | (source >>> 62);
            source <<= 2;
        }
        return sampleIndex;
    }

    private static void assertElementaryIntervalCounts(
            int[] x,
            int[] y,
            int logSamples,
            int logIntervals,
            int expectedCount,
            String context) {
        int sampleCount = 1 << logSamples;
        for (int logX = 0; logX <= logIntervals; logX++) {
            int logY = logIntervals - logX;
            int[] counts = new int[1 << logIntervals];
            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                int xBin = logX == 0 ? 0 : x[sampleIndex] >>> (32 - logX);
                int yBin = logY == 0 ? 0 : y[sampleIndex] >>> (32 - logY);
                int interval = (yBin << logX) | xBin;
                counts[interval]++;
            }
            for (int interval = 0; interval < counts.length; interval++) {
                assertEquals(
                        expectedCount,
                        counts[interval],
                        context
                                + " logSamples=" + logSamples
                                + " intervals=" + (1 << logX) + "x" + (1 << logY)
                                + " interval=" + interval);
            }
        }
    }

    private static void assertRoundedElementaryDistribution(
            int[] x, int[] y, int logSamples, String context) {
        int sampleCount = 1 << logSamples;
        int logIntervals = 8;
        int expected = sampleCount >> logIntervals;
        for (int logX = 0; logX <= logIntervals; logX++) {
            int logY = logIntervals - logX;
            int[] counts = new int[1 << logIntervals];
            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                int xBin = logX == 0 ? 0 : x[sampleIndex] >>> (32 - logX);
                int yBin = logY == 0 ? 0 : y[sampleIndex] >>> (32 - logY);
                counts[(yBin << logX) | xBin]++;
            }
            for (int interval = 0; interval < counts.length; interval++) {
                assertTrue(
                        Math.abs(counts[interval] - expected) <= 1,
                        context
                                + " logSamples=" + logSamples
                                + " intervals=" + (1 << logX) + "x" + (1 << logY)
                                + " interval=" + interval
                                + " count=" + counts[interval]);
            }
        }
    }

    private static int permuteDigit(int selector, int digit) {
        int matrixSelector = selector >>> 2;
        int matrixIndex = (matrixSelector + (matrixSelector << 1)) >>> 29;
        int matrix = (0x00b7_e6d9 >>> (matrixIndex * 4)) & 15;
        int xMask = -(digit & 1);
        int yMask = -(digit >>> 1);
        return (selector & 3)
                ^ (xMask & (matrix & 3))
                ^ (yMask & (matrix >>> 2));
    }

    private static long morton(int pixelX, int pixelY) {
        return (leftShift2(pixelY) << 1) | leftShift2(pixelX);
    }

    private static long leftShift2(int value) {
        long expanded = Integer.toUnsignedLong(value);
        expanded = (expanded ^ expanded << 16) & 0x0000_ffff_0000_ffffL;
        expanded = (expanded ^ expanded << 8) & 0x00ff_00ff_00ff_00ffL;
        expanded = (expanded ^ expanded << 4) & 0x0f0f_0f0f_0f0f_0f0fL;
        expanded = (expanded ^ expanded << 2) & 0x3333_3333_3333_3333L;
        return (expanded ^ expanded << 1) & 0x5555_5555_5555_5555L;
    }

    private static int selector(long higherDigits, int dimensionHash) {
        int value = (int) higherDigits
                ^ (int) (higherDigits >>> 32) * 0x9e37_79b9
                ^ dimensionHash;
        return avalanche(value);
    }

    private static int avalanche(int value) {
        value ^= value >>> 16;
        value *= 0x7feb_352d;
        value ^= value >>> 15;
        value *= 0x846c_a68b;
        return value ^ value >>> 16;
    }

    private static long sampleHash(int dimension, int seed) {
        int first = avalanche(
                seed ^ dimension * 0x9e37_79b9 ^ 0xa511_e9b3);
        int second = avalanche(
                seed ^ dimension * 0x85eb_ca6b ^ 0x63d8_3595);
        return Integer.toUnsignedLong(first)
                | Integer.toUnsignedLong(second) << 32;
    }

    private static int dimensionOne(long sampleIndex) {
        int value = 0;
        for (int column = 0; sampleIndex != 0; column++, sampleIndex >>>= 1) {
            if ((sampleIndex & 1) != 0) {
                value ^= DIMENSION_ONE[column];
            }
        }
        return value;
    }

    private static int fastOwen(int value, int seed) {
        value = Integer.reverse(value);
        value ^= value * 0x3d20_adea;
        value += seed;
        value *= (seed >>> 16) | 1;
        value ^= value * 0x0552_6c56;
        value ^= value * 0x53a2_2864;
        return Integer.reverse(value);
    }

    private static float sampleFloat(int value) {
        float scaled = (float) Integer.toUnsignedLong(value) * 0x1p-32F;
        return Math.min(scaled, Math.nextDown(1.0F));
    }

    private static Path shader(String name) {
        return Path.of(
                System.getProperty("prime.test.slangShaderDirectory"), name);
    }
}
