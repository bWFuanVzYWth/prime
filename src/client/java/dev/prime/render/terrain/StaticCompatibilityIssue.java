// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** Recoverable static-resource incompatibility reported after worker translation. */
public record StaticCompatibilityIssue(Type type, int textureId) {
    public StaticCompatibilityIssue {
        java.util.Objects.requireNonNull(type, "type");
        if (textureId <= 0 || textureId > 0x00ff_ffff) {
            throw new IllegalArgumentException("Compatibility issue TextureId is invalid");
        }
    }

    public enum Type {
        AMBIGUOUS_TRANSMISSIVE_TOPOLOGY(
                "an open or ambiguous transparent component was omitted");

        private final String description;

        Type(String description) {
            this.description = description;
        }

        public String description() {
            return this.description;
        }
    }
}
