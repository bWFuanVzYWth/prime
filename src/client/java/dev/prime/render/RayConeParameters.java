package dev.prime.render;

/** Semantic primary-ray footprint and texture-LOD bias before the Vulkan ABI encoding. */
public record RayConeParameters(float width, float mipBias) {
    /** Maximum change in the final unclamped texture LOD caused by the binary16 ABI. */
    public static final double MAXIMUM_BINARY16_LOD_ERROR = 1.0 / 512.0;

    public RayConeParameters {
        if (!(width > 0.0F) || !Float.isFinite(width) || !Float.isFinite(mipBias)) {
            throw new IllegalArgumentException(
                    "Ray-cone width must be positive and finite, and mip bias must be finite");
        }
        float encodedWidth = binary16RoundTrip(width);
        float encodedBias = binary16RoundTrip(mipBias);
        if (!(encodedWidth > 0.0F)
                || !Float.isFinite(encodedWidth)
                || !Float.isFinite(encodedBias)) {
            throw new IllegalArgumentException(
                    "Ray-cone parameters are not representable by the Vulkan ABI");
        }
        double lodError = lodError(width, mipBias, encodedWidth, encodedBias);
        if (!(lodError <= MAXIMUM_BINARY16_LOD_ERROR)) {
            throw new IllegalArgumentException(
                    "Ray-cone binary16 encoding exceeds the texture-LOD error contract");
        }
    }

    public static RayConeParameters fromProjection(
            float projectionM00,
            float projectionM11,
            int width,
            int height,
            float mipBias) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Ray-cone render dimensions must be positive");
        }
        float horizontal = 2.0F / (width * Math.abs(projectionM00));
        float vertical = 2.0F / (height * Math.abs(projectionM11));
        return new RayConeParameters(Math.max(horizontal, vertical), mipBias);
    }

    public double binary16LodError() {
        return lodError(
                this.width,
                this.mipBias,
                binary16RoundTrip(this.width),
                binary16RoundTrip(this.mipBias));
    }

    private static float binary16RoundTrip(float value) {
        return Float.float16ToFloat(Float.floatToFloat16(value));
    }

    private static double lodError(
            float width, float mipBias, float encodedWidth, float encodedBias) {
        return Math.abs(
                Math.log(encodedWidth / (double) width) / Math.log(2.0)
                        + encodedBias - mipBias);
    }
}
