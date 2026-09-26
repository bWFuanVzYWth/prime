package dev.primept.render;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RendererSlotTest {
    private static final class Backend implements RendererSlot.Backend {
        final String name;
        final List<String> events;
        boolean failStart, failClose;
        Backend(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }
        @Override
        public void start() {
            events.add(name + ":start");
            if (failStart)
                throw new IllegalStateException("start");
        }
        @Override
        public void close() {
            events.add(name + ":complete-destroy");
            if (failClose)
                throw new IllegalStateException("retirement not proven");
        }
    }

    @Test
    void vanillaAndMultiplePrimeBackendsShareOneLazySlot() throws Exception {
        var events = new ArrayList<String>();
        try (var slot = new RendererSlot<String, Backend>()) {
            for (String name : List.of("vanilla", "prime-a", "prime-b", "vanilla")) {
                slot.select(name, () -> {
                    assertEquals(RendererSlot.State.STARTING, slot.state());
                    events.add(name + ":construct");
                    return new Backend(name, events);
                });
                assertEquals(name, slot.active().name);
                slot.select(name, () -> {
                    throw new AssertionError("Same renderer must not construct twice");
                });
            }
        }
        assertEquals(List.of("vanilla:construct", "vanilla:start", "vanilla:complete-destroy",
                             "prime-a:construct", "prime-a:start", "prime-a:complete-destroy",
                             "prime-b:construct", "prime-b:start", "prime-b:complete-destroy",
                             "vanilla:construct", "vanilla:start", "vanilla:complete-destroy"),
                     events);
    }

    @Test
    void uncertainOldCompletionBlocksNewFactoryAndCannotBeClearedByAnotherClose() throws Exception {
        var events = new ArrayList<String>();
        var slot = new RendererSlot<String, Backend>();
        var old = new Backend("old", events);
        slot.select("old", () -> old);
        old.failClose = true;
        assertThrows(IllegalStateException.class,
                     () -> slot.select("new", () -> { throw new AssertionError("must not run"); }));
        assertEquals(RendererSlot.State.BLOCKED, slot.state());
        assertNull(slot.active());
        old.failClose =
                false; // A second call returning normally is not evidence the original GPU failure was safe.
        assertThrows(IllegalStateException.class, slot::close);
        assertThrows(IllegalStateException.class, () -> slot.select("new", () -> old));
        assertEquals(List.of("old:start", "old:complete-destroy"), events);
    }

    @Test
    void partialCreationIsRetiredBeforeRecoveryAndFailedRetirementRemainsOwned() throws Exception {
        var events = new ArrayList<String>();
        var slot = new RendererSlot<String, Backend>();
        var bad = new Backend("bad", events);
        bad.failStart = true;
        assertThrows(IllegalStateException.class, () -> slot.select("bad", () -> bad));
        assertEquals(RendererSlot.State.EMPTY, slot.state());
        slot.select("fallback", () -> new Backend("fallback", events));
        assertEquals(List.of("bad:start", "bad:complete-destroy", "fallback:start"), events);
        slot.close();
        bad.failClose = true;
        var failure =
                assertThrows(IllegalStateException.class, () -> slot.select("bad", () -> bad));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(RendererSlot.State.BLOCKED, slot.state());
        assertNull(slot.active());
    }

    @Test
    void workerCannotSwitchOrRetireTheRenderThreadOwner() throws Exception {
        var slot = new RendererSlot<String, Backend>();
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                slot.select("bad", () -> { throw new AssertionError("must not construct"); });
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        worker.start();
        worker.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(RendererSlot.State.EMPTY, slot.state());
    }

    @Test
    void factoryCannotReenterAndOrphanAnInnerRenderer() throws Exception {
        var slot = new RendererSlot<String, Backend>();
        var events = new ArrayList<String>();
        slot.select("outer", () -> {
            assertThrows(IllegalStateException.class,
                         () -> slot.select("inner", () -> { throw new AssertionError(); }));
            assertThrows(IllegalStateException.class, slot::close);
            return new Backend("outer", events);
        });
        assertEquals(List.of("outer:start"), events);
        slot.close();
        assertThrows(IllegalArgumentException.class,
                     () -> slot.select("failed", () -> { throw new IllegalArgumentException(); }));
        assertEquals(RendererSlot.State.EMPTY, slot.state());
        assertNull(slot.key());
    }
}
