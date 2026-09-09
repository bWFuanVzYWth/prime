// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.material.CoverageMode;
import dev.prime.render.material.MaterialDetail;
import dev.prime.render.material.MediumHint;
import dev.prime.render.material.PrimitiveControl;
import dev.prime.render.material.ScatteringFamily;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MaterialRecipeResolverTest {
    private static final SpriteId ID = new SpriteId("minecraft", "block/oak_planks");
    private static final CapturedSprite SPRITE = new CapturedSprite(
            ID, 1, 16, 16, true, new int[] {0}, null);

    @Test
    void resolvesPerSpriteAvailabilityWithoutInferringItFromDummyTextures() {
        LabPbrMaterialSet materials = new LabPbrMaterialSet(
                Set.of(ID), Set.of(), Map.of());

        PrimitiveControl control = MaterialRecipeResolver.resolve(
                SPRITE,
                BuiltinMaterialClass.WOOD,
                true,
                false,
                false,
                materials,
                true,
                false,
                false,
                true,
                true);

        assertEquals(CoverageMode.ALPHA_CUTOUT, control.material().coverage());
        assertEquals(ScatteringFamily.OPAQUE, control.material().scattering());
        assertEquals(MediumHint.NONE, control.material().medium());
        assertEquals(BuiltinMaterialClass.WOOD, control.material().builtinClass());
        assertTrue(control.material().hasDetail(MaterialDetail.NORMAL_TEXTURE));
        assertFalse(control.material().hasDetail(MaterialDetail.OPTICAL_TEXTURE));
        assertTrue(control.tangentNegative());
        assertTrue(control.animated());
        assertTrue(control.frontFaceOnly());
    }

    @Test
    void ignoresTangentHandednessWhenNoNormalSpriteExists() {
        PrimitiveControl control = MaterialRecipeResolver.resolve(
                SPRITE,
                BuiltinMaterialClass.DEFAULT,
                false,
                true,
                false,
                new LabPbrMaterialSet(Set.of(), Set.of(ID), Map.of()),
                false,
                true,
                false,
                true,
                false);

        assertEquals(ScatteringFamily.DIELECTRIC_SOLID, control.material().scattering());
        assertEquals(MediumHint.WATER, control.material().medium());
        assertTrue(control.material().hasDetail(MaterialDetail.OPTICAL_TEXTURE));
        assertFalse(control.tangentNegative());
    }

    @Test
    void disablingNormalTexturesPreservesEveryOtherResourcePackChannel() {
        LabPbrEmissionMap emission = LabPbrEmissionMap.fromSpecular(
                new int[] {0x01000000}, 1, 1, 1, 1, 1, 1);
        LabPbrHeightMap height = LabPbrHeightMap.fromNormal(
                new int[] {0x028080ff}, 1, 1, 1, 1, 1, 1);
        LabPbrMaterialMap material = new LabPbrMaterialMap(
                new LabPbrAtlasFrame.MaterialSource(
                        new int[] {0xff8080ff}, 1, 1, 1, 1, 1, 1),
                new LabPbrAtlasFrame.MaterialSource(
                        new int[] {0xff000400}, 1, 1, 1, 1, 1, 1));
        LabPbrMaterialSet source = new LabPbrMaterialSet(
                Map.of(),
                Set.of(ID),
                Set.of(ID),
                Map.of(ID, emission),
                Map.of(ID, height),
                Map.of(ID, material));

        LabPbrMaterialSet filtered = source.withoutNormalTextures();

        assertFalse(filtered.hasNormal(ID));
        assertTrue(filtered.hasSpecular(ID));
        assertEquals(emission, filtered.emissionMap(ID));
        assertEquals(height, filtered.heightMap(ID));
        assertEquals(material, filtered.materialMap(ID));
    }
}
