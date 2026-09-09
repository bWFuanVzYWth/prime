// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import java.util.Objects;
import org.joml.Matrix4f;

/** Projects the directional sun into the exact camera raster used by the aerial LUT. */
public final class AerialEpipolarMapping {
    public static final float PROJECTION_LIMIT = 16_384.0F;
    private static final float HOMOGENEOUS_EPSILON = 1.0e-6F;

    private AerialEpipolarMapping() {}

    public static Epipole project(
            FrameCamera camera,
            SunDirection direction) {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(direction, "direction");
        Matrix4f viewProjection =
                new Matrix4f(camera.inverseViewProjection()).invert();
        float clipX = viewProjection.m00() * direction.x()
                + viewProjection.m10() * direction.y()
                + viewProjection.m20() * direction.z();
        float clipY = viewProjection.m01() * direction.x()
                + viewProjection.m11() * direction.y()
                + viewProjection.m21() * direction.z();
        float clipW = viewProjection.m03() * direction.x()
                + viewProjection.m13() * direction.y()
                + viewProjection.m23() * direction.z();
        float x;
        float y;
        if (Math.abs(clipW) > HOMOGENEOUS_EPSILON) {
            x = clipX / clipW;
            y = clipY / clipW;
        } else {
            float length = (float) Math.hypot(clipX, clipY);
            if (length > HOMOGENEOUS_EPSILON) {
                x = clipX / length * PROJECTION_LIMIT;
                y = clipY / length * PROJECTION_LIMIT;
            } else {
                x = 0.0F;
                y = 0.0F;
            }
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return new Epipole(0.0F, 0.0F);
        }
        return new Epipole(
                Math.max(-PROJECTION_LIMIT, Math.min(PROJECTION_LIMIT, x)),
                Math.max(-PROJECTION_LIMIT, Math.min(PROJECTION_LIMIT, y)));
    }

    public record Epipole(float x, float y) {
        public Epipole {
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                throw new IllegalArgumentException("Epipole must be finite");
            }
        }
    }
}
