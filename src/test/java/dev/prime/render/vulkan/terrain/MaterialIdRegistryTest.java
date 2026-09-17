// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.terrain.MaterialKey;
import dev.prime.render.terrain.MediumKey;
import dev.prime.render.terrain.PrimitivePacking;
import org.junit.jupiter.api.Test;

final class MaterialIdRegistryTest {
    @Test void shadowCompilationSelectsOnlyMaterialBackedGlass() {
        MaterialIdRegistry registry = new MaterialIdRegistry(new MediumIdRegistry());
        int opaque = registry.resolve(new MaterialKey(1, null, 0));
        int thin = registry.resolve(new MaterialKey(2, null, PrimitivePacking.CONTROL_DIELECTRIC_THIN));
        int glass = registry.resolve(new MaterialKey(3,
                new MediumKey(MediumKey.Kind.TEXTURE, 3, 0, false), PrimitivePacking.CONTROL_DIELECTRIC_SOLID));
        int water = registry.resolve(new MaterialKey(4, MediumKey.CAMERA_WATER,
                PrimitivePacking.CONTROL_DIELECTRIC_SOLID | PrimitivePacking.CONTROL_WATER_MEDIUM));
        assertEquals(0, registry.shadowTexture(65535));
        assertEquals(0, registry.shadowTexture(opaque << 16));
        assertEquals(2, registry.shadowTexture(thin << 16 | 65535));
        assertEquals(3, registry.shadowTexture(glass << 16 | 1));
        assertEquals(0, registry.shadowTexture(water << 16));
    }
    @Test
    void assignsDenseStableRendererLifetimeIds() {
        MediumIdRegistry mediumIds = new MediumIdRegistry();
        MaterialIdRegistry registry = new MaterialIdRegistry(mediumIds);
        MaterialKey first = new MaterialKey(7, null, 0);
        MaterialKey second = new MaterialKey(8, null, 0);
        MediumKey glass = new MediumKey(MediumKey.Kind.TEXTURE, 9, 0, false);
        MaterialKey transmissive = new MaterialKey(
                9, glass, PrimitivePacking.CONTROL_DIELECTRIC_SOLID);

        assertEquals(1, registry.resolve(first));
        assertEquals(2, registry.resolve(second));
        assertEquals(3, registry.resolve(transmissive));
        assertEquals(1, registry.resolve(first));
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
                MaterialIdRegistry.encodeCoreWord(new MaterialKey(
                        7,
                        null,
                        PrimitivePacking.CONTROL_NORMAL_TEXTURE)));
    }

    @Test
    void appendUploadLeavesPublishedIdsUntouchedAndCanBeRetried() {
        MaterialIdRegistry registry = new MaterialIdRegistry(new MediumIdRegistry());
        registry.resolve(new MaterialKey(1, null, 0));
        int published = registry.recordCount();
        int[] prefix = registry.encodedCoreRecords();
        assertEquals(1, registry.resolve(new MaterialKey(1, null, 0)));
        assertArrayEquals(new int[0], registry.encodedCoreRecords(published));
        registry.resolve(new MaterialKey(2, null, 0));
        int[] append = registry.encodedCoreRecords(published);
        assertArrayEquals(new int[] {2, 0}, append);
        assertArrayEquals(append, registry.encodedCoreRecords(published));
        assertArrayEquals(prefix, java.util.Arrays.copyOf(registry.encodedCoreRecords(), prefix.length));
    }

    @Test
    void failsBeforeReusingOrTruncatingTheU16IdentityDomain() {
        MaterialIdRegistry registry = new MaterialIdRegistry(new MediumIdRegistry());
        for (int textureId = 1; textureId <= 0xffff; textureId++) {
            assertEquals(
                    textureId,
                    registry.resolve(new MaterialKey(textureId, null, 0)));
        }

        assertThrows(
                IllegalStateException.class,
                () -> registry.resolve(new MaterialKey(
                        1,
                        null,
                        PrimitivePacking.CONTROL_NORMAL_TEXTURE)));
    }
}
