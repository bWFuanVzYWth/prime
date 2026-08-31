package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.mixin.accessor.SpriteContentsAccessor;
import dev.prime.mixin.accessor.TextureAtlasAccessor;
import dev.prime.mixin.accessor.TextureAtlasSpriteAccessor;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.scene.TextureIdRegistry;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.terrain.LabPbrEmissionMap;
import dev.prime.render.terrain.LabPbrHeightMap;
import dev.prime.render.terrain.LabPbrMaterialMap;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Captures Minecraft's stitched LabPBR sources into a Vulkan-independent immutable frame. */
public final class VanillaLabPbrAtlas {
    private static final Identifier FORMAT_RESOURCE = Identifier.withDefaultNamespace(
            "optifine/texture.properties");
    private static final String SUPPORTED_FORMAT = "lab-pbr/1.3";

    private final AtomicLong requestedGeneration = new AtomicLong();
    private final TextureIdRegistry textureIds = new TextureIdRegistry();
    private TextureAtlas capturedAtlas;
    private long capturedGeneration = -1L;
    private LabPbrAtlasFrame.Snapshot snapshot;
    private List<SpriteContents.AnimationState> animations = List.of();

    public static boolean hasStitchedSprites(TextureAtlas atlas) {
        return !((TextureAtlasAccessor) (Object) atlas).prime$texturesByName().isEmpty();
    }

    public LabPbrAtlasFrame ensure(Minecraft minecraft, TextureAtlas atlas) {
        long generation = this.requestedGeneration.get();
        if (this.snapshot == null
                || this.capturedAtlas != atlas
                || this.capturedGeneration != generation) {
            Capture capture = this.capture(minecraft.getResourceManager(), atlas);
            this.capturedAtlas = atlas;
            this.capturedGeneration = generation;
            this.snapshot = capture.snapshot;
            this.animations = capture.animations;
        }
        ArrayList<LabPbrAtlasFrame.AnimationSample> samples =
                new ArrayList<>(this.animations.size());
        for (SpriteContents.AnimationState animation : this.animations) {
            samples.add(sample(animation));
        }
        return new LabPbrAtlasFrame(generation, this.snapshot, samples);
    }

    public void requestReload() {
        this.requestedGeneration.incrementAndGet();
    }

    /** Drops the one-shot base RGBA8 capture after canonical pages and animation caches own it. */
    public void retireBaseColorSources(long generation) {
        if (this.snapshot != null && this.capturedGeneration == generation) {
            this.snapshot = this.snapshot.withoutBaseColorSources();
        }
    }

