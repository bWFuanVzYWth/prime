package dev.prime.render;

import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Canonical camera data shared by ray generation and temporal reconstruction.
 *
 * <p>Minecraft renders the world with {@code renderedProjection * cameraViewRotation}, but view
 * bob and hurt effects are appended to the projection even though they are affine camera-space
 * transforms. NRD requires a non-jittered projection and a world-to-view matrix. For rigid camera
 * effects Prime therefore decomposes the exact rendered transform into:
 *
 * <ul>
 *   <li>{@code projection}: Minecraft's untouched perspective projection;</li>
 *   <li>{@code viewRotation}: an orthonormal world-to-view rotation with no translation;</li>
 *   <li>{@code renderX/Y/Z}: the effective pinhole, including the affine view-effect offset.</li>
 * </ul>
 *
 * <p>{@code x/y/z} remain the physical Minecraft camera position for terrain streaming and
 * atmospheric altitude. {@code inverseViewProjection} remains the inverse of the exact Mojang
 * transform, so ray directions are bit-for-bit based on the matrix used for world rendering.
 * JOML, GLSL and NRD all use column vectors and column-major storage; no transpose is applied.
 * Minecraft Vulkan depth is [0, 1] reversed-Z (near=1, far=0). Prime core images map row zero to
 * NDC y=+1; the presentation boundary flips once when writing Minecraft's bottom-up target.
 */
public final class FrameCamera {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final Matrix4f projection;
    private final Matrix4f viewRotation;
    private final Matrix4f inverseViewProjection;
    private final double x;
    private final double y;
    private final double z;
    private final double renderX;
    private final double renderY;
    private final double renderZ;

    public FrameCamera(
            Matrix4fc projection,
            Matrix4fc viewRotation,
            Matrix4fc inverseViewProjection,
            double x,
            double y,
            double z,
            double renderX,
            double renderY,
            double renderZ) {
        this(
                new Matrix4f(Objects.requireNonNull(projection, "projection")),
                new Matrix4f(Objects.requireNonNull(viewRotation, "view rotation")),
                new Matrix4f(Objects.requireNonNull(
                        inverseViewProjection, "inverse view projection")),
                x,
                y,
                z,
                renderX,
                renderY,
                renderZ);
    }

    // Only fresh matrices already owned by this class may use this overload. It avoids three
    // defensive copies on the per-frame camera capture path.
    private FrameCamera(
            Matrix4f projection,
            Matrix4f viewRotation,
            Matrix4f inverseViewProjection,
            double x,
            double y,
            double z,
            double renderX,
            double renderY,
            double renderZ) {
        this.projection = projection;
        this.viewRotation = viewRotation;
        this.inverseViewProjection = inverseViewProjection;
        this.x = x;
        this.y = y;
        this.z = z;
        this.renderX = renderX;
        this.renderY = renderY;
        this.renderZ = renderZ;
    }

    FrameCamera(Matrix4f inverseViewProjection, double x, double y, double z) {
        this(
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(inverseViewProjection),
                x,
                y,
                z,
                x,
                y,
                z);
    }

    public Matrix4fc projection() {
        return this.projection;
    }

    public Matrix4fc viewRotation() {
        return this.viewRotation;
    }

