package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class PrimeNumericalGpuTest {
    private static final long SEED = 0x4E55_4D45_5249_4301L;
    private static final int INPUT_WORDS = 2;
    private static final int WITNESS_WORDS = 4;

    private static final int NAN = 1;
    private static final int POSITIVE_INFINITY = 2;
    private static final int NEGATIVE_INFINITY = 4;
    private static final int FINITE_NEGATIVE = 8;
    private static final int ABOVE_FP16 = 16;
    private static final int ABOVE_UNIT = 128;
    private static final int INVALID_DIRECTION = 256;

    private static final int[] SCALAR_BITS = {
        0x0000_0000,
        0x8000_0000,
        0x0000_0001,
        0x8000_0001,
        0x3f00_0000,
        0xbf00_0000,
        0x3f80_0000,
        0xbf80_0000,
        0x3f80_0001,
        0x3f7f_ffff,
        0x477f_e000,
        0x477f_e001,
        0x7f7f_ffff,
        0xff7f_ffff,
        0x7f80_0000,
        0xff80_0000,
        0x7fc0_0001,
        0xffc0_0001
    };

    private static final int[][] DIRECTIONS = {
        {0x0000_0000, 0x0000_0000, 0x0000_0000},
        {0x8000_0000, 0x8000_0000, 0x8000_0000},
        {0x3f80_0000, 0x0000_0000, 0x0000_0000},
        {0x0000_0000, 0xbf80_0000, 0x0000_0000},
        {0x3f19_999a, 0x3f4c_cccd, 0x0000_0000},
        {0x33d6_bf95, 0x0000_0000, 0x0000_0000},
        {0x4000_0000, 0x0000_0000, 0x0000_0000},
        {0x7f7f_ffff, 0x7f7f_ffff, 0x7f7f_ffff},
        {0x7f80_0000, 0x0000_0000, 0x0000_0000},
        {0x0000_0000, 0xff80_0000, 0x0000_0000},
        {0x0000_0000, 0x0000_0000, 0x7fc0_0001},
        {0x3f80_0001, 0x0000_0000, 0x0000_0000}
    };

    private static ShaderComputeRunner runner;

    @Test
    void productionClassifiersRecognizeEveryNonFiniteSignAndNumericDomain()
            throws IOException {
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "prime_numerical_properties.comp.spv");
        List<Case> cases = cases();
        ByteBuffer input = ShaderTestBuffer.inputs(cases.size(), INPUT_WORDS);
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            Case testCase = cases.get(caseIndex);
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 0, testCase.kind());
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 1, testCase.expected());
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 2, testCase.alternative());
            for (int component = 0; component < 3; component++) {
                ShaderTestBuffer.putInt(
                        input,
                        caseIndex,
                        INPUT_WORDS,
                        1,
                        component,
                        testCase.bits()[component]);
            }
        }

        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                input,
                cases.size(),
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        for (int bits : SCALAR_BITS) {
            float value = Float.intBitsToFloat(bits);
            float ftzValue = flushSubnormal(value);
            cases.add(new Case(
                    0, classifyNonFinite(value), classifyNonFinite(ftzValue), bits, 0, 0));
            cases.add(new Case(
                    2, classifyNonnegative(value), classifyNonnegative(ftzValue), bits, 0, 0));
            cases.add(new Case(
                    4, classifyRadiance(value), classifyRadiance(ftzValue), bits, 0, 0));
            cases.add(new Case(
                    6, classifyUnit(value), classifyUnit(ftzValue), bits, 0, 0));
        }
        for (int index = 0; index < SCALAR_BITS.length; index++) {
            int x = SCALAR_BITS[index];
            int y = SCALAR_BITS[(index * 5 + 3) % SCALAR_BITS.length];
            int z = SCALAR_BITS[(index * 11 + 7) % SCALAR_BITS.length];
            float fx = Float.intBitsToFloat(x);
            float fy = Float.intBitsToFloat(y);
            float fz = Float.intBitsToFloat(z);
            float ftzX = flushSubnormal(fx);
            float ftzY = flushSubnormal(fy);
            float ftzZ = flushSubnormal(fz);
            cases.add(new Case(
                    1,
                    classifyNonFinite(fx, fy, fz),
                    classifyNonFinite(ftzX, ftzY, ftzZ),
                    x,
                    y,
                    z));
            cases.add(new Case(
                    3,
                    classifyNonnegative(fx, fy, fz),
                    classifyNonnegative(ftzX, ftzY, ftzZ),
                    x,
                    y,
                    z));
            cases.add(new Case(
                    5,
                    classifyRadiance(fx, fy, fz),
                    classifyRadiance(ftzX, ftzY, ftzZ),
                    x,
                    y,
                    z));
            cases.add(new Case(
                    7,
                    classifyUnit(fx, fy, fz),
                    classifyUnit(ftzX, ftzY, ftzZ),
                    x,
                    y,
                    z));
        }
        for (int[] bits : DIRECTIONS) {
            float x = Float.intBitsToFloat(bits[0]);
            float y = Float.intBitsToFloat(bits[1]);
            float z = Float.intBitsToFloat(bits[2]);
            int direction = classifyDirection(x, y, z);
            cases.add(new Case(8, direction, direction, bits[0], bits[1], bits[2]));
            int optional = x == 0.0F && y == 0.0F && z == 0.0F ? 0 : direction;
            cases.add(new Case(9, optional, optional, bits[0], bits[1], bits[2]));
        }
        return cases;
    }

    private static int classifyNonFinite(float value) {
        if (Float.isNaN(value)) {
            return NAN;
        }
        if (!Float.isInfinite(value)) {
            return 0;
        }
        return value > 0.0F ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
    }

    private static int classifyNonFinite(float x, float y, float z) {
        return classifyNonFinite(x) | classifyNonFinite(y) | classifyNonFinite(z);
    }

    private static int classifyNonnegative(float value) {
        int flags = classifyNonFinite(value);
        return flags == 0 && value < 0.0F ? FINITE_NEGATIVE : flags;
    }

    private static int classifyNonnegative(float x, float y, float z) {
        return classifyNonnegative(x) | classifyNonnegative(y) | classifyNonnegative(z);
    }

    private static int classifyRadiance(float value) {
        int flags = classifyNonnegative(value);
        if (flags == 0 && value > 65_504.0F) {
            flags |= ABOVE_FP16;
        }
        return flags;
    }

    private static int classifyRadiance(float x, float y, float z) {
        return classifyRadiance(x) | classifyRadiance(y) | classifyRadiance(z);
    }

    private static int classifyUnit(float value) {
        int flags = classifyNonnegative(value);
        return flags == 0 && value > 1.0F ? ABOVE_UNIT : flags;
    }

    private static int classifyUnit(float x, float y, float z) {
        return classifyUnit(x) | classifyUnit(y) | classifyUnit(z);
    }

    private static int classifyDirection(float x, float y, float z) {
        int flags = classifyNonFinite(x, y, z);
        if (flags != 0) {
            return flags;
        }
        float lengthSquared = x * x + y * y + z * z;
        return !Float.isFinite(lengthSquared)
                        || !(lengthSquared > 1.0e-12F)
                        || Math.abs(lengthSquared - 1.0F) > 1.0e-3F
                ? INVALID_DIRECTION
                : 0;
    }

    private static float flushSubnormal(float value) {
        return Math.abs(value) < Float.MIN_NORMAL ? Math.copySign(0.0F, value) : value;
    }

    private record Case(int kind, int expected, int alternative, int[] bits) {
        private Case(int kind, int expected, int alternative, int x, int y, int z) {
            this(kind, expected, alternative, new int[] {x, y, z});
        }
    }
}
