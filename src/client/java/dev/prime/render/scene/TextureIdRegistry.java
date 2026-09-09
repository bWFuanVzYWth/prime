// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread-owned stable identities for every texture seen by one renderer lifetime. */
public final class TextureIdRegistry {
    private final Map<SpriteId, Integer> ids = new HashMap<>();
    private int nextId = 1;

    public int resolve(SpriteId texture) {
        Objects.requireNonNull(texture, "texture");
        Integer existing = this.ids.get(texture);
        if (existing != null) {
            return existing;
        }
        if (this.nextId > CapturedSprite.MAX_TEXTURE_ID) {
            throw new IllegalStateException("Prime exhausted its 24-bit texture ID space");
        }
        int assigned = this.nextId++;
        this.ids.put(texture, assigned);
        return assigned;
    }
}
