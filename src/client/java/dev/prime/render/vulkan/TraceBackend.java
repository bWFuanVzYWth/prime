package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
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
    private final StarmapTexture starmap;
    private final BsdfLookupTable bsdfLookup;
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
        StarmapTexture starTexture = null;
        BsdfLookupTable lookup = null;
        RealtimeStbnTable stbn = null;
        long layout = 0L;
        try {
            starTexture = new StarmapTexture(context);
            lookup = new BsdfLookupTable(context);
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
            List<VulkanImage> materialBaseColorPages,
            List<VulkanImage> materialNormalPages,
            List<VulkanImage> materialOpticalPages,
            VulkanBuffer textureRecords,
            TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.TintOperatorBinding tintOperators,
            AtmospherePipeline atmosphere) {
        if (!materialCore.present()) {
            throw new IllegalArgumentException("Scene has no material-core binding");
        }
        if (!tintOperators.present()) {
            throw new IllegalArgumentException("Scene has no tint-operator binding");
        }
        if (this.sceneBindings != null
                && this.sceneBindings.matches(
                        tlas,
                        SrgbTextureView.imageView(atlasView),
                        atlasSampler.vkSampler(),
                        sceneTextures,
                        materialBaseColorPages,
                        materialNormalPages,
                        materialOpticalPages,
                        textureRecords.handle(),
                        materialCore,
                        tintOperators,
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
                materialBaseColorPages,
                materialNormalPages,
                materialOpticalPages,
                textureRecords,
                materialCore,
                tintOperators,
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
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_TLAS)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_BLOCK_ATLAS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(ShaderAbi.SCENE_TEXTURE_COUNT)
                .stageFlags(ALL_RT_STAGES);
        int[] storageBindings = new int[] {
            ShaderAbi.DESCRIPTOR_SKY_VIEW,
            ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW,
            ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH,
            ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
            ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
        };
        for (int binding : storageBindings) {
            bindings.get(cursor++)
                    .binding(binding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        int[] sampledBindings = new int[] {
            ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY,
            ShaderAbi.DESCRIPTOR_STARMAP
        };
        for (int binding : sampledBindings) {
            bindings.get(cursor++)
                    .binding(binding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(ALL_RT_STAGES);
        }
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(ShaderAbi.BASE_COLOR_PAGE_COUNT)
                .stageFlags(ALL_RT_STAGES);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_MATERIAL_NORMAL_PAGES)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(ShaderAbi.MATERIAL_PAGE_COUNT)
                .stageFlags(ALL_RT_STAGES);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_MATERIAL_OPTICAL_PAGES)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(ShaderAbi.MATERIAL_PAGE_COUNT)
                .stageFlags(ALL_RT_STAGES);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(ALL_RT_STAGES);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_MATERIAL_CORE_RECORDS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(ALL_RT_STAGES);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_TINT_OPERATORS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(ALL_RT_STAGES);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_REALTIME_STBN)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        for (int binding : sunShadowBindings()) {
            bindings.get(cursor++)
                    .binding(binding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(cursor)
                .binding(ShaderAbi.DESCRIPTOR_SUN_SHADOW_QUERY)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VkDescriptorSetLayoutCreateInfo createInfo =
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(), createInfo, null, pointer),
                "create shared trace descriptor layout");
        return pointer.get(0);
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
        private final List<Long> baseColorPages;
        private final List<Long> normalPages;
        private final List<Long> opticalPages;
        private final long textureRecords;
        private final TerrainScene.MaterialCoreBinding materialCore;
        private final TerrainScene.TintOperatorBinding tintOperators;
        private final long skyView;
        private final long transmittanceLow;
        private final long transmittanceHigh;
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
                List<VulkanImage> baseColorPages,
                List<VulkanImage> normalPages,
                List<VulkanImage> opticalPages,
                long textureRecords,
                TerrainScene.MaterialCoreBinding materialCore,
                TerrainScene.TintOperatorBinding tintOperators,
                AtmospherePipeline atmosphere,
                long[] sunShadowDepths) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.tlas = tlas;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.sceneTextures = List.copyOf(sceneTextures);
            this.baseColorPages = pageViews(baseColorPages);
            this.normalPages = pageViews(normalPages);
            this.opticalPages = pageViews(opticalPages);
            this.textureRecords = textureRecords;
            this.materialCore = materialCore;
            this.tintOperators = tintOperators;
            this.skyView = atmosphere.skyView().view();
            this.transmittanceLow = atmosphere.transmittanceLow().view();
            this.transmittanceHigh = atmosphere.transmittanceHigh().view();
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
                List<VulkanImage> baseColorPages,
                List<VulkanImage> normalPages,
                List<VulkanImage> opticalPages,
                VulkanBuffer textureRecords,
                TerrainScene.MaterialCoreBinding materialCore,
                TerrainScene.TintOperatorBinding tintOperators,
                AtmospherePipeline atmosphere,
                BsdfLookupTable bsdfLookup,
                StarmapTexture starmap,
                RealtimeStbnTable realtimeStbn) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(5, stack);
                sizes.get(0)
                        .type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                        .descriptorCount(1);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(5 + SunShadowClipmap.BANK_COUNT
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
                        .descriptorCount(4);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(
                                context.vkDevice(), poolInfo, null, poolPointer),
                        "create shared trace descriptor pool");
                long pool = poolPointer.get(0);
                try {
                    VkDescriptorSetAllocateInfo allocation =
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(pool)
                                    .pSetLayouts(stack.longs(layout));
                    LongBuffer setPointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(
                                    context.vkDevice(), allocation, setPointer),
                            "allocate shared trace descriptor set");
                    long set = setPointer.get(0);
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
                    int sampledStart = atmosphereStart + 5;
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
                        atmosphere.transmittanceLow(),
                        atmosphere.transmittanceHigh(),
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
                            .imageView(bsdfLookup.transmissionGgxEnergy().view())
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
                            VkDescriptorBufferInfo.calloc(5, stack);
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
                    VkDescriptorBufferInfo tintOperatorInfo = bufferInfos.get(4);
                    tintOperatorInfo
                            .buffer(tintOperators.buffer())
                            .offset(0L)
                            .range(tintOperators.bytes());
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
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_BLOCK_ATLAS)
                            .descriptorCount(ShaderAbi.SCENE_TEXTURE_COUNT)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(0).address(), ShaderAbi.SCENE_TEXTURE_COUNT));
                    int[] atmosphereBindings = new int[] {
                        ShaderAbi.DESCRIPTOR_SKY_VIEW,
                        ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW,
                        ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH,
                        ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
                        ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
                    };
                    for (int index = 0; index < atmosphereBindings.length; index++) {
                        writes.get(write++)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(atmosphereBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        infos.get(atmosphereStart + index).address(), 1));
                    }
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(sampledStart).address(), 1));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_MATERIAL_NORMAL_PAGES)
                            .descriptorCount(ShaderAbi.MATERIAL_PAGE_COUNT)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(normalStart).address(),
                                    ShaderAbi.MATERIAL_PAGE_COUNT));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_MATERIAL_OPTICAL_PAGES)
                            .descriptorCount(ShaderAbi.MATERIAL_PAGE_COUNT)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(opticalStart).address(),
                                    ShaderAbi.MATERIAL_PAGE_COUNT));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_STARMAP)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(starmapIndex).address(), 1));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    textureRecordInfo.address(), 1));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_MATERIAL_CORE_RECORDS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    materialCoreInfo.address(), 1));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES)
                            .descriptorCount(ShaderAbi.BASE_COLOR_PAGE_COUNT)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(baseColorStart).address(),
                                    ShaderAbi.BASE_COLOR_PAGE_COUNT));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_REALTIME_STBN)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    stbnInfo.address(), 1));
                    writes.get(write++)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TINT_OPERATORS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    tintOperatorInfo.address(), 1));
                    int[] shadowBindings = sunShadowBindings();
                    for (int index = 0; index < shadowBindings.length; index++) {
                        writes.get(write++)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(shadowBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        infos.get(shadowStart + index).address(), 1));
                    }
                    writes.get(write)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_SUN_SHADOW_QUERY)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    queryInfo.address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new SceneBindings(
                            context,
                            pool,
                            set,
                            tlas,
                            atlasColorView,
                            atlasSampler.vkSampler(),
                            sceneTextures,
                            baseColorPages,
                            normalPages,
                            opticalPages,
                            textureRecords.handle(),
                            materialCore,
                            tintOperators,
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
                List<VulkanImage> candidateBaseColorPages,
                List<VulkanImage> candidateNormalPages,
                List<VulkanImage> candidateOpticalPages,
                long candidateTextureRecords,
                TerrainScene.MaterialCoreBinding candidateMaterialCore,
                TerrainScene.TintOperatorBinding candidateTintOperators,
                AtmospherePipeline atmosphere) {
            if (this.tlas != candidateTlas
                    || this.atlasView != candidateAtlasView
                    || this.atlasSampler != candidateAtlasSampler
                    || !this.sceneTextures.equals(candidateSceneTextures)
                    || !matchesPageViews(this.baseColorPages, candidateBaseColorPages)
                    || !matchesPageViews(this.normalPages, candidateNormalPages)
                    || !matchesPageViews(this.opticalPages, candidateOpticalPages)
                    || this.textureRecords != candidateTextureRecords
                    || !this.materialCore.equals(candidateMaterialCore)
                    || !this.tintOperators.equals(candidateTintOperators)
                    || this.skyView != atmosphere.skyView().view()
                    || this.transmittanceLow != atmosphere.transmittanceLow().view()
                    || this.transmittanceHigh != atmosphere.transmittanceHigh().view()
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

        private static List<Long> pageViews(List<VulkanImage> pages) {
            return pages.stream().map(VulkanImage::view).toList();
        }

        private static boolean matchesPageViews(
                List<Long> expected, List<VulkanImage> candidate) {
            if (expected.size() != candidate.size()) {
                return false;
            }
            for (int index = 0; index < expected.size(); index++) {
                if (expected.get(index).longValue() != candidate.get(index).view()) {
                    return false;
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
