package dev.prime.render.terrain;

import dev.prime.render.data.RendererDataContracts;

/** Source-faithful RGB8/tint translation into the renderer's linear Rec.2020 material domain. */
public final class CanonicalColorEncoding {
    /** Audited maximum absolute error for one source-color page decode plus one tint operator. */
    public static final float MAXIMUM_REFLECTANCE_ERROR = 1.0F / 2048.0F;
    private static final double[][] SRGB_TO_REC2020 = copyMatrix(
            RendererDataContracts.LINEAR_SRGB_TO_LINEAR_REC2020);
    private static final double[][] REC2020_TO_SRGB = inverse(SRGB_TO_REC2020);
    private static final float[] SRGB8_TO_LINEAR = createSrgb8ToLinear();

    private CanonicalColorEncoding() {
    }

    /** Encodes Rec.2020 RGB as binary16 and exact source coverage as the integer-valued A lane. */
    public static long encodeRgba16f(int argb) {
        double sourceRed = decodeSrgb8(argb >>> 16);
        double sourceGreen = decodeSrgb8(argb >>> 8);
        double sourceBlue = decodeSrgb8(argb);
        float red = (float) (
                SRGB_TO_REC2020[0][0] * sourceRed
                        + SRGB_TO_REC2020[0][1] * sourceGreen
                        + SRGB_TO_REC2020[0][2] * sourceBlue);
        float green = (float) (
                SRGB_TO_REC2020[1][0] * sourceRed
                        + SRGB_TO_REC2020[1][1] * sourceGreen
                        + SRGB_TO_REC2020[1][2] * sourceBlue);
        float blue = (float) (
                SRGB_TO_REC2020[2][0] * sourceRed
                        + SRGB_TO_REC2020[2][1] * sourceGreen
                        + SRGB_TO_REC2020[2][2] * sourceBlue);
        return encodeLinearRgba16f(red, green, blue, argb >>> 24);
    }

