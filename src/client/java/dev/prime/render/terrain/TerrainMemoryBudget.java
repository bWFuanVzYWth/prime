// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** Pure CPU segmentation and scheduling policy; neither value limits total scene geometry. */
public final class TerrainMemoryBudget {
    public static final long TARGET_SEGMENT_BYTES = 16L * 1024L * 1024L;
    public static final int TARGET_SEGMENT_TRIANGLES = 128 * 1024;
    private static final long ESTIMATED_SEGMENT_BUILD_MULTIPLIER = 3L;

    private TerrainMemoryBudget() {}

    public static int segmentTriangleTarget(long devicePrimitiveLimit) {
        if (Long.compareUnsigned(devicePrimitiveLimit, 2L) < 0) {
            throw new IllegalArgumentException(
                    "Vulkan BLAS primitive capacity cannot represent one quad");
        }
        long javaArrayLimit = Math.min(
                Integer.MAX_VALUE / 9L,
                Integer.MAX_VALUE / (long) CpuSectionMesh.PRIMITIVE_WORDS);
        long target = TARGET_SEGMENT_TRIANGLES;
        if (Long.compareUnsigned(devicePrimitiveLimit, target) < 0) {
            target = devicePrimitiveLimit;
        }
        target = Math.min(target, javaArrayLimit);
        return Math.toIntExact(target & ~1L);
    }

    public static int maximumInFlight(int workerLimit, long maximumHeapBytes) {
        if (workerLimit <= 0 || maximumHeapBytes <= 0L) {
            throw new IllegalArgumentException("Terrain worker and heap limits must be positive");
        }
        long bytesPerJob = Math.multiplyExact(
                TARGET_SEGMENT_BYTES, ESTIMATED_SEGMENT_BUILD_MULTIPLIER);
        long memoryBudget = Math.max(bytesPerJob, maximumHeapBytes / 4L);
        long memoryLimited = Math.max(1L, memoryBudget / bytesPerJob);
        return (int) Math.min(workerLimit, Math.min(memoryLimited, Integer.MAX_VALUE));
    }

    public static boolean startsNewSegment(
            long currentBytes,
            int currentTriangles,
            CpuSectionMesh next,
            int triangleTarget) {
        if (currentBytes < 0L || currentTriangles < 0) {
            throw new IllegalArgumentException("Current terrain segment size must not be negative");
        }
        if (triangleTarget <= 0) {
            throw new IllegalArgumentException("Terrain segment triangle target must be positive");
        }
        if (next == null) {
            throw new IllegalArgumentException("Next terrain mesh must not be null");
        }
        return currentTriangles > 0
                && (currentBytes
                                > TARGET_SEGMENT_BYTES
                                        - Math.min(next.byteSize(), TARGET_SEGMENT_BYTES)
                        || currentTriangles
                                > triangleTarget
                                        - Math.min(next.geometry().triangleCount(), triangleTarget));
    }
}
