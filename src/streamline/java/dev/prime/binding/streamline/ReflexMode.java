package dev.prime.binding.streamline;

/** sl::ReflexMode */
public enum ReflexMode {
    OFF(0),
    LOW_LATENCY(1),
    LOW_LATENCY_WITH_BOOST(2);

    public final int value;

    ReflexMode(int value) {
        this.value = value;
    }

    public static ReflexMode fromValue(int value) {
        for (ReflexMode mode : values()) {
            if (mode.value == value) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown sl::ReflexMode value: " + value);
    }
}
