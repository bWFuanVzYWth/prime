package dev.prime.binding.streamline;

/** sl::LogLevel */
public enum LogLevel {
    OFF(0),
    DEFAULT(1),
    VERBOSE(2);

    public final int value;

    LogLevel(int value) {
        this.value = value;
    }

    public static LogLevel fromValue(int value) {
        for (LogLevel level : values()) {
            if (level.value == value) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown sl::LogLevel value: " + value);
    }
}
