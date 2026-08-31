package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.MaterialIdResolver;
import dev.prime.render.terrain.MaterialTableCandidate;
import dev.prime.render.shader.ShaderAbi;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread-owned renderer-lifetime MaterialId allocator; IDs are never reused. */
final class MaterialIdRegistry {
    static final long BUFFER_BYTES = Math.multiplyExact(
            (long) MaterialIdResolver.MAX_ID + 1L,
            ShaderAbi.MATERIAL_CORE_RECORD_SIZE);

    private final Map<MaterialTableCandidate.Key, Integer> ids = new HashMap<>();
    private final int[] coreRecords = new int[MaterialIdResolver.MAX_ID + 1];
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
        this.coreRecords[assigned] = encodeCore(key);
        return assigned;
    }

    int[] encodedCoreRecords() {
        return Arrays.copyOf(this.coreRecords, this.nextId);
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

    static int encodeCore(MaterialTableCandidate.Key key) {
        Objects.requireNonNull(key, "key");
        int textureId = key.textureId();
        int control = key.materialControl();
        if ((textureId & ~ShaderAbi.MATERIAL_CORE_TEXTURE_ID_MASK) != 0
                || (control & ~ShaderAbi.MATERIAL_CORE_RECIPE_CONTROL_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Material core facts exceed their generated ABI fields");
        }
        return textureId | control << ShaderAbi.MATERIAL_CORE_RECIPE_CONTROL_SHIFT;
    }
}
