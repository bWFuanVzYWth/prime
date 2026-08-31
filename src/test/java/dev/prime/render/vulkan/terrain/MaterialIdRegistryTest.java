package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.terrain.MaterialTableCandidate;
import dev.prime.render.terrain.PrimitivePacking;
import org.junit.jupiter.api.Test;

final class MaterialIdRegistryTest {
    @Test
    void assignsDenseStableRendererLifetimeIds() {
        MaterialIdRegistry registry = new MaterialIdRegistry();
        MaterialTableCandidate.Key first =
                new MaterialTableCandidate.Key(7, null, 0);
        MaterialTableCandidate.Key second =
                new MaterialTableCandidate.Key(8, null, 0);

        assertEquals(1, registry.resolve(first));
        assertEquals(2, registry.resolve(second));
        assertEquals(1, registry.resolve(first));
        assertEquals(new MaterialIdRegistry.Snapshot(2, 2), registry.snapshot());
        assertArrayEquals(
                new int[] {
                    0,
                    MaterialIdRegistry.encodeCore(first),
                    MaterialIdRegistry.encodeCore(second)
                },
                registry.encodedCoreRecords());
        assertEquals(
                7 | PrimitivePacking.CONTROL_NORMAL_TEXTURE << 16,
                MaterialIdRegistry.encodeCore(new MaterialTableCandidate.Key(
                        7,
                        null,
                        PrimitivePacking.CONTROL_NORMAL_TEXTURE)));
    }

    @Test
    void failsBeforeReusingOrTruncatingTheU16IdentityDomain() {
        MaterialIdRegistry registry = new MaterialIdRegistry();
        for (int textureId = 1; textureId <= 0xffff; textureId++) {
            assertEquals(
                    textureId,
                    registry.resolve(new MaterialTableCandidate.Key(textureId, null, 0)));
        }

        assertThrows(
                IllegalStateException.class,
                () -> registry.resolve(new MaterialTableCandidate.Key(
                        1,
                        null,
                        PrimitivePacking.CONTROL_NORMAL_TEXTURE)));
        assertEquals(new MaterialIdRegistry.Snapshot(0xffff, 0xffff), registry.snapshot());
    }
}
