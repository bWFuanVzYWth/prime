// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.post;

import dev.prime.render.shader.ShaderAbi;

/** Backend-resolved transparent-surface guide behavior consumed by the path integrator. */
public enum TransparentGuideMode {
    REFLECTION_AND_TRANSMISSION(ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_NRD),
    TRANSMISSION_ONLY(ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR),
    DISABLED(ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_DISABLED);

    private final int abiValue;

    TransparentGuideMode(int abiValue) {
        this.abiValue = abiValue;
    }

    public int abiValue() {
        return this.abiValue;
    }
}
