package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstanceCaptureTest {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private static final float[] UV = {1, 1, 0, 0};

    @Test void encodesAtomicDefinitionsAndAffineInstancesWithOwnedSourceBytes() {
        try (var context = new InstanceCapture(7)) {
            ByteBuffer source = quad();
            var prototype = context.prototype(4, 4, 24, 0, 12, 16, source);
            source.putFloat(0, 99);
            var instance = context.instance();
            float[] transform = {-2, 0, .5f, 3, 0, 4, 0, 5, 0, 0, 6, 7};
            float[] uv = {.25f, .5f, .125f, .375f};
            context.beginFrame();
            context.observe(instance, prototype, 29_999_999.25, 64, -3, transform, 19, 2, 0x80402010, uv);
            context.endFrame();
            var packet = context.sealDelta();
            var bytes = packet.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(328, packet.byteSize());
            assertEquals(Packets.MAGIC, bytes.getInt(0));
            assertEquals(7, bytes.getInt(8));
            assertEquals(7, bytes.getLong(16));
            assertEquals(1, bytes.getLong(24));
            assertEquals(1, bytes.getInt(32));
            assertEquals(0, bytes.getInt(36));
            assertEquals(1, bytes.getInt(40));
            assertEquals(0, bytes.getInt(44));
            assertEquals(prototype.id(), bytes.getLong(48));
            assertEquals(0, bytes.getFloat(104), "Source was owned before the producer changed it");
            int offset = 200;
            assertEquals(instance.id(), bytes.getLong(offset));
            assertEquals(prototype.id(), bytes.getLong(offset + 16));
            assertEquals(29_999_999.25, bytes.getDouble(offset + 24));
            for (int i = 0; i < 12; i++) assertEquals(transform[i], bytes.getFloat(offset + 48 + 4 * i));
            assertEquals(19, bytes.getInt(offset + 96));
            assertEquals(2, bytes.getInt(offset + 100));
            assertEquals(0x80102040, bytes.getInt(offset + 104));
            assertEquals(0, bytes.getInt(offset + 108));
            for (int i = 0; i < 4; i++) assertEquals(uv[i], bytes.getFloat(offset + 112 + 4 * i));
            assertThrows(IllegalStateException.class, context::beginFrame);
            context.acknowledge();
        }
    }

    @Test void tenThousandStableInstancesSendNothingAndOneChangeSendsOnlyOneRecord() {
        try (var context = new InstanceCapture(1)) {
            var prototype = prototype(context);
            var instances = new InstanceCapture.Instance[10_000];
            context.beginFrame();
            for (int i = 0; i < instances.length; i++) {
                instances[i] = context.instance();
                observe(context, instances[i], prototype, i);
            }
            context.endFrame();
            var initial = context.sealDelta();
            assertEquals(48 + 56 + 96 + 10_000 * 128, initial.byteSize());
            int capacity = context.stats().capacity(), growths = context.stats().growths();
            context.acknowledge();

            context.beginFrame();
            for (int i = 0; i < instances.length; i++) observe(context, instances[i], prototype, i);
            context.endFrame();
            assertNull(context.sealDelta());
            assertEquals(0, context.stats().bytes());
            assertEquals(10_000, context.stats().activeInstances());

            context.beginFrame();
            for (int i = 0; i < instances.length; i++)
                observe(context, instances[i], prototype, i == 5432 ? i + .5 : i);
            context.endFrame();
            var change = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(176, change.remaining());
            assertEquals(0, change.getInt(32));
            assertEquals(1, change.getInt(40));
            assertEquals(instances[5432].id(), change.getLong(48));
            assertEquals(capacity, context.stats().capacity());
            assertEquals(growths, context.stats().growths());
            context.acknowledge();
        }
    }

    @Test void removalAndReappearancePreserveIdentityAndIncreaseRevision() {
        try (var context = new InstanceCapture(1)) {
            var prototype = prototype(context);
            var instance = context.instance();
            context.beginFrame(); observe(context, instance, prototype, 0); context.endFrame();
            context.sealDelta(); context.acknowledge();
            context.beginFrame(); context.endFrame();
            var removal = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(64, removal.remaining());
            assertEquals(1, removal.getInt(44));
            assertEquals(instance.id(), removal.getLong(48));
            assertEquals(2, removal.getLong(56));
            context.acknowledge();
            context.beginFrame(); observe(context, instance, prototype, 0); context.endFrame();
            var returned = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(176, returned.remaining());
            assertEquals(instance.id(), returned.getLong(48));
            assertEquals(3, returned.getLong(56));
            context.acknowledge();
        }
    }

    @Test void releasedPrototypeSurvivesUntilItsLastInstanceChanges() {
        try (var context = new InstanceCapture(1)) {
            var old = prototype(context);
            var a = context.instance(); var b = context.instance();
            context.beginFrame(); observe(context, a, old, 0); observe(context, b, old, 1); context.endFrame();
            context.sealDelta(); context.acknowledge();

            var replacement = prototype(context);
            context.release(old);
            context.beginFrame(); observe(context, a, replacement, 0); observe(context, b, old, 1); context.endFrame();
            var mixed = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1, mixed.getInt(32));
            assertEquals(0, mixed.getInt(36));
            assertEquals(1, mixed.getInt(40));
            context.acknowledge();

            context.beginFrame(); observe(context, a, replacement, 0); observe(context, b, replacement, 1); context.endFrame();
            var retired = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(192, retired.remaining());
            assertEquals(1, retired.getInt(36));
            assertEquals(1, retired.getInt(40));
            assertEquals(old.id(), retired.getLong(48));
            context.acknowledge();
            assertThrows(IllegalArgumentException.class, () -> context.release(old));
        }
    }

    @Test void skippedSubmissionsKeepTheNewestStateAndCancelNeverPublishedObjects() {
        try (var context = new InstanceCapture(1)) {
            var prototype = prototype(context);
            var instance = context.instance();
            context.beginFrame(); observe(context, instance, prototype, 1); context.endFrame();
            context.beginFrame(); observe(context, instance, prototype, 2); context.endFrame();
            var packet = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(2, packet.getDouble(200 + 24));
            assertEquals(1, packet.getLong(24));
            context.acknowledge();
            var transientPrototype = prototype(context);
            var transientInstance = context.instance();
            context.beginFrame();
            observe(context, instance, prototype, 2); observe(context, transientInstance, transientPrototype, 3);
            context.endFrame();
            context.release(transientPrototype);
            context.beginFrame(); observe(context, instance, prototype, 2); context.endFrame();
            assertNull(context.sealDelta());
            assertThrows(IllegalArgumentException.class, () -> context.release(transientPrototype));
        }
    }

    @Test void contextsRejectForeignAndDuplicateHandlesAndCloseBorrowedStorage() {
        try (var a = new InstanceCapture(1); var b = new InstanceCapture(2)) {
            var prototype = prototype(a);
            var instance = a.instance();
            a.beginFrame();
            assertThrows(IllegalArgumentException.class, () -> observe(a, b.instance(), prototype, 0));
            observe(a, instance, prototype, 0);
            assertThrows(IllegalStateException.class, () -> observe(a, instance, prototype, 0));
            a.endFrame();
            var packet = a.sealDelta();
            a.close();
            assertThrows(IllegalStateException.class,
                    () -> packet.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }

    private static void observe(InstanceCapture context, InstanceCapture.Instance instance,
            InstanceCapture.Prototype prototype, double x) {
        context.observe(instance, prototype, x, 0, 0, IDENTITY, 0, 0, -1, UV);
    }
    private static InstanceCapture.Prototype prototype(InstanceCapture context) {
        return context.prototype(4, 4, 24, 0, 12, 16, quad());
    }
    private static ByteBuffer quad() {
        var bytes = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++)
            bytes.putFloat(i & 1).putFloat(i >>> 1).putFloat(0).putInt(-1).putFloat(0).putFloat(0);
        return bytes.flip();
    }
}
