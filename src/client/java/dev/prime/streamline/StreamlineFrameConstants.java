package dev.prime.streamline;

import dev.prime.binding.streamline.Constants;
import dev.prime.binding.streamline.SlBoolean;
import dev.prime.render.FrameCamera;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Pure coordinate and scalar conversion for one Streamline common-constants update. */
final class StreamlineFrameConstants {
    private final float[] cameraViewToClip;
    private final float[] clipToCameraView;
    private final float[] clipToLensClip;
    private final float[] clipToPrevClip;
    private final float[] prevClipToClip;
    private final float jitterX;
    private final float jitterY;
    private final int motionWidth;
    private final int motionHeight;
    private final Vector3f cameraPosition;
    private final Vector3f cameraUp;
    private final Vector3f cameraRight;
    private final Vector3f cameraForward;
    private final float fieldOfView;
    private final float aspectRatio;
    private final boolean reset;

    private StreamlineFrameConstants(
            float[] cameraViewToClip,
            float[] clipToCameraView,
            float[] clipToLensClip,
            float[] clipToPrevClip,
            float[] prevClipToClip,
            float jitterX,
            float jitterY,
            int motionWidth,
            int motionHeight,
            Vector3f cameraPosition,
            Vector3f cameraUp,
            Vector3f cameraRight,
            Vector3f cameraForward,
            float fieldOfView,
            float aspectRatio,
            boolean reset) {
        this.cameraViewToClip = cameraViewToClip;
        this.clipToCameraView = clipToCameraView;
        this.clipToLensClip = clipToLensClip;
        this.clipToPrevClip = clipToPrevClip;
        this.prevClipToClip = prevClipToClip;
        this.jitterX = jitterX;
        this.jitterY = jitterY;
        this.motionWidth = motionWidth;
        this.motionHeight = motionHeight;
        this.cameraPosition = cameraPosition;
        this.cameraUp = cameraUp;
        this.cameraRight = cameraRight;
        this.cameraForward = cameraForward;
        this.fieldOfView = fieldOfView;
        this.aspectRatio = aspectRatio;
        this.reset = reset;
    }

    static StreamlineFrameConstants create(
            FrameCamera current,
            FrameCamera previous,
            SubpixelJitter sampleJitter,
            boolean reset,
            int motionWidth,
            int motionHeight) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(sampleJitter, "sample jitter");
        if (!current.isFinite() || !previous.isFinite()) {
            throw new IllegalArgumentException("Streamline camera history must be finite");
        }
        if (motionWidth <= 0 || motionHeight <= 0) {
            throw new IllegalArgumentException("Streamline motion extent must be positive");
        }