    public Matrix4fc inverseViewProjection() {
        return this.inverseViewProjection;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public double renderX() {
        return this.renderX;
    }

    public double renderY() {
        return this.renderY;
    }

    public double renderZ() {
        return this.renderZ;
    }

    /** True when every captured matrix and physical/effective position can cross a GPU boundary. */
    public boolean isFinite() {
        return this.projection.isFinite()
                && this.viewRotation.isFinite()
                && this.inverseViewProjection.isFinite()
                && Double.isFinite(this.x)
                && Double.isFinite(this.y)
                && Double.isFinite(this.z)
                && Double.isFinite(this.renderX)
                && Double.isFinite(this.renderY)
                && Double.isFinite(this.renderZ);
    }

    public static FrameCamera tryCreate(
            Matrix4fc renderedProjection,
            Matrix4fc baseProjection,
            Matrix4fc cameraViewRotation,
            double x,
            double y,
            double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        Matrix4f projectionCopy = new Matrix4f(baseProjection);
        Matrix4f exactViewProjection = new Matrix4f(renderedProjection).mul(cameraViewRotation);
        if (!isInvertible(exactViewProjection) || !isInvertible(projectionCopy)) {
            return null;
        }

        Scratch scratch = SCRATCH.get();
        Matrix4f cameraEffect = scratch.cameraEffect.set(projectionCopy)
                .invert()
                .mul(renderedProjection);
        Matrix4f effectedWorldToView = cameraEffect.mul(cameraViewRotation);
        Matrix4f canonicalWorldToView = new Matrix4f(effectedWorldToView);
        double effectiveX = x;
        double effectiveY = y;
        double effectiveZ = z;
        if (isRigid(effectedWorldToView)) {
            Vector3f cameraOffset = effectedWorldToView.invert()
                    .transformPosition(scratch.cameraOffset.zero());
            if (!cameraOffset.isFinite()) {
                return null;
            }
            effectiveX += cameraOffset.x;
            effectiveY += cameraOffset.y;
            effectiveZ += cameraOffset.z;
            canonicalWorldToView.m30(0.0F).m31(0.0F).m32(0.0F);
        } else {
            // Portal/nausea scaling is not an orthonormal camera. Preserve Mojang's exact rays and
            // the previous NRD fallback rather than pretending the scale is a rigid transform.
            projectionCopy.set(renderedProjection);
            canonicalWorldToView.set(cameraViewRotation);
        }

        Matrix4f inverse = exactViewProjection.invert();
        return inverse.isFinite()
                ? new FrameCamera(
                        projectionCopy,
                        canonicalWorldToView,
                        inverse,
                        x,
                        y,
                        z,
                        effectiveX,
                        effectiveY,
                        effectiveZ)
                : null;
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }
        if (!(object instanceof FrameCamera other)) {
            return false;
        }
        return this.projection.equals(other.projection)
                && this.viewRotation.equals(other.viewRotation)
                && this.inverseViewProjection.equals(other.inverseViewProjection)
                && Double.compare(this.x, other.x) == 0
                && Double.compare(this.y, other.y) == 0
                && Double.compare(this.z, other.z) == 0
                && Double.compare(this.renderX, other.renderX) == 0
                && Double.compare(this.renderY, other.renderY) == 0
                && Double.compare(this.renderZ, other.renderZ) == 0;
    }

    @Override
    public int hashCode() {
        int result = this.projection.hashCode();
        result = 31 * result + this.viewRotation.hashCode();
        result = 31 * result + this.inverseViewProjection.hashCode();
        result = 31 * result + Double.hashCode(this.x);
        result = 31 * result + Double.hashCode(this.y);
        result = 31 * result + Double.hashCode(this.z);
        result = 31 * result + Double.hashCode(this.renderX);
        result = 31 * result + Double.hashCode(this.renderY);
        return 31 * result + Double.hashCode(this.renderZ);
    }

    private static boolean isInvertible(Matrix4fc matrix) {
        float determinant = matrix.determinant();
        return matrix.isFinite()
                && Float.isFinite(determinant)
                && Math.abs(determinant) >= 1.0e-20F;
    }

    private static boolean isRigid(Matrix4fc matrix) {
        float tolerance = 1.0e-3F;
        if (Math.abs(matrix.m03()) > tolerance
                || Math.abs(matrix.m13()) > tolerance
                || Math.abs(matrix.m23()) > tolerance
                || Math.abs(matrix.m33() - 1.0F) > tolerance) {
            return false;
        }
        float xLengthSquared = matrix.m00() * matrix.m00()
                + matrix.m01() * matrix.m01()
                + matrix.m02() * matrix.m02();
        float yLengthSquared = matrix.m10() * matrix.m10()
                + matrix.m11() * matrix.m11()
                + matrix.m12() * matrix.m12();
        float zLengthSquared = matrix.m20() * matrix.m20()
                + matrix.m21() * matrix.m21()
                + matrix.m22() * matrix.m22();
        float xy = matrix.m00() * matrix.m10()
                + matrix.m01() * matrix.m11()
                + matrix.m02() * matrix.m12();
        float xz = matrix.m00() * matrix.m20()
                + matrix.m01() * matrix.m21()
                + matrix.m02() * matrix.m22();
        float yz = matrix.m10() * matrix.m20()
                + matrix.m11() * matrix.m21()
                + matrix.m12() * matrix.m22();
        return Math.abs(xLengthSquared - 1.0F) <= tolerance
                && Math.abs(yLengthSquared - 1.0F) <= tolerance
                && Math.abs(zLengthSquared - 1.0F) <= tolerance
                && Math.abs(xy) <= tolerance
                && Math.abs(xz) <= tolerance
                && Math.abs(yz) <= tolerance;
    }

    private static final class Scratch {
        private final Matrix4f cameraEffect = new Matrix4f();
        private final Vector3f cameraOffset = new Vector3f();
    }
}
