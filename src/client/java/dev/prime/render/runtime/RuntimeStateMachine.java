// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

public final class RuntimeStateMachine {
    private RuntimeState current = RuntimeState.UNAVAILABLE;

    public RuntimeState current() {
        return this.current;
    }

    public void disabled() {
        this.current = RuntimeState.DISABLED;
    }

    public void unavailable() {
        if (this.current != RuntimeState.FAILED) {
            this.current = RuntimeState.UNAVAILABLE;
        }
    }

    public void rendererReady() {
        if (this.current != RuntimeState.FAILED) {
            this.current = RuntimeState.WAITING_FOR_WORLD;
        }
    }

    public void worldAbsent() {
        if (this.current != RuntimeState.DISABLED
                && this.current != RuntimeState.FAILED
                && this.current != RuntimeState.UNAVAILABLE) {
            this.current = RuntimeState.WAITING_FOR_WORLD;
        }
    }

    public void worldChanged() {
        this.worldAbsent();
    }

    public void worldStreaming(boolean active) {
        if (this.current != RuntimeState.DISABLED
                && this.current != RuntimeState.FAILED
                && this.current != RuntimeState.UNAVAILABLE) {
            if (this.current != RuntimeState.ACTIVE) {
                this.current = active ? RuntimeState.ACTIVE : RuntimeState.STREAMING;
            }
        }
    }

    public void fail() {
        this.current = RuntimeState.FAILED;
    }

    public void shutdown() {
        this.current = RuntimeState.UNAVAILABLE;
    }
}
