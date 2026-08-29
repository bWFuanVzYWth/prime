package dev.prime.render.vulkan;

/** Single-owner identity gate for one prepared but unresolved resource submission. */
final class PendingSubmission<T> {
    private T value;

    boolean active() {
        return this.value != null;
    }

    void begin(T candidate) {
        java.util.Objects.requireNonNull(candidate, "candidate");
        if (this.value != null) {
            throw new IllegalStateException("A resource submission is already pending");
        }
        this.value = candidate;
    }

    void complete(T candidate, String mismatchMessage) {
        if (candidate == null || candidate != this.value) {
            throw new IllegalArgumentException(mismatchMessage);
        }
        this.value = null;
    }

    T clear() {
        T result = this.value;
        this.value = null;
        return result;
    }
}
