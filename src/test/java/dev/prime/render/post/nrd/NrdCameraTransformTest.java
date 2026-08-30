package dev.prime.render.post.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.data.RendererDataContracts;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

final class NrdCameraTransformTest {
    private static final float EPSILON = 2.0e-5F;
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            (float) Math.toRadians(70.0), 16.0F / 9.0F, 512.0F, 0.05F, true);
    private static final Vector3f STATIC_WORLD_POINT = new Vector3f(0.0F, 0.0F, -10.0F);

    @Test
    void nrdProjectionNamesTheSameImageRowsAsPrime() {
        Matrix4f nrdProjection = NrdCameraTransform.projectionForNrd(PROJECTION);
        Vector2f upperInPrimeImage = NrdCameraTransform.screenUv(
                nrdProjection, new Vector3f(0.0F, -1.0F, -10.0F));
        Vector2f lowerInPrimeImage = NrdCameraTransform.screenUv(
                nrdProjection, new Vector3f(0.0F, 1.0F, -10.0F));

        assertTrue(upperInPrimeImage.y < 0.5F);
        assertTrue(lowerInPrimeImage.y > 0.5F);
    }

    @Test
    void forwardAndUpwardCameraMotionReprojectsToThePreviousPixel() {
        FrameCamera previous = camera(new Matrix4f(), 0.0, 0.0, 0.0);

        FrameCamera movedForward = camera(new Matrix4f(), 0.0, 0.0, -1.0);
        assertReprojectsStaticPoint(previous, movedForward);
        float forwardDepthDelta = previousViewZ(previous, movedForward)
                - currentViewZ(movedForward);
        assertTrue(forwardDepthDelta > 0.0F, "approaching geometry must increase previous-current view Z");

        FrameCamera movedUp = camera(new Matrix4f(), 0.0, 1.0, 0.0);
        Motion upwardMotion = motion(previous, movedUp);
        assertTrue(upwardMotion.currentUv.y < upwardMotion.previousUv.y);
        assertTrue(upwardMotion.vector.y > 0.0F);
        assertReprojectsStaticPoint(previous, movedUp);
    }

    @Test
    void yawAndPitchReprojectInTheExactOppositeDirectionOfCurrentImageMotion() {
        FrameCamera previous = camera(new Matrix4f(), 0.0, 0.0, 0.0);

        FrameCamera yawed = camera(
                new Matrix4f().rotateY((float) Math.toRadians(12.0)), 0.0, 0.0, 0.0);
        Motion yawMotion = motion(previous, yawed);
        assertTrue(yawMotion.currentUv.x < yawMotion.previousUv.x);
        assertTrue(yawMotion.vector.x > 0.0F);
        assertReprojectsStaticPoint(previous, yawed);

        FrameCamera pitched = camera(
                new Matrix4f().rotateX((float) Math.toRadians(9.0)), 0.0, 0.0, 0.0);
        Motion pitchMotion = motion(previous, pitched);
        assertTrue(pitchMotion.currentUv.y > pitchMotion.previousUv.y);
        assertTrue(pitchMotion.vector.y < 0.0F);
        assertReprojectsStaticPoint(previous, pitched);
    }

    @Test
    void jitteredHitAndSkyDirectionsHaveZeroMotionForAStaticCamera() {
        FrameCamera camera = camera(new Matrix4f(), 0.0, 0.0, 0.0);
        Matrix4f clipToWorld = NrdCameraTransform.currentClipToWorld(camera);
        Matrix4f previousWorldToClip = NrdCameraTransform.previousWorldToClip(camera, camera);
        int width = 1920;
        int height = 1080;
        int pixelX = 733;
        int pixelY = 419;
        for (int phase = 1; phase <= 32; phase++) {
            float jitterX = halton(phase, 2) - 0.5F;
            float jitterY = halton(phase, 3) - 0.5F;
            Vector2f sampleUv = new Vector2f(
                    (pixelX + 0.5F + jitterX) / width,
                    (pixelY + 0.5F + jitterY) / height);
            Vector3f ray = rayDirection(clipToWorld, sampleUv);
            Vector3f hit = new Vector3f(ray).mul(37.0F);

            Vector2f previousHitUv = NrdCameraTransform.screenUv(previousWorldToClip, hit);
            Vector2f previousSkyUv = skyUv(previousWorldToClip, ray);
            assertEquals(0.0F, previousHitUv.x - sampleUv.x, EPSILON);
            assertEquals(0.0F, previousHitUv.y - sampleUv.y, EPSILON);
            assertEquals(0.0F, previousSkyUv.x - sampleUv.x, EPSILON);
            assertEquals(0.0F, previousSkyUv.y - sampleUv.y, EPSILON);

            Vector2f centerUv = new Vector2f(
                    (pixelX + 0.5F) / width,
                    (pixelY + 0.5F) / height);
            assertEquals(jitterX / width, previousHitUv.x - centerUv.x, EPSILON);
            assertEquals(jitterY / height, previousHitUv.y - centerUv.y, EPSILON);
        }
    }

    @Test
    void pixelUnitsPreserveSmallScreenMotionThroughFp16() {
        int width = 3840;
        float uvMotion = 0.0000002F;

        float normalizedRoundTrip = Float.float16ToFloat(Float.floatToFloat16(uvMotion));
        float pixelRoundTrip = Float.float16ToFloat(Float.floatToFloat16(uvMotion * width))
                / width;

        assertTrue(Math.abs(pixelRoundTrip - uvMotion) < Math.abs(normalizedRoundTrip - uvMotion));
        assertEquals(uvMotion, pixelRoundTrip, 1.0e-10F);
    }

    private static void assertReprojectsStaticPoint(FrameCamera previous, FrameCamera current) {
        Motion motion = motion(previous, current);
        assertEquals(motion.previousUv.x, motion.currentUv.x + motion.vector.x, EPSILON);
        assertEquals(motion.previousUv.y, motion.currentUv.y + motion.vector.y, EPSILON);

        Vector3f directPreviousRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) previous.renderX(),
                STATIC_WORLD_POINT.y - (float) previous.renderY(),
                STATIC_WORLD_POINT.z - (float) previous.renderZ());
        Vector2f directPreviousUv = NrdCameraTransform.screenUv(
                NrdCameraTransform.projectionForNrd(previous.projection())
                        .mul(previous.viewRotation()),
                directPreviousRelative);
        assertEquals(directPreviousUv.x, motion.previousUv.x, EPSILON);
        assertEquals(directPreviousUv.y, motion.previousUv.y, EPSILON);

    }

    private static Motion motion(FrameCamera previous, FrameCamera current) {
        Vector3f currentRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) current.renderX(),
                STATIC_WORLD_POINT.y - (float) current.renderY(),
                STATIC_WORLD_POINT.z - (float) current.renderZ());
        Matrix4f currentWorldToClip = NrdCameraTransform.projectionForNrd(current.projection())
                .mul(current.viewRotation());
        Vector2f currentUv = NrdCameraTransform.screenUv(currentWorldToClip, currentRelative);
        Vector2f previousUv = NrdCameraTransform.screenUv(
                NrdCameraTransform.previousWorldToClip(current, previous), currentRelative);
        double[] canonical = RendererDataContracts.visibleMotionUv(
                previousUv.x, previousUv.y, currentUv.x, currentUv.y);
        Vector2f vector = new Vector2f(previousUv).sub(currentUv);
        assertEquals(canonical[0], vector.x, EPSILON);
        assertEquals(canonical[1], vector.y, EPSILON);
        return new Motion(currentUv, previousUv, vector);
    }

    private static float currentViewZ(FrameCamera current) {
        Vector3f currentRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) current.renderX(),
                STATIC_WORLD_POINT.y - (float) current.renderY(),
                STATIC_WORLD_POINT.z - (float) current.renderZ());
        return Math.abs(current.viewRotation().transformPosition(currentRelative).z);
    }

    private static float previousViewZ(FrameCamera previous, FrameCamera current) {
        Vector3f currentRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) current.renderX(),
                STATIC_WORLD_POINT.y - (float) current.renderY(),
                STATIC_WORLD_POINT.z - (float) current.renderZ());
        return Math.abs(NrdCameraTransform.previousWorldToView(current, previous)
                .transformPosition(currentRelative)
                .z);
    }

    private static Vector3f rayDirection(Matrix4f clipToWorld, Vector2f uv) {
        float clipX = uv.x * 2.0F - 1.0F;
        float clipY = 1.0F - uv.y * 2.0F;
        Vector4f near = clipToWorld.transform(new Vector4f(clipX, clipY, 1.0F, 1.0F));
        Vector4f far = clipToWorld.transform(new Vector4f(clipX, clipY, 0.0F, 1.0F));
        near.div(near.w);
        far.div(far.w);
        return new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
    }

    private static Vector2f skyUv(Matrix4f worldToClip, Vector3f direction) {
        Vector4f clip = worldToClip.transform(
                new Vector4f(direction.x, direction.y, direction.z, 0.0F));
        return new Vector2f(
                clip.x / clip.w * 0.5F + 0.5F,
                clip.y / clip.w * -0.5F + 0.5F);
    }

    private static float halton(int index, int base) {
        float value = 0.0F;
        float fraction = 1.0F;
        int remaining = index;
        while (remaining > 0) {
            fraction /= base;
            value += fraction * (remaining % base);
            remaining /= base;
        }
        return value;
    }

    private static FrameCamera camera(Matrix4f viewRotation, double x, double y, double z) {
        Matrix4f inverseViewProjection = new Matrix4f(PROJECTION)
                .mul(viewRotation)
                .invert();
        return new FrameCamera(
                new Matrix4f(PROJECTION),
                new Matrix4f(viewRotation),
                inverseViewProjection,
                x,
                y,
                z,
                x,
                y,
                z);
    }

    private record Motion(Vector2f currentUv, Vector2f previousUv, Vector2f vector) {}
}
