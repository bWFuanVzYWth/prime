package dev.prime.binding.streamline;

/** sl::DLSSGStatus (uint32_t bitmask) */
public enum DlssgStatus {
    FAIL_RESOLUTION_TOO_LOW(1),
    FAIL_COMMON_CONSTANTS_INVALID(1 << 3);

    public final int mask;

    DlssgStatus(int mask) {
        this.mask = mask;
    }
}
