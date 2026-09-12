// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
package dev.prime.render.post;

import dev.prime.render.AstronomySettings;
import java.util.Objects;

/** Direct camera starlight only; camera media retain their transport evaluation. */
public record StarsFrameParameters(AstronomySettings astronomy, float multiplier, boolean cameraInWater) {
    public StarsFrameParameters {
        Objects.requireNonNull(astronomy, "astronomy");
        if (!Float.isFinite(multiplier) || multiplier < 0.0F) {
            throw new IllegalArgumentException("Invalid star radiance multiplier");
        }
    }
}
