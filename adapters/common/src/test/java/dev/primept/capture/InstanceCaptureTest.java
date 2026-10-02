package dev.primept.capture;

import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.*;
import static dev.primept.abi.PrimeAbi.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstanceCaptureTest {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private static final float[] UV = {1, 1, 0, 0};

    @Test
    void immutableVerticesRemainSharedAndValidAfterPrototypeAcknowledgement() {
        ByteBuffer source = quad();
        var authored = InstanceCapture.OwnedVertices.copyOf(source);
        source.putFloat(0, 77);
        assertThrows(java.nio.ReadOnlyBufferException.class,
                     () -> authored.bytes().putFloat(0, 88));
        try (var context = new InstanceCapture(1)) {
            context.prototype(4, 4, 24, 0, 12, 16, authored);
            var batch = context.sealDelta();
            var definition =
                    PrimeInstanceBatch.prototypes(batch).reinterpret(PrimePrototypeSource.SIZE);
            var span = PrimePrototypeSource.spans(definition).reinterpret(PrimeMeshSpan.SIZE);
            assertEquals(0, PrimeByteSpan.data(PrimeMeshSpan.vertices(span))
                                    .reinterpret(96)
                                    .get(JAVA_FLOAT, 0));
            assertEquals(88 + 32 + 48 + 96, context.stats().bytes());
            context.acknowledge();
            assertEquals(0, authored.bytes().getFloat(0));
            assertEquals(96, authored.byteSize());
        }
    }

    @Test
    void encodesAtomicDefinitionsAndAffineInstancesWithOwnedSourceBytes() {
        try (var context = new InstanceCapture(7)) {
            ByteBuffer source = quad();
            var prototype = context.prototype(4, 4, 24, 0, 12, 16, source);
            source.putFloat(0, 99);
            var instance = context.instance();
            float[] transform = {-2, 0, .5f, 3, 0, 4, 0, 5, 0, 0, 6, 7};
            float[] uv = {.25f, .5f, .125f, .375f};
            context.beginFrame();
            context.observe(instance, prototype, 29_999_999.25, 64, -3, transform, 19, 2,
                            0x80402010, uv);
            context.endFrame();
            var packet = context.sealDelta();
            assertEquals(PrimeInstanceBatch.SIZE, packet.byteSize());
            assertEquals(PrimeInstanceBatch.SIZE,
                         PrimeHeader.struct_size(PrimeInstanceBatch.header(packet)));
            assertEquals(PRIME_ABI_VERSION,
                         PrimeHeader.abi_version(PrimeInstanceBatch.header(packet)));
            assertEquals(7, PrimeInstanceBatch.epoch(packet));
            assertEquals(1, PrimeInstanceBatch.sequence(packet));
            counts(packet, 1, 0, 1, 0);
            var definition =
                    PrimeInstanceBatch.prototypes(packet).reinterpret(PrimePrototypeSource.SIZE);
            assertEquals(prototype.id(), PrimePrototypeSource.id(definition));
            var span = PrimePrototypeSource.spans(definition).reinterpret(PrimeMeshSpan.SIZE);
            var payload = PrimeMeshSpan.vertices(span);
            assertEquals(96, PrimeByteSpan.count(payload));
            assertEquals(0, PrimeByteSpan.data(payload).reinterpret(96).get(JAVA_FLOAT, 0),
                         "Source was owned before the producer changed it");
            var value = instance(packet);
            assertEquals(instance.id(), PrimeInstanceSource.id(value));
            assertEquals(prototype.id(), PrimeInstanceSource.prototype_id(value));
            assertEquals(29_999_999.25, PrimeInstanceSource.origin(value, 0));
            for (int i = 0; i < 12; i++)
                assertEquals(transform[i], PrimeInstanceSource.transform(value, i));
            assertEquals(19, PrimeInstanceSource.texture_id(value));
            assertEquals(2, PrimeInstanceSource.flags(value));
            assertEquals(0x80102040, PrimeInstanceSource.rgba(value));
            assertEquals(0, PrimeInstanceSource.reserved(value));
            for (int i = 0; i < 4; i++)
                assertEquals(uv[i], PrimeInstanceSource.uv_transform(value, i));
            assertThrows(IllegalStateException.class, context::beginFrame);
            context.acknowledge();
        }
    }

    @Test
    void tenThousandStableInstancesSendNothingAndOneChangeSendsOnlyOneRecord() {
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
            assertEquals(88 + 32 + 48 + 96 + 10_000 * 128, context.stats().bytes());
            counts(initial, 1, 0, 10_000, 0);
            int capacity = context.stats().capacity(), growths = context.stats().growths();
            context.acknowledge();

            context.beginFrame();
            for (int i = 0; i < instances.length; i++)
                observe(context, instances[i], prototype, i);
            context.endFrame();
            assertNull(context.sealDelta());
            assertEquals(0, context.stats().bytes());
            assertEquals(10_000, context.stats().activeInstances());

            context.beginFrame();
            for (int i = 0; i < instances.length; i++)
                observe(context, instances[i], prototype, i == 5432 ? i + .5 : i);
            context.endFrame();
            var change = context.sealDelta();
            assertEquals(88 + 128, context.stats().bytes());
            counts(change, 0, 0, 1, 0);
            assertEquals(instances[5432].id(), PrimeInstanceSource.id(instance(change)));
            assertEquals(capacity, context.stats().capacity());
            assertEquals(growths, context.stats().growths());
            context.acknowledge();
        }
    }

    @Test
    void removalAndReappearancePreserveIdentityAndIncreaseRevision() {
        try (var context = new InstanceCapture(1)) {
            var prototype = prototype(context);
            var instance = context.instance();
            context.beginFrame();
            observe(context, instance, prototype, 0);
            context.endFrame();
            context.sealDelta();
            context.acknowledge();
            context.beginFrame();
            context.endFrame();
            var removal = context.sealDelta();
            assertEquals(88 + 16, context.stats().bytes());
            counts(removal, 0, 0, 0, 1);
            var removed =
                    PrimeInstanceBatch.instance_removals(removal).reinterpret(PrimeRemoval.SIZE);
            assertEquals(instance.id(), PrimeRemoval.id(removed));
            assertEquals(2, PrimeRemoval.revision(removed));
            context.acknowledge();
            context.beginFrame();
            observe(context, instance, prototype, 0);
            context.endFrame();
            var returned = context.sealDelta();
            assertEquals(88 + 128, context.stats().bytes());
            counts(returned, 0, 0, 1, 0);
            assertEquals(instance.id(), PrimeInstanceSource.id(instance(returned)));
            assertEquals(3, PrimeInstanceSource.revision(instance(returned)));
            context.acknowledge();
        }
    }

    @Test
    void releasedPrototypeSurvivesUntilItsLastInstanceChanges() {
        try (var context = new InstanceCapture(1)) {
            var old = prototype(context);
            var a = context.instance();
            var b = context.instance();
            context.beginFrame();
            observe(context, a, old, 0);
            observe(context, b, old, 1);
            context.endFrame();
            context.sealDelta();
            context.acknowledge();

            var replacement = prototype(context);
            context.release(old);
            context.beginFrame();
            observe(context, a, replacement, 0);
            observe(context, b, old, 1);
            context.endFrame();
            var mixed = context.sealDelta();
            counts(mixed, 1, 0, 1, 0);
            context.acknowledge();

            context.beginFrame();
            observe(context, a, replacement, 0);
            observe(context, b, replacement, 1);
            context.endFrame();
            var retired = context.sealDelta();
            assertEquals(88 + 16 + 128, context.stats().bytes());
            counts(retired, 0, 1, 1, 0);
            var removed =
                    PrimeInstanceBatch.prototype_removals(retired).reinterpret(PrimeRemoval.SIZE);
            assertEquals(old.id(), PrimeRemoval.id(removed));
            context.acknowledge();
            assertThrows(IllegalArgumentException.class, () -> context.release(old));
        }
    }

    @Test
    void skippedSubmissionsKeepTheNewestStateAndCancelNeverPublishedObjects() {
        try (var context = new InstanceCapture(1)) {
            var prototype = prototype(context);
            var instance = context.instance();
            context.beginFrame();
            observe(context, instance, prototype, 1);
            context.endFrame();
            context.beginFrame();
            observe(context, instance, prototype, 2);
            context.endFrame();
            var packet = context.sealDelta();
            assertEquals(2, PrimeInstanceSource.origin(instance(packet), 0));
            assertEquals(1, PrimeInstanceBatch.sequence(packet));
            context.acknowledge();
            var transientPrototype = prototype(context);
            var transientInstance = context.instance();
            context.beginFrame();
            observe(context, instance, prototype, 2);
            observe(context, transientInstance, transientPrototype, 3);
            context.endFrame();
            context.release(transientPrototype);
            context.beginFrame();
            observe(context, instance, prototype, 2);
            context.endFrame();
            assertNull(context.sealDelta());
            assertThrows(IllegalArgumentException.class, () -> context.release(transientPrototype));
        }
    }

    @Test
    void contextsRejectForeignAndDuplicateHandlesAndCloseBorrowedStorage() {
        try (var a = new InstanceCapture(1); var b = new InstanceCapture(2)) {
            var prototype = prototype(a);
            var instance = a.instance();
            a.beginFrame();
            assertThrows(IllegalArgumentException.class,
                         () -> observe(a, b.instance(), prototype, 0));
            observe(a, instance, prototype, 0);
            assertThrows(IllegalStateException.class, () -> observe(a, instance, prototype, 0));
            a.endFrame();
            var packet = a.sealDelta();
            a.close();
            assertThrows(IllegalStateException.class,
                         () -> packet.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }

    private static void counts(MemorySegment batch, long prototypes, long prototypeRemovals,
                               long instances, long instanceRemovals) {
        assertEquals(prototypes, PrimeInstanceBatch.prototype_count(batch));
        assertEquals(prototypeRemovals, PrimeInstanceBatch.prototype_removal_count(batch));
        assertEquals(instances, PrimeInstanceBatch.instance_count(batch));
        assertEquals(instanceRemovals, PrimeInstanceBatch.instance_removal_count(batch));
    }
    private static MemorySegment instance(MemorySegment batch) {
        assertEquals(1, PrimeInstanceBatch.instance_count(batch));
        return PrimeInstanceBatch.instances(batch).reinterpret(PrimeInstanceSource.SIZE);
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
