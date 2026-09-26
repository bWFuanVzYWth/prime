package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.primept.capture.TerrainPoolLease;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionBufferBuilderPool.class)
public abstract class ExclusiveTerrainPoolMixin implements TerrainPoolLease {
    @Unique private final Object primept$leaseLock = new Object();
    @Unique private int primept$leases;
    @Unique private boolean primept$retiring;

    @WrapMethod(method = "acquire")
    private SectionBufferBuilderPack primept$acquire(Operation<SectionBufferBuilderPack> original) {
        synchronized (primept$leaseLock) {
            if (primept$retiring)
                return null;
            var buffer = original.call();
            if (buffer != null)
                ++primept$leases;
            return buffer;
        }
    }
    @WrapMethod(method = "release")
    private void primept$release(SectionBufferBuilderPack buffer, Operation<Void> original) {
        synchronized (primept$leaseLock) {
            original.call(buffer);
            --primept$leases;
            primept$leaseLock.notifyAll();
        }
    }
    @Override
    public void primept$retireAndAwait(long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (primept$leaseLock) {
            primept$retiring = true;
            // MC runTask returns the pack directly on the worker after doTask/discardAll. No render-thread
            // callback is needed for this proof. Exceptional workers that leaked a lease block replacement.
            while (primept$leases != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    throw new IllegalStateException("Vanilla terrain compiler did not release " +
                                                    primept$leases + " source buffer leases");
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(primept$leaseLock,
                                                                        remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while retiring vanilla terrain source pages", exception);
                }
            }
        }
    }
}
