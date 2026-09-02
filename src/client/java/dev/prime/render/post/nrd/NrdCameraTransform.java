package dev.prime.render.post.nrd;

import dev.prime.render.FrameCamera;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Camera-relative transforms for Prime's top-left reconstruction images.
 *
 * <p>Both APIs store column-major matrices and multiply column vectors. Minecraft's internal
 * presentation target remains bottom-up, but Prime raygen and reconstruction images map row zero
 * to clip {@code y = +1}. NRD, NGX and Streamline therefore consume the unmodified Minecraft
 * projection. Vertical conversion remains only in explicit Minecraft presentation/UI adapters.
 *
 * <p>World positions are camera-relative for floating-point precision. A position relative to the
 * current effective pinhole is transformed into the previous view by adding
 * {@code currentCamera - previousCamera} before applying the previous rotation. The sign follows
 * directly from {@code world = currentRelative + currentCamera}.
 */
public final class NrdCameraTransform {
    private NrdCameraTransform() {}

    public static Matrix4f projectionForNrd(Matrix4fc minecraftProjection) {
        return projectionForNrd(minecraftProjection, new Matrix4f());
    }

    public static Matrix4f projectionForNrd(Matrix4fc minecraftProjection, Matrix4f result) {
        return result.set(minecraftProjection);
    }

    public static Matrix4f currentClipToWorld(FrameCamera current) {
        return currentClipToWorld(current, new Matrix4f());
    }

    public static Matrix4f currentClipToWorld(FrameCamera current, Matrix4f result) {
        return projectionForNrd(current.projection(), result)
                .mul(current.viewRotation())
                .invert();
    }

    public static Matrix4f previousWorldToView(FrameCamera current, FrameCamera previous) {
        return previousWorldToView(current, previous, new Matrix4f());
    }

    public static Matrix4f previousWorldToView(
            FrameCamera current, FrameCamera previous, Matrix4f result) {
        return result.set(previous.viewRotation()).translate(
                (float) (current.renderX() - previous.renderX()),
                (float) (current.renderY() - previous.renderY()),
                (float) (current.renderZ() - previous.renderZ()));
    }

    public static Matrix4f previousWorldToClip(FrameCamera current, FrameCamera previous) {
        return previousWorldToClip(current, previous, new Matrix4f(), new Matrix4f());
    }

    public static Matrix4f previousWorldToClip(
            FrameCamera current,
            FrameCamera previous,
            Matrix4f result,
            Matrix4f worldToViewScratch) {
        return projectionForNrd(previous.projection(), result)
                .mul(previousWorldToView(current, previous, worldToViewScratch));
    }

    public static Vector2f screenUv(Matrix4fc worldToClip, Vector3fc position) {
        Vector4f clip = worldToClip.transform(
                new Vector4f(position.x(), position.y(), position.z(), 1.0F));
        float inverseW = 1.0F / clip.w;
        return new Vector2f(
                clip.x * inverseW * 0.5F + 0.5F,
                clip.y * inverseW * -0.5F + 0.5F);
    }
}
