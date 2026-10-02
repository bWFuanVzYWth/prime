package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Reusable source-wire storage. No geometry computation or host objects. */
public final class RouteBuffer {
    private ByteBuffer bytes = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
    private void reserve(int count) {
        int required = Math.addExact(bytes.position(), count);
        if (required > (256 << 20))
            throw new IllegalArgumentException("Routed source packet exceeds 256 MiB");
        if (required <= bytes.capacity())
            return;
        int capacity = (int)Math.min(256L << 20, Math.max(required, (long)bytes.capacity() * 2));
        var next = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
        bytes.flip();
        next.put(bytes);
        bytes = next;
    }
    public RouteBuffer i(int value) {
        reserve(4);
        bytes.putInt(value);
        return this;
    }
    public RouteBuffer l(long value) {
        reserve(8);
        bytes.putLong(value);
        return this;
    }
    public RouteBuffer f(float value) {
        reserve(4);
        bytes.putFloat(value);
        return this;
    }
    public RouteBuffer d(double value) {
        reserve(8);
        bytes.putDouble(value);
        return this;
    }
    public RouteBuffer rgba(int argb) {
        return i((argb & 0xff00ff00) | ((argb >>> 16) & 255) | ((argb & 255) << 16));
    }
    public int size() {
        return bytes.position();
    }
    public void integerAt(int offset, int value) {
        bytes.putInt(offset, value);
    }
    public void clear() {
        bytes.clear();
    }
    public RouteBuffer header(int operation, long epoch) {
        clear();
        return i(LegacyPackets.MAGIC).i(LegacyPackets.ABI_VERSION).i(operation).i(0).l(epoch);
    }
    public RouteBuffer append(RouteBuffer other) {
        reserve(other.size());
        bytes.put(other.bytes.array(), 0, other.size());
        return this;
    }
    public RouteBuffer append(byte[] source, int offset, int length) {
        reserve(length);
        bytes.put(source, offset, length);
        return this;
    }
    public byte[] seal() {
        return Arrays.copyOf(bytes.array(), bytes.position());
    }
}
