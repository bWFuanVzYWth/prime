// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import dev.prime.render.material.CoverageMode;
import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.material.MaterialDetail;
import dev.prime.render.material.MaterialRecipe;
import dev.prime.render.material.MediumHint;
import dev.prime.render.material.PrimitiveControl;
import dev.prime.render.material.ScatteringFamily;
import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import java.util.Objects;

/** Pure translation from captured surface facts and resource availability to canonical recipes. */
final class MaterialRecipeResolver {
    private MaterialRecipeResolver() {
    }

    static PrimitiveControl resolve(
            CapturedSectionGeometry.Surface surface,
            LabPbrMaterialSet materials,
            boolean cutout,
            boolean transmissive,
            boolean thinWalled,
            boolean tangentNegative,
            boolean frontFaceOnly) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(materials, "materials");
        return resolve(
                surface.sprite(),
                surface.builtinMaterialClass(),
                surface.animated(),
                surface.water(),
                surface.foliage(),
                materials,
                cutout,
                transmissive,
                thinWalled,
                tangentNegative,
                frontFaceOnly);
    }

    static PrimitiveControl resolve(
            CapturedSprite sprite,
            BuiltinMaterialClass builtinMaterialClass,
            boolean animated,
            boolean water,
            boolean foliage,
            LabPbrMaterialSet materials,
            boolean cutout,
            boolean transmissive,
            boolean thinWalled,
            boolean tangentNegative,
            boolean frontFaceOnly) {
        Objects.requireNonNull(sprite, "sprite");
        Objects.requireNonNull(builtinMaterialClass, "builtinMaterialClass");
        Objects.requireNonNull(materials, "materials");
        ScatteringFamily scattering = foliage
                ? ScatteringFamily.FOLIAGE_THIN
                : transmissive
                        ? thinWalled
                                ? ScatteringFamily.DIELECTRIC_THIN
                                : ScatteringFamily.DIELECTRIC_SOLID
                        : ScatteringFamily.OPAQUE;
        MediumHint medium = water
                ? MediumHint.WATER
                : transmissive ? MediumHint.GLASS : MediumHint.NONE;
        int details = (materials.hasNormal(sprite.id())
                        ? MaterialDetail.NORMAL_TEXTURE.bit()
                        : 0)
                | (materials.hasSpecular(sprite.id())
                        ? MaterialDetail.OPTICAL_TEXTURE.bit()
                        : 0);
        MaterialRecipe recipe = new MaterialRecipe(
                cutout ? CoverageMode.ALPHA_CUTOUT : CoverageMode.OPAQUE,
                scattering,
                medium,
                details,
                builtinMaterialClass);
        return new PrimitiveControl(
                recipe,
                animated,
                tangentNegative && (details & MaterialDetail.NORMAL_TEXTURE.bit()) != 0,
                frontFaceOnly);
    }
}
