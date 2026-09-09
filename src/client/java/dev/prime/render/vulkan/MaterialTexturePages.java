// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * Owns translated material pages and the logical-texture indirection table.
 *
 * <p>Normal pages store a normalized tangent-space direction in RG, LabPBR AO in B, and the
 * equivalent GGX perceptual roughness of the filtered normal distribution in A. Source height
 * remains CPU-owned by geometric displacement. Optical pages preserve their source semantics.
 * A missing map is represented by immutable availability bits in terrain primitives, not by an
 * ambiguous texel sentinel. Animated maps follow the base sprite's real source-frame sequence; a
 * single-frame auxiliary map is intentionally reused for every frame.
 */
public final class MaterialTexturePages implements AutoCloseable {
    private static final int BASE_COLOR_BYTES_PER_PIXEL = 8;
    private static final int AUXILIARY_BYTES_PER_PIXEL = 4;
    private static final int NORMAL_DEFAULT_ARGB = 0x008080ff;
    private static final int OPTICAL_DEFAULT_ARGB = 0xff000400;
    private enum Channel {
        BASE_COLOR(
                BASE_COLOR_BYTES_PER_PIXEL,
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                "canonical base-color",
                0),
        NORMAL(
                AUXILIARY_BYTES_PER_PIXEL,
                VK12.VK_FORMAT_R8G8B8A8_UNORM,
                "material normal",
                NORMAL_DEFAULT_ARGB),
        OPTICAL(
                AUXILIARY_BYTES_PER_PIXEL,
                VK12.VK_FORMAT_R8G8B8A8_UNORM,
                "material optical",
                OPTICAL_DEFAULT_ARGB);

        final int bytesPerPixel;
        final int format;
        final String label;
        final int defaultArgb;

        Channel(int bytesPerPixel, int format, String label, int defaultArgb) {
            this.bytesPerPixel = bytesPerPixel;
            this.format = format;
            this.label = label;
            this.defaultArgb = defaultArgb;
        }

        TexturePageLayout.Layout pack(LabPbrAtlasFrame.Snapshot source) {
            return switch (this) {
                case BASE_COLOR -> TexturePageLayout.packBaseColor(
                        source.sprites(), source.mipLevels());
                case NORMAL -> TexturePageLayout.pack(
                        source.sprites(), LabPbrAtlasFrame.Sprite::normal, source.mipLevels());
                case OPTICAL -> TexturePageLayout.pack(
                        source.sprites(), LabPbrAtlasFrame.Sprite::specular, source.mipLevels());
            };
        }

        LabPbrAtlasFrame.MaterialSource material(LabPbrAtlasFrame.Sprite sprite) {
            return switch (this) {
                case NORMAL -> sprite.normal();
                case OPTICAL -> sprite.specular();
                case BASE_COLOR -> null;
            };
        }
    }
    private static final Channel[] CHANNELS = Channel.values();

    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final ArrayList<AnimationUpdate> animationUpdates = new ArrayList<>();
    private final ArrayList<Copy> animationCopies = new ArrayList<>();
    private final ArrayList<VulkanImage> changedImages = new ArrayList<>();
    private List<LabPbrAtlasFrame.AnimationSample> animationSamples = List.of();
    private Resources resources;
    private FrameToken pending;
    private boolean closed;

    public MaterialTexturePages(VulkanContext context, StagingArena stagingArena) {
        this.context = context;
        this.stagingArena = stagingArena;
    }

