package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.terrain.CanonicalColorEncoding;
import org.junit.jupiter.api.Test;

final class TintOperatorTableEncodingTest {
    @Test
    void tableRowsPreserveOperatorAndCacheItsExactWhiteInput() {
        int packedRgb = 0x0023_a7e1;
        CanonicalColorEncoding.TintOperator operator =
                CanonicalColorEncoding.tintOperator(packedRgb);
        float[] entry = TintOperatorTable.encodedEntry(packedRgb);
        CanonicalColorEncoding.Color tint = operator.apply(
                new CanonicalColorEncoding.Color(1.0F, 1.0F, 1.0F, 1.0F));

        assertEquals(TintOperatorTable.ENTRY_SIZE / Float.BYTES, entry.length);
        assertArrayEquals(
                new float[] {
                    operator.m00(), operator.m01(), operator.m02(),
                    tint.red(),
                    operator.m10(), operator.m11(), operator.m12(),
                    tint.green(),
                    operator.m20(), operator.m21(), operator.m22(),
                    tint.blue()
                },
                entry);
    }

    @Test
    void whiteOperatorIsIdentityAndWhiteConstant() {
        assertArrayEquals(
                new float[] {
                    1.0F, 0.0F, 0.0F, 1.0F,
                    0.0F, 1.0F, 0.0F, 1.0F,
                    0.0F, 0.0F, 1.0F, 1.0F
                },
                TintOperatorTable.encodedEntry(0x00ff_ffff),
                2.0e-6F);
    }
}
