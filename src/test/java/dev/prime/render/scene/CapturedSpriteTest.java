// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CapturedSpriteTest {
    @Test
    void equalityUsesStableIdentityLayoutAndFramesButNotPixelAdapterIdentity() {
        CapturedSprite first = sprite(
                new SpriteId("example", "block/animated"),
                new int[] {2, 0},
                (x, y) -> 0xff00_0000);
        CapturedSprite second = sprite(
                new SpriteId("example", "block/animated"),
                new int[] {2, 0},
                (x, y) -> 0xffff_ffff);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(
                first,
                sprite(
                        new SpriteId("other", "block/animated"),
                        new int[] {2, 0},
                        null));
        assertNotEquals(
                first,
                sprite(
                        new SpriteId("example", "block/animated"),
                        new int[] {0, 2},
                        null));
        assertEquals("example:block/animated", first.id().toString());
    }

    @Test
    void frameSequenceIsDefensivelyCopiedAndValidated() {
        int[] frames = {3, 1};
        CapturedSprite sprite = sprite(
                new SpriteId("prime", "block/frames"), frames, null);
        frames[0] = 7;

        assertEquals(2, sprite.uniqueFrameCount());
        assertEquals(3, sprite.uniqueFrame(0));
        assertEquals(1, sprite.uniqueFrame(1));
        assertThrows(
                IllegalArgumentException.class,
                () -> sprite(
                        new SpriteId("prime", "block/duplicate"),
                        new int[] {1, 1},
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> sprite(
                        new SpriteId("prime", "block/negative"),
                        new int[] {-1},
                        null));
    }

    private static CapturedSprite sprite(
            SpriteId id, int[] frames, PixelSampler sampler) {
        SpritePixelView pixels = sampler == null
                ? null
                : new SpritePixelView() {
                    @Override
                    public int imageWidth() {
                        return 32;
                    }

                    @Override
                    public int imageHeight() {
                        return 16;
                    }

                    @Override
                    public int argb(int x, int y) {
                        return sampler.argb(x, y);
                    }
                };
        return new CapturedSprite(
                id,
                1,
                16,
                16,
                true,
                frames,
                pixels);
    }

    @FunctionalInterface
    private interface PixelSampler {
        int argb(int x, int y);
    }
}
