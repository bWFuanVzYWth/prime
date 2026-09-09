// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** Immutable frame layout shared by CPU-side sprite-channel samplers. */
record SpriteSheetLayout(
        int imageWidth,
        int imageHeight,
        int frameWidth,
        int frameHeight,
        int columns,
        int frameCount) {
    SpriteSheetLayout {
        if (imageWidth <= 0
                || imageHeight <= 0
                || frameWidth <= 0
                || frameHeight <= 0
                || columns <= 0
                || frameCount <= 0
                || (long) columns * frameWidth > imageWidth
                || ((long) frameCount + columns - 1L) / columns * frameHeight
                        > imageHeight) {
            throw new IllegalArgumentException("Sprite-sheet layout does not match its image");
        }
    }

    static SpriteSheetLayout forPixels(
            int pixelCount,
            int imageWidth,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        if (imageWidth <= 0 || pixelCount % imageWidth != 0) {
            throw new IllegalArgumentException("Sprite pixels do not form a rectangular image");
        }
        return new SpriteSheetLayout(
                imageWidth,
                pixelCount / imageWidth,
                frameWidth,
                frameHeight,
                columns,
                frameCount);
    }

    int index(int requestedFrame, float localU, float localV) {
        int frame = frame(requestedFrame);
        int x = Math.min((int) (clampUnit(localU) * this.frameWidth), this.frameWidth - 1);
        int y = Math.min((int) (clampUnit(localV) * this.frameHeight), this.frameHeight - 1);
        return (frameOriginY(frame) + y) * this.imageWidth + frameOriginX(frame) + x;
    }

    int frame(int requestedFrame) {
        return this.frameCount == 1
                ? 0
                : Math.max(0, Math.min(requestedFrame, this.frameCount - 1));
    }

    int frameOriginX(int frame) {
        return frame % this.columns * this.frameWidth;
    }

    int frameOriginY(int frame) {
        return frame / this.columns * this.frameHeight;
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }
}
