package dev.primept.render;

import java.util.Objects;
import java.util.function.Supplier;

/** One active resource owner. Factories are lazy; failed retirement never permits a second owner. */
public final class RendererSlot<K, R extends RendererSlot.Backend> implements AutoCloseable {
    public interface Backend extends AutoCloseable {
        /** Called only after the previous backend has completed and released all exclusive resources. */
        void start() throws Exception;
        /** Success is proof of retirement. A failed close leaves the slot blocked with this owner retained. */
        @Override void close() throws Exception;
    }

    public enum State { EMPTY, STARTING, ACTIVE, STOPPING, BLOCKED }
    private final Thread owner = Thread.currentThread();
    private State state = State.EMPTY;
    private K key;
    private R backend;

    public State state() {
        return state;
    }
    public K key() {
        return key;
    }
    public R active() {
        return state == State.ACTIVE ? backend : null;
    }

    /** The factory constructs only a lightweight owner; fallible resource creation belongs in start(). */
    public void select(K requested, Supplier<? extends R> factory) throws Exception {
        checkTransition();
        Objects.requireNonNull(requested);
        Objects.requireNonNull(factory);
        if (state == State.ACTIVE && requested.equals(key))
            return;
        retire();
        key = requested;
        state = State.STARTING;
        R candidate;
        try {
            candidate = Objects.requireNonNull(factory.get(), "Renderer factory returned null");
            backend = candidate;
        } catch (Exception | Error failure) {
            clear();
            throw failure;
        }
        try {
            candidate.start();
            state = State.ACTIVE;
        } catch (Exception | Error failure) {
            state = State.STOPPING;
            try {
                candidate.close();
                clear();
            } catch (Exception | Error retirement) {
                state = State.BLOCKED;
                failure.addSuppressed(retirement);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        checkTransition();
        retire();
    }

    private void retire() throws Exception {
        if (state == State.EMPTY)
            return;
        state = State.STOPPING;
        try {
            backend.close();
            clear();
        } catch (Exception | Error failure) {
            state = State.BLOCKED;
            throw failure;
        }
    }

    private void clear() {
        backend = null;
        key = null;
        state = State.EMPTY;
    }
    private void checkTransition() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Renderer transitions require their owner thread");
        if (state == State.BLOCKED)
            throw new IllegalStateException(
                    "Renderer retirement is unresolved; another renderer cannot be created");
        if (state == State.STARTING || state == State.STOPPING)
            throw new IllegalStateException("Reentrant renderer transition");
    }
}
