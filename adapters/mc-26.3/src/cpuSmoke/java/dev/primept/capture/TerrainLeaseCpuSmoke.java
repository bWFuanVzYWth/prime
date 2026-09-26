package dev.primept.capture;

import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

final class TerrainLeaseCpuSmoke {
    static void run() throws Exception {
        var pool = SectionBufferBuilderPool.allocate(1);
        var held = pool.acquire();
        check(held != null, "Source pack acquired");
        var gate = (TerrainPoolLease)pool;
        boolean rejected = false;
        try {
            gate.primept$retireAndAwait(1_000_000);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        check(rejected && pool.acquire() == null && pool.getFreeBufferCount() == 0,
              "Unproven CPU completion blocks replacement and new leases");
        held.clearAll(); // Timeout must retain valid worker-owned pages.
        pool.release(held);
        gate.primept$retireAndAwait(100_000_000);
        check(pool.getFreeBufferCount() == 1, "Late worker return supplies completion proof");
        pool.close();

        var concurrent = SectionBufferBuilderPool.allocate(1);
        var lease = concurrent.acquire();
        var began = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread retiring = Thread.ofPlatform().start(() -> {
            began.countDown();
            try {
                ((TerrainPoolLease)concurrent).primept$retireAndAwait(1_000_000_000);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        began.await();
        concurrent.release(lease);
        retiring.join(2000);
        check(!retiring.isAlive() && failure.get() == null,
              "Worker release does not require a blocked render-thread task");
        concurrent.close();

        var buffers = new RenderBuffers(1);
        var exclusive = (ExclusiveRenderBuffers)buffers;
        var staged = buffers.stagedVertexBuffer();
        var oldPool = buffers.sectionBufferPool();
        exclusive.primept$retireTerrainBuffers();
        check(buffers.fixedBufferPack() == null && buffers.sectionBufferPool() == null,
              "No inactive terrain CPU packs retained");
        check(buffers.stagedVertexBuffer() == staged, "Shared hand/HUD staging owner survives");
        exclusive.primept$restoreTerrainBuffers();
        check(buffers.fixedBufferPack() != null && buffers.sectionBufferPool() != null &&
                      buffers.sectionBufferPool() != oldPool,
              "Vanilla allocates new terrain packs only on activation");
        exclusive.primept$retireTerrainBuffers();
        buffers.close();
        System.out.println(
                "PRIME_PT_TERRAIN_LEASE_CPU_OK: in-flight CPU lease timeout retains pages, late completion, shared staging, exclusive retire/recreate");
    }
    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
}
