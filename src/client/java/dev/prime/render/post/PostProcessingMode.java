// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.post;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Selects one complete realtime denoising and reconstruction pipeline. */
public enum PostProcessingMode {
    NRD_FSR("nrd_fsr"),
    DLSS_RR("dlss_rr"),
    DISABLED("disabled");

    /** RR is requested by default; the renderer falls back to NRD-FSR when NGX is unavailable. */
    public static final PostProcessingMode DEFAULT = DLSS_RR;

    private final String id;

    PostProcessingMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<PostProcessingMode> findById(String id) {
        return StableIds.find(values(), id, PostProcessingMode::id);
    }

    public static PostProcessingMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }
}
