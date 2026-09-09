// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene;

import java.util.Arrays;
import java.util.Objects;

/** Immutable logical texture identity and animation facts captured at the vanilla boundary. */
public final class CapturedSprite {
    public static final int MAX_TEXTURE_ID = (1 << 24) - 1;

    private final SpriteId id;
    private final int textureId;
    private final int frameWidth;
    private final int frameHeight;
    private final boolean animated;
    private final int[] uniqueFrames;
    private final SpritePixelView pixelView;

    public CapturedSprite(
            SpriteId id,
            int textureId,
            int frameWidth,
            int frameHeight,
            boolean animated,
            int[] uniqueFrames,
            SpritePixelView pixelView) {
        this.id = Objects.requireNonNull(id, "id");
        if (textureId <= 0 || textureId > MAX_TEXTURE_ID) {
            throw new IllegalArgumentException("Captured texture ID must be a nonzero 24-bit value");
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("Captured sprite frame dimensions must be positive");
        }
        Objects.requireNonNull(uniqueFrames, "uniqueFrames");
        if (uniqueFrames.length == 0) {
            throw new IllegalArgumentException("Captured sprite must contain at least one frame");
        }
        for (int index = 0; index < uniqueFrames.length; index++) {
            int frame = uniqueFrames[index];
            if (frame < 0) {
                throw new IllegalArgumentException(
                        "Captured sprite frame sequence must be nonnegative and unique");
            }
            for (int previous = 0; previous < index; previous++) {
                if (uniqueFrames[previous] == frame) {
                    throw new IllegalArgumentException(
                            "Captured sprite frame sequence must be nonnegative and unique");
                }
            }
        }
        this.textureId = textureId;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.animated = animated;
        this.uniqueFrames = uniqueFrames.clone();
        this.pixelView = pixelView;
    }

    public SpriteId id() {
        return this.id;
    }

    public int textureId() {
        return this.textureId;
    }

    public int frameWidth() {
        return this.frameWidth;
    }

    public int frameHeight() {
        return this.frameHeight;
    }

    public boolean animated() {
        return this.animated;
    }

    public int uniqueFrameCount() {
        return this.uniqueFrames.length;
    }

    public int uniqueFrame(int index) {
        return this.uniqueFrames[index];
    }

    /** Returns null when the resource boundary could not safely expose base pixels. */
    public SpritePixelView pixelView() {
        return this.pixelView;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CapturedSprite sprite)) {
            return false;
        }
        return this.id.equals(sprite.id)
                && this.textureId == sprite.textureId
                && this.frameWidth == sprite.frameWidth
                && this.frameHeight == sprite.frameHeight
                && this.animated == sprite.animated
                && Arrays.equals(this.uniqueFrames, sprite.uniqueFrames);
    }

    @Override
    public int hashCode() {
        int result = this.id.hashCode();
        result = 31 * result + this.textureId;
        result = 31 * result + this.frameWidth;
        result = 31 * result + this.frameHeight;
        result = 31 * result + Boolean.hashCode(this.animated);
        return 31 * result + Arrays.hashCode(this.uniqueFrames);
    }
}
