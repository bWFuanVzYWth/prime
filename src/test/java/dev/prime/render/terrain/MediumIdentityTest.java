package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.scene.CapturedSectionGeometry;
import org.junit.jupiter.api.Test;

final class MediumIdentityTest {
    private static final SurfaceDefinition.UvMapping UV =
            new SurfaceDefinition.UvMapping(0, 0, 1, 0, 1, 1, 0, 1);

    @Test
    void catalogUsesExactTransitiveSourceIdentityAndKeepsParametersSeparate() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("medium_glass", 11);
                SectionMeshAccumulatorTest.TestSprite pane =
                        new SectionMeshAccumulatorTest.TestSprite("medium_pane", 12);
                SectionMeshAccumulatorTest.TestSprite other =
                        new SectionMeshAccumulatorTest.TestSprite("medium_other", 13)) {
            CapturedSectionGeometry.Surface familyGlass = surface(glass, 0xff80a0c0, 7);
            CapturedSectionGeometry.Surface familyPane = surface(pane, 0xff80a0c0, 7);
            CapturedSectionGeometry.Surface otherFamily = surface(glass, 0xff80a0c0, 8);
            CapturedSectionGeometry.Surface textureGlass = surface(glass, 0xff80a0c0, 0);
            CapturedSectionGeometry.Surface sameTexture = surface(glass, 0xff80a0c0, 0);
            CapturedSectionGeometry.Surface otherTexture = surface(other, 0xff80a0c0, 0);
            CapturedSectionGeometry.Surface otherTint = surface(glass, 0xff80a0c1, 0);

            MediumCatalog catalog = new MediumCatalog();
            int familyId = catalog.resolve(binding(familyGlass, TransmissiveTopology.SOLID));
            assertEquals(
                    familyId,
                    catalog.resolve(binding(familyPane, TransmissiveTopology.SOLID)));
            assertNotEquals(
                    familyId,
                    catalog.resolve(binding(otherFamily, TransmissiveTopology.SOLID)));
            int textureId = catalog.resolve(binding(textureGlass, TransmissiveTopology.SOLID));
            assertEquals(
                    textureId,
                    catalog.resolve(binding(sameTexture, TransmissiveTopology.SOLID)));
            assertNotEquals(
                    textureId,
                    catalog.resolve(binding(otherTexture, TransmissiveTopology.SOLID)));
            assertNotEquals(
                    textureId,
                    catalog.resolve(binding(otherTint, TransmissiveTopology.SOLID)));
            assertNotEquals(familyId, textureId);
            assertEquals(0, catalog.resolve(binding(textureGlass, TransmissiveTopology.THIN_SHEET)));
        }
    }

    @Test
    void rendererRemapCoversPrimitiveAndBoundaryReferencesWithoutTouchingInput() {
        int[] localToRenderer = {0, 0x1020_3040, 0xfedc_ba98};
        int[] primitives = new int[2 * CpuSectionMesh.PRIMITIVE_WORDS];
        int solidFlags = PrimitivePacking.encodeLegacySemantics(
                false, false, true, false, false, false);
        primitives[3] = PrimitivePacking.packTintControl(0, solidFlags);
        primitives[5] = PrimitivePacking.packControlTexture(solidFlags, 1);
        primitives[PrimitivePacking.MEDIUM_ID_WORD] = 1;
        primitives[CpuSectionMesh.PRIMITIVE_WORDS + 3] =
                PrimitivePacking.packTintControl(0, solidFlags);
        primitives[CpuSectionMesh.PRIMITIVE_WORDS + 5] =
                PrimitivePacking.packControlTexture(solidFlags, 1);
        primitives[CpuSectionMesh.PRIMITIVE_WORDS + PrimitivePacking.MEDIUM_ID_WORD] = 2;

        int[] remappedPrimitives =
                MediumIdResolver.primitiveRecords(primitives, localToRenderer);

        assertNotSame(primitives, remappedPrimitives);
        assertEquals(1, primitives[PrimitivePacking.MEDIUM_ID_WORD]);
        assertEquals(0x1020_3040, remappedPrimitives[PrimitivePacking.MEDIUM_ID_WORD]);
        assertEquals(
                0xfedc_ba98,
                remappedPrimitives[
                        CpuSectionMesh.PRIMITIVE_WORDS + PrimitivePacking.MEDIUM_ID_WORD]);

        int[] boundary = {
            1,
            CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
            0,
            0,
            1,
            2
        };
        int[] remappedBoundary =
                MediumIdResolver.surfaceRelations(boundary, 1, localToRenderer);
        assertArrayEquals(
                new int[] {
                    1,
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
                    0,
                    0,
                    1,
                    0xfedc_ba98
                },
                remappedBoundary);
        assertEquals(2, boundary[5]);

        int[] vacuum = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        assertSame(vacuum, MediumIdResolver.primitiveRecords(vacuum, new int[] {0}));
        primitives[PrimitivePacking.MEDIUM_ID_WORD] = 3;
        assertThrows(
                IllegalArgumentException.class,
                () -> MediumIdResolver.primitiveRecords(primitives, localToRenderer));
        int[] missingIdentity = primitives.clone();
        missingIdentity[PrimitivePacking.MEDIUM_ID_WORD] = 0;
        assertThrows(
                IllegalArgumentException.class,
                () -> MediumIdResolver.primitiveRecords(
                        missingIdentity, localToRenderer));
    }

    private static SurfaceDefinition.MaterialBinding binding(
            CapturedSectionGeometry.Surface surface,
            TransmissiveTopology topology) {
        return new SurfaceDefinition.MaterialBinding(surface, UV, topology);
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int tint,
            int mediumFamily) {
        return CapturedSectionGeometry.Surface.uniform(
                tint,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                0,
                sprite.sprite(),
                new CapturedSectionGeometry.BlockFacts(0, 0, 0, mediumFamily));
    }
}
