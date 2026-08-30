package dev.prime.binding.streamline;

/** sl::PreferenceFlags (uint64_t bitmask) */
public enum PreferenceFlag {
    DISABLE_CL_STATE_TRACKING(1L),
    DISABLE_DEBUG_TEXT(1L << 1),
    USE_MANUAL_HOOKING(1L << 2),
    ALLOW_OTA(1L << 3),
    BYPASS_OS_VERSION_CHECK(1L << 4),
    USE_DXGI_FACTORY_PROXY(1L << 5),
    LOAD_DOWNLOADED_PLUGINS(1L << 6),
    USE_FRAME_BASED_RESOURCE_TAGGING(1L << 7);

    public final long mask;

    PreferenceFlag(long mask) {
        this.mask = mask;
    }
}
