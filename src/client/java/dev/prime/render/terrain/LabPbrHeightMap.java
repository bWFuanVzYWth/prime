package dev.prime.render.terrain;

import java.util.Arrays;

/** Immutable CPU view of the LabPBR normal-map alpha height channel. */
public final class LabPbrHeightMap {
    private final byte[] encoded;
    private final SpriteSheetLayout layout;
    private final byte[] frameMinimum;

    private LabPbrHeightMap(
            byte[] encoded,
            SpriteSheetLayout layout,
            byte[] frameMinimum) {
        this.encoded = encoded;
        this.layout = layout;
        this.frameMinimum = frameMinimum;
    }

    public static LabPbrHeightMap fromNormal(
            int[] argb,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        if (argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "LabPBR height-map layout does not match its pixels");
        }
        SpriteSheetLayout layout = new SpriteSheetLayout(
                width, height, frameWidth, frameHeight, columns, frameCount);
        byte[] encoded = new byte[argb.length];
        for (int index = 0; index < argb.length; index++) {
            encoded[index] = (byte) (argb[index] >>> 24);
        }
        byte[] frameMinimum = new byte[frameCount];
        Arrays.fill(frameMinimum, (byte) 0xff);
        for (int frame = 0; frame < frameCount; frame++) {
            int frameX = layout.frameOriginX(frame);
            int frameY = layout.frameOriginY(frame);
            int minimum = 255;
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    minimum = Math.min(
                            minimum,
                            Byte.toUnsignedInt(
                                    encoded[(frameY + y) * width + frameX + x]));
                }
            }
            frameMinimum[frame] = (byte) minimum;
        }
        return new LabPbrHeightMap(encoded, layout, frameMinimum);
    }

    /**
     * Samples outward-only relief after rebasing the frame's lowest authored height to zero.
     *
     * <p>LabPBR height commonly occupies a narrow high-valued band because its original use is
     * inward parallax depth. Removing that per-frame DC offset prevents a flat map from lifting
     * the whole face and preserves the authored excursion instead of stretching every material to
     * the configured maximum.
     */
    float sample(int requestedFrame, float localU, float localV) {
        int frame = this.layout.frame(requestedFrame);
        int encodedHeight = Byte.toUnsignedInt(
                this.encoded[this.layout.index(frame, localU, localV)]);
        int minimum = Byte.toUnsignedInt(this.frameMinimum[frame]);
        return (encodedHeight - minimum) / 255.0F;
    }

    byte[] replayEncoded() {
        return Arrays.copyOf(this.encoded, this.encoded.length);
    }

    SpriteSheetLayout replayLayout() {
        return this.layout;
    }

    static LabPbrHeightMap replay(byte[] encoded, SpriteSheetLayout layout) {
        if (encoded.length != Math.multiplyExact(layout.imageWidth(), layout.imageHeight())) {
            throw new IllegalArgumentException("Height replay pixels do not match their layout");
        }
        byte[] copy = Arrays.copyOf(encoded, encoded.length);
        byte[] frameMinimum = new byte[layout.frameCount()];
        Arrays.fill(frameMinimum, (byte) 0xff);
        for (int frame = 0; frame < layout.frameCount(); frame++) {
            int minimum = 255;
            int frameX = layout.frameOriginX(frame);
            int frameY = layout.frameOriginY(frame);
            for (int y = 0; y < layout.frameHeight(); y++) {
                for (int x = 0; x < layout.frameWidth(); x++) {
                    minimum = Math.min(
                            minimum,
                            Byte.toUnsignedInt(copy[
                                    (frameY + y) * layout.imageWidth() + frameX + x]));
                }
            }
            frameMinimum[frame] = (byte) minimum;
        }
        return new LabPbrHeightMap(copy, layout, frameMinimum);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LabPbrHeightMap map)) {
            return false;
        }
        return this.layout.equals(map.layout)
                && Arrays.equals(this.encoded, map.encoded);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(this.encoded);
        return 31 * result + this.layout.hashCode();
    }
}