    public LabPbrMaterialSet ensure(
            LabPbrAtlasFrame frame, long vanillaAtlasView) {
        if (this.closed) {
            throw new IllegalStateException("Material texture pages are closed");
        }
        long generation = frame.sourceGeneration();
        this.animationSamples = frame.animations();
        if (this.resources != null
                && this.resources.sourceGeneration == generation
                && this.resources.vanillaAtlasView == vanillaAtlasView) {
            return this.resources.materials;
        }
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Cannot replace material texture pages with an outstanding upload");
        }
        Resources replacement = build(frame.snapshot(), vanillaAtlasView, generation);
        Resources previous = this.resources;
        this.resources = replacement;
        this.animationUpdates.clear();
        this.animationCopies.clear();
        if (previous != null) {
            if (previous.prepared) {
                this.context.defer(previous);
            } else {
                previous.destroy();
            }
        }
        return replacement.materials;
    }

    /** Source-pack generation represented by the current translated page set. */
    public long sourceGeneration() {
        return requireResources().sourceGeneration;
    }

    public Binding binding() {
        return requireResources().binding;
    }

    /** Records the complete generation upload before it can be consumed by a frame. */
    public FrameToken prepareInitial(VkCommandBuffer commandBuffer) {
        if (requireResources().prepared) {
            return null;
        }
        return this.prepare(commandBuffer, true);
    }

    /** Records only real animation changes; static generation work is forbidden in frame use. */
    public FrameToken prepareAnimations(VkCommandBuffer commandBuffer) {
        if (!requireResources().prepared) {
            throw new IllegalStateException(
                    "Material texture generation was not uploaded during bootstrap");
        }
        return this.prepare(commandBuffer, false);
    }

    private FrameToken prepare(VkCommandBuffer commandBuffer, boolean initialUpload) {
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Previous LabPBR upload has not been submitted or abandoned");
        }
        Resources current = requireResources();
        this.animationCopies.clear();
        this.changedImages.clear();
        if (initialUpload) {
            recordInitialUpload(commandBuffer, current);
        }
        current.collectAnimationChanges(this.animationSamples, this.animationUpdates);
        if (this.animationUpdates.isEmpty()) {
            return this.publish(null, current, initialUpload, 0);
        }
        long requiredCapacity = 0L;
        for (AnimationUpdate update : this.animationUpdates) {
            requiredCapacity = Math.max(
                    requiredCapacity,
                    animationEndOffset(0L, update.owner));
        }
        StagingArena.Batch batch = this.stagingArena.tryBeginBatch(requiredCapacity);
        if (batch == null) {
            return this.publish(null, current, initialUpload, 0);
        }
        try {
            long budget = 0L;
            int acceptedCount = 0;
            for (int index = 0; index < this.animationUpdates.size(); index++) {
                AnimationUpdate change = this.animationUpdates.get(index);
                long spriteBudget = animationEndOffset(budget, change.owner);
                if (spriteBudget > batch.capacity()) {
                    continue;
                }
                for (Channel channel : CHANNELS) {
                    TextureAnimationFrames frames = change.owner.frames(channel);
                    if (frames != null) {
                        TexturePageLayout.Placement placement = frames.placement();
                        PageResource page = current.pages(channel).get(placement.page());
                        for (int mip = 0; mip < frames.mipLevels(); mip++) {
                            addAnimatedCopy(
                                    this.animationCopies,
                                    batch,
                                    page.image,
                                    frames,
                                    change.sample,
                                    mip,
                                    channel.bytesPerPixel);
                        }
                    }
                }
                budget = spriteBudget;
                this.animationUpdates.set(acceptedCount++, change);
            }
            if (this.animationCopies.isEmpty()) {
                batch.close();
                return this.publish(null, current, initialUpload, 0);
            }
            for (Copy copy : this.animationCopies) {
                if (!this.changedImages.contains(copy.image)) {
                    this.changedImages.add(copy.image);
                }
            }
            transitionImages(
                    commandBuffer,
                    this.changedImages,
                    current.prepared || initialUpload,
                    true);
            for (Copy copy : this.animationCopies) {
                recordCopy(commandBuffer, copy);
            }
            transitionImages(
                    commandBuffer,
                    this.changedImages,
                    true,
                    false);
            return this.publish(
                    batch, current, initialUpload, acceptedCount);
        } catch (RuntimeException exception) {
            throw ResourceCleanup.close(batch, exception);
        }
    }

    /** Must be called immediately after the command buffer containing the token is submitted. */
    public void submitted(FrameToken token) {
        if (token == null) {
            return;
        }
        if (token.pages != this) {
            throw new IllegalArgumentException("LabPBR frame token does not belong to this submission");
        }
        this.complete(token, "LabPBR frame token does not belong to this submission");
        if (token.initialUpload) {
            token.owner.prepared = true;
            token.owner.markImagesInitialized();
        }
        for (int index = 0; index < token.animationUpdateCount; index++) {
            AnimationUpdate update = this.animationUpdates.get(index);
            update.owner.lastSample = update.sample;
        }
        RuntimeException failure = null;
        if (token.batch != null) {
            failure = ResourceCleanup.run(token.batch::submitted, null);
            failure = ResourceCleanup.close(token.batch, failure);
        }
        if (token.initialUpload) {
            failure = ResourceCleanup.run(
                    () -> token.owner.retireUploads(this.context), failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    /** Releases staging for a recorded upload whose command buffer was not submitted. */
    public void abandon(FrameToken token) {
        if (token == null) {
            return;
        }
        if (token.pages != this) {
            throw new IllegalArgumentException(
                    "Material frame token does not belong to these texture pages");
        }
        this.complete(token, "Material frame token does not belong to these texture pages");
        ResourceCleanup.throwIfFailed(ResourceCleanup.close(token.batch, null));
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            RuntimeException failure = null;
            FrameToken abandoned = this.pending;
            this.pending = null;
            if (abandoned != null) {
                failure = ResourceCleanup.close(abandoned.batch, failure);
            }
            if (this.resources != null) {
                failure = ResourceCleanup.destroy(this.resources, failure);
                this.resources = null;
            }
            ResourceCleanup.throwIfFailed(failure);
        }
    }

    private FrameToken publish(
            StagingArena.Batch batch,
            Resources owner,
            boolean initialUpload,
            int animationUpdateCount) {
        if (!initialUpload && batch == null) {
            return null;
        }
        if (batch != null) {
            batch.prepareForSubmission();
        }
        FrameToken token = new FrameToken(
                this,
                batch,
                owner,
                initialUpload,
                animationUpdateCount);
        this.pending = token;
        return token;
    }

    private void complete(FrameToken token, String mismatchMessage) {
        if (token == null || token != this.pending) {
            throw new IllegalArgumentException(mismatchMessage);
        }
        this.pending = null;
    }

    private Resources requireResources() {
        if (this.resources == null) {
            throw new IllegalStateException(
                    "Material texture pages were not synchronized with Minecraft");
        }
        return this.resources;
    }

    private Resources build(
            LabPbrAtlasFrame.Snapshot source,
            long vanillaAtlasView,
            long sourceGeneration) {
        for (LabPbrAtlasFrame.Sprite sprite : source.sprites()) {
            if (sprite.baseColor() == null) {
                throw new IllegalStateException(
                        "Canonical base-color source was retired before page construction");
            }
        }
        ArrayList<TexturePageLayout.Layout> layouts = new ArrayList<>(CHANNELS.length);
        ArrayList<List<PageResource>> pages = new ArrayList<>(CHANNELS.length);
        VulkanBuffer textureRecords = null;
        Resources resources = null;
        try {
            for (Channel channel : CHANNELS) {
                TexturePageLayout.Layout layout = channel.pack(source);
                layouts.add(layout);
                pages.add(this.buildPages(source, layout, channel));
            }
            List<TexturePageLayout.Layout> builtLayouts = List.copyOf(layouts);
            List<List<PageResource>> builtPages = List.copyOf(pages);
            textureRecords = this.buildTextureRecords(source, builtLayouts, builtPages);
            resources = new Resources(
                    sourceGeneration,
                    vanillaAtlasView,
                    builtPages,
                    textureRecords,
                    source.materials(),
                    source,
                    builtLayouts);
            List<PageResource> baseColorPages = resources.pages(Channel.BASE_COLOR);
            List<PageResource> normalPages = resources.pages(Channel.NORMAL);
            List<PageResource> opticalPages = resources.pages(Channel.OPTICAL);
            PrimeInfo.LOGGER.info(
                    "Translated material storage: {} textures, base={} pages/{} bytes, normal={} pages/{} bytes, optical={} pages/{} bytes, records={} bytes, animation cache={} bytes",
                    source.sprites().size(),
                    baseColorPages.size(),
                    pageBytes(baseColorPages),
                    normalPages.size(),
                    pageBytes(normalPages),
                    opticalPages.size(),
                    pageBytes(opticalPages),
                    textureRecords.size(),
                    resources.animationFrameBytes());
            return resources;
        } catch (RuntimeException exception) {
            if (resources != null) {
                throw ResourceCleanup.destroy(resources, exception);
            }
            RuntimeException failure = ResourceCleanup.destroy(textureRecords, exception);
            for (int index = pages.size() - 1; index >= 0; index--) {
                failure = destroyPages(pages.get(index), failure);
            }
            throw failure;
        }
    }

    private List<PageResource> buildPages(
            LabPbrAtlasFrame.Snapshot source,
            TexturePageLayout.Layout layout,
            Channel channel) {
        ArrayList<PageResource> pages = new ArrayList<>(layout.pages().size());
        try {
            int usage = VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            for (int pageIndex = 0; pageIndex < layout.pages().size(); pageIndex++) {
                int width = layout.pages().get(pageIndex).width();
                int height = layout.pages().get(pageIndex).height();
                int mipLevels = Math.min(
                        source.mipLevels(),
                        32 - Integer.numberOfLeadingZeros(Math.max(width, height)));
                VulkanImage image = this.context.createMipmappedImage2D(
                        width,
                        height,
                        mipLevels,
                        channel.format,
                        usage,
                        "Prime " + channel.label + " page " + pageIndex);
                VulkanBuffer upload = null;
                try {
                    long byteSize = totalMipBytes(
                            width, height, mipLevels, channel.bytesPerPixel);
                    upload = this.context.createBuffer(
                            byteSize,
                            VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            true,
                            "Prime " + channel.label + " page upload " + pageIndex);
                    fillPage(
                            upload,
                            width,
                            height,
                            mipLevels,
                            pageIndex,
                            source.sprites(),
                            layout,
                            channel);
                    pages.add(new PageResource(
                            image, upload, channel.bytesPerPixel));
                } catch (RuntimeException exception) {
                    RuntimeException failure = ResourceCleanup.destroy(upload, exception);
                    throw ResourceCleanup.destroy(image, failure);
                }
            }
            return List.copyOf(pages);
        } catch (RuntimeException exception) {
            throw destroyPages(pages, exception);
        }
    }

    private VulkanBuffer buildTextureRecords(
            LabPbrAtlasFrame.Snapshot source,
            List<TexturePageLayout.Layout> layouts,
            List<List<PageResource>> pages) {
        TexturePageLayout.Layout baseColorLayout = layouts.get(Channel.BASE_COLOR.ordinal());
        TexturePageLayout.Layout normalLayout = layouts.get(Channel.NORMAL.ordinal());
        TexturePageLayout.Layout opticalLayout = layouts.get(Channel.OPTICAL.ordinal());
        List<PageResource> baseColorPages = pages.get(Channel.BASE_COLOR.ordinal());
        List<PageResource> normalPages = pages.get(Channel.NORMAL.ordinal());
        List<PageResource> opticalPages = pages.get(Channel.OPTICAL.ordinal());
        int maximumTextureId = 0;
        for (LabPbrAtlasFrame.Sprite sprite : source.sprites()) {
            maximumTextureId = Math.max(maximumTextureId, sprite.textureId());
        }
        long byteSize = Math.multiplyExact(
                (long) maximumTextureId + 1L, ShaderAbi.TEXTURE_RECORD_SIZE);
        VulkanBuffer result = this.context.createBuffer(
                byteSize,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true,
                "Prime texture records");
        long target = result.mappedAddress();
        MemoryUtil.memSet(target, 0, byteSize);
        for (LabPbrAtlasFrame.Sprite sprite : source.sprites()) {
            long record = target + (long) sprite.textureId() * ShaderAbi.TEXTURE_RECORD_SIZE;
            TexturePageLayout.Placement baseColor =
                    baseColorLayout.placement(sprite.textureId());
            if (baseColor == null) {
                throw new IllegalStateException("Base-color texture has no page placement");
            }
            TexturePageLayout.Placement normal = normalLayout.placement(sprite.textureId());
            TexturePageLayout.Placement specular = opticalLayout.placement(sprite.textureId());
            TexturePageLayout.Page normalPage = normal == null
                    ? null
                    : normalLayout.pages().get(normal.page());
            TexturePageLayout.Page opticalPage = specular == null
                    ? null
                    : opticalLayout.pages().get(specular.page());
            int normalMip = normal == null
                    ? 0
                    : textureMipLimit(sprite, normalPages.get(normal.page()).image);
            int specularMip = specular == null
                    ? 0
                    : textureMipLimit(sprite, opticalPages.get(specular.page()).image);
            int baseColorMip = textureMipLimit(
                    sprite, baseColorPages.get(baseColor.page()).image);
            TexturePageLayout.Page baseColorPage =
                    baseColorLayout.pages().get(baseColor.page());
            writeTextureRecord(
                    record,
                    sprite,
                    baseColor,
                    baseColorPage,
                    normal,
                    normalPage,
                    specular,
                    opticalPage,
                    baseColorMip,
                    normalMip,
                    specularMip);
        }
        result.flush(0L, byteSize);
        return result;
    }

    private static int textureMipLimit(
            LabPbrAtlasFrame.Sprite sprite,
            VulkanImage page) {
        return textureMipLevels(sprite, page.mipLevels()) - 1;
    }

    private static int textureMipLevels(
            LabPbrAtlasFrame.Sprite sprite,
            int availableLevels) {
        int logicalLevels = 32 - Integer.numberOfLeadingZeros(
                Math.max(sprite.contentWidth(), sprite.contentHeight()));
        return Math.min(availableLevels, logicalLevels);
    }

    private static void putPackedExtent(long address, int offset, int x, int y) {
        if ((x | y) < 0 || x > 0xffff || y > 0xffff) {
            throw new IllegalStateException("Texture record coordinate exceeds its 16-bit ABI field");
        }
        MemoryUtil.memPutInt(address + offset, x | y << 16);
    }

    private static RuntimeException destroyPages(
            List<PageResource> pages,
            RuntimeException failure) {
        for (int index = pages.size() - 1; index >= 0; index--) {
            failure = ResourceCleanup.destroy(pages.get(index), failure);
        }
        return failure;
    }

    private static long pageBytes(List<PageResource> pages) {
        long result = 0L;
        for (PageResource page : pages) {
            result = Math.addExact(
                    result,
                    totalMipBytes(
                            page.image.width(),
                            page.image.height(),
                            page.image.mipLevels(),
                            page.bytesPerPixel));
        }
        return result;
    }

    static void writeTextureRecord(
            long record,
            LabPbrAtlasFrame.Sprite sprite,
            TexturePageLayout.Placement baseColor,
            TexturePageLayout.Page baseColorPage,
            TexturePageLayout.Placement normal,
            TexturePageLayout.Page normalPage,
            TexturePageLayout.Placement optical,
            TexturePageLayout.Page opticalPage,
            int baseColorMip,
            int normalMip,
            int opticalMip) {
        putPackedExtent(
                record,
                ShaderAbi.TEXTURE_BASE_ORIGIN_OFFSET,
                baseColor.contentX(),
                baseColor.contentY());
        putPackedExtent(
                record,
                ShaderAbi.TEXTURE_FRAME_EXTENT_OFFSET,
                sprite.contentWidth(),
                sprite.contentHeight());
        MemoryUtil.memPutInt(
                record + ShaderAbi.TEXTURE_BASE_INFO_OFFSET,
                baseColorMip
                        | baseColor.page() << ShaderAbi.TEXTURE_BASE_PAGE_SHIFT
                        | pageExtentCode(baseColorPage)
                                << ShaderAbi.TEXTURE_PAGE_EXTENT_CODE_SHIFT);
        putPackedExtent(
                record,
                ShaderAbi.TEXTURE_NORMAL_ORIGIN_OFFSET,
                normal == null ? 0 : normal.contentX(),
                normal == null ? 0 : normal.contentY());
        int normalPageIndex = normal == null ? 0xff : normal.page();
        int opticalPageIndex = optical == null ? 0xff : optical.page();
        MemoryUtil.memPutInt(
                record + ShaderAbi.TEXTURE_AUXILIARY_INFO_OFFSET,
                normalPageIndex
                        | opticalPageIndex << 8
                        | normalMip << 16
                        | opticalMip << 24);
        putPackedExtent(
                record,
                ShaderAbi.TEXTURE_OPTICAL_ORIGIN_OFFSET,
                optical == null ? 0 : optical.contentX(),
                optical == null ? 0 : optical.contentY());
        int normalExtentCode = normalPage == null
                ? ShaderAbi.TEXTURE_PAGE_EXTENT_QUERY_CODE
                : pageExtentCode(normalPage);
        int opticalExtentCode = opticalPage == null
                ? ShaderAbi.TEXTURE_PAGE_EXTENT_QUERY_CODE
                : pageExtentCode(opticalPage);
        MemoryUtil.memPutInt(
                record + ShaderAbi.TEXTURE_AUXILIARY_EXTENT_OFFSET,
                normalExtentCode | opticalExtentCode << 16);
        MemoryUtil.memPutInt(record + 28L, 0);
    }

    private static int pageExtentCode(TexturePageLayout.Page page) {
        int width = page.width();
        int height = page.height();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Texture page extent is not positive");
        }
        int heightCode = height == width
                ? ShaderAbi.TEXTURE_PAGE_HEIGHT_SAME_AS_WIDTH_CODE
                : Integer.numberOfTrailingZeros(height);
        boolean exactHeight = height == width
                || (Integer.bitCount(height) == 1
                        && heightCode < ShaderAbi.TEXTURE_PAGE_HEIGHT_SAME_AS_WIDTH_CODE);
        if (width <= ShaderAbi.TEXTURE_PAGE_WIDTH_MINUS_ONE_MASK + 1 && exactHeight) {
            int code = width - 1 | heightCode << ShaderAbi.TEXTURE_PAGE_HEIGHT_LOG2_SHIFT;
            if (code != ShaderAbi.TEXTURE_PAGE_EXTENT_QUERY_CODE) {
                return code;
            }
        }
        return ShaderAbi.TEXTURE_PAGE_EXTENT_QUERY_CODE;
    }

    private static void fillPage(
            VulkanBuffer upload,
            int width,
            int height,
            int mipLevels,
            int pageIndex,
            List<LabPbrAtlasFrame.Sprite> sprites,
            TexturePageLayout.Layout layout,
            Channel channel) {
        long byteSize = totalMipBytes(
                width, height, mipLevels, channel.bytesPerPixel);
        long target = upload.mappedAddress();
        if (channel == Channel.BASE_COLOR) {
            MemoryUtil.memSet(target, 0, byteSize);
        } else {
            fillArgb(target, byteSize, channel.defaultArgb);
        }
        long mipOffset = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            int mipWidth = Math.max(1, width >> mip);
            int mipHeight = Math.max(1, height >> mip);
            for (LabPbrAtlasFrame.Sprite sprite : sprites) {
                TexturePageLayout.Placement placement = layout.placement(sprite.textureId());
                if (placement != null
                        && placement.page() == pageIndex
                        && mip < textureMipLevels(sprite, mipLevels)) {
                    if (channel == Channel.BASE_COLOR) {
                        writeColorSpriteRgba16f(
                                target,
                                mipOffset,
                                mipWidth,
                                placement,
                                java.util.Objects.requireNonNull(
                                        sprite.baseColor(), "baseColor"),
                                LabPbrAtlasFrame.AnimationSample.ZERO,
                                mip,
                                false);
                    } else {
                        LabPbrAtlasFrame.MaterialSource material = channel.material(sprite);
                        if (material != null) {
                            writeSprite(
                                    target,
                                    mipOffset,
                                    mipWidth,
                                    placement,
                                    material,
                                    LabPbrAtlasFrame.AnimationSample.ZERO,
                                    mip,
                                    false,
                                    channel == Channel.OPTICAL);
                        }
                    }
                }
            }
            mipOffset += (long) mipWidth * mipHeight * channel.bytesPerPixel;
        }
        upload.flush(0L, byteSize);
    }

    static void writeSprite(
            long target,
            long baseOffset,
            int rowWidth,
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.MaterialSource source,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            boolean tightlyPacked,
            boolean specular) {
        LabPbrAtlasFrame.Sprite sprite = placement.sprite();
        int outputWidth = sprite.mipWidth(mip);
        int outputHeight = sprite.mipHeight(mip);
        int destinationX = tightlyPacked ? 0 : placement.mipX(mip);
        int destinationY = tightlyPacked ? 0 : placement.mipY(mip);
        int baseWidth = sprite.contentWidth() + 2 * sprite.padding();
        int baseHeight = sprite.contentHeight() + 2 * sprite.padding();
        for (int y = 0; y < outputHeight; y++) {
            double baseY0 = (double) y * baseHeight / outputHeight - sprite.padding();
            double baseY1 = (double) (y + 1) * baseHeight / outputHeight - sprite.padding();
            for (int x = 0; x < outputWidth; x++) {
                double baseX0 = (double) x * baseWidth / outputWidth - sprite.padding();
                double baseX1 = (double) (x + 1) * baseWidth / outputWidth - sprite.padding();
                int pixel = source.filtered(
                        sample,
                        baseX0,
                        baseY0,
                        baseX1,
                        baseY1,
                        sprite.contentWidth(),
                        sprite.contentHeight(),
                        specular);
                long offset = Math.addExact(
                        baseOffset,
                        Math.multiplyExact(
                                Math.addExact(
                                        Math.multiplyExact((long) destinationY + y, rowWidth),
                                        (long) destinationX + x),
                                4L));
                writeArgb(target, offset, pixel);
            }
        }
    }

    private static void recordInitialUpload(VkCommandBuffer commandBuffer, Resources resources) {
        transitionImages(commandBuffer, resources.allImages(), false, true);
        for (PageResource page : resources.allPages()) {
            long offset = 0L;
            for (int mip = 0; mip < page.image.mipLevels(); mip++) {
                int width = Math.max(1, page.image.width() >> mip);
                int height = Math.max(1, page.image.height() >> mip);
                recordCopy(commandBuffer, new Copy(
                        page.image,
                        page.upload.handle(),
                        offset,
                        mip,
                        0,
                        0,
                        width,
                        height));
                offset += (long) width * height * page.bytesPerPixel;
            }
        }
        transitionImages(commandBuffer, resources.allImages(), true, false);
    }

    private static void addAnimatedCopy(
            List<Copy> copies,
            StagingArena.Batch batch,
            VulkanImage image,
            TextureAnimationFrames frames,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            int bytesPerPixel) {
        TexturePageLayout.Placement placement = frames.placement();
        LabPbrAtlasFrame.Sprite sprite = placement.sprite();
        int width = sprite.mipWidth(mip);
        int height = sprite.mipHeight(mip);
        long byteSize = Math.multiplyExact(
                Math.multiplyExact((long) width, height), bytesPerPixel);
        StagingArena.Slice slice = batch.allocate(byteSize, bytesPerPixel);
        frames.write(slice.mappedAddress(), sample, mip);
        copies.add(new Copy(
                image,
                slice.buffer(),
                slice.offset(),
                mip,
                placement.mipX(mip),
                placement.mipY(mip),
                width,
                height));
    }

    private static void recordCopy(VkCommandBuffer commandBuffer, Copy copy) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(copy.bufferOffset)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            region.get(0).imageSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(copy.mip)
                    .baseArrayLayer(0)
                    .layerCount(1);
            region.get(0).imageOffset().set(copy.x, copy.y, 0);
            region.get(0).imageExtent().set(copy.width, copy.height, 1);
            VK12.vkCmdCopyBufferToImage(
                    commandBuffer,
                    copy.buffer,
                    copy.image.image(),
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region);
        }
    }

    private static void transitionImages(
            VkCommandBuffer commandBuffer,
            List<VulkanImage> images,
            boolean initialized,
            boolean toTransfer) {
        if (images.isEmpty()) {
            return;
        }
        long sourceStage = toTransfer
                ? (initialized
                        ? KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                : VK12.VK_PIPELINE_STAGE_TRANSFER_BIT;
        long sourceAccess = toTransfer && initialized
                ? VK12.VK_ACCESS_SHADER_READ_BIT
                : toTransfer ? 0L : VK12.VK_ACCESS_TRANSFER_WRITE_BIT;
        long destinationStage = toTransfer
                ? VK12.VK_PIPELINE_STAGE_TRANSFER_BIT
                : KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        long destinationAccess = toTransfer
                ? VK12.VK_ACCESS_TRANSFER_WRITE_BIT
                : VK12.VK_ACCESS_SHADER_READ_BIT;
        int oldLayout = toTransfer
                ? (initialized
                        ? VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                        : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                : VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        int newLayout = toTransfer
                ? VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                : VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(images.size(), stack);
            for (int index = 0; index < images.size(); index++) {
                VulkanImage image = images.get(index);
                VulkanSync.setImageBarrier(
                        barriers.get(index),
                        image.image(),
                        oldLayout,
                        newLayout,
                        sourceStage,
                        sourceAccess,
                        destinationStage,
                        destinationAccess,
                        image.mipLevels());
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
    }

    static long totalMipBytes(int width, int height, int mipLevels) {
        return totalMipBytes(width, height, mipLevels, AUXILIARY_BYTES_PER_PIXEL);
    }

    static long totalMipBytes(
            int width, int height, int mipLevels, int bytesPerPixel) {
        if (bytesPerPixel <= 0) {
            throw new IllegalArgumentException("Texture bytes per pixel must be positive");
        }
        long result = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            result = Math.addExact(
                    result,
                    Math.multiplyExact(
                            (long) Math.max(1, width >> mip) * Math.max(1, height >> mip),
                            bytesPerPixel));
        }
        return result;
    }

    static void writeArgb(ByteBuffer target, int offset, int argb) {
        target.put(offset, (byte) (argb >>> 16));
        target.put(offset + 1, (byte) (argb >>> 8));
        target.put(offset + 2, (byte) argb);
        target.put(offset + 3, (byte) (argb >>> 24));
    }

    static void writeArgb(long target, long offset, int argb) {
        MemoryUtil.memPutByte(target + offset, (byte) (argb >>> 16));
        MemoryUtil.memPutByte(target + offset + 1L, (byte) (argb >>> 8));
        MemoryUtil.memPutByte(target + offset + 2L, (byte) argb);
        MemoryUtil.memPutByte(target + offset + 3L, (byte) (argb >>> 24));
    }

    static void writeRgba16f(long target, long offset, long encoded) {
        MemoryUtil.memPutShort(target + offset, (short) encoded);
        MemoryUtil.memPutShort(target + offset + 2L, (short) (encoded >>> 16));
        MemoryUtil.memPutShort(target + offset + 4L, (short) (encoded >>> 32));
        MemoryUtil.memPutShort(target + offset + 6L, (short) (encoded >>> 48));
    }

    private static void fillArgb(long target, long byteSize, int argb) {
        if ((byteSize & 3L) != 0L) {
            throw new IllegalArgumentException("RGBA page byte size must be pixel aligned");
        }
        int patternSize = (int) Math.min(byteSize, 1L << 20);
        ByteBuffer pattern = MemoryUtil.memAlloc(patternSize);
        try {
            for (int offset = 0; offset < patternSize; offset += Integer.BYTES) {
                writeArgb(pattern, offset, argb);
            }
            long source = MemoryUtil.memAddress(pattern);
            for (long offset = 0L; offset < byteSize; offset += patternSize) {
                MemoryUtil.memCopy(
                        source,
                        target + offset,
                        Math.min(patternSize, byteSize - offset));
            }
        } finally {
            MemoryUtil.memFree(pattern);
        }
    }

    private static long animationEndOffset(
            long cursor,
            AnimatedMaterialSprite animation) {
        long result = cursor;
        for (Channel channel : CHANNELS) {
            TextureAnimationFrames frames = animation.frames(channel);
            if (frames != null) {
                for (int mip = 0; mip < frames.mipLevels(); mip++) {
                    long bytes = Math.multiplyExact(
                            Math.multiplyExact(
                                    (long) animation.sprite.mipWidth(mip),
                                    animation.sprite.mipHeight(mip)),
                            channel.bytesPerPixel);
                    result = StagingArena.requiredEndOffset(
                            result, bytes, channel.bytesPerPixel);
                }
            }
        }
        return result;
    }

    public static final class FrameToken {
        private final MaterialTexturePages pages;
        private final StagingArena.Batch batch;
        private final Resources owner;
        private final boolean initialUpload;
        private final int animationUpdateCount;

        private FrameToken(
                MaterialTexturePages pages,
                StagingArena.Batch batch,
                Resources owner,
                boolean initialUpload,
                int animationUpdateCount) {
            this.pages = pages;
            this.batch = batch;
            this.owner = owner;
            this.initialUpload = initialUpload;
            this.animationUpdateCount = animationUpdateCount;
        }
    }

    public record Binding(
            List<VulkanImage> baseColorPages,
            List<VulkanImage> normalPages,
            List<VulkanImage> opticalPages,
            VulkanBuffer textureRecords) {
        public Binding {
            baseColorPages = List.copyOf(baseColorPages);
            normalPages = List.copyOf(normalPages);
            opticalPages = List.copyOf(opticalPages);
            Objects.requireNonNull(textureRecords, "textureRecords");
        }
    }

    private record Copy(
            VulkanImage image,
            long buffer,
            long bufferOffset,
            int mip,
            int x,
            int y,
            int width,
            int height) {
    }

    private record AnimationUpdate(
            LabPbrAtlasFrame.AnimationSample sample,
            AnimatedMaterialSprite owner) {
    }

    private static final class AnimatedMaterialSprite
            implements com.mojang.blaze3d.vulkan.Destroyable {
        private final LabPbrAtlasFrame.Sprite sprite;
        private final TextureAnimationFrames[] frames;
        private final int animationIndex;
        private LabPbrAtlasFrame.AnimationSample lastSample;

        static AnimatedMaterialSprite create(
                LabPbrAtlasFrame.Sprite source,
                TexturePageLayout.Placement baseColor,
                TexturePageLayout.Placement normal,
                TexturePageLayout.Placement specular,
                List<List<PageResource>> pages) {
            if (!source.animated()) {
                return null;
            }
            TextureAnimationFrames colorFrames = null;
            TextureAnimationFrames normalFrames = null;
            TextureAnimationFrames specularFrames = null;
            try {
                if (baseColor != null && source.baseColor().frameCount() > 1) {
                    VulkanImage image = pages.get(Channel.BASE_COLOR.ordinal())
                            .get(baseColor.page()).image;
                    colorFrames = TextureAnimationFrames.color(
                            baseColor,
                            source.baseColor(),
                            textureMipLevels(source, image.mipLevels()));
                }
                if (normal != null && source.normal().frameCount() > 1) {
                    VulkanImage image = pages.get(Channel.NORMAL.ordinal())
                            .get(normal.page()).image;
                    normalFrames = TextureAnimationFrames.material(
                            normal,
                            source.normal(),
                            textureMipLevels(source, image.mipLevels()),
                            false);
                }
                if (specular != null && source.specular().frameCount() > 1) {
                    VulkanImage image = pages.get(Channel.OPTICAL.ordinal())
                            .get(specular.page()).image;
                    specularFrames = TextureAnimationFrames.material(
                            specular,
                            source.specular(),
                            textureMipLevels(source, image.mipLevels()),
                            true);
                }
                if (colorFrames == null && normalFrames == null && specularFrames == null) {
                    return null;
                }
                return new AnimatedMaterialSprite(source, new TextureAnimationFrames[] {
                    colorFrames, normalFrames, specularFrames
                });
            } catch (RuntimeException | Error failure) {
                ResourceCleanup.destroy(specularFrames, null);
                ResourceCleanup.destroy(normalFrames, null);
                ResourceCleanup.destroy(colorFrames, null);
                throw failure;
            }
        }

        private AnimatedMaterialSprite(
                LabPbrAtlasFrame.Sprite source,
                TextureAnimationFrames[] frames) {
            this.sprite = source;
            this.frames = frames;
            this.animationIndex = source.animationIndex();
            this.lastSample = null;
        }

        TextureAnimationFrames frames(Channel channel) {
            return this.frames[channel.ordinal()];
        }

        long frameBytes() {
            long result = 0L;
            for (TextureAnimationFrames channel : this.frames) {
                result += channel == null ? 0L : channel.byteSize();
            }
            return result;
        }

        @Override
        public void destroy() {
            RuntimeException failure = null;
            for (int index = this.frames.length - 1; index >= 0; index--) {
                failure = ResourceCleanup.destroy(this.frames[index], failure);
            }
            ResourceCleanup.throwIfFailed(failure);
        }
    }

    private static final class PageResource implements com.mojang.blaze3d.vulkan.Destroyable {
        private final VulkanImage image;
        private final int bytesPerPixel;
        private VulkanBuffer upload;
        private boolean destroyed;

        private PageResource(VulkanImage image, VulkanBuffer upload, int bytesPerPixel) {
            this.image = image;
            this.upload = upload;
            this.bytesPerPixel = bytesPerPixel;
        }

        private void retireUpload(VulkanContext context) {
            VulkanBuffer retired = this.upload;
            this.upload = null;
            if (retired != null) {
                context.defer(retired);
            }
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                RuntimeException failure = ResourceCleanup.destroy(this.upload, null);
                failure = ResourceCleanup.destroy(this.image, failure);
                ResourceCleanup.throwIfFailed(failure);
            }
        }
    }

    private static final class Resources implements com.mojang.blaze3d.vulkan.Destroyable {
        private final long sourceGeneration;
        private final long vanillaAtlasView;
        private final List<List<PageResource>> pages;
        private final List<PageResource> allPages;
        private final List<VulkanImage> allImages;
        private final VulkanBuffer textureRecords;
        private final Binding binding;
        private final LabPbrMaterialSet materials;
        private final List<AnimatedMaterialSprite> animated;
        private boolean prepared;
        private boolean destroyed;

        Resources(
                long sourceGeneration,
                long vanillaAtlasView,
                List<List<PageResource>> pages,
                VulkanBuffer textureRecords,
                LabPbrMaterialSet materials,
                LabPbrAtlasFrame.Snapshot source,
                List<TexturePageLayout.Layout> layouts) {
            this.sourceGeneration = sourceGeneration;
            this.vanillaAtlasView = vanillaAtlasView;
            this.pages = pages;
            ArrayList<PageResource> allPages = new ArrayList<>();
            for (List<PageResource> channel : this.pages) {
                allPages.addAll(channel);
            }
            this.allPages = List.copyOf(allPages);
            List<List<VulkanImage>> images = this.pages.stream().map(Resources::images).toList();
            this.allImages = images(this.allPages);
            this.textureRecords = textureRecords;
            this.binding = new Binding(
                    images.get(Channel.BASE_COLOR.ordinal()),
                    images.get(Channel.NORMAL.ordinal()),
                    images.get(Channel.OPTICAL.ordinal()),
                    textureRecords);
            this.materials = materials;
            ArrayList<AnimatedMaterialSprite> animated = new ArrayList<>();
            try {
                TexturePageLayout.Layout baseColorLayout =
                        layouts.get(Channel.BASE_COLOR.ordinal());
                TexturePageLayout.Layout normalLayout = layouts.get(Channel.NORMAL.ordinal());
                TexturePageLayout.Layout opticalLayout = layouts.get(Channel.OPTICAL.ordinal());
                for (LabPbrAtlasFrame.Sprite sprite : source.sprites()) {
                    TexturePageLayout.Placement baseColor =
                            baseColorLayout.placement(sprite.textureId());
                    TexturePageLayout.Placement normal =
                            normalLayout.placement(sprite.textureId());
                    TexturePageLayout.Placement specular =
                            opticalLayout.placement(sprite.textureId());
                    AnimatedMaterialSprite animation = AnimatedMaterialSprite.create(
                            sprite, baseColor, normal, specular, this.pages);
                    if (animation != null) {
                        animated.add(animation);
                    }
                }
            } catch (RuntimeException | Error failure) {
                destroyAnimations(animated, null);
                throw failure;
            }
            this.animated = List.copyOf(animated);
        }

        long animationFrameBytes() {
            long result = 0L;
            for (AnimatedMaterialSprite animation : this.animated) {
                result = Math.addExact(result, animation.frameBytes());
            }
            return result;
        }

        List<PageResource> pages(Channel channel) {
            return this.pages.get(channel.ordinal());
        }

        List<PageResource> allPages() {
            return this.allPages;
        }

        List<VulkanImage> allImages() {
            return this.allImages;
        }

        void markImagesInitialized() {
            for (PageResource page : this.allPages()) {
                page.image.markInitialized();
            }
        }

        private static List<VulkanImage> images(List<PageResource> pages) {
            ArrayList<VulkanImage> result = new ArrayList<>(pages.size());
            for (PageResource page : pages) {
                result.add(page.image);
            }
            return List.copyOf(result);
        }

        void collectAnimationChanges(
                List<LabPbrAtlasFrame.AnimationSample> samples,
                ArrayList<AnimationUpdate> result) {
            result.clear();
            for (AnimatedMaterialSprite animation : this.animated) {
                if (animation.animationIndex >= samples.size()) {
                    throw new IllegalStateException("LabPBR animation sample set is incomplete");
                }
                LabPbrAtlasFrame.AnimationSample sample = samples.get(animation.animationIndex);
                if (!sample.equals(animation.lastSample)) {
                    result.add(new AnimationUpdate(sample, animation));
                }
            }
        }

        void retireUploads(VulkanContext context) {
            RuntimeException failure = null;
            for (PageResource page : this.allPages()) {
                failure = ResourceCleanup.run(() -> page.retireUpload(context), failure);
            }
            ResourceCleanup.throwIfFailed(failure);
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                RuntimeException failure = destroyAnimations(this.animated, null);
                failure = ResourceCleanup.destroy(this.textureRecords, failure);
                for (int index = this.pages.size() - 1; index >= 0; index--) {
                    failure = destroyPages(this.pages.get(index), failure);
                }
                ResourceCleanup.throwIfFailed(failure);
            }
        }

        private static RuntimeException destroyAnimations(
                List<AnimatedMaterialSprite> animations,
                RuntimeException failure) {
            for (int index = animations.size() - 1; index >= 0; index--) {
                failure = ResourceCleanup.destroy(animations.get(index), failure);
            }
            return failure;
        }
    }

    static void writeColorSpriteF32(
            long target,
            long baseOffset,
            int rowWidth,
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.ColorSource source,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            boolean tightlyPacked) {
        writeColorSprite(
                target,
                baseOffset,
                rowWidth,
                placement,
                source,
                sample,
                mip,
                tightlyPacked,
                true);
    }

    private static void writeColorSpriteRgba16f(
            long target,
            long baseOffset,
            int rowWidth,
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.ColorSource source,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            boolean tightlyPacked) {
        writeColorSprite(
                target,
                baseOffset,
                rowWidth,
                placement,
                source,
                sample,
                mip,
                tightlyPacked,
                false);
    }

    private static void writeColorSprite(
            long target,
            long baseOffset,
            int rowWidth,
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.ColorSource source,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            boolean tightlyPacked,
            boolean f32) {
        LabPbrAtlasFrame.Sprite sprite = placement.sprite();
        int outputWidth = sprite.mipWidth(mip);
        int outputHeight = sprite.mipHeight(mip);
        int destinationX = tightlyPacked ? 0 : placement.mipX(mip);
        int destinationY = tightlyPacked ? 0 : placement.mipY(mip);
        int baseWidth = sprite.contentWidth() + 2 * sprite.padding();
        int baseHeight = sprite.contentHeight() + 2 * sprite.padding();
        int bytesPerPixel = f32 ? 4 * Float.BYTES : BASE_COLOR_BYTES_PER_PIXEL;
        float[] filtered = new float[4];
        for (int y = 0; y < outputHeight; y++) {
            double baseY0 = (double) y * baseHeight / outputHeight - sprite.padding();
            double baseY1 = (double) (y + 1) * baseHeight / outputHeight - sprite.padding();
            for (int x = 0; x < outputWidth; x++) {
                double baseX0 = (double) x * baseWidth / outputWidth - sprite.padding();
                double baseX1 = (double) (x + 1) * baseWidth / outputWidth - sprite.padding();
                source.filtered(
                        sample,
                        baseX0,
                        baseY0,
                        baseX1,
                        baseY1,
                        sprite.contentWidth(),
                        sprite.contentHeight(),
                        filtered);
                long offset = Math.addExact(
                        baseOffset,
                        Math.multiplyExact(
                                Math.addExact(
                                        Math.multiplyExact((long) destinationY + y, rowWidth),
                                        (long) destinationX + x),
                                bytesPerPixel));
                if (f32) {
                    MemoryUtil.memPutFloat(target + offset, filtered[0]);
                    MemoryUtil.memPutFloat(target + offset + 4L, filtered[1]);
                    MemoryUtil.memPutFloat(target + offset + 8L, filtered[2]);
                    MemoryUtil.memPutFloat(target + offset + 12L, filtered[3]);
                } else {
                    writeRgba16f(
                            target,
                            offset,
                            dev.prime.render.terrain.CanonicalColorEncoding
                                    .encodeLinearRgba16f(
                                            filtered[0],
                                            filtered[1],
                                            filtered[2],
                                            filtered[3]));
                }
            }
        }
    }
}