    private Capture capture(ResourceManager resourceManager, TextureAtlas atlas) {
        TextureAtlasAccessor atlasAccess = (TextureAtlasAccessor) (Object) atlas;
        boolean supported = readsLabPbr13(resourceManager);
        Set<SpriteId> normalSprites = new HashSet<>();
        Set<SpriteId> specularSprites = new HashSet<>();
        Map<SpriteId, LabPbrEmissionMap> emissionMaps = new HashMap<>();
        Map<SpriteId, LabPbrHeightMap> heightMaps = new HashMap<>();
        Map<SpriteId, LabPbrMaterialMap> materialMaps = new HashMap<>();
        Map<SpriteId, Integer> textureIds = new HashMap<>();
        ArrayList<MaterialDraft> drafts = new ArrayList<>();
        ArrayList<TextureAtlasSprite> atlasSprites =
                new ArrayList<>(atlasAccess.prime$texturesByName().values());
        atlasSprites.sort(Comparator.comparing(
                sprite -> sprite.contents().name().toString()));
        for (TextureAtlasSprite sprite : atlasSprites) {
            Identifier name = sprite.contents().name();
            SpriteId spriteId = spriteId(name);
            int textureId = this.textureIds.resolve(spriteId);
            textureIds.put(spriteId, textureId);
            SpriteContents contents = sprite.contents();
            NativeImage baseImage =
                    ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
            if (baseImage == null || baseImage.isClosed()) {
                throw new IllegalStateException(
                        "Stitched base-color source is unavailable for " + name);
            }
            LabPbrAtlasFrame.ColorSource baseColor = LabPbrAtlasFrame.ColorSource.capture(
                    baseImage.getWidth(),
                    baseImage.getHeight(),
                    contents.width(),
                    contents.height(),
                    baseImage::getPixel);
            LabPbrAtlasFrame.MaterialSource normal = supported
                    ? readMaterial(resourceManager, materialResource(name, "_n"), sprite)
                    : null;
            LabPbrAtlasFrame.MaterialSource specular = supported
                    ? readMaterial(resourceManager, materialResource(name, "_s"), sprite)
                    : null;
            if (supported) {
                if (normal != null) {
                    normalSprites.add(spriteId);
                    heightMaps.put(spriteId, LabPbrHeightMap.fromNormal(
                            normal.pixels(),
                            normal.width(),
                            normal.height(),
                            normal.frameWidth(),
                            normal.frameHeight(),
                            normal.columns(),
                            normal.frameCount()));
                }
                if (specular != null) {
                    specularSprites.add(spriteId);
                    LabPbrEmissionMap emission = LabPbrEmissionMap.fromSpecular(
                            specular.pixels(),
                            specular.width(),
                            specular.height(),
                            specular.frameWidth(),
                            specular.frameHeight(),
                            specular.columns(),
                            specular.frameCount());
                    if (emission != null) {
                        emissionMaps.put(spriteId, emission);
                    }
                }
                if ((normal != null || specular != null) && !contents.isAnimated()) {
                    materialMaps.put(spriteId, new LabPbrMaterialMap(
                            materialPixels(normal, contents.width(), contents.height(), false),
                            materialPixels(specular, contents.width(), contents.height(), true)));
                }
            }
            drafts.add(new MaterialDraft(textureId, sprite, baseColor, normal, specular));
        }

        Map<Identifier, SpriteContents.AnimationState> stateByName =
                animationStatesByName(atlasAccess);
        ArrayList<SpriteContents.AnimationState> animations = new ArrayList<>();
        IdentityHashMap<SpriteContents.AnimationState, Integer> animationIndices =
                new IdentityHashMap<>();
        for (MaterialDraft draft : drafts) {
            SpriteContents.AnimationState state =
                    stateByName.get(draft.sprite.contents().name());
            if (state != null && state.animationInfo.frames.size() > 1
                    && !animationIndices.containsKey(state)) {
                animationIndices.put(state, animations.size());
                animations.add(state);
            }
        }
        ArrayList<LabPbrAtlasFrame.Sprite> sprites = new ArrayList<>(drafts.size());
        for (MaterialDraft draft : drafts) {
            TextureAtlasSprite sprite = draft.sprite;
            SpriteContents contents = sprite.contents();
            SpriteContents.AnimationState state = stateByName.get(contents.name());
            sprites.add(new LabPbrAtlasFrame.Sprite(
                    draft.textureId,
                    sprite.getX(),
                    sprite.getY(),
                    contents.width(),
                    contents.height(),
                    ((TextureAtlasSpriteAccessor) (Object) sprite).prime$padding(),
                    draft.baseColor,
                    draft.normal,
                    draft.specular,
                    state == null ? -1 : animationIndices.getOrDefault(state, -1)));
        }

        int width = drafts.isEmpty() ? 1 : atlasAccess.prime$width();
        int height = drafts.isEmpty() ? 1 : atlasAccess.prime$height();
        int mipLevels = drafts.isEmpty() ? 1 : Math.max(1, atlasAccess.prime$maxMipLevel() + 1);
        LabPbrMaterialSet materials = new LabPbrMaterialSet(
                textureIds,
                normalSprites,
                specularSprites,
                emissionMaps,
                heightMaps,
                materialMaps);
        PrimeInfo.LOGGER.info(
                "Loaded LabPBR 1.3 material sources: {} normal maps, {} specular maps, {} emissive maps, {} animated sprites",
                normalSprites.size(), specularSprites.size(), emissionMaps.size(), animations.size());
        return new Capture(
                new LabPbrAtlasFrame.Snapshot(width, height, mipLevels, materials, sprites),
                animations);
    }

    private static Map<Identifier, SpriteContents.AnimationState> animationStatesByName(
            TextureAtlasAccessor atlas) {
        Map<Identifier, SpriteContents.AnimationState> result = new HashMap<>();
        List<SpriteContents.AnimationState> states = atlas.prime$animatedTextureStates();
        int stateIndex = 0;
        for (TextureAtlasSprite sprite : atlas.prime$sprites()) {
            if (!sprite.contents().isAnimated()) {
                continue;
            }
            if (stateIndex >= states.size()) {
                break;
            }
            result.put(sprite.contents().name(), states.get(stateIndex++));
        }
        return result;
    }

