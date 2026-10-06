package dev.primept;

import static dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.LongSupplier;

/** Render-thread display sampler; its fixed CPU snapshot outlives all synchronous FFM reads. */
public final class PresentationFps {
    private static final long POLL_NS = 250_000_000L;
    private static final MemorySegment snapshot =
            Arena.ofAuto().allocate(PrimePresentationStats.LAYOUT);
    private static LongSupplier clock = System::nanoTime;
    private static PresentationFrameRate rate = new PresentationFrameRate();
    private static long polledAt;
    private static boolean polled;
    private PresentationFps() {}

    public static int framesPerSecond() {
        long now = clock.getAsLong();
        if (!polled || now < polledAt || now - polledAt >= POLL_NS) {
            polled = true;
            polledAt = now;
            int status = NativeBridge.presentationStatistics(snapshot);
            if (status == 0)
                rate.observe(now, PrimePresentationStats.epoch(snapshot),
                             PrimePresentationStats.total_presented(snapshot),
                             PrimePresentationStats.sample_id(snapshot),
                             PrimePresentationStats.active(snapshot) != 0,
                             PrimePresentationStats.valid(snapshot) != 0);
            else if (status == 1)
                rate.advance(now);
            else
                rate.clear();
        } else
            rate.advance(now);
        return rate.framesPerSecond();
    }
}
