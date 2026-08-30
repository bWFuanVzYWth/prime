package dev.prime.binding.streamline;

/** sl::DLSSGMode */
public enum DlssgMode {
    OFF(0),
    ON(1),
    AUTO(2),
    DYNAMIC(3);

    public final int value;

    DlssgMode(int value) {
        this.value = value;
    }

    public static DlssgMode fromValue(int value) {
        for (DlssgMode mode : values()) {
            if (mode.value == value) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown sl::DLSSGMode value: " + value);
    }
}
