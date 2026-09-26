package dev.primept.capture;

/** Finishes actual source builders, then releases their pages without creating raster GPU buffers. */
public interface CaptureStagedBuffer {
    void primept$drainCapturedSource();
}
