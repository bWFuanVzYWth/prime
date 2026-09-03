package dev.prime.render.terrain;

/** Immutable CPU view of the LabPBR specular alpha channel used by light extraction. */
public final class LabPbrEmissionMap {
    private final LabPbrAtlasFrame.MaterialSource source;
    private final boolean hasPositiveEmission;
    private final int alphaHash;

    private LabPbrEmissionMap(
            LabPbrAtlasFrame.MaterialSource source,
            boolean hasPositiveEmission) {
        this.source = source;
        this.hasPositiveEmission = hasPositiveEmission;
        this.alphaHash = source.alphaHashCode();
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
        return fromSpecular(new LabPbrAtlasFrame.MaterialSource(
                argb, width, height, frameWidth, frameHeight, columns, frameCount));
    }

    public static LabPbrEmissionMap fromSpecular(
            LabPbrAtlasFrame.MaterialSource source) {
        boolean authored = false;
        boolean positive = false;
        for (int index = 0; index < source.width() * source.height(); index++) {
            int alpha = source.argb(index) >>> 24;
            authored |= alpha < 255;
            positive |= alpha > 0 && alpha < 255;
        }
        return authored
                ? new LabPbrEmissionMap(source, positive)
                : null;
    }

    boolean hasPositiveEmission() {
        return this.hasPositiveEmission;
    }

    /** Samples the same clamped source frame and normalized sprite coordinates as the GPU atlas. */
    float sample(int requestedFrame, float localU, float localV) {
        return decode(this.source.argb(
                this.source.index(requestedFrame, localU, localV)) >>> 24);
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
        return this.alphaHash == map.alphaHash
                && this.source.alphaEquals(map.source);
    }

    @Override
    public int hashCode() {
        return this.alphaHash;
    }
}
