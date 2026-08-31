package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.MaterialIdResolver;
import dev.prime.render.terrain.MaterialTableCandidate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread-owned renderer-lifetime MaterialId allocator; IDs are never reused. */
final class MaterialIdRegistry {
    private final Map<MaterialTableCandidate.Key, Integer> ids = new HashMap<>();
    private int nextId = 1;

    int resolve(MaterialTableCandidate.Key key) {
        Objects.requireNonNull(key, "key");
        Integer existing = this.ids.get(key);
        if (existing != null) {
            return existing;
        }
        if (this.nextId > MaterialIdResolver.MAX_ID) {
            throw new IllegalStateException("Prime exhausted its u16 MaterialId space");
        }
        int assigned = this.nextId++;
        this.ids.put(key, assigned);
        return assigned;
    }

    Snapshot snapshot() {
        return new Snapshot(this.ids.size(), this.nextId - 1);
    }

    record Snapshot(int assignedCount, int highWaterId) {
        Snapshot {
            if (assignedCount < 0
                    || assignedCount > MaterialIdResolver.MAX_ID
                    || highWaterId < 0
                    || highWaterId > MaterialIdResolver.MAX_ID
                    || assignedCount != highWaterId) {
                throw new IllegalArgumentException("Invalid renderer MaterialId statistics");
            }
        }
    }
}
