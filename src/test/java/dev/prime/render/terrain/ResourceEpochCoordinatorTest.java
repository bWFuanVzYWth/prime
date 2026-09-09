// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ResourceEpochCoordinatorTest {
    @Test
    void retireWaitsForEveryReaderAndResumeAdvancesTheEpoch() {
        ResourceEpochCoordinator coordinator = new ResourceEpochCoordinator();
        ResourceEpochCoordinator.Lease first = coordinator.tryAcquire();
        ResourceEpochCoordinator.Lease second = coordinator.tryAcquire();
        assertTrue(first != null && second != null);
        ResourceEpochCoordinator.Epoch retiredEpoch = first.epoch();

        ResourceEpochCoordinator.Reload reload = coordinator.pause();

        assertFalse(reload.ready().isDone());
        assertNull(coordinator.tryAcquire());
        assertNull(coordinator.tryAcquire(retiredEpoch));
        first.close();
        assertFalse(reload.ready().isDone());
        second.close();
        assertTrue(reload.ready().isDone());

        coordinator.finish(reload);
        ResourceEpochCoordinator.Lease resumed = coordinator.tryAcquire();
        assertTrue(resumed != null);
        assertEquals(Math.incrementExact(retiredEpoch.id()), resumed.epoch().id());
        resumed.close();
        coordinator.close();
    }

    @Test
    void queuedWorkerCannotEnterTheRetiredEpoch() throws Exception {
        ResourceEpochCoordinator coordinator = new ResourceEpochCoordinator();
        ResourceEpochCoordinator.Lease dispatch = coordinator.tryAcquire();
        assertTrue(dispatch != null);
        ResourceEpochCoordinator.Epoch epoch = dispatch.epoch();
        dispatch.close();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<Void> active = worker.submit(() -> {
                try (ResourceEpochCoordinator.Lease lease = coordinator.tryAcquire(epoch)) {
                    assertTrue(lease != null);
                    running.countDown();
                    assertTrue(release.await(10L, TimeUnit.SECONDS));
                }
                return null;
            });
            assertTrue(running.await(10L, TimeUnit.SECONDS));
            Future<ResourceEpochCoordinator.Lease> queued =
                    worker.submit(() -> coordinator.tryAcquire(epoch));

            ResourceEpochCoordinator.Reload reload = coordinator.pause();
            assertFalse(reload.ready().isDone());
            release.countDown();
            active.get();
            reload.ready().get();
            assertNull(queued.get());

            coordinator.finish(reload);
        }
        coordinator.close();
    }

    @Test
    void abortIsIdempotentButFinishAndCrossedResolutionFail() {
        ResourceEpochCoordinator coordinator = new ResourceEpochCoordinator();
        ResourceEpochCoordinator.Lease oldLease = coordinator.tryAcquire();
        assertTrue(oldLease != null);
        ResourceEpochCoordinator.Epoch oldEpoch = oldLease.epoch();

        ResourceEpochCoordinator.Reload aborted = coordinator.pause();
        coordinator.abort(aborted);
        coordinator.abort(aborted);
        assertNull(coordinator.tryAcquire());
        oldLease.close();
        ResourceEpochCoordinator.Lease reopened = coordinator.tryAcquire();
        assertTrue(reopened != null);
        reopened.close();
        assertThrows(IllegalStateException.class, () -> coordinator.finish(aborted));
        assertNull(coordinator.tryAcquire(oldEpoch));

        ResourceEpochCoordinator.Reload finished = coordinator.pause();
        coordinator.finish(finished);
        assertThrows(IllegalStateException.class, () -> coordinator.finish(finished));
        assertThrows(IllegalStateException.class, () -> coordinator.abort(finished));
        coordinator.close();
    }

    @Test
    void staleAndForeignTicketsFailClearly() {
        ResourceEpochCoordinator first = new ResourceEpochCoordinator();
        ResourceEpochCoordinator second = new ResourceEpochCoordinator();
        ResourceEpochCoordinator.Reload reload = first.pause();

        assertThrows(IllegalArgumentException.class, () -> second.finish(reload));
        assertThrows(IllegalStateException.class, first::pause);

        first.abort(reload);
        first.close();
        second.close();
    }

    @Test
    void closeRejectsAdmissionAndDoesNotLetAResolvedTicketReopen() {
        ResourceEpochCoordinator coordinator = new ResourceEpochCoordinator();
        ResourceEpochCoordinator.Lease lease = coordinator.tryAcquire();
        assertTrue(lease != null);
        ResourceEpochCoordinator.Reload reload = coordinator.pause();

        coordinator.close();
        coordinator.abort(reload);
        lease.close();

        assertTrue(reload.ready().isDone());
        assertNull(coordinator.tryAcquire());
        assertThrows(IllegalStateException.class, lease::close);
        assertThrows(IllegalStateException.class, coordinator::pause);
    }

    @Test
    void highContentionAdmissionDrainsWithoutPolling() throws Exception {
        ResourceEpochCoordinator coordinator = new ResourceEpochCoordinator();
        CountDownLatch entered = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> readers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                readers.add(executor.submit(() -> {
                    for (int attempt = 0; attempt < 10_000; attempt++) {
                        ResourceEpochCoordinator.Lease lease = coordinator.tryAcquire();
                        assertTrue(lease != null);
                        lease.close();
                    }
                    ResourceEpochCoordinator.Lease active = coordinator.tryAcquire();
                    assertTrue(active != null);
                    entered.countDown();
                    assertTrue(release.await(10L, TimeUnit.SECONDS));
                    active.close();
                    return null;
                }));
            }
            ResourceEpochCoordinator.Reload reload;
            try {
                assertTrue(entered.await(10L, TimeUnit.SECONDS));
                reload = coordinator.pause();
                assertFalse(reload.ready().isDone());
            } finally {
                release.countDown();
            }
            for (Future<?> reader : readers) {
                reader.get();
            }
            reload.ready().get();
            coordinator.finish(reload);
        }
        coordinator.close();
    }
}
