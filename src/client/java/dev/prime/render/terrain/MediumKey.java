package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;

/** Exact CPU identity of one translated participating medium. */
public record MediumKey(Kind kind, int sourceIdentity, int tint, boolean water) {
    public enum Kind {
        FLUID,
        FAMILY,
        TEXTURE
    }

    public static final MediumKey CAMERA_WATER =
            new MediumKey(Kind.FLUID, 0, 0, true);

    public MediumKey {
        java.util.Objects.requireNonNull(kind, "kind");
        if (sourceIdentity < 0) {
            throw new IllegalArgumentException("Medium source identity must not be negative");
        }
        if (kind == Kind.FLUID && (sourceIdentity != 0 || tint != 0)) {
            throw new IllegalArgumentException("Fluid identity must not carry material fields");
        }
        if (kind != Kind.FLUID && sourceIdentity == 0) {
            throw new IllegalArgumentException("Material medium identity must be nonzero");
        }
    }

    static MediumKey of(CapturedSectionGeometry.Surface surface) {
        if (surface.fluid() != null) {
            return surface.water()
                    ? CAMERA_WATER
                    : new MediumKey(Kind.FLUID, 0, 0, false);
        }
        int family = surface.block() == null ? 0 : surface.block().mediumFamily();
        return family != 0
                ? new MediumKey(
                        Kind.FAMILY,
                        family,
                        ClusterSceneTranslator.averageColor(surface),
                        surface.water())
                : new MediumKey(
                        Kind.TEXTURE,
                        surface.sprite().textureId(),
                        ClusterSceneTranslator.averageColor(surface),
                        surface.water());
    }
}
