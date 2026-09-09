// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.diagnostic.RrResponsivity;

/** Immutable, non-persistent controls owned by the client runtime. */
public record SessionControls(
        boolean screenshotRequested,
        boolean rendererDiagnostics,
        boolean rawOutput,
        ImageDiagnosticSelection imageDiagnostics,
        float rrResponsivity) {
    public SessionControls {
        imageDiagnostics = java.util.Objects.requireNonNull(imageDiagnostics, "imageDiagnostics");
        rrResponsivity = RrResponsivity.requireValid(rrResponsivity);
    }

    public static SessionControls defaults() {
        return new SessionControls(
                false,
                false,
                false,
                ImageDiagnosticSelection.off(),
                RrResponsivity.DEFAULT);
    }
}
