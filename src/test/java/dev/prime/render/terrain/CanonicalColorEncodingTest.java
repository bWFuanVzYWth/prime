package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.data.RendererDataContracts;
import org.junit.jupiter.api.Test;

final class CanonicalColorEncodingTest {
    private static final int[] CODES = {
        0, 1, 2, 4, 8, 16, 32, 64, 96, 128, 160, 192, 224, 240, 254, 255
    };
    private static final int[] TINT_RGB = {
        0x0000_0000,
        0x00ff_ffff,
        0x00ff_0000,
        0x0000_ff00,
        0x0000_00ff,
        0x0091_bd59,
        0x003f_76e4,
        0x00b0_2e26,
        0x00f9_fffe,
        0x0012_3456,
        0x00aa_7733
    };

    @Test
    void everySourceCodeUsesTheRendererSrgbContract() {
        for (int code = 0; code < 256; code++) {
            assertEquals(
                    (float) RendererDataContracts.decodeSrgb(code / 255.0),
                    CanonicalColorEncoding.decodeSrgb8(code));
        }
    }

    @Test
    void integerValuedHalfCoveragePreservesEverySourceCodeExactly() {
        for (int alpha = 0; alpha < 256; alpha++) {
            long encoded = CanonicalColorEncoding.encodeRgba16f(alpha << 24);
            CanonicalColorEncoding.Color decoded =
                    CanonicalColorEncoding.decodeRgba16f(encoded);

            assertEquals(alpha / 255.0F, decoded.alpha());
        }
    }

    @Test
    void halfBaseAndTintOperatorStayWithinTheAuditedReflectanceError() {
        float maximum = 0.0F;
        for (int tintRgb : TINT_RGB) {
            int tint = packedTint(tintRgb);
            CanonicalColorEncoding.TintOperator operator =
                    CanonicalColorEncoding.tintOperator(tint);
            for (int red : CODES) {
                for (int green : CODES) {
                    for (int blue : CODES) {
                        int argb = 0xff00_0000 | red << 16 | green << 8 | blue;
                        CanonicalColorEncoding.Color expected =
                                CanonicalColorEncoding.sourcePath(argb, tint);
                        CanonicalColorEncoding.Color actual = operator.apply(
                                CanonicalColorEncoding.decodeRgba16f(
                                        CanonicalColorEncoding.encodeRgba16f(argb)));
                        maximum = Math.max(maximum, error(expected, actual));
                    }
                }
            }
        }

        assertTrue(maximum <= CanonicalColorEncoding.MAXIMUM_REFLECTANCE_ERROR,
                "Maximum canonical color error was " + maximum);
    }

    @Test
    void analyticHalfErrorBoundCoversEveryRgb8Tint() {
        double operatorNorm = CanonicalColorEncoding.maximumOperatorInfinityNorm();
        double halfRoundError = Math.scalb(1.0, -12);
        double floatOperatorAllowance = Math.scalb(1.0, -22);

        assertTrue(operatorNorm < 1.5);
        assertTrue(
                operatorNorm * halfRoundError + floatOperatorAllowance
                        < CanonicalColorEncoding.MAXIMUM_REFLECTANCE_ERROR);
    }

    @Test
    void linearMipFilteringCommutesWithCanonicalConversionAndTint() {
        int[] texels = {0xff00_2040, 0xff80_ffff, 0xffff_4000, 0xff04_0810};
        int tint = packedTint(0x0091_bd59);
        CanonicalColorEncoding.TintOperator operator =
                CanonicalColorEncoding.tintOperator(tint);
        CanonicalColorEncoding.Color expected = averageSource(texels, tint);
        CanonicalColorEncoding.Color canonical = operator.apply(averageCanonical(texels));

        assertTrue(error(expected, canonical)
                <= CanonicalColorEncoding.MAXIMUM_REFLECTANCE_ERROR);
    }

    @Test
    void minecraftArgbTintKeepsRedAndBlueAcrossThePrimitiveRgba8Abi() {
        int grassArgb = 0xff91_bd59;
        int packedTint = PrimitivePacking.packTint(grassArgb) & 0x00ff_ffff;
        CanonicalColorEncoding.Color actual = CanonicalColorEncoding.tintOperator(packedTint)
                .apply(new CanonicalColorEncoding.Color(1.0F, 1.0F, 1.0F, 1.0F));
        CanonicalColorEncoding.Color expected = CanonicalColorEncoding.sourcePath(
                0xffff_ffff, packedTint);

        assertEquals(0x0059_bd91, packedTint);
        assertEquals(expected.red(), actual.red());
        assertEquals(expected.green(), actual.green());
        assertEquals(expected.blue(), actual.blue());
        assertTrue(actual.red() > actual.blue());
    }

    private static CanonicalColorEncoding.Color averageSource(int[] texels, int tint) {
        float red = 0.0F;
        float green = 0.0F;
        float blue = 0.0F;
        float alpha = 0.0F;
        for (int texel : texels) {
            CanonicalColorEncoding.Color color =
                    CanonicalColorEncoding.sourcePath(texel, tint);
            red += color.red();
            green += color.green();
            blue += color.blue();
            alpha += color.alpha();
        }
        return new CanonicalColorEncoding.Color(
                red / texels.length,
                green / texels.length,
                blue / texels.length,
                alpha / texels.length);
    }

    private static CanonicalColorEncoding.Color averageCanonical(int[] texels) {
        float red = 0.0F;
        float green = 0.0F;
        float blue = 0.0F;
        float alpha = 0.0F;
        for (int texel : texels) {
            CanonicalColorEncoding.Color color = CanonicalColorEncoding.decodeRgba16f(
                    CanonicalColorEncoding.encodeRgba16f(texel));
            red += color.red();
            green += color.green();
            blue += color.blue();
            alpha += color.alpha();
        }
        return new CanonicalColorEncoding.Color(
                red / texels.length,
                green / texels.length,
                blue / texels.length,
                alpha / texels.length);
    }

    private static float error(
            CanonicalColorEncoding.Color expected,
            CanonicalColorEncoding.Color actual) {
        return Math.max(
                Math.abs(expected.red() - actual.red()),
                Math.max(
                        Math.abs(expected.green() - actual.green()),
                        Math.abs(expected.blue() - actual.blue())));
    }

    private static int packedTint(int rgb) {
        return PrimitivePacking.packTint(0xff00_0000 | rgb) & 0x00ff_ffff;
    }
}
