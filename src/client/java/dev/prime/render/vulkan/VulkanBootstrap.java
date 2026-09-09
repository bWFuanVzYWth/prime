// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public final class VulkanBootstrap {
    private static final StateMachine<VulkanDevice> STATE = new StateMachine<>(
            VulkanCapabilities.unavailable(
                    "unknown", "Vulkan device negotiation has not run"));

    private VulkanBootstrap() {
    }

    /** Starts one device-creation attempt and invalidates every token and device from older attempts. */
    public static Negotiation beginNegotiation() {
        return STATE.begin();
    }

    public static void recordNegotiation(
            Negotiation negotiation, VulkanCapabilities capabilities) {
        STATE.record(negotiation, capabilities);
    }

    public static void attachDevice(
            Negotiation negotiation, VulkanDevice vulkanDevice) {
        STATE.attach(negotiation, vulkanDevice);
    }

    public static Snapshot snapshot() {
        State<VulkanDevice> state = STATE.snapshot();
        return new Snapshot(state.capabilities(), state.device());
    }

    public record Snapshot(VulkanCapabilities capabilities, @Nullable VulkanDevice device) {
        public Snapshot {
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    public record Negotiation(long generation) {
        public Negotiation {
            if (generation <= 0L) {
                throw new IllegalArgumentException("Vulkan negotiation generation must be positive");
            }
        }
    }

    static final class StateMachine<T> {
        private static final VulkanCapabilities NOT_RECORDED =
                VulkanCapabilities.unavailable(
                        "unknown", "Vulkan device negotiation is in progress");

        private final AtomicReference<State<T>> state;

        StateMachine(VulkanCapabilities initialCapabilities) {
            this.state = new AtomicReference<>(new State<>(
                    0L,
                    Objects.requireNonNull(initialCapabilities, "initialCapabilities"),
                    null,
                    false));
        }

        Negotiation begin() {
            while (true) {
                State<T> current = this.state.get();
                long generation = Math.incrementExact(current.generation());
                State<T> next = new State<>(generation, NOT_RECORDED, null, false);
                if (this.state.compareAndSet(current, next)) {
                    return new Negotiation(generation);
                }
            }
        }

        void record(Negotiation negotiation, VulkanCapabilities capabilities) {
            Objects.requireNonNull(capabilities, "capabilities");
            while (true) {
                State<T> current = this.requireCurrent(negotiation);
                if (current.negotiated()) {
                    throw new IllegalStateException(
                            "Vulkan capabilities were already recorded for negotiation "
                                    + negotiation.generation());
                }
                State<T> next = new State<>(
                        current.generation(), capabilities, null, true);
                if (this.state.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        void attach(Negotiation negotiation, T device) {
            Objects.requireNonNull(device, "device");
            while (true) {
                State<T> current = this.requireCurrent(negotiation);
                if (!current.negotiated()) {
                    throw new IllegalStateException(
                            "Vulkan capabilities are not recorded for negotiation "
                                    + negotiation.generation());
                }
                if (current.device() != null) {
                    throw new IllegalStateException(
                            "A Vulkan device is already attached to negotiation "
                                    + negotiation.generation());
                }
                State<T> next = new State<>(
                        current.generation(), current.capabilities(), device, true);
                if (this.state.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        State<T> snapshot() {
            return this.state.get();
        }

        private State<T> requireCurrent(Negotiation negotiation) {
            Objects.requireNonNull(negotiation, "negotiation");
            State<T> current = this.state.get();
            if (current.generation() != negotiation.generation()) {
                throw new IllegalStateException(
                        "Stale Vulkan negotiation " + negotiation.generation()
                                + "; current generation is " + current.generation());
            }
            return current;
        }
    }

    record State<T>(
            long generation,
            VulkanCapabilities capabilities,
            @Nullable T device,
            boolean negotiated) {
        State {
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
            if (!negotiated && device != null) {
                throw new IllegalArgumentException(
                        "A device cannot precede Vulkan capability negotiation");
            }
        }
    }
}
