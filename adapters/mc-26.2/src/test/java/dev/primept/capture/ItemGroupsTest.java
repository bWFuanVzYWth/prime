package dev.primept.capture;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemGroupsTest {
    private static final DynamicCapture.Material MATERIAL =
            new DynamicCapture.Material(2, 1, false, true);

    @Test
    void groupsKeepExactEqualityFirstOccurrenceOrderAndCollisionChecks() {
        try (var owner = new InstanceCapture(1)) {
            ItemCapture.begin(owner);
            var scope = new ItemCapture.Scope(new ModelCapture.Submission());
            scope.begin();
            var pose = new Matrix4f();
            Object first = scope.group(MATERIAL, -1, pose);
            assertSame(first, scope.group(MATERIAL, -1, pose));
            Object second = scope.group(MATERIAL, -1, new Matrix4f().translation(1, 0, 0));
            assertNotSame(first, second);
            assertSame(first, scope.group(MATERIAL, -1, pose));
            assertSame(first, scope.group(MATERIAL, -1, new Matrix4f().m30(-0.0f)));
            assertNotSame(first,
                          scope.group(new DynamicCapture.Material(2, 2, false, true), -1, pose));
            assertNotSame(first, scope.group(MATERIAL, 1, pose));
            var collisionA = new Matrix4f().m00(.5f).m10(1f);
            var collisionB = new Matrix4f()
                                     .m00(Float.intBitsToFloat(Float.floatToRawIntBits(.5f) + 1))
                                     .m10(Float.intBitsToFloat(Float.floatToRawIntBits(1f) - 31));
            assertNotSame(scope.group(MATERIAL, -1, collisionA),
                          scope.group(MATERIAL, -1, collisionB));
            var nan = new Matrix4f().m00(Float.NaN);
            assertNotSame(scope.group(MATERIAL, -1, nan), scope.group(MATERIAL, -1, nan));
            scope.begin();
            assertSame(first, scope.group(MATERIAL, -1, new Matrix4f().translation(12, 0, 0)),
                       "Frame reuse retains the earliest available instance identity");
            assertSame(second, scope.group(MATERIAL, -1, pose));
        } finally {
            ItemCapture.close();
        }
    }

    @Test
    void distinctGroupsAndReverseReuseAvoidQuadraticMatching() {
        try (var owner = new InstanceCapture(1)) {
            ItemCapture.begin(owner);
            var scope = new ItemCapture.Scope(new ModelCapture.Submission());
            scope.begin();
            var poses = new Matrix4f[10000];
            var groups = new Object[poses.length];
            for (int i = 0; i < poses.length; ++i) {
                poses[i] = new Matrix4f().translation(i, i % 7, 0);
                groups[i] = scope.group(MATERIAL, -1, poses[i]);
            }
            for (int i = poses.length - 1; i >= 0; --i)
                assertSame(groups[i], scope.group(MATERIAL, -1, poses[i]));
            assertTrue(scope.groupComparisons < 4L * poses.length,
                       "Lookup work follows actual quads/groups rather than their product");
        } finally {
            ItemCapture.close();
        }
    }
}
