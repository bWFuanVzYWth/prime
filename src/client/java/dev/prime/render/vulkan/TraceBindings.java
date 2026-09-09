// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

/**
 * Render-thread-owned shared trace descriptor view.
 *
 * <p>Pipelines borrow this narrow view; descriptor allocation and upload ownership remain in
 * {@link TraceBackend}.
 */
public final class TraceBindings {
    private final long descriptorSetLayout;
    private long descriptorSet;
    private boolean ready;
    private boolean closed;

    TraceBindings(long descriptorSetLayout) {
        if (descriptorSetLayout == 0L) {
            throw new IllegalArgumentException("Trace descriptor-set layout must be non-zero");
        }
        this.descriptorSetLayout = descriptorSetLayout;
    }

    public long descriptorSetLayout() {
        requireOpen();
        return this.descriptorSetLayout;
    }

    public long descriptorSet() {
        requireOpen();
        if (this.descriptorSet == 0L) {
            throw new IllegalStateException("Shared trace descriptors have not been initialized");
        }
        return this.descriptorSet;
    }

    public boolean ready() {
        return !this.closed && this.ready;
    }

    void publishDescriptorSet(long descriptorSet) {
        requireOpen();
        if (descriptorSet == 0L) {
            throw new IllegalArgumentException("Trace descriptor set must be non-zero");
        }
        this.descriptorSet = descriptorSet;
    }

    void setReady(boolean ready) {
        requireOpen();
        this.ready = ready;
    }

    void close() {
        this.closed = true;
        this.ready = false;
        this.descriptorSet = 0L;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Trace bindings are closed");
        }
    }
}
