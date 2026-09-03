package dev.prime.render.terrain;

import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.scene.SpriteId;
import java.util.Map;
import java.util.Set;

/** Immutable resource-pack material availability captured by terrain build jobs. */
public record LabPbrMaterialSet(
        Map<SpriteId, Integer> textureIds,
        Set<SpriteId> normalSprites,
        Set<SpriteId> specularSprites,
        Map<SpriteId, LabPbrEmissionMap> emissionMaps,
        Map<SpriteId, LabPbrHeightMap> heightMaps,
        Map<SpriteId, LabPbrMaterialMap> materialMaps) {
    public static final LabPbrMaterialSet EMPTY = new LabPbrMaterialSet(
            Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of());

    public LabPbrMaterialSet(
            Set<SpriteId> normalSprites,
            Set<SpriteId> specularSprites,
            Map<SpriteId, LabPbrEmissionMap> emissionMaps) {
        this(Map.of(), normalSprites, specularSprites, emissionMaps, Map.of(), Map.of());
    }

    public LabPbrMaterialSet {
        textureIds = Map.copyOf(textureIds);
        normalSprites = Set.copyOf(normalSprites);
        specularSprites = Set.copyOf(specularSprites);
        emissionMaps = Map.copyOf(emissionMaps);
        heightMaps = Map.copyOf(heightMaps);
        materialMaps = Map.copyOf(materialMaps);
    }

    public boolean hasNormal(SpriteId sprite) {
        return this.normalSprites.contains(sprite);
    }

    public boolean hasSpecular(SpriteId sprite) {
        return this.specularSprites.contains(sprite);
    }

    public LabPbrEmissionMap emissionMap(SpriteId sprite) {
        return this.emissionMaps.get(sprite);
    }

    public LabPbrHeightMap heightMap(SpriteId sprite) {
        return this.heightMaps.get(sprite);
    }

    public LabPbrMaterialMap materialMap(SpriteId sprite) {
        return this.materialMaps.get(sprite);
    }

    public int textureId(SpriteId sprite) {
        Integer result = this.textureIds.get(sprite);
        if (result == null) {
            throw new IllegalArgumentException("Sprite is absent from the texture catalog: " + sprite);
        }
        return result;
    }

    public LabPbrMaterialSet withoutNormalTextures() {
        return this.normalSprites.isEmpty()
                ? this
                : new LabPbrMaterialSet(
                        this.textureIds,
                        Set.of(),
                        this.specularSprites,
                        this.emissionMaps,
                        this.heightMaps,
                        this.materialMaps);
    }

    /** Whether existing terrain remains valid under {@code other}; catalog extension is harmless. */
    public boolean translationEquivalent(
            LabPbrMaterialSet other, SurfaceDetailMode mode) {
        java.util.Objects.requireNonNull(other, "other");
        java.util.Objects.requireNonNull(mode, "mode");
        return mappingsPreserved(this.textureIds, other.textureIds)
                && (!mode.usesResourceNormals()
                        || this.normalSprites.equals(other.normalSprites))
                && this.specularSprites.equals(other.specularSprites)
                && this.emissionMaps.equals(other.emissionMaps)
                && (!mode.usesGeometryDisplacement()
                        || this.heightMaps.equals(other.heightMaps)
                                && this.materialMaps.equals(other.materialMaps));
    }

    /**
     * Whether publishing {@code replacement} would make an existing primitive's texture lookup
     * invalid. Added maps can be adopted by ordinary replacement builds; removed maps and changed
     * IDs require old primitives to leave the resident scene before new descriptors are used.
     */
    public boolean invalidatesResidentTextureLookups(LabPbrMaterialSet replacement) {
        java.util.Objects.requireNonNull(replacement, "replacement");
        return !mappingsPreserved(this.textureIds, replacement.textureIds)
                || !replacement.normalSprites.containsAll(this.normalSprites)
                || !replacement.specularSprites.containsAll(this.specularSprites);
    }

    private static boolean mappingsPreserved(
            Map<SpriteId, Integer> existing,
            Map<SpriteId, Integer> replacement) {
        for (Map.Entry<SpriteId, Integer> entry : existing.entrySet()) {
            if (!entry.getValue().equals(replacement.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
