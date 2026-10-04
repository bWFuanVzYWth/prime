package dev.primept.render;

/** Sampling sequence for an actual target extent; skipped frames retain the sequence. */
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
        // Packet carries the unsigned 32-bit bit pattern; wrap does not invalidate temporal history.
        return next++;
    }

    public void reset() {
        width = height = next = 0;
    }
}
