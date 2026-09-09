// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.material;

import java.util.Objects;

/** Source-neutral material facts retained by translated geometry. */
public record MaterialRecipe(
        CoverageMode coverage,
        ScatteringFamily scattering,
        MediumHint medium,
        int detailMask,
        BuiltinMaterialClass builtinClass) {
    public MaterialRecipe {
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(scattering, "scattering");
        Objects.requireNonNull(medium, "medium");
        Objects.requireNonNull(builtinClass, "builtinClass");
        if ((detailMask & ~MaterialDetail.MASK) != 0) {
            throw new IllegalArgumentException("Material detail mask contains reserved bits");
        }
        if (medium == MediumHint.WATER && scattering != ScatteringFamily.DIELECTRIC_SOLID) {
            throw new IllegalArgumentException("Water must be a solid dielectric");
        }
        boolean dielectric = scattering == ScatteringFamily.DIELECTRIC_SOLID
                || scattering == ScatteringFamily.DIELECTRIC_THIN;
        if (medium == MediumHint.GLASS && !dielectric) {
            throw new IllegalArgumentException("Glass must use a dielectric scattering family");
        }
        if (medium == MediumHint.NONE && dielectric) {
            throw new IllegalArgumentException("Dielectric geometry requires a medium hint");
        }
        if (medium != MediumHint.NONE && !dielectric) {
            throw new IllegalArgumentException("Opaque and foliage materials cannot own a medium");
        }
        if (scattering == ScatteringFamily.FOLIAGE_THIN
                && coverage != CoverageMode.ALPHA_CUTOUT) {
            throw new IllegalArgumentException("Foliage must use alpha-cutout coverage");
        }
    }

    public boolean hasDetail(MaterialDetail detail) {
        return (this.detailMask & detail.bit()) != 0;
    }
}
