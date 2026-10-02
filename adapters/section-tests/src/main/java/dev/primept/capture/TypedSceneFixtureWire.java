package dev.primept.capture;

import static dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test-only typed readers and disk-oracle serialization; never a production FFM input. */
final class TypedSceneFixtureWire {
    static MemorySegment element(MemorySegment pointer, long count, long index, long size) {
        if (index < 0 || index >= count)
            throw new AssertionError("Missing typed fixture record");
        return pointer.reinterpret(Math.multiplyExact(count, size)).asSlice(index * size, size);
    }
    static MemorySegment dynamicSpan(MemorySegment batch, long index) {
        return element(PrimeDynamicBatch.spans(batch), PrimeDynamicBatch.count(batch), index,
                       PrimeMeshSpan.SIZE);
    }
    static MemorySegment prototypeSpan(MemorySegment batch, long prototypeIndex, long spanIndex) {
        var prototype = element(PrimeInstanceBatch.prototypes(batch),
                                PrimeInstanceBatch.prototype_count(batch), prototypeIndex,
                                PrimePrototypeSource.SIZE);
        return element(PrimePrototypeSource.spans(prototype), PrimePrototypeSource.count(prototype),
                       spanIndex, PrimeMeshSpan.SIZE);
    }
    static MemorySegment instance(MemorySegment batch, long index) {
        return element(PrimeInstanceBatch.instances(batch),
                       PrimeInstanceBatch.instance_count(batch), index, PrimeInstanceSource.SIZE);
    }
    static MemorySegment vertices(MemorySegment span) {
        var bytes = PrimeMeshSpan.vertices(span);
        return PrimeByteSpan.data(bytes).reinterpret(PrimeByteSpan.count(bytes));
    }
    record Mesh(int stride, int position, int color, int uv, byte[] bytes) {
        ByteBuffer buffer() {
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
    }
    static Mesh mesh(MemorySegment span) {
        return new Mesh(PrimeMeshSpan.stride(span), PrimeMeshSpan.position_offset(span),
                        PrimeMeshSpan.color_offset(span), PrimeMeshSpan.uv_offset(span),
                        vertices(span).toArray(ValueLayout.JAVA_BYTE));
    }
    /** Preserve the independent Rust oracle's operation-6 file layout. */
    static byte[] dynamic(MemorySegment batch) {
        long count = PrimeDynamicBatch.count(batch), size = 64;
        for (long i = 0; i < count; ++i)
            size = Math.addExact(size, 32 + vertices(dynamicSpan(batch, i)).byteSize());
        var out = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x54505250)
                .putInt(LegacyPackets.ABI_VERSION)
                .putInt(6)
                .putInt(0)
                .putLong(PrimeDynamicBatch.epoch(batch));
        out.putLong(PrimeDynamicBatch.sequence(batch));
        for (int i = 0; i < 3; ++i)
            out.putDouble(PrimeDynamicBatch.origin(batch, i));
        out.putInt(Math.toIntExact(count)).putInt(0);
        for (long i = 0; i < count; ++i) {
            var span = dynamicSpan(batch, i);
            out.putInt(PrimeMeshSpan.texture_id(span))
                    .putInt(PrimeMeshSpan.flags(span))
                    .putInt(PrimeMeshSpan.topology(span))
                    .putInt(PrimeMeshSpan.vertex_count(span))
                    .putInt(PrimeMeshSpan.stride(span))
                    .putInt(PrimeMeshSpan.position_offset(span))
                    .putInt(PrimeMeshSpan.color_offset(span))
                    .putInt(PrimeMeshSpan.uv_offset(span));
            out.put(vertices(span).asByteBuffer());
        }
        return out.array();
    }
}
