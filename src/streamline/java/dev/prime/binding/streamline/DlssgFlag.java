package dev.prime.binding.streamline;

/** sl::DLSSGFlags (uint32_t bitmask) */
public enum DlssgFlag {
    SHOW_ONLY_INTERPOLATED_FRAME(1),
    DYNAMIC_RESOLUTION_ENABLED(1 << 1),
    REQUEST_VRAM_ESTIMATE(1 << 2),
    RETAIN_RESOURCES_WHEN_OFF(1 << 3),
    ENABLE_FULLSCREEN_MENU_DETECTION(1 << 4);

    public final int mask;

    DlssgFlag(int mask) {
        this.mask = mask;
    }
}
