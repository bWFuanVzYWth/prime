package dev.primept.capture;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.renderer.texture.TextureManager;

/** Non-owning source provenance outlives renderer switches; no pixel copies are retained here. */
public final class CanonicalTextureSources {
    public enum Kind { SKIN, MAP, MISSING }
    private record Source(WeakReference<DynamicTexture> owner, Kind kind) {}
    private static final IdentityHashMap<GpuTexture, Source> SOURCES = new IdentityHashMap<>();
    private static final ThreadLocal<DynamicTexture> MAP_READ = new ThreadLocal<>();
    private CanonicalTextureSources() {}

    public static DynamicTexture beginMapRead(DynamicTexture owner) {
        DynamicTexture previous = MAP_READ.get();
        MAP_READ.set(owner);
        return previous;
    }
    public static void endMapRead(DynamicTexture previous) {
        if (previous == null)
            MAP_READ.remove();
        else
            MAP_READ.set(previous);
    }
    public static boolean isMapRead(DynamicTexture owner) {
        return MAP_READ.get() == owner;
    }

    /** Called only by the actual known creator/update path, after a successful real upload. */
    public static void register(DynamicTexture owner, Kind kind) {
        if (owner.getClass() != DynamicTexture.class || !Capabilities.supported(kind))
            return;
        var source = (CanonicalDynamicTexture)owner;
        if (!source.primept$hasUploadedPixels() || source.primept$pixelsExposed())
            return;
        GpuTexture texture = owner.getTexture();
        if (texture.isClosed())
            return;
        Source previous = SOURCES.get(texture);
        if (previous == null || previous.owner.get() != owner || previous.kind != kind)
            SOURCES.put(texture, new Source(new WeakReference<>(owner), kind));
    }

    static NativeImage pixels(GpuTexture texture) {
        Source source = SOURCES.get(texture);
        if (source == null || texture.isClosed())
            return null;
        DynamicTexture owner = source.owner.get();
        if (owner == null) {
            SOURCES.remove(texture);
            return null;
        }
        var access = (CanonicalDynamicTexture)owner;
        if (access.primept$pixelsExposed() || !access.primept$hasUploadedPixels() ||
            owner.getTexture() != texture)
            return null;
        NativeImage image = access.primept$peekPixels();
        return image == null || image.isClosed() ? null : image;
    }
    static String kind(GpuTexture texture) {
        Source source = SOURCES.get(texture);
        if (source == null)
            return "unknown";
        DynamicTexture owner = source.owner.get();
        return source.kind + (owner == null ? "/released"
                              : ((CanonicalDynamicTexture)owner).primept$pixelsExposed()
                                      ? "/mutable-alias-exposed"
                                      : "");
    }
    public static void release(GpuTexture texture) {
        SOURCES.remove(texture);
    }

    private static final class Capabilities {
        private static final boolean IMAGE =
                known(DynamicTexture.class) && known(NativeImage.class);
        private static final boolean SKIN = IMAGE && known(SkinTextureDownloader.class);
        private static final boolean MISSING = IMAGE && known(TextureManager.class);
        private static final boolean MAP = IMAGE && knownMap();
        static boolean supported(Kind kind) {
            return switch (kind) {
                case SKIN -> SKIN;
                case MAP -> MAP;
                case MISSING -> MISSING;
            };
        }
        private static boolean knownMap() {
            try {
                return known(Class.forName(
                        "net.minecraft.client.resources.MapTextureManager$MapInstance", false,
                        CanonicalTextureSources.class.getClassLoader()));
            } catch (ClassNotFoundException exception) {
                return false;
            }
        }
        private static boolean known(Class<?> type) {
            for (var method : type.getDeclaredMethods())
                for (var annotation : method.getDeclaredAnnotations()) {
                    if (!annotation.annotationType().getName().equals(
                                "org.spongepowered.asm.mixin.transformer.meta.MixinMerged"))
                        continue;
                    try {
                        String origin =
                                (String)annotation.annotationType().getMethod("mixin").invoke(
                                        annotation);
                        // Fabric's pinned resource-loader version adds only this unrelated reload identifier.
                        if (type == TextureManager.class &&
                            method.getName().equals("fabric$getId") &&
                            origin.equals(
                                    "net.fabricmc.fabric.mixin.resource.client.KeyedClientResourceReloadListenerMixin"))
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
}
