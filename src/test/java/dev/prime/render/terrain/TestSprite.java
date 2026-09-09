// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.scene.SpritePixelView;
import java.util.Arrays;

/** Mutable pixels and immutable capture metadata shared by terrain translation tests. */
final class TestSprite {
    private static final int FRAME_SIZE = 16;

    private final int[] pixels = new int[FRAME_SIZE * FRAME_SIZE];
    private final CapturedSprite sprite;

    TestSprite() {
        this("merge_test");
    }

    TestSprite(String path) {
        this(path, 1);
    }

    TestSprite(String path, int textureId) {
        this.sprite = new CapturedSprite(
                new SpriteId("prime", path),
                textureId,
                FRAME_SIZE,
                FRAME_SIZE,
                false,
                new int[] {0},
                new ArrayPixels(this.pixels));
    }

    void fill(int argb) {
        Arrays.fill(this.pixels, argb);
    }

    void setPixel(int x, int y, int argb) {
        this.pixels[x + y * FRAME_SIZE] = argb;
    }

    CapturedSprite sprite() {
        return this.sprite;
    }

    SpriteId id() {
        return this.sprite.id();
    }

    private record ArrayPixels(int[] pixels) implements SpritePixelView {
        @Override
        public int imageWidth() {
            return FRAME_SIZE;
        }

        @Override
        public int imageHeight() {
            return FRAME_SIZE;
        }

        @Override
        public int argb(int x, int y) {
            return this.pixels[x + y * FRAME_SIZE];
        }
    }
}
