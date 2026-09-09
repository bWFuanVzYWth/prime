// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/** Detects camera translation large enough to replace the visible scene in one frame. */
public final class CameraDiscontinuity {
    static final double TELEPORT_DISTANCE = 32.0;

    private CameraDiscontinuity() {
    }

    public static boolean isCut(FrameCamera previous, FrameCamera current) {
        if (previous == null || current == null) {
            return true;
        }
        double dx = current.x() - previous.x();
        double dy = current.y() - previous.y();
        double dz = current.z() - previous.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        return !Double.isFinite(distanceSquared)
                || distanceSquared > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
    }
}