    /** Encodes bounded linear Rec.2020 reflectance and a raw 0..255 coverage code. */
    public static long encodeLinearRgba16f(
            float red, float green, float blue, float coverageCode) {
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)
                || !Float.isFinite(coverageCode)
                || red < 0.0F || red > 1.0F
                || green < 0.0F || green > 1.0F
                || blue < 0.0F || blue > 1.0F
                || coverageCode < 0.0F || coverageCode > 255.0F) {
            throw new IllegalArgumentException(
                    "Canonical base color exceeds its bounded domain: rgb=("
                            + red + ", " + green + ", " + blue
                            + "), coverage=" + coverageCode);
        }
        int encodedRed = Float.floatToFloat16(red) & 0xffff;
        int encodedGreen = Float.floatToFloat16(green) & 0xffff;
        int encodedBlue = Float.floatToFloat16(blue) & 0xffff;
        int encodedCoverage = Float.floatToFloat16(coverageCode) & 0xffff;
        return Integer.toUnsignedLong(encodedRed)
                | (long) encodedGreen << 16
                | (long) encodedBlue << 32
                | (long) encodedCoverage << 48;
    }

    public static Color decodeRgba16f(long encoded) {
        return new Color(
                Float.float16ToFloat((short) encoded),
                Float.float16ToFloat((short) (encoded >>> 16)),
                Float.float16ToFloat((short) (encoded >>> 32)),
                Float.float16ToFloat((short) (encoded >>> 48)) / 255.0F);
    }

    public static float decodeSrgb8(int code) {
        return SRGB8_TO_LINEAR[code & 0xff];
    }

    /**
     * Builds the exact operator for the RGB lanes of the PrimitiveRecord RGBA8 word.
     *
     * <p>The physical integer is little-lane RGBA8: R occupies bits 0..7, G bits 8..15 and B
     * bits 16..23. It is deliberately not the display-oriented {@code 0xRRGGBB} notation.
     */
    public static TintOperator tintOperator(int packedRgbaRgb) {
        if ((packedRgbaRgb & 0xff00_0000) != 0) {
            throw new IllegalArgumentException("Packed tint exceeds RGB8");
        }
        double red = decodeSrgb8(packedRgbaRgb);
        double green = decodeSrgb8(packedRgbaRgb >>> 8);
        double blue = decodeSrgb8(packedRgbaRgb >>> 16);
        double[] tint = {red, green, blue};
        float[] operator = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                double value = 0.0;
                for (int channel = 0; channel < 3; channel++) {
                    value += SRGB_TO_REC2020[row][channel]
                            * tint[channel]
                            * REC2020_TO_SRGB[channel][column];
                }
                operator[row * 3 + column] = (float) value;
            }
        }
        return new TintOperator(
                operator[0], operator[1], operator[2],
                operator[3], operator[4], operator[5],
                operator[6], operator[7], operator[8]);
    }

    static Color sourcePath(int argb, int packedRgbaRgb) {
        float baseRed = decodeSrgb8(argb >>> 16);
        float baseGreen = decodeSrgb8(argb >>> 8);
        float baseBlue = decodeSrgb8(argb);
        float tintRed = decodeSrgb8(packedRgbaRgb);
        float tintGreen = decodeSrgb8(packedRgbaRgb >>> 8);
        float tintBlue = decodeSrgb8(packedRgbaRgb >>> 16);
        return new Color(
                matrixValue(0, baseRed * tintRed, baseGreen * tintGreen, baseBlue * tintBlue),
                matrixValue(1, baseRed * tintRed, baseGreen * tintGreen, baseBlue * tintBlue),
                matrixValue(2, baseRed * tintRed, baseGreen * tintGreen, baseBlue * tintBlue),
                (argb >>> 24) / 255.0F);
    }

    static double maximumOperatorInfinityNorm() {
        double maximum = 0.0;
        for (int mask = 0; mask < 8; mask++) {
            int tint = ((mask & 1) == 0 ? 0 : 0xff)
                    | ((mask & 2) == 0 ? 0 : 0xff) << 8
                    | ((mask & 4) == 0 ? 0 : 0xff) << 16;
            TintOperator operator = tintOperator(tint);
            maximum = Math.max(maximum, operator.infinityNorm());
        }
        return maximum;
    }

    static float matrixValue(int row, float red, float green, float blue) {
        return (float) SRGB_TO_REC2020[row][0] * red
                + (float) SRGB_TO_REC2020[row][1] * green
                + (float) SRGB_TO_REC2020[row][2] * blue;
    }

    private static float[] createSrgb8ToLinear() {
        float[] result = new float[256];
        for (int code = 0; code < result.length; code++) {
            result[code] = (float) RendererDataContracts.decodeSrgb(code / 255.0);
        }
        return result;
    }

    private static double[][] copyMatrix(double[][] source) {
        if (source.length != 3) {
            throw new IllegalStateException("Renderer color matrix must be 3x3");
        }
        double[][] result = new double[3][3];
        for (int row = 0; row < 3; row++) {
            if (source[row].length != 3) {
                throw new IllegalStateException("Renderer color matrix must be 3x3");
            }
            System.arraycopy(source[row], 0, result[row], 0, 3);
        }
        return result;
    }

    private static double[][] inverse(double[][] value) {
        double a = value[0][0];
        double b = value[0][1];
        double c = value[0][2];
        double d = value[1][0];
        double e = value[1][1];
        double f = value[1][2];
        double g = value[2][0];
        double h = value[2][1];
        double i = value[2][2];
        double determinant = a * (e * i - f * h)
                - b * (d * i - f * g)
                + c * (d * h - e * g);
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1.0e-12) {
            throw new IllegalStateException("Renderer color matrix is not invertible");
        }
        double inverse = 1.0 / determinant;
        return new double[][] {
            {(e * i - f * h) * inverse, (c * h - b * i) * inverse,
                    (b * f - c * e) * inverse},
            {(f * g - d * i) * inverse, (a * i - c * g) * inverse,
                    (c * d - a * f) * inverse},
            {(d * h - e * g) * inverse, (b * g - a * h) * inverse,
                    (a * e - b * d) * inverse}
        };
    }

    public record Color(float red, float green, float blue, float alpha) {
    }

    /** Row-major Rec.2020 operator for one exact source RGB8 tint identity. */
    public record TintOperator(
            float m00,
            float m01,
            float m02,
            float m10,
            float m11,
            float m12,
            float m20,
            float m21,
            float m22) {
        public Color apply(Color color) {
            float red = this.m00 * color.red + this.m01 * color.green + this.m02 * color.blue;
            float green = this.m10 * color.red + this.m11 * color.green + this.m12 * color.blue;
            float blue = this.m20 * color.red + this.m21 * color.green + this.m22 * color.blue;
            return new Color(
                    clampReflectance(red),
                    clampReflectance(green),
                    clampReflectance(blue),
                    color.alpha);
        }

        double infinityNorm() {
            return Math.max(
                    Math.abs(this.m00) + Math.abs(this.m01) + Math.abs(this.m02),
                    Math.max(
                            Math.abs(this.m10) + Math.abs(this.m11) + Math.abs(this.m12),
                            Math.abs(this.m20) + Math.abs(this.m21) + Math.abs(this.m22)));
        }

        private static float clampReflectance(float value) {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }
}
