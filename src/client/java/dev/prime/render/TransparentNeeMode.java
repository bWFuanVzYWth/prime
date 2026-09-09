// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import java.util.Arrays;
import java.util.Optional;

/** Direct-light visibility policy at specular transparent interfaces. */
public enum TransparentNeeMode {
    STRAIGHT_APPROXIMATION("straight_approximation"),
    UNBIASED_BSDF_ONLY("unbiased_bsdf_only");

    public static final TransparentNeeMode DEFAULT = STRAIGHT_APPROXIMATION;

    private final String id;

    TransparentNeeMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<TransparentNeeMode> findById(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static TransparentNeeMode fromId(String id) {
        return findById(id).orElseThrow(
                () -> new IllegalArgumentException("Unknown transparent NEE mode: " + id));
    }
}
