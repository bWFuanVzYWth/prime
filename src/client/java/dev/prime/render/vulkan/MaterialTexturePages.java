package dev.prime.render.vulkan;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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
    private static final int NORMAL_DEFAULT_ARGB = 0x008080ff;
    private static final int OPTICAL_DEFAULT_ARGB = 0xff000400;

    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final ArrayList<AnimationUpdate> animationUpdates = new ArrayList<>();
    private final ArrayList<Copy> animationCopies = new ArrayList<>();
    private List<LabPbrAtlasFrame.AnimationSample> animationSamples = List.of();
    private Resources resources;
    private final PendingSubmission<FrameToken> pending = new PendingSubmission<>();
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
        if (this.pending.active()) {
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

    public List<VulkanImage> normalPages() {
        return requireResources().normalImages();
    }

    public List<VulkanImage> opticalPages() {
        return requireResources().opticalImages();
    }

    public VulkanBuffer textureRecords() {
        return requireResources().textureRecords;
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
        if (this.pending.active()) {
            throw new IllegalStateException(
                    "Previous LabPBR upload has not been submitted or abandoned");
        }
        Resources current = requireResources();
        this.animationCopies.clear();
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
                if (change.owner.normal != null) {
                    TexturePageLayout.Placement placement = change.owner.normal.placement();
                    PageResource page = current.normalPages.get(placement.page());
                    int mipLevels = change.owner.normal.mipLevels();
                    for (int mip = 0; mip < mipLevels; mip++) {
                        addAnimatedCopy(
                                this.animationCopies,
                                batch,
                                page.image,
                                change.owner.normal,
                                change.sample,
                                mip);
                    }
                }
                if (change.owner.specular != null) {
                    TexturePageLayout.Placement placement = change.owner.specular.placement();
                    PageResource page = current.opticalPages.get(placement.page());
                    int mipLevels = change.owner.specular.mipLevels();
                    for (int mip = 0; mip < mipLevels; mip++) {
                        addAnimatedCopy(
                                this.animationCopies,
                                batch,
                                page.image,
                                change.owner.specular,
                                change.sample,
                                mip);
                    }
                }
                budget = spriteBudget;
                this.animationUpdates.set(acceptedCount++, change);
            }
            if (this.animationCopies.isEmpty()) {
                batch.close();
                return this.publish(null, current, initialUpload, 0);
            }
            ArrayList<VulkanImage> changedImages = new ArrayList<>();
            for (Copy copy : this.animationCopies) {
                if (!changedImages.contains(copy.image)) {
                    changedImages.add(copy.image);
                }
            }
            transitionImages(
                    commandBuffer,
                    changedImages,
                    current.prepared || initialUpload,
                    true);
            for (Copy copy : this.animationCopies) {
                recordCopy(commandBuffer, copy);
            }
            transitionImages(
                    commandBuffer,
                    changedImages,
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
        this.pending.complete(
                token, "LabPBR frame token does not belong to this submission");
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
        this.pending.complete(
                token, "Material frame token does not belong to these texture pages");
        ResourceCleanup.throwIfFailed(ResourceCleanup.close(token.batch, null));
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            RuntimeException failure = null;
            FrameToken abandoned = this.pending.clear();
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
        this.pending.begin(token);
        return token;
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
        TexturePageLayout.Layout normalLayout = TexturePageLayout.pack(
                source.sprites(), LabPbrAtlasFrame.Sprite::normal, source.mipLevels());
        TexturePageLayout.Layout opticalLayout = TexturePageLayout.pack(
                source.sprites(), LabPbrAtlasFrame.Sprite::specular, source.mipLevels());
        List<PageResource> normalPages = List.of();
        List<PageResource> opticalPages = List.of();
        VulkanBuffer textureRecords = null;
        Resources resources = null;
        try {
            normalPages = this.buildPages(
                    source, normalLayout, true, NORMAL_DEFAULT_ARGB);
            opticalPages = this.buildPages(
                    source, opticalLayout, false, OPTICAL_DEFAULT_ARGB);
            textureRecords = this.buildTextureRecords(
                    source, normalLayout, opticalLayout, normalPages, opticalPages);
            resources = new Resources(
                    sourceGeneration,
                    vanillaAtlasView,
                    normalPages,
                    opticalPages,
                    textureRecords,
                    source.materials(),
                    source.sprites(),
                    normalLayout,
                    opticalLayout);
            PrimeInfo.LOGGER.info(
                    "Translated material storage: {} textures, normal={} pages/{} bytes, optical={} pages/{} bytes, records={} bytes, animation cache={} bytes",
                    source.sprites().size(),
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
            failure = destroyPages(opticalPages, failure);
            failure = destroyPages(normalPages, failure);
            throw failure;
        }
    }

    private List<PageResource> buildPages(
            LabPbrAtlasFrame.Snapshot source,
            TexturePageLayout.Layout layout,
            boolean normal,
            int defaultArgb) {
        ArrayList<PageResource> pages = new ArrayList<>(layout.pages().size());
        try {
            int usage = VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            for (int pageIndex = 0; pageIndex < layout.pages().size(); pageIndex++) {
                int width = layout.pages().get(pageIndex).width();
                int height = layout.pages().get(pageIndex).height();
                int mipLevels = Math.min(
                        source.mipLevels(),
                        32 - Integer.numberOfLeadingZeros(Math.max(width, height)));
                String channel = normal ? "normal" : "optical";
                VulkanImage image = this.context.createMipmappedImage2D(
                        width,
                        height,
                        mipLevels,
                        VK12.VK_FORMAT_R8G8B8A8_UNORM,
                        usage,
                        "Prime material " + channel + " page " + pageIndex);
                VulkanBuffer upload = null;
                try {
                    long byteSize = totalMipBytes(width, height, mipLevels);
                    upload = this.context.createBuffer(
                            byteSize,
                            VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            true,
                            "Prime material " + channel + " page upload " + pageIndex);
                    fillPage(
                            upload,
                            width,
                            height,
                            mipLevels,
                            pageIndex,
                            source.sprites(),
                            layout,
                            normal,
                            defaultArgb);
                    pages.add(new PageResource(image, upload));
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
            TexturePageLayout.Layout normalLayout,
            TexturePageLayout.Layout opticalLayout,
            List<PageResource> normalPages,
            List<PageResource> opticalPages) {
        int maximumTextureId = 0;
        for (LabPbrAtlasFrame.Sprite sprite : source.sprites()) {
            maximumTextureId = Math.max(maximumTextureId, sprite.textureId());
        }
        long byteSize = Math.multiplyExact((long) maximumTextureId + 1L, 32L);
        VulkanBuffer result = this.context.createBuffer(
                byteSize,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true,
                "Prime texture records");
        long target = result.mappedAddress();
        MemoryUtil.memSet(target, 0, byteSize);
        for (LabPbrAtlasFrame.Sprite sprite : source.sprites()) {
            long record = target + (long) sprite.textureId() * 32L;
            putPackedExtent(
                    record,
                    0,
                    sprite.x() + sprite.padding(),
                    sprite.y() + sprite.padding());
            putPackedExtent(record, 4, sprite.contentWidth(), sprite.contentHeight());
            TexturePageLayout.Placement normal = normalLayout.placement(sprite.textureId());
            TexturePageLayout.Placement specular = opticalLayout.placement(sprite.textureId());
            putPackedExtent(
                    record,
                    8,
                    normal == null ? 0 : normal.contentX(),
                    normal == null ? 0 : normal.contentY());
            putPackedExtent(
                    record,
                    12,
                    specular == null ? 0 : specular.contentX(),
                    specular == null ? 0 : specular.contentY());
            int normalPage = normal == null ? 0xff : normal.page();
            int specularPage = specular == null ? 0xff : specular.page();
            int normalMip = normal == null
                    ? 0
                    : textureMipLimit(sprite, normalPages.get(normal.page()).image);
            int specularMip = specular == null
                    ? 0
                    : textureMipLimit(sprite, opticalPages.get(specular.page()).image);
            MemoryUtil.memPutInt(
                    record + 16L,
                    normalPage | specularPage << 8 | normalMip << 16 | specularMip << 24);
            MemoryUtil.memPutInt(
                    record + 20L,
                    textureMipLevels(sprite, source.mipLevels()) - 1);
            MemoryUtil.memPutInt(record + 24L, 0);
            MemoryUtil.memPutInt(record + 28L, 0);
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
                            page.image.mipLevels()));
        }
        return result;
    }

    private static void fillPage(
            VulkanBuffer upload,
            int width,
            int height,
            int mipLevels,
            int pageIndex,
            List<LabPbrAtlasFrame.Sprite> sprites,
            TexturePageLayout.Layout layout,
            boolean normal,
            int defaultArgb) {
        long byteSize = totalMipBytes(width, height, mipLevels);
        long target = upload.mappedAddress();
        fillArgb(target, byteSize, defaultArgb);
        long mipOffset = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            int mipWidth = Math.max(1, width >> mip);
            int mipHeight = Math.max(1, height >> mip);
            for (LabPbrAtlasFrame.Sprite sprite : sprites) {
                TexturePageLayout.Placement placement = layout.placement(sprite.textureId());
                LabPbrAtlasFrame.MaterialSource material =
                        normal ? sprite.normal() : sprite.specular();
                if (placement != null
                        && placement.page() == pageIndex
                        && material != null
                        && mip < textureMipLevels(sprite, mipLevels)) {
                    writeSprite(
                            target,
                            mipOffset,
                            mipWidth,
                            placement,
                            material,
                            LabPbrAtlasFrame.AnimationSample.ZERO,
                            mip,
                            false,
                            !normal);
                }
            }
            mipOffset += (long) mipWidth * mipHeight * 4L;
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
                offset += (long) width * height * 4L;
            }
        }
        transitionImages(commandBuffer, resources.allImages(), true, false);
    }

    private static void addAnimatedCopy(
            List<Copy> copies,
            StagingArena.Batch batch,
            VulkanImage image,
            MaterialAnimationFrames frames,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip) {
        TexturePageLayout.Placement placement = frames.placement();
        LabPbrAtlasFrame.Sprite sprite = placement.sprite();
        int width = sprite.mipWidth(mip);
        int height = sprite.mipHeight(mip);
        long byteSize = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        StagingArena.Slice slice = batch.allocate(byteSize, 4L);
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(images.size(), stack);
            for (int index = 0; index < images.size(); index++) {
                VulkanImage image = images.get(index);
                fillBarrier(
                        barriers.get(index), image, image.mipLevels(),
                        initialized,
                        toTransfer);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
    }

    private static void fillBarrier(
            VkImageMemoryBarrier2 barrier,
            VulkanImage image,
            int mipLevels,
            boolean initialized,
            boolean toTransfer) {
        barrier.sType$Default()
                .srcStageMask(toTransfer
                        ? (initialized ? KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        : VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(toTransfer && initialized ? VK12.VK_ACCESS_SHADER_READ_BIT : toTransfer ? 0L : VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstStageMask(toTransfer
                        ? VK12.VK_PIPELINE_STAGE_TRANSFER_BIT
                        : KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR)
                .dstAccessMask(toTransfer ? VK12.VK_ACCESS_TRANSFER_WRITE_BIT : VK12.VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(toTransfer
                        ? (initialized ? VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                        : VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(toTransfer
                        ? VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                        : VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image.image());
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(mipLevels)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    static long totalMipBytes(int width, int height, int mipLevels) {
        long result = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            result = Math.addExact(
                    result,
                    Math.multiplyExact(
                            (long) Math.max(1, width >> mip) * Math.max(1, height >> mip),
                            4L));
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

    static long animationEndOffset(
            long cursor, int width, int height, boolean normal, boolean specular) {
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        long result = cursor;
        if (normal) {
            result = StagingArena.requiredEndOffset(result, bytes, 4L);
        }
        return specular
                ? StagingArena.requiredEndOffset(result, bytes, 4L)
                : result;
    }

    private static long animationEndOffset(
            long cursor,
            AnimatedMaterialSprite animation) {
        long result = cursor;
        if (animation.normal != null) {
            int mipLevels = animation.normal.mipLevels();
            for (int mip = 0; mip < mipLevels; mip++) {
                result = animationEndOffset(
                        result,
                        animation.sprite.mipWidth(mip),
                        animation.sprite.mipHeight(mip),
                        true,
                        false);
            }
        }
        if (animation.specular != null) {
            int mipLevels = animation.specular.mipLevels();
            for (int mip = 0; mip < mipLevels; mip++) {
                result = animationEndOffset(
                        result,
                        animation.sprite.mipWidth(mip),
                        animation.sprite.mipHeight(mip),
                        false,
                        true);
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
        private final MaterialAnimationFrames normal;
        private final MaterialAnimationFrames specular;
        private final int animationIndex;
        private LabPbrAtlasFrame.AnimationSample lastSample;

        static AnimatedMaterialSprite create(
                LabPbrAtlasFrame.Sprite source,
                TexturePageLayout.Placement normal,
                TexturePageLayout.Placement specular,
                List<PageResource> normalPages,
                List<PageResource> opticalPages) {
            MaterialAnimationFrames normalFrames = null;
            MaterialAnimationFrames specularFrames = null;
            try {
                if (normal != null && source.normal().frameCount() > 1) {
                    VulkanImage image = normalPages.get(normal.page()).image;
                    normalFrames = MaterialAnimationFrames.create(
                            normal,
                            source.normal(),
                            textureMipLevels(source, image.mipLevels()),
                            false);
                }
                if (specular != null && source.specular().frameCount() > 1) {
                    VulkanImage image = opticalPages.get(specular.page()).image;
                    specularFrames = MaterialAnimationFrames.create(
                            specular,
                            source.specular(),
                            textureMipLevels(source, image.mipLevels()),
                            true);
                }
                return new AnimatedMaterialSprite(source, normalFrames, specularFrames);
            } catch (RuntimeException | Error failure) {
                ResourceCleanup.destroy(specularFrames, null);
                ResourceCleanup.destroy(normalFrames, null);
                throw failure;
            }
        }

        private AnimatedMaterialSprite(
                LabPbrAtlasFrame.Sprite source,
                MaterialAnimationFrames normal,
                MaterialAnimationFrames specular) {
            this.sprite = source;
            this.normal = normal;
            this.specular = specular;
            this.animationIndex = source.animationIndex();
            this.lastSample = null;
        }

        long frameBytes() {
            return (this.normal == null ? 0L : this.normal.byteSize())
                    + (this.specular == null ? 0L : this.specular.byteSize());
        }

        @Override
        public void destroy() {
            RuntimeException failure = ResourceCleanup.destroy(this.specular, null);
            failure = ResourceCleanup.destroy(this.normal, failure);
            ResourceCleanup.throwIfFailed(failure);
        }
    }

    private static final class PageResource implements com.mojang.blaze3d.vulkan.Destroyable {
        private final VulkanImage image;
        private VulkanBuffer upload;
        private boolean destroyed;

        private PageResource(VulkanImage image, VulkanBuffer upload) {
            this.image = image;
            this.upload = upload;
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
        private final List<PageResource> normalPages;
        private final List<PageResource> opticalPages;
        private final List<PageResource> allPages;
        private final List<VulkanImage> normalImages;
        private final List<VulkanImage> opticalImages;
        private final List<VulkanImage> allImages;
        private final VulkanBuffer textureRecords;
        private final LabPbrMaterialSet materials;
        private final List<AnimatedMaterialSprite> animated;
        private boolean prepared;
        private boolean destroyed;

        Resources(
                long sourceGeneration,
                long vanillaAtlasView,
                List<PageResource> normalPages,
                List<PageResource> opticalPages,
                VulkanBuffer textureRecords,
                LabPbrMaterialSet materials,
                List<LabPbrAtlasFrame.Sprite> sprites,
                TexturePageLayout.Layout normalLayout,
                TexturePageLayout.Layout opticalLayout) {
            this.sourceGeneration = sourceGeneration;
            this.vanillaAtlasView = vanillaAtlasView;
            this.normalPages = normalPages;
            this.opticalPages = opticalPages;
            ArrayList<PageResource> allPages = new ArrayList<>(
                    normalPages.size() + opticalPages.size());
            allPages.addAll(normalPages);
            allPages.addAll(opticalPages);
            this.allPages = List.copyOf(allPages);
            this.normalImages = images(normalPages);
            this.opticalImages = images(opticalPages);
            this.allImages = images(this.allPages);
            this.textureRecords = textureRecords;
            this.materials = materials;
            ArrayList<AnimatedMaterialSprite> animated = new ArrayList<>();
            try {
                for (LabPbrAtlasFrame.Sprite sprite : sprites) {
                    TexturePageLayout.Placement normal =
                            normalLayout.placement(sprite.textureId());
                    TexturePageLayout.Placement specular =
                            opticalLayout.placement(sprite.textureId());
                    boolean animatedNormal = normal != null && sprite.normal().frameCount() > 1;
                    boolean animatedSpecular =
                            specular != null && sprite.specular().frameCount() > 1;
                    if (sprite.animated() && (animatedNormal || animatedSpecular)) {
                        animated.add(AnimatedMaterialSprite.create(
                                sprite,
                                normal,
                                specular,
                                normalPages,
                                opticalPages));
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

        List<VulkanImage> normalImages() {
            return this.normalImages;
        }

        List<VulkanImage> opticalImages() {
            return this.opticalImages;
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
                failure = destroyPages(this.opticalPages, failure);
                failure = destroyPages(this.normalPages, failure);
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

}
