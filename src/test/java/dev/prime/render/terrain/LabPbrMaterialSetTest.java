package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.scene.SpriteId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LabPbrMaterialSetTest {
    private static final SpriteId SPRITE = new SpriteId("test", "surface");

    @Test
    void normalPixelsDoNotInvalidateNormalMappedTerrain() {
        LabPbrMaterialSet first = materials(
                Set.of(SPRITE), Set.of(), height(1), material(1));
        LabPbrMaterialSet replacement = materials(
                Set.of(SPRITE), Set.of(), height(2), material(2));

        assertTrue(first.translationEquivalent(
                replacement, SurfaceDetailMode.RESOURCE_NORMAL));
        assertFalse(first.invalidatesResidentTextureLookups(replacement));
    }

    @Test
    void displacementPixelsRemainPartOfTheMeshContract() {
        LabPbrMaterialSet first = materials(
                Set.of(), Set.of(), height(1), material(1));
        LabPbrMaterialSet replacement = materials(
                Set.of(), Set.of(), height(2), material(2));

        assertFalse(first.translationEquivalent(
                replacement, SurfaceDetailMode.GEOMETRIC_DISPLACEMENT));
    }

    @Test
    void removingAReferencedMapRequiresResidentEviction() {
        LabPbrMaterialSet first = materials(
                Set.of(SPRITE), Set.of(SPRITE), height(1), material(1));
        LabPbrMaterialSet replacement = materials(
                Set.of(), Set.of(), height(1), material(1));

        assertTrue(first.invalidatesResidentTextureLookups(replacement));
        assertFalse(replacement.invalidatesResidentTextureLookups(first));
    }

    @Test
    void extendingTheStableCatalogDoesNotInvalidateExistingPrimitives() {
        SpriteId added = new SpriteId("test", "added");
        LabPbrMaterialSet first = materials(
                Set.of(), Set.of(), height(1), material(1));
        LabPbrMaterialSet replacement = new LabPbrMaterialSet(
                Map.of(SPRITE, 1, added, 2),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(SPRITE, height(1)),
                Map.of(SPRITE, material(1)));

        assertTrue(first.translationEquivalent(replacement, SurfaceDetailMode.NONE));
        assertFalse(first.invalidatesResidentTextureLookups(replacement));
    }

    private static LabPbrMaterialSet materials(
            Set<SpriteId> normals,
            Set<SpriteId> optical,
            LabPbrHeightMap height,
            LabPbrMaterialMap material) {
        return new LabPbrMaterialSet(
                Map.of(SPRITE, 1),
                normals,
                optical,
                Map.of(),
                Map.of(SPRITE, height),
                Map.of(SPRITE, material));
    }

    private static LabPbrHeightMap height(int value) {
        return LabPbrHeightMap.fromNormal(
                new int[] {value << 24 | 0x008080ff}, 1, 1, 1, 1, 1, 1);
    }

    private static LabPbrMaterialMap material(int value) {
        return new LabPbrMaterialMap(
                new LabPbrAtlasFrame.MaterialSource(
                        new int[] {value}, 1, 1, 1, 1, 1, 1),
                new LabPbrAtlasFrame.MaterialSource(
                        new int[] {value}, 1, 1, 1, 1, 1, 1));
    }
}
