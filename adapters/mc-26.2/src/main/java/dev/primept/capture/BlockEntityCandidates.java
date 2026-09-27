package dev.primept.capture;

import dev.primept.PrimeClient;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Conservative mechanical broad phase; unknown callbacks always use the original dispatcher path. */
public final class BlockEntityCandidates {
    private final IdentityHashMap<Class<?>, Boolean> entityClasses = new IdentityHashMap<>();
    private final IdentityHashMap<Class<?>, Boolean> rendererClasses = new IdentityHashMap<>();
    private final IdentityHashMap<BlockEntityType<?>, Kind> types = new IdentityHashMap<>();
    private BlockEntityRenderDispatcher dispatcher;
    private long resourceEpoch;
    private boolean supported;
    private final boolean profile = Boolean.getBoolean("primept.profile");
    private int frames;
    private long inspected, rejectedFar, unknownFallback, nearCube, localExtractions,
            globalExtractions;

    public boolean prepare(BlockEntityRenderDispatcher current, long epoch) {
        if (dispatcher == current && resourceEpoch == epoch)
            return false;
        dispatcher = current;
        resourceEpoch = epoch;
        entityClasses.clear();
        rendererClasses.clear();
        types.clear();
        resetProfile();
        supported = current.getClass() == BlockEntityRenderDispatcher.class &&
                    known(BlockEntityRenderDispatcher.class) && known(BlockEntity.class) &&
                    known(BlockEntityType.class) && known(BlockEntityRenderer.class);
        return true;
    }

    public void clear() {
        dispatcher = null;
        entityClasses.clear();
        rendererClasses.clear();
        types.clear();
        supported = false;
        resetProfile();
    }

    public boolean outsideDefaultRange(BlockEntity entity, Vec3 camera) {
        if (profile)
            ++inspected;
        if (kind(entity) != Kind.BOUNDED)
            return fallback();
        var pos = entity.getBlockPos();
        // The default predicate uses block-center distance strictly below 64.
        // A cube broad phase only excludes points that this sphere cannot admit;
        // all survivors still run the real shouldRender exactly once.
        boolean outside = Math.abs(pos.getX() + 0.5 - camera.x) >= 64.0 ||
                          Math.abs(pos.getY() + 0.5 - camera.y) >= 64.0 ||
                          Math.abs(pos.getZ() + 0.5 - camera.z) >= 64.0;
        if (profile) {
            if (outside)
                ++rejectedFar;
            else
                ++nearCube;
        }
        return outside;
    }

    enum Kind { BOUNDED, ABSENT, UNKNOWN }
    Kind kind(BlockEntity entity) {
        if (!supported)
            return Kind.UNKNOWN;
        Class<?> entityClass = entity.getClass();
        Boolean pure = entityClasses.get(entityClass);
        if (pure == null) {
            pure = standardEntity(entityClass);
            entityClasses.put(entityClass, pure);
        }
        if (!pure)
            return Kind.UNKNOWN;
        BlockEntityType<?> type = entity.getType();
        Kind kind = types.get(type);
        if (kind == null) {
            if (type.getClass() != BlockEntityType.class)
                kind = Kind.UNKNOWN;
            else {
                var renderer = dispatcher.getRenderer(entity);
                kind = renderer == null                        ? Kind.ABSENT
                       : standardRenderer(renderer.getClass()) ? Kind.BOUNDED
                                                               : Kind.UNKNOWN;
            }
            types.put(type, kind);
        }
        return kind;
    }

    private boolean fallback() {
        if (profile)
            ++unknownFallback;
        return false;
    }
    public void recordTryExtract(boolean global) {
        if (profile) {
            if (global)
                ++globalExtractions;
            else
                ++localExtractions;
        }
    }
    public void finishFrame() {
        if (!profile || ++frames < 120)
            return;
        PrimeClient.LOGGER.info(
                "BE candidate batch frames={} inspected={} rejectedFar={} unknownFallback={} nearCube={} localTryExtract={} globalTryExtract={}",
                frames, inspected, rejectedFar, unknownFallback, nearCube, localExtractions,
                globalExtractions);
        resetProfile();
    }
    private void resetProfile() {
        frames = 0;
        inspected = rejectedFar = unknownFallback = nearCube = localExtractions =
                globalExtractions = 0;
    }

    private boolean standardRenderer(Class<?> type) {
        Boolean cached = rendererClasses.get(type);
        if (cached != null)
            return cached;
        boolean eligible = inherited(type, BlockEntityRenderer.class, "shouldRender",
                                     BlockEntity.class, Vec3.class) &&
                           inherited(type, BlockEntityRenderer.class, "getViewDistance") &&
                           inherited(type, BlockEntityRenderer.class, "shouldRenderOffScreen") &&
                           knownHierarchy(type, Object.class);
        rendererClasses.put(type, eligible);
        return eligible;
    }

