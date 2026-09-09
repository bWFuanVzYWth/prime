// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import java.util.Arrays;
import java.util.Optional;

/** Selects one independent interactive renderer while retaining the shared scene boundary. */
public enum RealtimeRenderMode {
    PATH_TRACING("path_tracing"),
    LIGHTWEIGHT_PATH_TRACING("lightweight_path_tracing");

    public static final RealtimeRenderMode DEFAULT = PATH_TRACING;

    private final String id;

    RealtimeRenderMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<RealtimeRenderMode> findById(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }
}
