// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.fsr;

import dev.prime.render.shader.ShaderAbi;

/** Product-level FidelityFX constants and value types. */
public final class FsrSettings {
    public static final String SDK_VERSION = "1.1.4";
    public static final String UPSCALER_VERSION = ShaderAbi.FSR_VERSION;
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean FRAME_GENERATION_ENABLED = false;
    public static final float RCAS_SHARPNESS = 0.2F;
    /** Prime's display transform currently uses a fixed scene-linear exposure multiplier. */
    public static final float EXPOSURE = ShaderAbi.DISPLAY_EXPOSURE;
    private FsrSettings() {
    }
}