    private static boolean standardEntity(Class<?> type) {
        for (String method :
             new String[] {"getBlockPos", "getBlockState", "getType", "hasLevel", "isRemoved"})
            if (!inherited(type, BlockEntity.class, method))
                return false;
        return knownHierarchy(type, BlockEntity.class);
    }

    private static boolean inherited(Class<?> type, Class<?> owner, String method,
                                     Class<?>... parameters) {
        try {
            return type.getMethod(method, parameters).getDeclaringClass() == owner;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static boolean knownHierarchy(Class<?> type, Class<?> end) {
        for (Class<?> next = type; next != null && next != end; next = next.getSuperclass())
            if (!known(next))
                return false;
        return true;
    }

    public static boolean known(Class<?> type) {
        for (var method : type.getDeclaredMethods())
            for (var annotation : method.getDeclaredAnnotations()) {
                if (!annotation.annotationType().getName().equals(
                            "org.spongepowered.asm.mixin.transformer.meta.MixinMerged"))
                    continue;
                try {
                    String origin = (String)annotation.annotationType().getMethod("mixin").invoke(
                            annotation);
                    // The pinned Fabric hook copies validBlocks to HashSet in the constructor and adds blocks;
                    // it does not replace or inject callbacks into isValid.
                    if (type == BlockEntityType.class &&
                        origin.equals(
                                "net.fabricmc.fabric.mixin.object.builder.BlockEntityTypeMixin"))
                        continue;
                    if (type == BlockEntityType.class && method.getName().equals("getBlocks") &&
                        origin.equals("net.fabricmc.fabric.mixin.lookup.BlockEntityTypeAccessor"))
                        continue;
                    // Pinned attachment API adds its own data methods and NBT load/save hooks,
                    // without changing the five base getters used before distance rejection.
                    if (type == BlockEntity.class &&
                        (origin.equals(
                                 "net.fabricmc.fabric.mixin.attachment.AttachmentTargetsMixin") ||
                         origin.equals("net.fabricmc.fabric.mixin.attachment.BlockEntityMixin")))
                        continue;
                    if (type == BlockEntityRenderDispatcher.class &&
                        method.getName().equals("fabric$getId") &&
                        origin.equals(
                                "net.fabricmc.fabric.mixin.resource.client.KeyedClientResourceReloadListenerMixin"))
                        continue;
                    // This pinned transfer hook wraps inventory setChanged only, never source getters.
                    if (type == BaseContainerBlockEntity.class &&
                        origin.equals(
                                "net.fabricmc.fabric.mixin.transfer.BaseContainerBlockEntityMixin"))
                        continue;
                    // Pinned attachment hooks manage attachment data; they do not mutate BE membership.
                    if ((type == net.minecraft.world.level.chunk.ChunkAccess.class &&
                         (origin.equals(
                                  "net.fabricmc.fabric.mixin.attachment.AttachmentTargetsMixin") ||
                          origin.equals(
                                  "net.fabricmc.fabric.mixin.attachment.ChunkAccessMixin"))) ||
                        (type == net.minecraft.world.level.chunk.LevelChunk.class &&
                         (origin.equals("net.fabricmc.fabric.mixin.attachment.LevelChunkMixin") ||
                          origin.equals(
                                  "net.fabricmc.fabric.mixin.event.lifecycle.client.LevelChunkMixin"))))
                        continue;
                    // The pinned renderer API wraps only real model/tint emission, before raster sorting.
                    if (type == net.minecraft.client.renderer.chunk.SectionCompiler.class &&
                        origin.equals(
                                "net.fabricmc.fabric.mixin.client.renderer.block.render.SectionCompilerMixin"))
                        continue;
                    // Pinned block-getter hooks copy attachment data and expose biome/attachment getters.
                    // They do not synthesize blocks or compile geometry in an all-air section.
                    if ((type == net.minecraft.client.renderer.chunk.RenderRegionCache.class &&
                         origin.equals(
                                 "net.fabricmc.fabric.mixin.blockgetter.client.RenderRegionCacheMixin")) ||
                        (type == net.minecraft.client.renderer.chunk.RenderSectionRegion.class &&
                         origin.equals(
                                 "net.fabricmc.fabric.mixin.blockgetter.client.RenderSectionRegionMixin")))
                        continue;
                    // Pinned block API preserves modded air states on writes; hasOnlyAir is unchanged.
                    if (type == net.minecraft.world.level.chunk.LevelChunkSection.class &&
                        origin.equals("net.fabricmc.fabric.mixin.block.LevelChunkSectionMixin"))
                        continue;
                    if (!origin.startsWith("dev.primept.mixin."))
                        return false;
                } catch (ReflectiveOperationException exception) {
                    return false;
                }
            }
        return true;
    }
}
