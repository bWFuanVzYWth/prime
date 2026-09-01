package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.terrain.CanonicalColorEncoding;
import dev.prime.render.terrain.PrimitivePacking;
import org.junit.jupiter.api.Test;

final class TintSampleTableEncodingTest {
    @Test
    void tableEntryPreservesSourceLinearRgbaAsBinary16() {
        int argb = 0xa023_a7e1;
        int packedRgba = PrimitivePacking.packTint(argb);
        long entry = TintSampleTable.encodedEntry(packedRgba);

        assertEquals(Long.BYTES, TintSampleTable.ENTRY_SIZE);
        assertEquals(
                CanonicalColorEncoding.encodeLinearSrgbTintRgba16f(argb),
                entry);
        CanonicalColorEncoding.LinearTintModulation decoded =
                CanonicalColorEncoding.decodeLinearSrgbTintRgba16f(entry);
        assertEquals(CanonicalColorEncoding.decodeSrgb8(0x23), decoded.red(), 2.0e-4F);
        assertEquals(CanonicalColorEncoding.decodeSrgb8(0xa7), decoded.green(), 2.0e-4F);
        assertEquals(CanonicalColorEncoding.decodeSrgb8(0xe1), decoded.blue(), 2.0e-4F);
        assertEquals(0xa0 / 255.0F, decoded.alpha(), 2.0e-4F);
    }

    @Test
    void opaqueWhiteUsesTheIdentitySample() {
        CanonicalColorEncoding.LinearTintModulation decoded =
                CanonicalColorEncoding.decodeLinearSrgbTintRgba16f(
                        TintSampleTable.encodedEntry(0xffff_ffff));

        assertEquals(1.0F, decoded.red());
        assertEquals(1.0F, decoded.green());
        assertEquals(1.0F, decoded.blue());
        assertEquals(1.0F, decoded.alpha());
    }
}