    private static boolean readsLabPbr13(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(FORMAT_RESOURCE);
        if (resource.isEmpty()) {
            return false;
        }
        Properties properties = new Properties();
        try (InputStream input = resource.orElseThrow().open()) {
            properties.load(input);
        } catch (IOException exception) {
            PrimeInfo.LOGGER.warn("Unable to read LabPBR format declaration", exception);
            return false;
        }
        String format = properties.getProperty("format", "").trim();
        if (!SUPPORTED_FORMAT.equalsIgnoreCase(format)) {
            PrimeInfo.LOGGER.warn(
                    "Ignoring unsupported material format '{}'; Prime currently requires {}",
                    format,
                    SUPPORTED_FORMAT);
            return false;
        }
        return true;
    }

    private static Identifier materialResource(Identifier sprite, String suffix) {
        return Identifier.fromNamespaceAndPath(
                sprite.getNamespace(), "textures/" + sprite.getPath() + suffix + ".png");
    }

    private static SpriteId spriteId(Identifier identifier) {
        return new SpriteId(identifier.getNamespace(), identifier.getPath());
    }

    private static LabPbrMaterialMap.Pixels materialPixels(
            LabPbrAtlasFrame.MaterialSource source,
            int baseFrameWidth,
            int baseFrameHeight,
            boolean specular) {
        if (source == null) {
            return null;
        }
        int width = Math.multiplyExact(source.columns(), baseFrameWidth);
        int rows = Math.floorDiv(
                Math.addExact(source.frameCount(), source.columns() - 1),
                source.columns());
        int[] normalized = new int[Math.multiplyExact(
                width, Math.multiplyExact(rows, baseFrameHeight))];
        for (int frame = 0; frame < source.frameCount(); frame++) {
            LabPbrAtlasFrame.AnimationSample sample =
                    new LabPbrAtlasFrame.AnimationSample(frame, frame, 0);
            int frameX = frame % source.columns() * baseFrameWidth;
            int frameY = frame / source.columns() * baseFrameHeight;
            for (int y = 0; y < baseFrameHeight; y++) {
                for (int x = 0; x < baseFrameWidth; x++) {
                    normalized[(frameY + y) * width + frameX + x] = source.filtered(
                            sample,
                            x,
                            y,
                            x + 1.0,
                            y + 1.0,
                            baseFrameWidth,
                            baseFrameHeight,
                            specular);
                }
            }
        }
        return new LabPbrMaterialMap.Pixels(
                normalized,
                width,
                baseFrameWidth,
                baseFrameHeight,
                source.columns(),
                source.frameCount());
    }

    private static LabPbrAtlasFrame.MaterialSource readMaterial(
            ResourceManager manager,
            Identifier resourceId,
            TextureAtlasSprite baseSprite) {
        Optional<Resource> resource = manager.getResource(resourceId);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream input = resource.orElseThrow().open();
                NativeImage image = NativeImage.read(input)) {
            SpriteContents contents = baseSprite.contents();
            NativeImage baseImage =
                    ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
            return LabPbrAtlasFrame.MaterialSource.create(
                    image.getPixels(),
                    image.getWidth(),
                    image.getHeight(),
                    contents.width(),
                    contents.height(),
                    baseImage.getWidth(),
                    baseImage.getHeight());
        } catch (IOException | RuntimeException exception) {
            PrimeInfo.LOGGER.warn("Unable to read LabPBR material {}", resourceId, exception);
            return null;
        }
    }

    private static LabPbrAtlasFrame.AnimationSample sample(
            SpriteContents.AnimationState state) {
        List<SpriteContents.FrameInfo> frames = state.animationInfo.frames;
        if (frames.isEmpty()) {
            return LabPbrAtlasFrame.AnimationSample.ZERO;
        }
        int sequenceIndex = Math.max(0, Math.min(state.frame, frames.size() - 1));
        SpriteContents.FrameInfo frame = frames.get(sequenceIndex);
        if (!state.animationInfo.interpolateFrames) {
            return new LabPbrAtlasFrame.AnimationSample(frame.index(), frame.index(), 0);
        }
        SpriteContents.FrameInfo nextFrame = frames.get((sequenceIndex + 1) % frames.size());
        int frameTime = Math.max(1, frame.time());
        int progress = Math.min(999, (int) ((long) state.subFrame * 1000L / frameTime));
        return new LabPbrAtlasFrame.AnimationSample(frame.index(), nextFrame.index(), progress);
    }

    private record MaterialDraft(
            int textureId,
            TextureAtlasSprite sprite,
            LabPbrAtlasFrame.ColorSource baseColor,
            LabPbrAtlasFrame.MaterialSource normal,
            LabPbrAtlasFrame.MaterialSource specular) {
    }

    private record Capture(
            LabPbrAtlasFrame.Snapshot snapshot,
            List<SpriteContents.AnimationState> animations) {
    }
}
