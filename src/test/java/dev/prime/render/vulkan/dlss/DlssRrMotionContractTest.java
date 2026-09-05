package dev.prime.render.vulkan.dlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.nrd.NrdCameraTransform;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

final class DlssRrMotionContractTest {
    private static final float EPSILON = 3.0e-5F;
    private static final float PIXEL_EPSILON = 1.0e-4F;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int PIXEL_X = 941;
    private static final int PIXEL_Y = 527;
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            (float) Math.toRadians(70.0), (float) WIDTH / HEIGHT, 512.0F, 0.05F, true);

    @Test
    void ngxReceivesTheOppositeOfTheRaySampleJitter() {
        assertEquals(-0.25F, DlssRrNative.ngxJitterOffset(0.25F), 0.0F);
        assertEquals(0.375F, DlssRrNative.ngxJitterOffset(-0.375F), 0.0F);
    }

    @Test
    void everyRrJitterPhaseProducesZeroSurfaceAndSkyMotionForAStaticCamera() {
        FrameCamera camera = camera(new Matrix4f(), 0.0, 0.0, 0.0);

        for (ReconstructionQualityMode quality : ReconstructionQualityMode.values()) {
            for (int frame = 0; frame < DlssRrProfile.jitterPhaseCount(quality); frame++) {
                SubpixelJitter jitter = DlssRrProfile.jitter(quality, frame);
                Vector2f sampleUv = sampleUv(jitter);
                Vector3f sampledDirection = rayDirection(camera, sampleUv);
                Vector3f primaryPosition = new Vector3f(sampledDirection).mul(20.0F);

                Vector2f surfaceMotion = motion(
                        camera, camera, sampleUv, primaryPosition, false);
                Vector2f skyMotion = motion(
                        camera, camera, sampleUv, primaryPosition, true);
                assertVectorEquals(new Vector2f(), surfaceMotion);
                assertVectorEquals(new Vector2f(), skyMotion);

                Vector2f oldCenterUv = new Vector2f(
                        (PIXEL_X + 0.5F) / WIDTH,
                        (PIXEL_Y + 0.5F) / HEIGHT);
                Vector2f oldMotion = new Vector2f(sampleUv).sub(oldCenterUv);
                assertEquals(jitter.x(), oldMotion.x * WIDTH, PIXEL_EPSILON);
                assertEquals(jitter.y(), oldMotion.y * HEIGHT, PIXEL_EPSILON);
            }
        }
    }

    @Test
    void resetFrameUsesTheSameZeroMotionContractAtPhaseZero() {
        FrameCamera resetCamera = camera(
                new Matrix4f().rotateY((float) Math.toRadians(7.0)), 4.0, 2.0, -3.0);
        SubpixelJitter jitter = DlssRrProfile.jitter(
                ReconstructionQualityMode.PERFORMANCE, 0);
        Vector2f sampleUv = sampleUv(jitter);
        Vector3f primaryPosition = rayDirection(resetCamera, sampleUv).mul(12.0F);

        assertVectorEquals(
                new Vector2f(),
                motion(resetCamera, resetCamera, sampleUv, primaryPosition, false));
        assertVectorEquals(
                new Vector2f(),
                motion(resetCamera, resetCamera, sampleUv, primaryPosition, true));
    }

    @Test
    void cameraTranslationAndRotationRemainCurrentToPreviousAndScaleToRenderPixels() {
        FrameCamera previous = camera(new Matrix4f(), 0.0, 0.0, 0.0);
        FrameCamera current = camera(
                new Matrix4f()
                        .rotateY((float) Math.toRadians(8.0))
                        .rotateX((float) Math.toRadians(4.0)),
                0.4,
                0.6,
                -0.8);
        SubpixelJitter jitter = DlssRrProfile.jitter(
                ReconstructionQualityMode.PERFORMANCE, 17);
        Vector2f currentSampleUv = sampleUv(jitter);
        Vector3f primaryPosition = rayDirection(current, currentSampleUv).mul(18.0F);
        Vector2f previousUv = projectSurface(current, previous, primaryPosition);
        Vector2f normalizedMotion = motion(
                current, previous, currentSampleUv, primaryPosition, false);
        Vector2f motionPixels = new Vector2f(
                normalizedMotion.x * WIDTH,
                normalizedMotion.y * HEIGHT);

        assertEquals(previousUv.x, currentSampleUv.x + normalizedMotion.x, EPSILON);
        assertEquals(previousUv.y, currentSampleUv.y + normalizedMotion.y, EPSILON);
        assertEquals(
                previousUv.x * WIDTH,
                currentSampleUv.x * WIDTH + motionPixels.x,
                EPSILON * WIDTH);
        assertEquals(
                previousUv.y * HEIGHT,
                currentSampleUv.y * HEIGHT + motionPixels.y,
                EPSILON * HEIGHT);
        assertTrue(motionPixels.lengthSquared() > 1.0F);
    }

    @Test
    void physicalTraversalOriginKeepsStaticReflectionOnTheCameraRay() {
        FrameCamera camera = camera(new Matrix4f(), 0.0, 0.0, 0.0);
        Vector2f currentSampleUv = sampleUv(DlssRrProfile.jitter(
                ReconstructionQualityMode.QUALITY, 5));
        Vector3f cameraRay = rayDirection(camera, currentSampleUv);
        Vector3f primaryPosition = new Vector3f(cameraRay).mul(9.0F);
        Vector3f planeNormal = new Vector3f(0.8F, 0.1F, 0.59F).normalize();
        Vector3f reflectionDirection = reflect(cameraRay, planeNormal);
        Vector3f tracedTarget = new Vector3f(primaryPosition)
                .fma(31.0F, reflectionDirection);
        Vector3f virtualPosition = mirrorPoint(
                tracedTarget, primaryPosition, planeNormal);

        assertVectorEquals(
                new Vector2f(),
                motion(camera, camera, currentSampleUv, virtualPosition, false));
        assertTrue(new Vector3f(virtualPosition).cross(cameraRay)
                .lengthSquared() < 1.0e-8F);
    }

    @Test
    void transmissionAnchorPreservesIndependentTargetMotion() {
        FrameCamera camera = camera(new Matrix4f(), 0.0, 0.0, 0.0);
        SubpixelJitter jitter = DlssRrProfile.jitter(
                ReconstructionQualityMode.BALANCED, 6);
        Vector2f currentSampleUv = sampleUv(jitter);
        Vector3f currentAnchorPosition = rayDirection(camera, currentSampleUv)
                .mul(28.0F);
        Vector3f previousVirtualPosition = new Vector3f(currentAnchorPosition)
                .add(0.45F, -0.2F, 0.1F);

        Vector2f targetMotion = motion(
                camera,
                camera,
                currentSampleUv,
                previousVirtualPosition,
                false);
        Vector2f oldAnchorMotion = motion(
                camera,
                camera,
                currentSampleUv,
                currentAnchorPosition,
                false);

        assertTrue(targetMotion.lengthSquared() > 1.0e-8F);
        assertVectorEquals(new Vector2f(), oldAnchorMotion);
    }

    private static Vector2f sampleUv(SubpixelJitter jitter) {
        return new Vector2f(
                (PIXEL_X + 0.5F + jitter.x()) / WIDTH,
                (PIXEL_Y + 0.5F + jitter.y()) / HEIGHT);
    }

    private static Vector2f motion(
            FrameCamera current,
            FrameCamera previous,
            Vector2f currentSampleUv,
            Vector3f primaryPosition,
            boolean sky) {
        Vector2f previousUv = sky
                ? projectSky(current, previous, rayDirection(current, currentSampleUv))
                : projectSurface(current, previous, primaryPosition);
        return previousUv.sub(currentSampleUv, new Vector2f());
    }

    private static Vector3f reflect(Vector3f direction, Vector3f normal) {
        return new Vector3f(direction).fma(
                -2.0F * direction.dot(normal), normal);
    }

    private static Vector3f mirrorPoint(
            Vector3f point, Vector3f planePoint, Vector3f normal) {
        return new Vector3f(point).fma(
                -2.0F * new Vector3f(point).sub(planePoint).dot(normal),
                normal);
    }

    private static Vector2f projectSurface(
            FrameCamera current, FrameCamera previous, Vector3f position) {
        Vector4f clip = NrdCameraTransform.previousWorldToClip(current, previous)
                .transform(new Vector4f(position, 1.0F));
        return screenUv(clip);
    }

    private static Vector2f projectSky(
            FrameCamera current, FrameCamera previous, Vector3f direction) {
        Vector4f clip = NrdCameraTransform.previousWorldToClip(current, previous)
                .transform(new Vector4f(direction, 0.0F));
        return screenUv(clip);
    }

    private static Vector2f screenUv(Vector4f clip) {
        float inverseW = 1.0F / clip.w;
        return new Vector2f(
                clip.x * inverseW * 0.5F + 0.5F,
                clip.y * inverseW * -0.5F + 0.5F);
    }

    private static Vector3f rayDirection(FrameCamera camera, Vector2f screenUv) {
        Matrix4f clipToWorld = NrdCameraTransform.currentClipToWorld(camera);
        float clipX = screenUv.x * 2.0F - 1.0F;
        float clipY = screenUv.y * -2.0F + 1.0F;
        Vector4f near = clipToWorld.transform(new Vector4f(clipX, clipY, 1.0F, 1.0F));
        Vector4f far = clipToWorld.transform(new Vector4f(clipX, clipY, 0.0F, 1.0F));
        near.div(near.w);
        far.div(far.w);
        return new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
    }

    private static void assertVectorEquals(Vector2f expected, Vector2f actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
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
}
