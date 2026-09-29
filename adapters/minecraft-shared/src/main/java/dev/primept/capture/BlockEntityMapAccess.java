package dev.primept.capture;

import java.util.function.Supplier;

/** Scope only for pinned read-only consumers. Unknown callers keep the original mutable map and disable caching. */
public final class BlockEntityMapAccess {
    private static final ThreadLocal<Boolean> READING = new ThreadLocal<>();
    public static boolean reading() {
        return READING.get() == Boolean.TRUE;
    }
    public static <T> T read(Supplier<T> action) {
        boolean previous = reading();
        READING.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous)
                READING.set(Boolean.TRUE);
            else
                READING.remove();
        }
    }
    public interface Membership {
        boolean primept$escapedBlockEntities();
    }
    private BlockEntityMapAccess() {}
}
