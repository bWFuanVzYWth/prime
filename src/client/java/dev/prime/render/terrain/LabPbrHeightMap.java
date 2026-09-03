package dev.prime.render.terrain;

import java.util.Arrays;

/** Immutable CPU view of the LabPBR normal-map alpha height channel. */
public final class LabPbrHeightMap {
    private final LabPbrAtlasFrame.MaterialSource source;
    private final byte[] frameMinimum;
    private final int alphaHash;

    private LabPbrHeightMap(
            LabPbrAtlasFrame.MaterialSource source,
            byte[] frameMinimum) {
        this.source = source;
        this.frameMinimum = frameMinimum;
        this.alphaHash = source.alphaHashCode();
    }

    public static LabPbrHeightMap fromNormal(
            int[] argb,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        return fromNormal(new LabPbrAtlasFrame.MaterialSource(
                argb, width, height, frameWidth, frameHeight, columns, frameCount));
    }

    public static LabPbrHeightMap fromNormal(
            LabPbrAtlasFrame.MaterialSource source) {
        byte[] frameMinimum = new byte[source.frameCount()];
        Arrays.fill(frameMinimum, (byte) 0xff);
        for (int frame = 0; frame < source.frameCount(); frame++) {
            int frameX = source.frameOriginX(frame);
            int frameY = source.frameOriginY(frame);
            int minimum = 255;
            for (int y = 0; y < source.frameHeight(); y++) {
                for (int x = 0; x < source.frameWidth(); x++) {
                    minimum = Math.min(minimum,
                            source.argb((frameY + y) * source.width() + frameX + x) >>> 24);
                }
            }
            frameMinimum[frame] = (byte) minimum;
        }
        return new LabPbrHeightMap(source, frameMinimum);
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
        int frame = this.source.frame(requestedFrame);
        int encodedHeight = this.source.argb(this.source.index(frame, localU, localV)) >>> 24;
        int minimum = Byte.toUnsignedInt(this.frameMinimum[frame]);
        return (encodedHeight - minimum) / 255.0F;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LabPbrHeightMap map)) {
            return false;
        }
        return this.alphaHash == map.alphaHash
                && this.source.alphaEquals(map.source);
    }

    @Override
    public int hashCode() {
        return this.alphaHash;
    }
}
