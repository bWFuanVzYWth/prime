// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.diagnostic;

/** One session-only image diagnostic choice presented either full-screen or as a fixed grid. */
public interface ImageDiagnosticView {
    String id();

    default boolean active() {
        return !"off".equals(id());
    }

    default boolean grid() {
        return "grid".equals(id());
    }
}
