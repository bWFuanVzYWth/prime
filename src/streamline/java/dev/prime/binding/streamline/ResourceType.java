package dev.prime.binding.streamline;

/** sl::ResourceType (enum class : char) */
public enum ResourceType {
    TEX2D((byte) 0),
    BUFFER((byte) 1),
    COMMAND_QUEUE((byte) 2),
    COMMAND_BUFFER((byte) 3),
    COMMAND_POOL((byte) 4),
    FENCE((byte) 5),
    SWAPCHAIN((byte) 6),
    HOST_FENCE((byte) 7),
    UNKNOWN((byte) 8);

    public final byte value;

    ResourceType(byte value) {
        this.value = value;
    }

    public static ResourceType fromValue(byte value) {
        for (ResourceType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown sl::ResourceType value: " + value);
    }
}
