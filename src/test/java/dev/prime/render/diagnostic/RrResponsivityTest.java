// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RrResponsivityTest {
    @Test
    void mapsTheFullApiRangeToAContinuousSlider() {
        assertEquals(-1.0F, RrResponsivity.fromSlider(0.0));
        assertEquals(-0.5F, RrResponsivity.fromSlider(0.25));
        assertEquals(0.0F, RrResponsivity.fromSlider(0.5));
        assertEquals(1.0F, RrResponsivity.fromSlider(1.0));
        assertEquals(0.375, RrResponsivity.toSlider(RrResponsivity.DEFAULT));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> RrResponsivity.requireValid(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> RrResponsivity.requireValid(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> RrResponsivity.requireValid(-1.01F));
        assertThrows(IllegalArgumentException.class,
                () -> RrResponsivity.requireValid(1.01F));
    }
}
