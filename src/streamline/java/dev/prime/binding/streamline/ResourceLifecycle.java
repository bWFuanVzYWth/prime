package dev.prime.binding.streamline;

/** sl::ResourceLifecycle */
public enum ResourceLifecycle {
    ONLY_VALID_NOW(0),
    VALID_UNTIL_PRESENT(1),
    VALID_UNTIL_EVALUATE(2);

    public final int value;

    ResourceLifecycle(int value) {
        this.value = value;
    }

    public static ResourceLifecycle fromValue(int value) {
        for (ResourceLifecycle lifecycle : values()) {
            if (lifecycle.value == value) {
                return lifecycle;
            }
        }
        throw new IllegalArgumentException("Unknown sl::ResourceLifecycle value: " + value);
    }
}
