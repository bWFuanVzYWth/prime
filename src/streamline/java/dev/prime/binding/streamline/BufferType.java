package dev.prime.binding.streamline;

/** sl::BufferType (uint32_t) */
public enum BufferType {
    DEPTH(0),
    MOTION_VECTORS(1),
    HUD_LESS_COLOR(2),
    UI_ALPHA(69);

    public final int value;

    BufferType(int value) {
        this.value = value;
    }
}
