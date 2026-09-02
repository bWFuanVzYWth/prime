package dev.prime.binding.streamline;

/** sl::ResourceLifecycle */
public enum ResourceLifecycle {
    VALID_UNTIL_PRESENT(1);

    public final int value;

    ResourceLifecycle(int value) {
        this.value = value;
    }
}
