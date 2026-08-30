package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/** sl::Constants — {DCD35AD7-4E4A-4BAD-A90C-E0C49EB23AFE}, kStructVersion2. All matrices are row major. */
public final class Constants {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            sequenceLayout(16, JAVA_FLOAT).withName("cameraViewToClip"),
            sequenceLayout(16, JAVA_FLOAT).withName("clipToCameraView"),
            sequenceLayout(16, JAVA_FLOAT).withName("clipToLensClip"),
            sequenceLayout(16, JAVA_FLOAT).withName("clipToPrevClip"),
            sequenceLayout(16, JAVA_FLOAT).withName("prevClipToClip"),
            sequenceLayout(2, JAVA_FLOAT).withName("jitterOffset"),
            sequenceLayout(2, JAVA_FLOAT).withName("mvecScale"),
            sequenceLayout(2, JAVA_FLOAT).withName("cameraPinholeOffset"),
            sequenceLayout(3, JAVA_FLOAT).withName("cameraPos"),
            sequenceLayout(3, JAVA_FLOAT).withName("cameraUp"),
            sequenceLayout(3, JAVA_FLOAT).withName("cameraRight"),
            sequenceLayout(3, JAVA_FLOAT).withName("cameraFwd"),
            JAVA_FLOAT.withName("cameraNear"),
            JAVA_FLOAT.withName("cameraFar"),
            JAVA_FLOAT.withName("cameraFOV"),
            JAVA_FLOAT.withName("cameraAspectRatio"),
            JAVA_FLOAT.withName("motionVectorsInvalidValue"),
            JAVA_BYTE.withName("depthInverted"),
            JAVA_BYTE.withName("cameraMotionIncluded"),
            JAVA_BYTE.withName("motionVectors3D"),
            JAVA_BYTE.withName("reset"),
            JAVA_BYTE.withName("orthographicProjection"),
            JAVA_BYTE.withName("motionVectorsDilated"),
            JAVA_BYTE.withName("motionVectorsJittered"),
            paddingLayout(1),
            JAVA_FLOAT.withName("minRelativeLinearDepthObjectSeparation"));

    private static final long CAMERA_VIEW_TO_CLIP = LAYOUT.byteOffset(groupElement("cameraViewToClip"));
    private static final long CLIP_TO_CAMERA_VIEW = LAYOUT.byteOffset(groupElement("clipToCameraView"));
    private static final long CLIP_TO_LENS_CLIP = LAYOUT.byteOffset(groupElement("clipToLensClip"));
    private static final long CLIP_TO_PREV_CLIP = LAYOUT.byteOffset(groupElement("clipToPrevClip"));
    private static final long PREV_CLIP_TO_CLIP = LAYOUT.byteOffset(groupElement("prevClipToClip"));
    private static final long JITTER_OFFSET = LAYOUT.byteOffset(groupElement("jitterOffset"));
    private static final long MVEC_SCALE = LAYOUT.byteOffset(groupElement("mvecScale"));
    private static final long CAMERA_PINHOLE_OFFSET = LAYOUT.byteOffset(groupElement("cameraPinholeOffset"));
    private static final long CAMERA_POS = LAYOUT.byteOffset(groupElement("cameraPos"));
    private static final long CAMERA_UP = LAYOUT.byteOffset(groupElement("cameraUp"));
    private static final long CAMERA_RIGHT = LAYOUT.byteOffset(groupElement("cameraRight"));
    private static final long CAMERA_FWD = LAYOUT.byteOffset(groupElement("cameraFwd"));

    private static final VarHandle CAMERA_NEAR = LAYOUT.varHandle(groupElement("cameraNear"));
    private static final VarHandle CAMERA_FAR = LAYOUT.varHandle(groupElement("cameraFar"));
    private static final VarHandle CAMERA_FOV = LAYOUT.varHandle(groupElement("cameraFOV"));
    private static final VarHandle CAMERA_ASPECT_RATIO = LAYOUT.varHandle(groupElement("cameraAspectRatio"));
    private static final VarHandle MOTION_VECTORS_INVALID_VALUE = LAYOUT.varHandle(groupElement("motionVectorsInvalidValue"));
    private static final VarHandle DEPTH_INVERTED = LAYOUT.varHandle(groupElement("depthInverted"));
    private static final VarHandle CAMERA_MOTION_INCLUDED = LAYOUT.varHandle(groupElement("cameraMotionIncluded"));
    private static final VarHandle MOTION_VECTORS_3D = LAYOUT.varHandle(groupElement("motionVectors3D"));
    private static final VarHandle RESET = LAYOUT.varHandle(groupElement("reset"));
    private static final VarHandle ORTHOGRAPHIC_PROJECTION = LAYOUT.varHandle(groupElement("orthographicProjection"));
    private static final VarHandle MOTION_VECTORS_DILATED = LAYOUT.varHandle(groupElement("motionVectorsDilated"));
    private static final VarHandle MOTION_VECTORS_JITTERED = LAYOUT.varHandle(groupElement("motionVectorsJittered"));
    private static final VarHandle MIN_RELATIVE_LINEAR_DEPTH_OBJECT_SEPARATION = LAYOUT.varHandle(groupElement("minRelativeLinearDepthObjectSeparation"));

    private final MemorySegment segment;

    private Constants(MemorySegment segment) {
        this.segment = segment;
    }

    public static Constants allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xdcd35ad7, (short) 0x4e4a, (short) 0x4bad, 0xFE3AB29EC4E00CA9L, 2);
        // The SDK default constructor fills every matrix/vector and the trailing float scalars with INVALID_FLOAT
        for (long offset = CAMERA_VIEW_TO_CLIP; offset < 444; offset += 4) {
            segment.set(JAVA_FLOAT, offset, Float.MAX_VALUE);
        }
        Constants constants = new Constants(segment);
        constants.depthInverted(SlBoolean.INVALID);
        constants.cameraMotionIncluded(SlBoolean.INVALID);
        constants.motionVectors3D(SlBoolean.INVALID);
        constants.reset(SlBoolean.INVALID);
        constants.minRelativeLinearDepthObjectSeparation(40.0f);
        return constants;
    }

    public static Constants wrap(MemorySegment segment) {
        return new Constants(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public float[] cameraViewToClip() {
        return this.matrix(CAMERA_VIEW_TO_CLIP);
    }

    public Constants cameraViewToClip(float[] rowMajor) {
        return this.matrix(CAMERA_VIEW_TO_CLIP, rowMajor);
    }

    public float[] clipToCameraView() {
        return this.matrix(CLIP_TO_CAMERA_VIEW);
    }

    public Constants clipToCameraView(float[] rowMajor) {
        return this.matrix(CLIP_TO_CAMERA_VIEW, rowMajor);
    }

    public float[] clipToLensClip() {
        return this.matrix(CLIP_TO_LENS_CLIP);
    }

    public Constants clipToLensClip(float[] rowMajor) {
        return this.matrix(CLIP_TO_LENS_CLIP, rowMajor);
    }

    public float[] clipToPrevClip() {
        return this.matrix(CLIP_TO_PREV_CLIP);
    }

    public Constants clipToPrevClip(float[] rowMajor) {
        return this.matrix(CLIP_TO_PREV_CLIP, rowMajor);
    }

    public float[] prevClipToClip() {
        return this.matrix(PREV_CLIP_TO_CLIP);
    }

    public Constants prevClipToClip(float[] rowMajor) {
        return this.matrix(PREV_CLIP_TO_CLIP, rowMajor);
    }

    public float[] jitterOffset() {
        return this.floats(JITTER_OFFSET, 2);
    }

    public Constants jitterOffset(float x, float y) {
        return this.floats(JITTER_OFFSET, x, y);
    }

    public float[] mvecScale() {
        return this.floats(MVEC_SCALE, 2);
    }

    public Constants mvecScale(float x, float y) {
        return this.floats(MVEC_SCALE, x, y);
    }

    public float[] cameraPinholeOffset() {
        return this.floats(CAMERA_PINHOLE_OFFSET, 2);
    }

    public Constants cameraPinholeOffset(float x, float y) {
        return this.floats(CAMERA_PINHOLE_OFFSET, x, y);
    }

    public float[] cameraPos() {
        return this.floats(CAMERA_POS, 3);
    }

    public Constants cameraPos(float x, float y, float z) {
        return this.floats(CAMERA_POS, x, y, z);
    }

    public float[] cameraUp() {
        return this.floats(CAMERA_UP, 3);
    }

    public Constants cameraUp(float x, float y, float z) {
        return this.floats(CAMERA_UP, x, y, z);
    }

    public float[] cameraRight() {
        return this.floats(CAMERA_RIGHT, 3);
    }

    public Constants cameraRight(float x, float y, float z) {
        return this.floats(CAMERA_RIGHT, x, y, z);
    }

    public float[] cameraFwd() {
        return this.floats(CAMERA_FWD, 3);
    }

    public Constants cameraFwd(float x, float y, float z) {
        return this.floats(CAMERA_FWD, x, y, z);
    }

    public float cameraNear() {
        return (float) CAMERA_NEAR.get(this.segment, 0L);
    }

    public Constants cameraNear(float value) {
        CAMERA_NEAR.set(this.segment, 0L, value);
        return this;
    }

    public float cameraFar() {
        return (float) CAMERA_FAR.get(this.segment, 0L);
    }

    public Constants cameraFar(float value) {
        CAMERA_FAR.set(this.segment, 0L, value);
        return this;
    }

    public float cameraFOV() {
        return (float) CAMERA_FOV.get(this.segment, 0L);
    }

    public Constants cameraFOV(float value) {
        CAMERA_FOV.set(this.segment, 0L, value);
        return this;
    }

    public float cameraAspectRatio() {
        return (float) CAMERA_ASPECT_RATIO.get(this.segment, 0L);
    }

    public Constants cameraAspectRatio(float value) {
        CAMERA_ASPECT_RATIO.set(this.segment, 0L, value);
        return this;
    }

    public float motionVectorsInvalidValue() {
        return (float) MOTION_VECTORS_INVALID_VALUE.get(this.segment, 0L);
    }

    public Constants motionVectorsInvalidValue(float value) {
        MOTION_VECTORS_INVALID_VALUE.set(this.segment, 0L, value);
        return this;
    }

    public SlBoolean depthInverted() {
        return SlBoolean.fromValue((byte) DEPTH_INVERTED.get(this.segment, 0L));
    }

    public Constants depthInverted(SlBoolean value) {
        DEPTH_INVERTED.set(this.segment, 0L, value.value);
        return this;
    }

    public SlBoolean cameraMotionIncluded() {
        return SlBoolean.fromValue((byte) CAMERA_MOTION_INCLUDED.get(this.segment, 0L));
    }

    public Constants cameraMotionIncluded(SlBoolean value) {
        CAMERA_MOTION_INCLUDED.set(this.segment, 0L, value.value);
        return this;
    }

    public SlBoolean motionVectors3D() {
        return SlBoolean.fromValue((byte) MOTION_VECTORS_3D.get(this.segment, 0L));
    }

    public Constants motionVectors3D(SlBoolean value) {
        MOTION_VECTORS_3D.set(this.segment, 0L, value.value);
        return this;
    }

    public SlBoolean reset() {
        return SlBoolean.fromValue((byte) RESET.get(this.segment, 0L));
    }

    public Constants reset(SlBoolean value) {
        RESET.set(this.segment, 0L, value.value);
        return this;
    }

    public SlBoolean orthographicProjection() {
        return SlBoolean.fromValue((byte) ORTHOGRAPHIC_PROJECTION.get(this.segment, 0L));
    }

    public Constants orthographicProjection(SlBoolean value) {
        ORTHOGRAPHIC_PROJECTION.set(this.segment, 0L, value.value);
        return this;
    }

    public SlBoolean motionVectorsDilated() {
        return SlBoolean.fromValue((byte) MOTION_VECTORS_DILATED.get(this.segment, 0L));
    }

    public Constants motionVectorsDilated(SlBoolean value) {
        MOTION_VECTORS_DILATED.set(this.segment, 0L, value.value);
        return this;
    }

    public SlBoolean motionVectorsJittered() {
        return SlBoolean.fromValue((byte) MOTION_VECTORS_JITTERED.get(this.segment, 0L));
    }

    public Constants motionVectorsJittered(SlBoolean value) {
        MOTION_VECTORS_JITTERED.set(this.segment, 0L, value.value);
        return this;
    }

    public float minRelativeLinearDepthObjectSeparation() {
        return (float) MIN_RELATIVE_LINEAR_DEPTH_OBJECT_SEPARATION.get(this.segment, 0L);
    }

    public Constants minRelativeLinearDepthObjectSeparation(float value) {
        MIN_RELATIVE_LINEAR_DEPTH_OBJECT_SEPARATION.set(this.segment, 0L, value);
        return this;
    }

    private float[] matrix(long offset) {
        float[] matrix = new float[16];
        for (int i = 0; i < 16; i++) {
            matrix[i] = this.segment.get(JAVA_FLOAT, offset + i * 4L);
        }
        return matrix;
    }

    private Constants matrix(long offset, float[] rowMajor) {
        if (rowMajor.length != 16) {
            throw new IllegalArgumentException("Expected 16 floats, got " + rowMajor.length);
        }
        for (int i = 0; i < 16; i++) {
            this.segment.set(JAVA_FLOAT, offset + i * 4L, rowMajor[i]);
        }
        return this;
    }

    private float[] floats(long offset, int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = this.segment.get(JAVA_FLOAT, offset + i * 4L);
        }
        return values;
    }

    private Constants floats(long offset, float... values) {
        for (int i = 0; i < values.length; i++) {
            this.segment.set(JAVA_FLOAT, offset + i * 4L, values[i]);
        }
        return this;
    }
}
