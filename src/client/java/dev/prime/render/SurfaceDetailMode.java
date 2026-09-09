// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import java.util.Arrays;
import java.util.Optional;

/** Mutually exclusive texture-surface representation selected before terrain translation. */
public enum SurfaceDetailMode {
    NONE("none"),
    RESOURCE_NORMAL("normal"),
    GEOMETRIC_DISPLACEMENT("displacement");

    public static final SurfaceDetailMode DEFAULT = RESOURCE_NORMAL;

    private final String id;

    SurfaceDetailMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public boolean usesResourceNormals() {
        return this == RESOURCE_NORMAL;
    }

    public boolean usesGeometryDisplacement() {
        return this == GEOMETRIC_DISPLACEMENT;
    }

    public static Optional<SurfaceDetailMode> findById(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }
}
