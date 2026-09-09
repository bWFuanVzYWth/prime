// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class FrameCameraTest {
    @Test
    void publicConstructionDoesNotAliasMutableSourceMatrices() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 512.0F);
        Matrix4f viewRotation = new Matrix4f().rotateY(0.25F);
        Matrix4f inverseViewProjection =
                new Matrix4f(projection).mul(viewRotation).invert();
        Matrix4f expectedProjection = new Matrix4f(projection);
        Matrix4f expectedViewRotation = new Matrix4f(viewRotation);
        Matrix4f expectedInverseViewProjection = new Matrix4f(inverseViewProjection);
        FrameCamera camera = new FrameCamera(
                projection,
                viewRotation,
                inverseViewProjection,
                1.0,
                2.0,
                3.0,
                1.25,
                2.5,
                3.75);
        projection.zero();
        viewRotation.zero();
        inverseViewProjection.zero();

        assertEquals(expectedProjection, camera.projection());
        assertEquals(expectedViewRotation, camera.viewRotation());
        assertEquals(expectedInverseViewProjection, camera.inverseViewProjection());
    }

    @Test
    void valueEqualityIsPreservedAfterRecordReplacement() {
        Matrix4f projection = new Matrix4f().scale(2.0F);
        Matrix4f viewRotation = new Matrix4f().rotateX(0.5F);
        Matrix4f inverseViewProjection = new Matrix4f().translation(1.0F, 2.0F, 3.0F);
        FrameCamera first = new FrameCamera(
                projection,
                viewRotation,
                inverseViewProjection,
                1.0,
                2.0,
                3.0,
                4.0,
                5.0,
                6.0);
        FrameCamera second = new FrameCamera(
                projection,
                viewRotation,
                inverseViewProjection,
                1.0,
                2.0,
                3.0,
                4.0,
                5.0,
                6.0);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
