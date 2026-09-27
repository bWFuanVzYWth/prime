package dev.primept.capture;

/** Inclusive source columns. Difference visits only entering/leaving strips. */
public record ColumnWindow(int minX, int maxX, int minZ, int maxZ) {
    @FunctionalInterface
    public interface Visitor {
        void accept(int x, int z);
    }
    public static ColumnWindow centered(int x, int z, int radius) {
        if (radius < 0)
            throw new IllegalArgumentException("Negative source radius");
        return new ColumnWindow(Math.subtractExact(x, radius), Math.addExact(x, radius),
                                Math.subtractExact(z, radius), Math.addExact(z, radius));
    }
    public ColumnWindow intersect(ColumnWindow other) {
        return new ColumnWindow(Math.max(minX, other.minX), Math.min(maxX, other.maxX),
                                Math.max(minZ, other.minZ), Math.min(maxZ, other.maxZ));
    }
    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
    public boolean empty() {
        return minX > maxX || minZ > maxZ;
    }
    public void difference(ColumnWindow previous, Visitor visitor) {
        if (empty())
            return;
        if (previous != null && previous.empty())
            previous = null;
        for (long z = minZ; z <= maxZ; z++) {
            if (previous == null || z < previous.minZ || z > previous.maxZ) {
                row(minX, maxX, (int)z, visitor);
            } else {
                row(minX, Math.min((long)maxX, (long)previous.minX - 1), (int)z, visitor);
                row(Math.max((long)minX, (long)previous.maxX + 1), maxX, (int)z, visitor);
            }
        }
    }
    private static void row(long first, long last, int z, Visitor visitor) {
        for (long x = first; x <= last; x++)
            visitor.accept((int)x, z);
    }
}
