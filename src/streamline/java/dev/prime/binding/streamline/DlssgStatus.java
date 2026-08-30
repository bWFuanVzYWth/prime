package dev.prime.binding.streamline;

/** sl::DLSSGStatus (uint32_t bitmask) */
public enum DlssgStatus {
    OK(0),
    FAIL_RESOLUTION_TOO_LOW(1),
    FAIL_REFLEX_NOT_DETECTED_AT_RUNTIME(1 << 1),
    FAIL_HDR_FORMAT_NOT_SUPPORTED(1 << 2),
    FAIL_COMMON_CONSTANTS_INVALID(1 << 3),
    FAIL_GET_CURRENT_BACK_BUFFER_INDEX_NOT_CALLED(1 << 4);

    public final int mask;

    DlssgStatus(int mask) {
        this.mask = mask;
    }
}
