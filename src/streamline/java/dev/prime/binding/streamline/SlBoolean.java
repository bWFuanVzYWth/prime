package dev.prime.binding.streamline;

/** sl::Boolean (enum : char) */
public enum SlBoolean {
    FALSE((byte) 0),
    TRUE((byte) 1),
    INVALID((byte) 2);

    public final byte value;

    SlBoolean(byte value) {
        this.value = value;
    }

    public static SlBoolean fromValue(byte value) {
        for (SlBoolean b : values()) {
            if (b.value == value) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown sl::Boolean value: " + value);
    }
}
