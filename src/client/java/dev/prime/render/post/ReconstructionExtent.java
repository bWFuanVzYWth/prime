// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.post;

/** Positive render extent selected by a reconstruction backend. */
public record ReconstructionExtent(int width, int height) {
    public ReconstructionExtent {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Reconstruction extent must be positive");
        }
    }
}
