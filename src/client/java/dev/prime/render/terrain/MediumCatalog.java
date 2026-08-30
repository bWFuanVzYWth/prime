package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Invocation-local exact medium catalog; zero is permanently reserved for vacuum. */
final class MediumCatalog {
    private final Map<MediumKey, Integer> ids = new LinkedHashMap<>();
    private final ArrayList<MediumKey> keys = new ArrayList<>();

    int resolve(SurfaceDefinition.MaterialBinding binding) {
        return this.resolve(binding.surface(), binding.transmissiveTopology());
    }

    int resolve(SurfaceDefinition.MediumEndpoint endpoint) {
        return this.resolve(endpoint.surface(), endpoint.transmissiveTopology());
    }

    private int resolve(
            CapturedSectionGeometry.Surface surface,
            TransmissiveTopology topology) {
        if (!ClusterSceneTranslator.isTransmissive(surface)
                || topology.thinWalled()) {
            return 0;
        }
        MediumKey key = MediumKey.of(surface);
        Integer existing = this.ids.get(key);
        if (existing != null) {
            return existing;
        }
        int assigned = Math.addExact(this.keys.size(), 1);
        this.ids.put(key, assigned);
        this.keys.add(key);
        return assigned;
    }

    List<MediumKey> snapshot() {
        return List.copyOf(this.keys);
    }
}
