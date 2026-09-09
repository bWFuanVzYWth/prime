// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.diagnostic;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Exact external image inputs of the two REBLUR instances and SIGMA. */
public enum NrdInputView implements ImageDiagnosticView {
    OFF("off"),
    DENOISED_OUTPUT("denoised_output"),
    PRIMARY_GRID("primary_grid"),
    PRIMARY_MOTION("primary_motion"),
    PRIMARY_NORMAL_ROUGHNESS("primary_normal_roughness"),
    PRIMARY_VIEW_Z("primary_view_z"),
    PRIMARY_DIFFUSE_SH0("primary_diffuse_sh0"),
    PRIMARY_DIFFUSE_SH1("primary_diffuse_sh1"),
    PRIMARY_SPECULAR_SH0("primary_specular_sh0"),
    PRIMARY_SPECULAR_SH1("primary_specular_sh1"),
    REFLECTION_GRID("reflection_grid"),
    REFLECTION_MOTION("reflection_motion"),
    REFLECTION_NORMAL_ROUGHNESS("reflection_normal_roughness"),
    REFLECTION_VIEW_Z("reflection_view_z"),
    REFLECTION_DIFFUSE_SH0("reflection_diffuse_sh0"),
    REFLECTION_DIFFUSE_SH1("reflection_diffuse_sh1"),
    REFLECTION_SPECULAR_SH0("reflection_specular_sh0"),
    REFLECTION_SPECULAR_SH1("reflection_specular_sh1"),
    SIGMA_GRID("sigma_grid"),
    SIGMA_NORMAL_ROUGHNESS("sigma_normal_roughness"),
    SIGMA_VIEW_Z("sigma_view_z"),
    SIGMA_PENUMBRA("sigma_penumbra");

    private final String id;

    NrdInputView(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public boolean grid() {
        return this == PRIMARY_GRID || this == REFLECTION_GRID || this == SIGMA_GRID;
    }

    public static Optional<NrdInputView> findById(String id) {
        return StableIds.find(values(), id, NrdInputView::id);
    }

    public static NrdInputView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
