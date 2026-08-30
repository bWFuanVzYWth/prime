package dev.prime.binding.streamline;

/** sl::EngineType */
public enum EngineType {
    CUSTOM(0),
    UNREAL(1),
    UNITY(2);

    public final int value;

    EngineType(int value) {
        this.value = value;
    }

    public static EngineType fromValue(int value) {
        for (EngineType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown sl::EngineType value: " + value);
    }
}
