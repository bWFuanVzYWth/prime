// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

/** Owns NRD frame bindings until queue completion returns each allocation. */
final class NrdFrameBindingPool implements Destroyable {
    private final NrdDenoiser owner;
    private final ArrayDeque<NrdFrameBindings> free = new ArrayDeque<>();
    private final Set<NrdFrameBindings> all =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean destroyed;

    NrdFrameBindingPool(NrdDenoiser owner) {
        this.owner = owner;
    }

    NrdFrameBindings acquire(int requiredDispatches) {
        synchronized (this.free) {
            if (this.destroyed) {
                throw new IllegalStateException("NRD frame binding pool is destroyed");
            }
            for (Iterator<NrdFrameBindings> iterator = this.free.iterator();
                    iterator.hasNext();) {
                NrdFrameBindings bindings = iterator.next();
                if (bindings.dispatchCapacity() >= requiredDispatches) {
                    iterator.remove();
                    return bindings;
                }
            }
        }
        NrdFrameBindings created = NrdFrameBindings.create(this.owner, requiredDispatches);
        synchronized (this.free) {
            if (this.destroyed) {
                created.destroy();
                throw new IllegalStateException("NRD frame binding pool is destroyed");
            }
            this.all.add(created);
        }
        return created;
    }

    void recycle(NrdFrameBindings bindings) {
        synchronized (this.free) {
            if (this.destroyed) {
                bindings.destroy();
                this.all.remove(bindings);
            } else {
                this.free.addLast(bindings);
            }
        }
    }

    @Override
    public void destroy() {
        RuntimeException failure = null;
        synchronized (this.free) {
            if (this.destroyed) {
                return;
            }
            this.destroyed = true;
            for (NrdFrameBindings bindings : this.all) {
                failure = ResourceCleanup.destroy(bindings, failure);
            }
            this.all.clear();
            this.free.clear();
        }
        ResourceCleanup.throwIfFailed(failure);
    }
}
