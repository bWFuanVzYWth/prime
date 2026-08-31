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
        int red = Float.floatToFloat16((float) (
                SRGB_TO_REC2020[0][0] * sourceRed
                        + SRGB_TO_REC2020[0][1] * sourceGreen
                        + SRGB_TO_REC2020[0][2] * sourceBlue)) & 0xffff;
        int green = Float.floatToFloat16((float) (
                SRGB_TO_REC2020[1][0] * sourceRed
                        + SRGB_TO_REC2020[1][1] * sourceGreen
                        + SRGB_TO_REC2020[1][2] * sourceBlue)) & 0xffff;
        int blue = Float.floatToFloat16((float) (
                SRGB_TO_REC2020[2][0] * sourceRed
                        + SRGB_TO_REC2020[2][1] * sourceGreen
                        + SRGB_TO_REC2020[2][2] * sourceBlue)) & 0xffff;
        int coverage = Float.floatToFloat16((float) (argb >>> 24)) & 0xffff;
        return Integer.toUnsignedLong(red)
                | (long) green << 16
                | (long) blue << 32
                | (long) coverage << 48;
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

    public static TintOperator tintOperator(int packedRgb) {
        if ((packedRgb & 0xff00_0000) != 0) {
            throw new IllegalArgumentException("Packed tint exceeds RGB8");
        }
        double red = decodeSrgb8(packedRgb >>> 16);
        double green = decodeSrgb8(packedRgb >>> 8);
        double blue = decodeSrgb8(packedRgb);
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

    static Color sourcePath(int argb, int packedRgb) {
        float baseRed = decodeSrgb8(argb >>> 16);
        float baseGreen = decodeSrgb8(argb >>> 8);
        float baseBlue = decodeSrgb8(argb);
        float tintRed = decodeSrgb8(packedRgb >>> 16);
        float tintGreen = decodeSrgb8(packedRgb >>> 8);
        float tintBlue = decodeSrgb8(packedRgb);
        return new Color(
                matrixValue(0, baseRed * tintRed, baseGreen * tintGreen, baseBlue * tintBlue),
                matrixValue(1, baseRed * tintRed, baseGreen * tintGreen, baseBlue * tintBlue),
                matrixValue(2, baseRed * tintRed, baseGreen * tintGreen, baseBlue * tintBlue),
                (argb >>> 24) / 255.0F);
    }

    static double maximumOperatorInfinityNorm() {
        double maximum = 0.0;
        for (int mask = 0; mask < 8; mask++) {
            int tint = ((mask & 1) == 0 ? 0 : 0xff) << 16
                    | ((mask & 2) == 0 ? 0 : 0xff) << 8
                    | ((mask & 4) == 0 ? 0 : 0xff);
            TintOperator operator = tintOperator(tint);
            maximum = Math.max(maximum, operator.infinityNorm());
        }
        return maximum;
    }

    private static float matrixValue(int row, float red, float green, float blue) {
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
