// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class VulkanRendererDiagnosticFormattingTest {
    @Test
    void integersAndBytesAreNeverAbbreviated() {
        assertEquals("1,500", VulkanRenderer.count(1_500L));
        assertEquals("2,400,000 bytes", VulkanRenderer.bytes(2_400_000L));
        assertEquals(
                "12,345 x 6,789",
                VulkanRenderer.extent(12_345, 6_789));
    }

    @Test
    void finiteFloatFormattingPreservesTheOriginalFloat() {
        float[] values = {
            0.0F,
            -0.0F,
            1.0F,
            -1234.5678F,
            Float.MIN_VALUE,
            Float.MIN_NORMAL,
            Float.MAX_VALUE
        };
        for (float value : values) {
            float restored = Float.parseFloat(VulkanRenderer.scalar(value));
            assertEquals(
                    Float.floatToRawIntBits(value),
                    Float.floatToRawIntBits(restored),
                    () -> "lost float information for " + value);
        }
    }

    @Test
    void nonFiniteFloatStateRemainsExplicit() {
        assertEquals("NaN", VulkanRenderer.scalar(Float.NaN));
        assertEquals("Infinity", VulkanRenderer.scalar(Float.POSITIVE_INFINITY));
        assertEquals("-Infinity", VulkanRenderer.scalar(Float.NEGATIVE_INFINITY));
    }
}
