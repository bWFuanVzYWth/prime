package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/** sl::FeatureVersion — {6D5B51F0-076B-486D-9995-5A561043F5C1}, kStructVersion1. Out-only struct. */
public final class FeatureVersion {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            Version.LAYOUT.withName("versionSL"),
            Version.LAYOUT.withName("versionNGX"));

    private static final long VERSION_SL = LAYOUT.byteOffset(groupElement("versionSL"));
    private static final long VERSION_NGX = LAYOUT.byteOffset(groupElement("versionNGX"));

    private final MemorySegment segment;

    private FeatureVersion(MemorySegment segment) {
        this.segment = segment;
    }

    public static FeatureVersion allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x6d5b51f0, (short) 0x076b, (short) 0x486d, 0xC1F54310565A9599L, 1);
        return new FeatureVersion(segment);
    }

    public static FeatureVersion wrap(MemorySegment segment) {
        return new FeatureVersion(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public Version versionSL() {
        return Version.read(this.segment, VERSION_SL);
    }

    public Version versionNGX() {
        return Version.read(this.segment, VERSION_NGX);
    }
}
