package dev.primept.render;

/** A sample set belongs to one actual render-target extent, including after suspension. */
public final class FrameSequence {
    private int width, height, next;

    public int next(int width, int height) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("A suspended target cannot produce a frame");
        if (this.width != width || this.height != height) {
            this.width = width;
            this.height = height;
            next = 0;
        }
        // Packet carries the unsigned 32-bit bit pattern. Wrap explicitly begins a new history.
        return next++;
    }

    public void reset() {
        width = height = next = 0;
    }
}
