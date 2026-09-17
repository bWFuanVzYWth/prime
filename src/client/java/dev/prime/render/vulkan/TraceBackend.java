// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Shared scene ABI and immutable transport assets. The render thread is the sole owner; realtime,
 * offline, atmosphere and sun-shadow programs only borrow its descriptor set.
 */
public final class TraceBackend implements Destroyable {
    private static final int BINDING_COUNT = 27;
    private static final int STARMAP_UPLOAD = 1;
    private static final int BSDF_LOOKUP_UPLOAD = 1 << 1;
    private static final int REALTIME_STBN_UPLOAD = 1 << 2;
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    private final VulkanContext context;
    private final StaticSampledTexture starmap;
    private final StaticSampledTexture bsdfLookup;
    private final RealtimeStbnTable realtimeStbn;
    private final long descriptorSetLayout;
    private final TraceBindings bindings;
    private SunShadowPipeline sunShadowPipeline;
    private SceneBindings sceneBindings;
    private long nextFrameToken;
    private long pendingFrameToken;
    private int pendingUploads;
    private boolean staticResourcesPrepared;
    private boolean destroyed;

    public TraceBackend(VulkanContext context) {
        this.context = context;
        StaticSampledTexture starTexture = null;
        StaticSampledTexture lookup = null;
        RealtimeStbnTable stbn = null;
        long layout = 0L;
        try {
            starTexture = StaticSampledTexture.starmap(context);
            lookup = StaticSampledTexture.transmissionGgx(context);
            stbn = new RealtimeStbnTable(context);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                layout = createDescriptorSetLayout(context, stack);
            }
            this.starmap = starTexture;
            this.bsdfLookup = lookup;
            this.realtimeStbn = stbn;
            this.descriptorSetLayout = layout;
            this.bindings = new TraceBindings(layout);
            this.sunShadowPipeline = new SunShadowPipeline(context, this.bindings);
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.destroy(
                    this.sunShadowPipeline, exception);
            if (layout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), layout, null);
            }
            failure = ResourceCleanup.destroy(stbn, failure);
            failure = ResourceCleanup.destroy(lookup, failure);
            throw ResourceCleanup.destroy(starTexture, failure);
        }
    }

    public TraceBindings bindings() {
        return this.bindings;
    }

    /** Borrowed by reconstruction; this backend owns the texture through all frame retirement. */
    public VulkanImage starmapImage() { return this.starmap.image(); }
    public long starmapSampler() { return this.starmap.sampler(); }

    public SunShadowPipeline sunShadowPipeline() {
        return this.sunShadowPipeline;
    }

    public SunShadowPipeline prepareSunShadowReload() {
        return new SunShadowPipeline(this.context, this.bindings);
    }

    /** Publishes a prepared replacement and returns the previous pipeline for deferred retirement. */
    public SunShadowPipeline replaceSunShadowPipeline(SunShadowPipeline replacement) {
        if (replacement == null || replacement == this.sunShadowPipeline) {
            throw new IllegalArgumentException("Sun-shadow replacement is invalid");
        }
        SunShadowPipeline previous = this.sunShadowPipeline;
        this.sunShadowPipeline = replacement;
        return previous;
    }

    public void ensureSceneDescriptors(
            long tlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<SceneTexture> sceneTextures,
            MaterialTexturePages.Binding materialTextures,
            TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.SurfaceBinding surfaces,
            TerrainScene.TintSampleBinding tintSamples,
            AtmospherePipeline atmosphere) {
        if (!materialCore.present()) {
            throw new IllegalArgumentException("Scene has no material-core binding");
        }
        if (!tintSamples.present()) {
            throw new IllegalArgumentException("Scene has no tint-sample binding");
        }
        if (this.sceneBindings != null
                && this.sceneBindings.matches(
                        tlas,
                        SrgbTextureView.imageView(atlasView),
                        atlasSampler.vkSampler(),
                        sceneTextures,
                        materialTextures,
                        materialCore,
                        surfaces,
                        tintSamples,
                        atmosphere)) {
            return;
        }
        SceneBindings replacement = SceneBindings.create(
                this.context,
                this.descriptorSetLayout,
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                materialTextures,
                materialCore,
                surfaces,
                tintSamples,
                atmosphere,
                this.bsdfLookup,
                this.starmap,
                this.realtimeStbn);
        SceneBindings previous = this.sceneBindings;
        this.sceneBindings = replacement;
        this.bindings.publishDescriptorSet(replacement.descriptorSet);
        if (previous != null) {
            this.context.defer(previous);
        }
    }

    public long prepareStatic(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        if (this.pendingFrameToken != 0L) {
            throw new IllegalStateException("Trace-backend static upload is already pending");
        }
        int uploads = 0;
        try {
            if (this.starmap.prepare(commandBuffer, initialization)) {
                uploads |= STARMAP_UPLOAD;
            }
            if (this.bsdfLookup.prepare(commandBuffer, initialization)) {
                uploads |= BSDF_LOOKUP_UPLOAD;
            }
            if (this.realtimeStbn.prepare(commandBuffer)) {
                uploads |= REALTIME_STBN_UPLOAD;
            }
            if (uploads == 0) {
                this.staticResourcesPrepared = true;
                this.bindings.setReady(true);
                return 0L;
            }
            long token = ++this.nextFrameToken;
            if (token == 0L) {
                token = ++this.nextFrameToken;
            }
            this.pendingFrameToken = token;
            this.pendingUploads = uploads;
            this.bindings.setReady(true);
            return token;
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if ((uploads & BSDF_LOOKUP_UPLOAD) != 0) {
                failure = ResourceCleanup.run(this.bsdfLookup::abandon, failure);
            }
            if ((uploads & REALTIME_STBN_UPLOAD) != 0) {
                failure = ResourceCleanup.run(this.realtimeStbn::abandon, failure);
            }
            if ((uploads & STARMAP_UPLOAD) != 0) {
                failure = ResourceCleanup.run(this.starmap::abandon, failure);
            }
            throw failure;
        }
    }

    public void submitted(long token) {
        if (token == 0L) {
            return;
        }
        this.requirePendingToken(token);
        RuntimeException failure = null;
        if ((this.pendingUploads & STARMAP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.starmap::submitted, failure);
        }
        if ((this.pendingUploads & BSDF_LOOKUP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.bsdfLookup::submitted, failure);
        }
        if ((this.pendingUploads & REALTIME_STBN_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.realtimeStbn::submitted, failure);
        }
        this.staticResourcesPrepared = failure == null;
        this.pendingFrameToken = 0L;
        this.pendingUploads = 0;
        this.bindings.setReady(this.staticResourcesPrepared);
        ResourceCleanup.throwIfFailed(failure);
    }

    public void abandon(long token) {
        if (token == 0L) {
            return;
        }
        this.requirePendingToken(token);
        RuntimeException failure = null;
        if ((this.pendingUploads & BSDF_LOOKUP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.bsdfLookup::abandon, failure);
        }
        if ((this.pendingUploads & REALTIME_STBN_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.realtimeStbn::abandon, failure);
        }
        if ((this.pendingUploads & STARMAP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.starmap::abandon, failure);
        }
        this.pendingFrameToken = 0L;
        this.pendingUploads = 0;
        this.bindings.setReady(this.staticResourcesPrepared);
        ResourceCleanup.throwIfFailed(failure);
    }

    private void requirePendingToken(long token) {
        if (token != this.pendingFrameToken) {
            throw new IllegalArgumentException("Unknown trace-backend frame token");
        }
    }

    private static long createDescriptorSetLayout(
            VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
        int cursor = 0;
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_TLAS,
                KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_BLOCK_ATLAS,
                VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                ShaderAbi.SCENE_TEXTURE_COUNT, ALL_RT_STAGES);
        int[] storageBindings = new int[] {
            ShaderAbi.DESCRIPTOR_SKY_VIEW,
            ShaderAbi.DESCRIPTOR_CAMERA_TRANSMITTANCE,
            ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
            ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
        };
        for (int binding : storageBindings) {
            VulkanDescriptors.layoutBinding(
                    bindings.get(cursor++), binding,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        int[] sampledBindings = new int[] {
            ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY,
            ShaderAbi.DESCRIPTOR_STARMAP
        };
        for (int binding : sampledBindings) {
            VulkanDescriptors.layoutBinding(
                    bindings.get(cursor++), binding,
                    VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, ALL_RT_STAGES);
        }
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES,
                VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                ShaderAbi.BASE_COLOR_PAGE_COUNT, ALL_RT_STAGES);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_MATERIAL_NORMAL_PAGES,
                VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                ShaderAbi.MATERIAL_PAGE_COUNT, ALL_RT_STAGES);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_MATERIAL_OPTICAL_PAGES,
                VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                ShaderAbi.MATERIAL_PAGE_COUNT, ALL_RT_STAGES);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, ALL_RT_STAGES);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_MATERIAL_CORE_RECORDS,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, ALL_RT_STAGES);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_SURFACE_RECORDS,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
                KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
                        | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_TINT_SAMPLES,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, ALL_RT_STAGES);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_REALTIME_STBN,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        for (int binding : sunShadowBindings()) {
            VulkanDescriptors.layoutBinding(
                    bindings.get(cursor++), binding,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor), ShaderAbi.DESCRIPTOR_SUN_SHADOW_QUERY,
                VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        return VulkanDescriptors.createSetLayout(
                context,
                stack,
                bindings,
                "create shared trace descriptor layout");
    }

    private static int[] sunShadowBindings() {
        return new int[] {
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_0,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_1,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_2,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_3,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_4,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_5,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_6,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_7,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_8,
            ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_9
        };
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.sceneBindings != null) {
                this.sceneBindings.destroy();
                this.sceneBindings = null;
            }
            this.sunShadowPipeline.destroy();
            this.bindings.close();
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
            this.bsdfLookup.destroy();
            this.realtimeStbn.destroy();
            this.starmap.destroy();
        }
    }

    public record SceneTexture(long image, long view, long sampler) {
        public SceneTexture {
            if (image == 0L || view == 0L || sampler == 0L) {
                throw new IllegalArgumentException("Scene texture handles must be non-zero");
            }
        }
    }

    private static final class SceneBindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long tlas;
        private final long atlasView;
        private final long atlasSampler;
        private final List<SceneTexture> sceneTextures;
        private final MaterialTexturePages.Binding materialTextures;
        private final TerrainScene.MaterialCoreBinding materialCore;
        private final TerrainScene.SurfaceBinding surfaces;
        private final TerrainScene.TintSampleBinding tintSamples;
        private final long skyView;
        private final long cameraTransmittance;
        private final long aerialRadiance;
        private final long aerialTransmittance;
        private final long sunShadowQuery;
        private final long[] sunShadowDepths;
        private boolean destroyed;

        private SceneBindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long tlas,
                long atlasView,
                long atlasSampler,
                List<SceneTexture> sceneTextures,
                MaterialTexturePages.Binding materialTextures,
                TerrainScene.MaterialCoreBinding materialCore,
                TerrainScene.SurfaceBinding surfaces,
                TerrainScene.TintSampleBinding tintSamples,
                AtmospherePipeline atmosphere,
                long[] sunShadowDepths) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.tlas = tlas;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.sceneTextures = List.copyOf(sceneTextures);
            this.materialTextures = materialTextures;
            this.materialCore = materialCore;
            this.surfaces = surfaces;
            this.tintSamples = tintSamples;
            this.skyView = atmosphere.skyView().view();
            this.cameraTransmittance = atmosphere.cameraTransmittance().view();
            this.aerialRadiance = atmosphere.aerialRadiance().view();
            this.aerialTransmittance = atmosphere.aerialTransmittance().view();
            this.sunShadowQuery = atmosphere.sunShadowQuery().handle();
            this.sunShadowDepths = sunShadowDepths.clone();
        }

        private static SceneBindings create(
                VulkanContext context,
                long layout,
                long tlas,
                VulkanGpuTextureView atlasView,
                VulkanGpuSampler atlasSampler,
                List<SceneTexture> sceneTextures,
                MaterialTexturePages.Binding materialTextures,
                TerrainScene.MaterialCoreBinding materialCore,
                TerrainScene.SurfaceBinding surfaces,
                TerrainScene.TintSampleBinding tintSamples,
                AtmospherePipeline atmosphere,
                StaticSampledTexture bsdfLookup,
                StaticSampledTexture starmap,
                RealtimeStbnTable realtimeStbn) {
            List<VulkanImage> baseColorPages = materialTextures.baseColorPages();
            List<VulkanImage> normalPages = materialTextures.normalPages();
            List<VulkanImage> opticalPages = materialTextures.opticalPages();
            VulkanBuffer textureRecords = materialTextures.textureRecords();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(5, stack);
                sizes.get(0)
                        .type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                        .descriptorCount(1);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(4 + SunShadowClipmap.BANK_COUNT
                                * SunShadowClipmap.CASCADE_COUNT);
                sizes.get(2)
                        .type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(ShaderAbi.SCENE_TEXTURE_COUNT
                                + ShaderAbi.BASE_COLOR_PAGE_COUNT
                                + 2 * ShaderAbi.MATERIAL_PAGE_COUNT
                                + 2);
                sizes.get(3)
                        .type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1);
                sizes.get(4)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(5);
                long pool = VulkanDescriptors.createPool(
                        context,
                        stack,
                        1,
                        sizes,
                        "create shared trace descriptor pool");
                try {
                    long set = VulkanDescriptors.allocateSet(
                            context,
                            stack,
                            pool,
                            layout,
                            "allocate shared trace descriptor set");
                    if (sceneTextures.size() + 1 > ShaderAbi.SCENE_TEXTURE_COUNT) {
                        throw new IllegalArgumentException(
                                "Dynamic scene texture count exceeds the descriptor ABI");
                    }
                    if (baseColorPages.isEmpty()
                            || baseColorPages.size() > ShaderAbi.BASE_COLOR_PAGE_COUNT
                            || normalPages.isEmpty()
                            || normalPages.size() > ShaderAbi.MATERIAL_PAGE_COUNT
                            || opticalPages.isEmpty()
                            || opticalPages.size() > ShaderAbi.MATERIAL_PAGE_COUNT) {
                        throw new IllegalArgumentException(
                                "Translated material page count exceeds the descriptor ABI");
                    }
                    int atmosphereStart = ShaderAbi.SCENE_TEXTURE_COUNT;
                    int sampledStart = atmosphereStart + 4;
                    int baseColorStart = sampledStart + 1;
                    int normalStart = baseColorStart + ShaderAbi.BASE_COLOR_PAGE_COUNT;
                    int opticalStart = normalStart + ShaderAbi.MATERIAL_PAGE_COUNT;
                    int starmapIndex = opticalStart + ShaderAbi.MATERIAL_PAGE_COUNT;
                    int shadowStart = starmapIndex + 1;
                    long atlasColorView = SrgbTextureView.imageView(atlasView);
                    VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(
                            shadowStart + SunShadowClipmap.BANK_COUNT
                                    * SunShadowClipmap.CASCADE_COUNT,
                            stack);
                    for (int index = 0; index < ShaderAbi.SCENE_TEXTURE_COUNT; index++) {
                        SceneTexture texture = index == 0 || index > sceneTextures.size()
                                ? new SceneTexture(
                                        atlasView.texture().vkImage(),
                                        atlasColorView,
                                        atlasSampler.vkSampler())
                                : sceneTextures.get(index - 1);
                        infos.get(index)
                                .sampler(texture.sampler())
                                .imageView(texture.view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    VulkanImage[] atmosphereImages = new VulkanImage[] {
                        atmosphere.skyView(),
                        atmosphere.cameraTransmittance(),
                        atmosphere.aerialRadiance(),
                        atmosphere.aerialTransmittance()
                    };
                    for (int index = 0; index < atmosphereImages.length; index++) {
                        infos.get(atmosphereStart + index)
                                .imageView(atmosphereImages[index].view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    infos.get(sampledStart)
                            .sampler(bsdfLookup.sampler())
                            .imageView(bsdfLookup.image().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    for (int index = 0; index < ShaderAbi.BASE_COLOR_PAGE_COUNT; index++) {
                        VulkanImage baseColor = baseColorPages.get(
                                Math.min(index, baseColorPages.size() - 1));
                        infos.get(baseColorStart + index)
                                .sampler(atlasSampler.vkSampler())
                                .imageView(baseColor.view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    }
                    for (int index = 0; index < ShaderAbi.MATERIAL_PAGE_COUNT; index++) {
                        VulkanImage normal = normalPages.get(
                                Math.min(index, normalPages.size() - 1));
                        VulkanImage optical = opticalPages.get(
                                Math.min(index, opticalPages.size() - 1));
                        infos.get(normalStart + index)
                                .sampler(atlasSampler.vkSampler())
                                .imageView(normal.view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        infos.get(opticalStart + index)
                                .sampler(atlasSampler.vkSampler())
                                .imageView(optical.view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    }
                    infos.get(starmapIndex)
                            .sampler(starmap.sampler())
                            .imageView(starmap.image().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    long[] shadowViews = new long[
                            SunShadowClipmap.BANK_COUNT * SunShadowClipmap.CASCADE_COUNT];
                    for (int bank = 0; bank < SunShadowClipmap.BANK_COUNT; bank++) {
                        for (int cascade = 0;
                                cascade < SunShadowClipmap.CASCADE_COUNT;
                                cascade++) {
                            int index = bank * SunShadowClipmap.CASCADE_COUNT + cascade;
                            VulkanImage image = atmosphere.sunShadowDepth(bank, cascade);
                            shadowViews[index] = image.view();
                            infos.get(shadowStart + index)
                                    .imageView(image.view())
                                    .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                        }
                    }
                    VkWriteDescriptorSetAccelerationStructureKHR acceleration =
                            VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                                    .sType$Default()
                                    .pAccelerationStructures(stack.longs(tlas));
                    VkDescriptorBufferInfo.Buffer bufferInfos =
                            VkDescriptorBufferInfo.calloc(6, stack);
                    VkDescriptorBufferInfo queryInfo = bufferInfos.get(0);
                    queryInfo
                                    .buffer(atmosphere.sunShadowQuery().handle())
                                    .offset(0L)
                                    .range(ShaderAbi.SUN_SHADOW_QUERY_CONSTANT_SIZE);
                    VkDescriptorBufferInfo textureRecordInfo = bufferInfos.get(1);
                    textureRecordInfo
                            .buffer(textureRecords.handle())
                            .offset(0L)
                            .range(textureRecords.size());
                    VkDescriptorBufferInfo materialCoreInfo = bufferInfos.get(2);
                    materialCoreInfo
                            .buffer(materialCore.buffer())
                            .offset(0L)
                            .range(materialCore.bytes());
                    VkDescriptorBufferInfo stbnInfo = bufferInfos.get(3);
                    stbnInfo
                            .buffer(realtimeStbn.buffer().handle())
                            .offset(0L)
                            .range(realtimeStbn.buffer().size());
                    VkDescriptorBufferInfo tintSampleInfo = bufferInfos.get(4);
                    tintSampleInfo
                            .buffer(tintSamples.buffer())
                            .offset(0L)
                            .range(tintSamples.bytes());
                    VkDescriptorBufferInfo surfaceInfo = bufferInfos.get(5);
                    surfaceInfo.buffer(surfaces.buffer()).offset(0L).range(surfaces.bytes());
                    VkWriteDescriptorSet.Buffer writes =
                            VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
                    int write = 0;
                    writes.get(write++)
                            .sType$Default()
                            .pNext(acceleration.address())
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TLAS)
                            .descriptorCount(1)
                            .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                    VulkanDescriptors.writeImages(
                            writes.get(write++), set, ShaderAbi.DESCRIPTOR_BLOCK_ATLAS,
                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            infos.get(0), ShaderAbi.SCENE_TEXTURE_COUNT);
                    int[] atmosphereBindings = new int[] {
                        ShaderAbi.DESCRIPTOR_SKY_VIEW,
                        ShaderAbi.DESCRIPTOR_CAMERA_TRANSMITTANCE,
                        ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
                        ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
                    };
                    for (int index = 0; index < atmosphereBindings.length; index++) {
                        VulkanDescriptors.writeImage(
                                writes.get(write++), set, atmosphereBindings[index],
                                VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                infos.get(atmosphereStart + index));
                    }
                    VulkanDescriptors.writeImage(
                            writes.get(write++), set,
                            ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY,
                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            infos.get(sampledStart));
                    VulkanDescriptors.writeImages(
                            writes.get(write++), set,
                            ShaderAbi.DESCRIPTOR_MATERIAL_NORMAL_PAGES,
                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            infos.get(normalStart), ShaderAbi.MATERIAL_PAGE_COUNT);
                    VulkanDescriptors.writeImages(
                            writes.get(write++), set,
                            ShaderAbi.DESCRIPTOR_MATERIAL_OPTICAL_PAGES,
                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            infos.get(opticalStart), ShaderAbi.MATERIAL_PAGE_COUNT);
                    VulkanDescriptors.writeImage(
                            writes.get(write++), set, ShaderAbi.DESCRIPTOR_STARMAP,
                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            infos.get(starmapIndex));
                    VulkanDescriptors.writeBuffer(
                            writes.get(write++), set, ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS,
                            VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, textureRecordInfo);
                    VulkanDescriptors.writeBuffer(
                            writes.get(write++), set,
                            ShaderAbi.DESCRIPTOR_MATERIAL_CORE_RECORDS,
                            VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, materialCoreInfo);
                    VulkanDescriptors.writeImages(
                            writes.get(write++), set, ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES,
                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            infos.get(baseColorStart), ShaderAbi.BASE_COLOR_PAGE_COUNT);
                    VulkanDescriptors.writeBuffer(
                            writes.get(write++), set, ShaderAbi.DESCRIPTOR_REALTIME_STBN,
                            VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, stbnInfo);
                    VulkanDescriptors.writeBuffer(
                            writes.get(write++), set, ShaderAbi.DESCRIPTOR_SURFACE_RECORDS,
                            VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, surfaceInfo);
                    VulkanDescriptors.writeBuffer(
                            writes.get(write++), set, ShaderAbi.DESCRIPTOR_TINT_SAMPLES,
                            VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, tintSampleInfo);
                    int[] shadowBindings = sunShadowBindings();
                    for (int index = 0; index < shadowBindings.length; index++) {
                        VulkanDescriptors.writeImage(
                                writes.get(write++), set, shadowBindings[index],
                                VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                infos.get(shadowStart + index));
                    }
                    VulkanDescriptors.writeBuffer(
                            writes.get(write), set, ShaderAbi.DESCRIPTOR_SUN_SHADOW_QUERY,
                            VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, queryInfo);
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new SceneBindings(
                            context,
                            pool,
                            set,
                            tlas,
                            atlasColorView,
                            atlasSampler.vkSampler(),
                            sceneTextures,
                            materialTextures,
                            materialCore,
                            surfaces,
                            tintSamples,
                            atmosphere,
                            shadowViews);
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(
                long candidateTlas,
                long candidateAtlasView,
                long candidateAtlasSampler,
                List<SceneTexture> candidateSceneTextures,
                MaterialTexturePages.Binding candidateMaterialTextures,
                TerrainScene.MaterialCoreBinding candidateMaterialCore,
                TerrainScene.SurfaceBinding candidateSurfaces,
                TerrainScene.TintSampleBinding candidateTintSamples,
                AtmospherePipeline atmosphere) {
            if (this.tlas != candidateTlas
                    || this.atlasView != candidateAtlasView
                    || this.atlasSampler != candidateAtlasSampler
                    || !this.sceneTextures.equals(candidateSceneTextures)
                    || this.materialTextures != candidateMaterialTextures
                    || !this.materialCore.equals(candidateMaterialCore)
                    || !this.surfaces.equals(candidateSurfaces)
                    || !this.tintSamples.equals(candidateTintSamples)
                    || this.skyView != atmosphere.skyView().view()
                    || this.cameraTransmittance != atmosphere.cameraTransmittance().view()
                    || this.aerialRadiance != atmosphere.aerialRadiance().view()
                    || this.aerialTransmittance != atmosphere.aerialTransmittance().view()
                    || this.sunShadowQuery != atmosphere.sunShadowQuery().handle()) {
                return false;
            }
            for (int bank = 0; bank < SunShadowClipmap.BANK_COUNT; bank++) {
                for (int cascade = 0;
                        cascade < SunShadowClipmap.CASCADE_COUNT;
                        cascade++) {
                    int index = bank * SunShadowClipmap.CASCADE_COUNT + cascade;
                    if (this.sunShadowDepths[index]
                            != atmosphere.sunShadowDepth(bank, cascade).view()) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyDescriptorPool(
                        this.context.vkDevice(), this.descriptorPool, null);
            }
        }
    }
}
