// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class AerialEpipolarMappingTest {
    private static final float EPSILON = 1.0e-5F;

    @Test
    void projectsTheSunThroughTheExactInverseViewProjection() {
        Matrix4f viewProjection = new Matrix4f()
                .perspective((float) (Math.PI * 0.5), 1.0F, 0.1F, 1000.0F);
        FrameCamera camera = new FrameCamera(
                new Matrix4f(viewProjection).invert(),
                0.0,
                0.0,
                0.0);
        float inverseRootTwo = (float) (1.0 / Math.sqrt(2.0));

        AerialEpipolarMapping.Epipole center =
                AerialEpipolarMapping.project(
                        camera,
                        new SunDirection(0.0F, 0.0F, -1.0F));
        AerialEpipolarMapping.Epipole right =
                AerialEpipolarMapping.project(
                        camera,
                        new SunDirection(
                                inverseRootTwo,
                                0.0F,
                                -inverseRootTwo));

        assertEquals(0.0F, center.x(), EPSILON);
        assertEquals(0.0F, center.y(), EPSILON);
        assertEquals(1.0F, right.x(), EPSILON);
        assertEquals(0.0F, right.y(), EPSILON);
    }

    @Test
    void representsAParallelProjectionWithABoundedDistantEpipole() {
        Matrix4f viewProjection = new Matrix4f()
                .perspective((float) (Math.PI * 0.5), 1.0F, 0.1F, 1000.0F);
        FrameCamera camera = new FrameCamera(
                new Matrix4f(viewProjection).invert(),
                0.0,
                0.0,
                0.0);

        AerialEpipolarMapping.Epipole epipole =
                AerialEpipolarMapping.project(
                        camera,
                        new SunDirection(1.0F, 0.0F, 0.0F));

        assertEquals(AerialEpipolarMapping.PROJECTION_LIMIT, epipole.x());
        assertEquals(0.0F, epipole.y(), EPSILON);
    }
}
