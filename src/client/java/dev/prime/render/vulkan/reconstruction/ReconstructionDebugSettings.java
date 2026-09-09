// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.diagnostic.RrResponsivity;
import java.util.Objects;

/** Runtime-to-backend debug side channel excluded from semantic frame plans. */
public record ReconstructionDebugSettings(
        ImageDiagnosticSelection images,
        float rrResponsivity) {
    public ReconstructionDebugSettings {
        images = Objects.requireNonNull(images, "images");
        rrResponsivity = RrResponsivity.requireValid(rrResponsivity);
    }
}
