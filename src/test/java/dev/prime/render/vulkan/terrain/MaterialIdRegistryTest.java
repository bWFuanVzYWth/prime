package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.terrain.MaterialTableCandidate;
import dev.prime.render.terrain.MediumKey;
import dev.prime.render.terrain.PrimitivePacking;
import org.junit.jupiter.api.Test;

final class MaterialIdRegistryTest {
    @Test
    void assignsDenseStableRendererLifetimeIds() {
        MediumIdRegistry mediumIds = new MediumIdRegistry();
        MaterialIdRegistry registry = new MaterialIdRegistry(mediumIds);
        MaterialTableCandidate.Key first =
                new MaterialTableCandidate.Key(7, null, 0);
        MaterialTableCandidate.Key second =
                new MaterialTableCandidate.Key(8, null, 0);
        MediumKey glass = new MediumKey(MediumKey.Kind.TEXTURE, 9, 0, false);
        MaterialTableCandidate.Key transmissive = new MaterialTableCandidate.Key(
                9, glass, PrimitivePacking.CONTROL_DIELECTRIC_SOLID);

        assertEquals(1, registry.resolve(first));
        assertEquals(2, registry.resolve(second));
        assertEquals(3, registry.resolve(transmissive));
        assertEquals(1, registry.resolve(first));
        assertEquals(new MaterialIdRegistry.Snapshot(3, 3), registry.snapshot());
        assertArrayEquals(
                new int[] {
                    0,
                    0,
                    MaterialIdRegistry.encodeCoreWord(first),
                    0,
                    MaterialIdRegistry.encodeCoreWord(second),
                    0,
                    MaterialIdRegistry.encodeCoreWord(transmissive),
                    mediumIds.resolve(glass)
                },
                registry.encodedCoreRecords());
        assertEquals(
                7 | PrimitivePacking.CONTROL_NORMAL_TEXTURE << 16,
                MaterialIdRegistry.encodeCoreWord(new MaterialTableCandidate.Key(
                        7,
                        null,
                        PrimitivePacking.CONTROL_NORMAL_TEXTURE)));
    }

    @Test
    void failsBeforeReusingOrTruncatingTheU16IdentityDomain() {
        MaterialIdRegistry registry = new MaterialIdRegistry(new MediumIdRegistry());
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
