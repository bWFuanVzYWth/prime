// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene;

import java.util.Objects;

/** Stable Prime-owned resource identity independent of Minecraft's Identifier type. */
public record SpriteId(String namespace, String path) {
    public SpriteId {
        namespace = requirePart(namespace, "namespace");
        path = requirePart(path, "path");
    }

    private static String requirePart(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Sprite " + name + " must not be empty");
        }
        return value;
    }

    @Override
    public String toString() {
        return this.namespace + ':' + this.path;
    }
}