        Matrix4f projection = new Matrix4f(current.projection());
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Matrix4f currentViewToWorld = new Matrix4f(current.viewRotation()).invert();
        Matrix4f currentClipToPreviousClip = new Matrix4f(previous.projection())
                .mul(previous.viewRotation())
                .translate(
                        finiteDelta(current.renderX(), previous.renderX()),
                        finiteDelta(current.renderY(), previous.renderY()),
                        finiteDelta(current.renderZ(), previous.renderZ()))
                .mul(currentViewToWorld)
                .mul(inverseProjection);
        Matrix4f previousClipToCurrentClip =
                new Matrix4f(currentClipToPreviousClip).invert();
        Matrix4f cameraToWorld = new Matrix4f(current.viewRotation()).invert();
        Vector3f right = cameraToWorld.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f up = cameraToWorld.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f forward = cameraToWorld.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F));
        Vector3f position = new Vector3f(
                finiteFloat(current.renderX(), "camera X"),
                finiteFloat(current.renderY(), "camera Y"),
                finiteFloat(current.renderZ(), "camera Z"));
        float fieldOfView = 2.0F * (float) Math.atan(
                Math.abs(1.0F / current.projection().m11()));
        float aspectRatio = Math.abs(
                current.projection().m11() / current.projection().m00());
        if (!currentClipToPreviousClip.isFinite()
                || !previousClipToCurrentClip.isFinite()
                || !right.isFinite()
                || !up.isFinite()
                || !forward.isFinite()
                || !Float.isFinite(fieldOfView)
                || !Float.isFinite(aspectRatio)) {
            throw new IllegalArgumentException("Streamline common constants are not finite");
        }
        // Streamline consumes Prime's canonical top-left resources. Its jitter is projection
        // displacement, the component-wise inverse of the ray sample displacement.
        return new StreamlineFrameConstants(
                rowMajor(projection),
                rowMajor(inverseProjection),
                rowMajor(new Matrix4f()),
                rowMajor(currentClipToPreviousClip),
                rowMajor(previousClipToCurrentClip),
                -sampleJitter.x(),
                -sampleJitter.y(),
                motionWidth,
                motionHeight,
                position,
                up,
                right,
                forward,
                fieldOfView,
                aspectRatio,
                reset);
    }

    void write(Constants constants) {
        constants.cameraViewToClip(this.cameraViewToClip)
                .clipToCameraView(this.clipToCameraView)
                .clipToLensClip(this.clipToLensClip)
                .clipToPrevClip(this.clipToPrevClip)
                .prevClipToClip(this.prevClipToClip)
                .jitterOffset(this.jitterX, this.jitterY)
                .mvecScale((float) this.motionWidth, (float) this.motionHeight)
                .cameraPinholeOffset(0.0F, 0.0F)
                .cameraPos(this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z)
                .cameraUp(this.cameraUp.x, this.cameraUp.y, this.cameraUp.z)
                .cameraRight(this.cameraRight.x, this.cameraRight.y, this.cameraRight.z)
                .cameraFwd(this.cameraForward.x, this.cameraForward.y, this.cameraForward.z)
                // Prime supplies reversed-infinite depth: near/far follow the same convention as
                // the FSR host boundary, independent of Minecraft's finite raster projection.
                .cameraNear(Float.MAX_VALUE)
                .cameraFar(ShaderAbi.FSR_NEAR_PLANE)
                .cameraFOV(this.fieldOfView)
                .cameraAspectRatio(this.aspectRatio)
                .depthInverted(SlBoolean.TRUE)
                .cameraMotionIncluded(SlBoolean.TRUE)
                .motionVectors3D(SlBoolean.FALSE)
                .reset(this.reset ? SlBoolean.TRUE : SlBoolean.FALSE)
                .orthographicProjection(SlBoolean.FALSE)
                .motionVectorsDilated(SlBoolean.FALSE)
                .motionVectorsJittered(SlBoolean.FALSE)
                .minRelativeLinearDepthObjectSeparation(40.0F);
    }

    Vector4f transformClipToPrevious(Vector4f vector) {
        return transformRowMajor(this.clipToPrevClip, vector);
    }

    Vector4f transformPreviousClipToCurrent(Vector4f vector) {
        return transformRowMajor(this.prevClipToClip, vector);
    }

    private static float finiteDelta(double current, double previous) {
        return finiteFloat(current - previous, "camera history delta");
    }

    private static float finiteFloat(double value, String name) {
        float converted = (float) value;
        if (!Float.isFinite(converted)) {
            throw new IllegalArgumentException("Streamline " + name + " exceeds f32 range");
        }
        return converted;
    }

    private static float[] rowMajor(Matrix4fc matrix) {
        return new float[] {
            matrix.m00(), matrix.m10(), matrix.m20(), matrix.m30(),
            matrix.m01(), matrix.m11(), matrix.m21(), matrix.m31(),
            matrix.m02(), matrix.m12(), matrix.m22(), matrix.m32(),
            matrix.m03(), matrix.m13(), matrix.m23(), matrix.m33()
        };
    }

    private static Vector4f transformRowMajor(float[] matrix, Vector4f vector) {
        return new Vector4f(
                matrix[0] * vector.x
                        + matrix[1] * vector.y
                        + matrix[2] * vector.z
                        + matrix[3] * vector.w,
                matrix[4] * vector.x
                        + matrix[5] * vector.y
                        + matrix[6] * vector.z
                        + matrix[7] * vector.w,
                matrix[8] * vector.x
                        + matrix[9] * vector.y
                        + matrix[10] * vector.z
                        + matrix[11] * vector.w,
                matrix[12] * vector.x
                        + matrix[13] * vector.y
                        + matrix[14] * vector.z
                        + matrix[15] * vector.w);
    }
}
