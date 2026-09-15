// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** RGBA8 optical pages: complement roughness, Fresnel identity, tagged SSS/porosity, emission. */
public final class CanonicalOpticalEncoding {
    private CanonicalOpticalEncoding() {
    }

    public static int fromLabPbrArgb(int source) {
        int fresnel = source >>> 8 & 0xff;
        int blue = source & 0xff;
        // Preserve distinct dielectric identities: equal clamped F0 does not imply an air gap.
        int code = fresnel <= 237 ? fresnel + 1 : fresnel == 255 ? 239 : 0;
        int scattering = blue <= 64 ? blue : blue == 65 ? 0 : blue - 1;
        return (source & 0xffff_0000) | code << 8 | scattering;
    }
}
