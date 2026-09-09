// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.diagnostic;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Backend-independent transport outputs and renderer-owned derived images. */
public enum RendererImageView implements ImageDiagnosticView {
    OFF("off"),
    DENOISED_OUTPUT("denoised_output"),
    NOISY_DIFFUSE("noisy_diffuse"),
    NOISY_SPECULAR("noisy_specular"),
    STABLE_RADIANCE("stable_radiance"),
    SUN_LIGHTING("sun_lighting"),
    SUN_VISIBILITY("sun_visibility"),
    SUN_PENUMBRA("sun_penumbra"),
    NORMAL("normal"),
    ROUGHNESS("roughness"),
    DIFFUSE_ALBEDO("diffuse_albedo"),
    SPECULAR_ALBEDO("specular_albedo"),
    DIFFUSE_HIT_DISTANCE("diffuse_hit_distance"),
    SPECULAR_HIT_DISTANCE("specular_hit_distance"),
    PRIMARY_DEPTH("primary_depth"),
    GRID("grid");

    private final String id;

    RendererImageView(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return this.id;
    }

    public static Optional<RendererImageView> findById(String id) {
        return StableIds.find(values(), id, RendererImageView::id);
    }

    public static RendererImageView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
