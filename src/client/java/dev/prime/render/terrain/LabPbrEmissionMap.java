package dev.prime.render.terrain;

import java.util.Arrays;

/** Immutable CPU view of the LabPBR specular alpha channel used by light extraction. */
public final class LabPbrEmissionMap {
    private final byte[] encoded;
    private final SpriteSheetLayout layout;
    private final boolean hasPositiveEmission;

    private LabPbrEmissionMap(
            byte[] encoded,
            SpriteSheetLayout layout,
            boolean hasPositiveEmission) {
        this.encoded = encoded;
        this.layout = layout;
        this.hasPositiveEmission = hasPositiveEmission;
    }

    /** Returns null only when every texel uses LabPBR's 255 "not authored" sentinel. */
    public static LabPbrEmissionMap fromSpecular(
            int[] argb,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        if (argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("Specular pixel count does not match its dimensions");
        }
        SpriteSheetLayout layout = new SpriteSheetLayout(
                width, height, frameWidth, frameHeight, columns, frameCount);
        byte[] encoded = new byte[argb.length];
        boolean authored = false;
        boolean positive = false;
        for (int index = 0; index < argb.length; index++) {
            int alpha = argb[index] >>> 24;
            encoded[index] = (byte) alpha;
            authored |= alpha < 255;
            positive |= alpha > 0 && alpha < 255;
        }
        return authored
                ? new LabPbrEmissionMap(encoded, layout, positive)
                : null;
    }

    boolean hasPositiveEmission() {
        return this.hasPositiveEmission;
    }

    /** Samples the same clamped source frame and normalized sprite coordinates as the GPU atlas. */
    float sample(int requestedFrame, float localU, float localV) {
        return decode(Byte.toUnsignedInt(this.encoded[
                this.layout.index(requestedFrame, localU, localV)]));
    }

    static float decode(int encoded) {
        if (encoded < 0 || encoded > 255) {
            throw new IllegalArgumentException("LabPBR emission must be an unsigned byte");
        }
        return encoded < 255 ? encoded / 254.0F : 0.0F;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LabPbrEmissionMap map)) {
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
