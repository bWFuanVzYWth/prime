// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.material;

/** Optional source textures owned by the material adapter. */
public enum MaterialDetail {
    NORMAL_TEXTURE(1),
    OPTICAL_TEXTURE(2);

    public static final int MASK = NORMAL_TEXTURE.bit | OPTICAL_TEXTURE.bit;

    private final int bit;

    MaterialDetail(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return this.bit;
    }
}
