package dev.primept.capture;

/** Switching only: prohibit new compiler leases and prove all previous CPU users returned their pages. */
public interface TerrainPoolLease {
    void primept$retireAndAwait(long timeoutNanos);
}
