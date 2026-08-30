package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.prime.render.terrain.MediumKey;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MediumIdRegistryTest {
    @Test
    void identitiesAreStableAcrossClusterCatalogOrderAndWaterIsReserved() {
        MediumKey first = new MediumKey(MediumKey.Kind.FAMILY, 7, 0xff80a0c0, false);
        MediumKey second = new MediumKey(MediumKey.Kind.FAMILY, 8, 0xff80a0c0, false);
        MediumIdRegistry registry = new MediumIdRegistry();

        int[] initial = registry.resolve(List.of(MediumKey.CAMERA_WATER, first, second));
        int[] reordered = registry.resolve(List.of(second, MediumKey.CAMERA_WATER, first));

        assertArrayEquals(new int[] {0, 1, 2, 3}, initial);
        assertArrayEquals(new int[] {0, 3, 1, 2}, reordered);
        assertEquals(MediumIdRegistry.WATER_ID, initial[1]);
        assertNotEquals(initial[2], initial[3]);
    }

    @Test
    void snapshotReportsRendererLifetimeHighWater() {
        MediumIdRegistry registry = new MediumIdRegistry();
        assertEquals(new MediumIdRegistry.Snapshot(1, 1L), registry.snapshot());

        registry.resolve(List.of(
                new MediumKey(MediumKey.Kind.FAMILY, 7, 0xff102030, false),
                new MediumKey(MediumKey.Kind.FAMILY, 8, 0xff405060, false)));

        assertEquals(new MediumIdRegistry.Snapshot(3, 3L), registry.snapshot());
    }
}
