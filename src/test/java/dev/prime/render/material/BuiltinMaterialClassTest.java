package dev.prime.render.material;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class BuiltinMaterialClassTest {
    @Test
    void decodesDeclaredIdsAndRejectsReservedAndOutOfRangeValues() {
        for (BuiltinMaterialClass value : BuiltinMaterialClass.values()) {
            assertSame(value, BuiltinMaterialClass.fromId(value.id()));
        }
        for (int id : new int[] {Integer.MIN_VALUE, -1, 13, 14, 15, 16, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> BuiltinMaterialClass.fromId(id));
        }
    }
}
