// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.diagnostic;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Semantic projections of the exact image resources submitted to DLSS Ray Reconstruction. */
public enum RrInputView implements ImageDiagnosticView {
    OFF("off"),
    DENOISED_OUTPUT("denoised_output"),
    NOISY_INPUT("input_color"),
    DIFFUSE_ALBEDO("diffuse_albedo"),
    SPECULAR_ALBEDO("specular_albedo"),
    NORMAL("normal"),
    ROUGHNESS("roughness"),
    LINEAR_DEPTH("linear_depth"),
    MOTION("motion"),
    SPECULAR_HIT_DISTANCE("specular_hit_distance"),
    RESPONSIVITY("responsivity"),
    INPUT_COVERAGE("input_coverage"),
    OUTPUT_COVERAGE("output_coverage"),
    GRID("grid");

    private final String id;

    RrInputView(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return this.id;
    }

    public static Optional<RrInputView> findById(String id) {
        return StableIds.find(values(), id, RrInputView::id);
    }

    public static RrInputView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
