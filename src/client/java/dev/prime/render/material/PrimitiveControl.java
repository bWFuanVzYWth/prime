// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.material;

import java.util.Objects;

/** Material recipe plus geometry-only controls encoded by one primitive. */
public record PrimitiveControl(
        MaterialRecipe material,
        boolean animated,
        boolean tangentNegative,
        boolean frontFaceOnly) {
    public PrimitiveControl {
        Objects.requireNonNull(material, "material");
        if (tangentNegative && !material.hasDetail(MaterialDetail.NORMAL_TEXTURE)) {
            throw new IllegalArgumentException(
                    "Negative tangent handedness requires a normal texture");
        }
    }
}
