package dev.prime.streamline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.binding.streamline.Constants;
import dev.prime.binding.streamline.SlBoolean;
import dev.prime.render.FrameCamera;
import dev.prime.render.data.RendererDataContracts;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.shader.ShaderAbi;
import java.lang.foreign.Arena;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

final class StreamlineFrameConstantsTest {
    private static final float EPSILON = 3.0e-5F;
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            (float) Math.toRadians(70.0), 16.0F / 9.0F, 512.0F, 0.05F, true);

    @Test
    void constantsUseRowMajorMatricesAndCompleteCameraMetadata() {
        FrameCamera camera = camera(
                new Matrix4f().rotateY((float) Math.toRadians(20.0)),
                12.0,
                34.0,
                -56.0);
        StreamlineFrameConstants values = StreamlineFrameConstants.create(
                camera,
                camera,
                new SubpixelJitter(0.25F, -0.375F),
                true,
                1280,
                720);

        try (Arena arena = Arena.ofConfined()) {
            Constants constants = Constants.allocate(arena);
            values.write(constants);

            assertArrayEquals(rowMajor(PROJECTION), constants.cameraViewToClip());
            double[] canonicalJitter =
                    RendererDataContracts.projectionJitterPixels(0.25, -0.375);
            assertEquals(canonicalJitter[0], constants.jitterOffset()[0], 0.0);
            assertEquals(canonicalJitter[1], constants.jitterOffset()[1], 0.0);
            assertEquals(1280.0F, constants.mvecScale()[0]);
            assertEquals(720.0F, constants.mvecScale()[1]);
            assertEquals(12.0F, constants.cameraPos()[0]);
            assertEquals(34.0F, constants.cameraPos()[1]);
            assertEquals(-56.0F, constants.cameraPos()[2]);
            assertUnit(constants.cameraUp());
            assertUnit(constants.cameraRight());
            assertUnit(constants.cameraFwd());
            assertEquals(Float.MAX_VALUE, constants.cameraNear());
            assertEquals(ShaderAbi.FSR_NEAR_PLANE, constants.cameraFar());
            assertEquals(Float.MAX_VALUE, constants.motionVectorsInvalidValue());
            assertEquals(SlBoolean.TRUE, constants.depthInverted());
            assertEquals(SlBoolean.TRUE, constants.cameraMotionIncluded());
            assertEquals(SlBoolean.TRUE, constants.reset());
            assertEquals(SlBoolean.FALSE, constants.motionVectorsJittered());
        }
    }

    @Test
    void clipHistoryReprojectsTranslationAndRotationToThePreviousCamera() {
        FrameCamera previous = camera(
                new Matrix4f().rotateY((float) Math.toRadians(-8.0)),
                100.0,
                20.0,
                -40.0);
        FrameCamera current = camera(
                new Matrix4f()
                        .rotateY((float) Math.toRadians(13.0))
                        .rotateX((float) Math.toRadians(4.0)),
                102.5,
                21.0,
                -43.0);
        StreamlineFrameConstants values = StreamlineFrameConstants.create(
                current, previous, new SubpixelJitter(0.0F, 0.0F), false, 1920, 1080);
        Vector4f world = new Vector4f(90.0F, 23.0F, -70.0F, 1.0F);
        Vector4f currentRelative = new Vector4f(
                world.x - (float) current.renderX(),
                world.y - (float) current.renderY(),
                world.z - (float) current.renderZ(),
                1.0F);
        Vector4f currentClip = new Matrix4f(current.projection())
                .mul(current.viewRotation())
                .transform(currentRelative);
        Vector4f transformedPreviousClip = values.transformClipToPrevious(currentClip);
        Vector4f directPreviousClip = new Matrix4f(previous.projection())
                .mul(previous.viewRotation())
                .transform(new Vector4f(
                        world.x - (float) previous.renderX(),
                        world.y - (float) previous.renderY(),
                        world.z - (float) previous.renderZ(),
                        1.0F));
        assertHomogeneousEquals(directPreviousClip, transformedPreviousClip);

        Vector4f roundTrip = values.transformPreviousClipToCurrent(transformedPreviousClip);
        assertHomogeneousEquals(currentClip, roundTrip);
    }

    @Test
    void invalidCameraOrMotionExtentFailsBeforeNativeSubmission() {
        FrameCamera camera = camera(new Matrix4f(), 0.0, 0.0, 0.0);
        assertThrows(
                IllegalArgumentException.class,
                () -> StreamlineFrameConstants.create(
                        camera, camera, new SubpixelJitter(0.0F, 0.0F), false, 0, 720));
        FrameCamera unrepresentable = camera(new Matrix4f(), Double.MAX_VALUE, 0.0, 0.0);
        assertThrows(
                IllegalArgumentException.class,
                () -> StreamlineFrameConstants.create(
                        unrepresentable,
                        unrepresentable,
                        new SubpixelJitter(0.0F, 0.0F),
                        false,
                        1280,
                        720));
    }

    private static FrameCamera camera(Matrix4f viewRotation, double x, double y, double z) {
        return new FrameCamera(
                PROJECTION,
                viewRotation,
                new Matrix4f(PROJECTION).mul(viewRotation).invert(),
                x,
                y,
                z,
                x,
                y,
                z);
    }

    private static float[] rowMajor(Matrix4f matrix) {
        return new float[] {
            matrix.m00(), matrix.m10(), matrix.m20(), matrix.m30(),
            matrix.m01(), matrix.m11(), matrix.m21(), matrix.m31(),
            matrix.m02(), matrix.m12(), matrix.m22(), matrix.m32(),
            matrix.m03(), matrix.m13(), matrix.m23(), matrix.m33()
        };
    }

    private static void assertArrayEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], 0.0F, "matrix element " + index);
        }
    }

    private static void assertUnit(float[] vector) {
        assertTrue(vector.length == 3);
        float length = (float) Math.sqrt(
                vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        assertEquals(1.0F, length, EPSILON);
    }

    private static void assertHomogeneousEquals(Vector4f expected, Vector4f actual) {
        assertEquals(expected.x / expected.w, actual.x / actual.w, EPSILON);
        assertEquals(expected.y / expected.w, actual.y / actual.w, EPSILON);
        assertEquals(expected.z / expected.w, actual.z / actual.w, EPSILON);
    }
}
