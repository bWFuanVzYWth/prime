// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene;

/** Borrowed read-only pixels whose lifetime is bounded by the owning resource epoch lease. */
public interface SpritePixelView {
    int imageWidth();

    int imageHeight();

    int argb(int x, int y);
}
