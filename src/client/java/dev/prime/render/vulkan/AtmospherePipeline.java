// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static dev.prime.render.vulkan.AtmospherePrecomputation.*;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.render.AerialEpipolarMapping;
import dev.prime.render.AtmosphereCoordinates;
import dev.prime.render.AtmosphereSettings;
import dev.prime.render.FrameCamera;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.SunDirection;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

/**
 * Owns Prime's spectral atmosphere lookup tables and native Vulkan compute pipelines.
 *
 * <p>The transmittance and multiple-scattering tables depend only on the immutable atmosphere
 * model and are generated once per manual density setting. The sky table changes with eye altitude and sun elevation;
 * aerial perspective also changes with the relative camera projection and complete sun direction.
 * Atmosphere dispatch data travels through push constants. A separately synchronized 48-byte
 * uniform publishes the active directional-shadow bank and basis to later transport dispatches.
 */
public final class AtmospherePipeline implements Destroyable {
    public static final float AERIAL_MAX_DISTANCE_KM = ShaderAbi.ATMOSPHERE_AERIAL_MAX_DISTANCE_KM;

    private static final int PUSH_CONSTANT_SIZE = 128;
    private enum ImageRole {
        OPTICAL_DEPTH,
        SOURCE,
        MEAN,
        GROUND,
        SKY_VIEW,
        AERIAL_RADIANCE,
        AERIAL_TRANSMITTANCE,
        CAMERA_TRANSMITTANCE,
        HIGH
    }
    private static final int IMAGE_COUNT = ImageRole.values().length;
    private static final int MEDIUM_BINDING = 7;
    private static final int SUN_SHADOW_BINDING = 8;
    private static final int SUN_SHADOW_HIERARCHY_BINDING =
            SUN_SHADOW_BINDING
                    + SunShadowClipmap.BANK_COUNT * SunShadowClipmap.CASCADE_COUNT;
    private static final int SUN_SHADOW_HIERARCHY_COUNT = SunShadowClipmap.CASCADE_COUNT;
    private static final int SUN_SHADOW_HIERARCHY_WIDTH = SunShadowClipmap.RESOLUTION;
    private static final int SUN_SHADOW_HIERARCHY_HEIGHT = SunShadowClipmap.RESOLUTION;
    private static final int CAMERA_TRANSMITTANCE_BINDING =
            SUN_SHADOW_HIERARCHY_BINDING + SUN_SHADOW_HIERARCHY_COUNT;
    private static final int BINDING_COUNT = 33;
    private static final int[] IMAGE_BINDINGS = {0, 1, 2, 3, 4, 5, 6, 23, 24};
    private static final int[] FIELD_IMAGES = {1, 2, 3, 8};
    private static final int AERIAL_KEY_SIZE = 21;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private enum PipelineRole {
        TRANSMITTANCE(
                GeneratedShaderPrograms.resource("atmosphere_transmittance"),
                "Prime atmosphere transmittance pipeline"),
        MULTI_SCATTERING(
                GeneratedShaderPrograms.resource("atmosphere_multi_scattering"),
                "Prime atmosphere multiple scattering pipeline"),
        DIRECTIONS(GeneratedShaderPrograms.resource("atmosphere_directions"), "Prime atmosphere directions"),
        INCIDENT(GeneratedShaderPrograms.resource("atmosphere_incident"), "Prime atmosphere incident light"),
        MOMENTS(GeneratedShaderPrograms.resource("atmosphere_moments"), "Prime atmosphere moments"),
        GROUND(GeneratedShaderPrograms.resource("atmosphere_ground"), "Prime atmosphere ground"),
        SKY(
                GeneratedShaderPrograms.resource("atmosphere_sky"),
                "Prime atmosphere sky pipeline"),
        AERIAL(
                GeneratedShaderPrograms.resource("atmosphere_aerial"),
                "Prime atmosphere epipolar aerial-radiance pipeline"),
        AERIAL_TRANSMITTANCE(
                GeneratedShaderPrograms.resource("atmosphere_aerial_transmittance"),
                "Prime atmosphere aerial-transmittance pipeline"),
        SUN_SHADOW_HIERARCHY(
                GeneratedShaderPrograms.resource("sun_shadow_hierarchy"),
                "Prime sun shadow hierarchy pipeline");

        final String resourceName;
        final String label;

        PipelineRole(String resourceName, String label) {
            this.resourceName = resourceName;
            this.label = label;
        }
    }

    private final VulkanContext context;
    private final int aerosolDensitySteps;
    private final VulkanImage[] images;
    private final SunShadowClipmap sunShadow;
    private final VulkanImage[] sunShadowHierarchies;
    private final VulkanBuffer sunShadowQuery;
    private final VulkanBuffer medium;
    private final long sampler;
    // Owned by this render-thread pipeline until the bootstrap submission transfers them to
    // VulkanContext's existing deferred retirement queue. No frame may reuse solver scratch.
    private final VulkanImage[] spare;
    private final VulkanBuffer[] scratch;
    private final VulkanDescriptors.BoundSet[] solverDescriptors;
    private boolean solverRetired;
    private final long descriptorSetLayout;
    private final VulkanDescriptors.BoundSet descriptors;
    private final long pipelineLayout;
    private final long[] pipelines;
    private final VulkanImage[] initialImages;
    private final VulkanImage[] skyImage;
    private final VulkanImage[] aerialImages;
    private final VulkanImage[] dynamicImages;
    private final AtmosphereLutHistory history =
            new AtmosphereLutHistory(AERIAL_KEY_SIZE);
    private final float[] aerialMatrix = new float[16];
    private long nextFrameToken;
    private long pendingFrameToken;
    private int pendingChanges;
    private boolean staticPreparationPending;
    private boolean destroyed;

    public AtmospherePipeline(VulkanContext context, int aerosolDensitySteps) {
        this.context = context;
        AtmosphereSettings.densityScale(aerosolDensitySteps);
        this.aerosolDensitySteps = aerosolDensitySteps;
        VulkanImage[] images = new VulkanImage[IMAGE_COUNT];
        VulkanImage[] sunShadowHierarchies =
                new VulkanImage[SUN_SHADOW_HIERARCHY_COUNT];
        SunShadowClipmap newSunShadow = null;
        VulkanBuffer newSunShadowQuery = null;
        VulkanBuffer newMedium = null;
        long newSampler = 0L;
        VulkanImage[] spare = new VulkanImage[4];
        VulkanBuffer[] scratch = new VulkanBuffer[3];
        VulkanDescriptors.BoundSet[] solverDescriptors = new VulkanDescriptors.BoundSet[2];
        long newDescriptorSetLayout = 0L;
        VulkanDescriptors.BoundSet newDescriptors = null;
        long newPipelineLayout = 0L;
        long[] pipelines = new long[PipelineRole.values().length];
        try {
            images[ImageRole.OPTICAL_DEPTH.ordinal()] = staticImage(context, 512, 128,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime atmosphere optical depth");
            for (int bank = 0; bank < 2; bank++) {
                VulkanImage[] target = bank == 0 ? images : spare;
                int[] indices = bank == 0 ? FIELD_IMAGES : new int[] {0, 1, 2, 3};
                target[indices[0]] = staticImage(context, SUNS * PHASES, LOW_HEIGHTS * CONES,
                        VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime atmosphere source " + bank);
                target[indices[1]] = staticImage(context, SUNS, HEIGHTS,
                        VK12.VK_FORMAT_R32G32B32A32_SFLOAT, "Prime atmosphere mean " + bank);
                target[indices[2]] = staticImage(context, SUNS, 1,
                        VK12.VK_FORMAT_R32G32B32A32_SFLOAT, "Prime atmosphere ground " + bank);
                target[indices[3]] = staticImage(context, SUNS * 5, HEIGHTS - LOW_HEIGHTS + 1,
                        VK12.VK_FORMAT_R32G32B32A32_SFLOAT, "Prime atmosphere Rayleigh " + bank);
            }
            for (int index = 0; index < scratch.length; index++) {
                long vectors = (long) BATCH_HEIGHTS * SUNS * (index == 2 ? 7 : DIRECTIONS);
                scratch[index] = context.createBuffer(vectors * 16,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, "Prime atmosphere scratch " + index);
            }
            images[ImageRole.SKY_VIEW.ordinal()] = context.createImage2D(256, 256,
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT, VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                    "Prime atmosphere log sky view");
            images[ImageRole.CAMERA_TRANSMITTANCE.ordinal()] = context.createAtmosphereImage2D(
                    ShaderAbi.ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH,
                    1, "Prime atmosphere camera transmittance");
            images[ImageRole.AERIAL_RADIANCE.ordinal()] = context.createAtmosphereImage3D(
                    ShaderAbi.ATMOSPHERE_AERIAL_EPIPOLAR_SAMPLES,
                    ShaderAbi.ATMOSPHERE_AERIAL_EPIPOLAR_SLICES,
                    ShaderAbi.ATMOSPHERE_AERIAL_DEPTH,
                    "Prime atmosphere aerial radiance");
            images[ImageRole.AERIAL_TRANSMITTANCE.ordinal()] = context.createAtmosphereImage3D(
                    ShaderAbi.ATMOSPHERE_AERIAL_WIDTH,
                    ShaderAbi.ATMOSPHERE_AERIAL_HEIGHT,
                    ShaderAbi.ATMOSPHERE_AERIAL_DEPTH,
                    "Prime atmosphere aerial transmittance");
            for (int cascade = 0;
                    cascade < SUN_SHADOW_HIERARCHY_COUNT;
                    cascade++) {
                sunShadowHierarchies[cascade] = context.createImage2D(
                        SUN_SHADOW_HIERARCHY_WIDTH,
                        SUN_SHADOW_HIERARCHY_HEIGHT,
                        VK12.VK_FORMAT_R32G32_SFLOAT,
                        VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                        "Prime sun shadow hierarchy cascade " + cascade);
            }
            newSunShadow = new SunShadowClipmap(context);
            newSunShadowQuery = context.createBuffer(
                    ShaderAbi.SUN_SHADOW_QUERY_CONSTANT_SIZE,
                    VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime sun-shadow query constants");
            newMedium = createMedium(context, aerosolDensitySteps);
            newSampler = createSampler(context);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                newDescriptorSetLayout = createDescriptorSetLayout(context, stack);
                newPipelineLayout = createPipelineLayout(context, stack, newDescriptorSetLayout);
                pipelines = createComputePipelines(context, newPipelineLayout);
                newDescriptors = createDescriptors(
                        context,
                        stack,
                        newDescriptorSetLayout,
                        images,
                        sunShadowHierarchies,
                        newSunShadow,
                        newMedium, newSampler, spare, null, -1);
                for (int bank = 0; bank < 2; bank++) {
                    solverDescriptors[bank] = createDescriptors(context, stack, newDescriptorSetLayout,
                            images, sunShadowHierarchies, newSunShadow, newMedium, newSampler,
                            spare, scratch, bank);
                }
            }
            this.images = images;
            this.sunShadow = newSunShadow;
            this.sunShadowHierarchies = sunShadowHierarchies;
            this.sunShadowQuery = newSunShadowQuery;
            this.medium = newMedium;
            this.sampler = newSampler;
            this.spare = spare;
            this.scratch = scratch;
            this.solverDescriptors = solverDescriptors;
            this.descriptorSetLayout = newDescriptorSetLayout;
            this.descriptors = newDescriptors;
            this.pipelineLayout = newPipelineLayout;
            this.pipelines = pipelines;
            this.initialImages = new VulkanImage[
                    IMAGE_COUNT + SUN_SHADOW_HIERARCHY_COUNT + spare.length];
            System.arraycopy(images, 0, this.initialImages, 0, IMAGE_COUNT);
            System.arraycopy(
                    sunShadowHierarchies,
                    0,
                    this.initialImages,
                    IMAGE_COUNT,
                    SUN_SHADOW_HIERARCHY_COUNT);
            System.arraycopy(spare, 0, this.initialImages,
                    IMAGE_COUNT + SUN_SHADOW_HIERARCHY_COUNT, spare.length);
            this.skyImage = new VulkanImage[] {
                image(ImageRole.SKY_VIEW), image(ImageRole.CAMERA_TRANSMITTANCE)
            };
            this.aerialImages = new VulkanImage[] {
                image(ImageRole.AERIAL_RADIANCE), image(ImageRole.AERIAL_TRANSMITTANCE)
            };
            this.dynamicImages = new VulkanImage[] {
                image(ImageRole.CAMERA_TRANSMITTANCE),
                image(ImageRole.SKY_VIEW),
                image(ImageRole.AERIAL_RADIANCE),
                image(ImageRole.AERIAL_TRANSMITTANCE)
            };
        } catch (RuntimeException exception) {
            if (newDescriptors != null) newDescriptors.destroy();
            for (var set : solverDescriptors) if (set != null) set.destroy();
            for (var buffer : scratch) if (buffer != null) buffer.destroy();
            for (var image : spare) if (image != null) image.destroy();
            if (newSampler != 0L) VK12.vkDestroySampler(context.vkDevice(), newSampler, null);
            for (int index = pipelines.length - 1; index >= 0; index--) {
                destroyPipeline(context, pipelines[index]);
            }
            if (newPipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), newPipelineLayout, null);
            }
            if (newDescriptorSetLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), newDescriptorSetLayout, null);
            }
            if (newMedium != null) {
                newMedium.destroy();
            }
            if (newSunShadowQuery != null) {
                newSunShadowQuery.destroy();
            }
            if (newSunShadow != null) {
                newSunShadow.destroy();
            }
            for (VulkanImage image : images) {
                if (image != null) {
                    image.destroy();
                }
            }
            for (VulkanImage hierarchy : sunShadowHierarchies) {
                if (hierarchy != null) {
                    hierarchy.destroy();
                }
            }
            throw exception;
        }
    }

    private VulkanImage image(ImageRole role) {
        return this.images[role.ordinal()];
    }

    private long pipeline(PipelineRole role) {
        return this.pipelines[role.ordinal()];
    }

    public VulkanImage skyView() {
        return image(ImageRole.SKY_VIEW);
    }

    public VulkanImage cameraTransmittance() {
        return image(ImageRole.CAMERA_TRANSMITTANCE);
    }

    public VulkanImage aerialRadiance() {
        return image(ImageRole.AERIAL_RADIANCE);
    }

    public VulkanImage aerialTransmittance() {
        return image(ImageRole.AERIAL_TRANSMITTANCE);
    }

    /**
     * Projects the direction that owns the published aerial shadow cache.
     *
     * <p>The cache direction can intentionally lag the visible sun while its next RT bank is
     * assembled. Epipolar slices must follow that cached direction: otherwise their projections
     * are not lines in the cache plane and the conservative profile fallback produces dark rays.
     */
    public AerialEpipolarMapping.Epipole aerialEpipole(
            FrameCamera camera,
            SunDirection fallback) {
        return AerialEpipolarMapping.project(
                camera,
                this.sunShadow.activeDirection(fallback));
    }

    VulkanImage sunShadowDepth(int bank, int cascade) {
        return this.sunShadow.depth(bank, cascade);
    }

    VulkanBuffer sunShadowQuery() {
        return this.sunShadowQuery;
    }

    /** Records camera-independent LUT generation before the renderer becomes frame-ready. */
    public boolean prepareStatic(VkCommandBuffer commandBuffer) {
        if (this.history.staticPrepared()) {
            return false;
        }
        if (this.staticPreparationPending || this.pendingFrameToken != 0L) {
            throw new IllegalStateException("Atmosphere preparation is already pending");
        }
        transitionAllToGeneral(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(16).order(ByteOrder.nativeOrder());
            for (var step : AtmospherePrecomputation.plan()) {
                push.putInt(0, step.firstHeight()).putInt(4, step.heightCount()).putInt(8, step.iteration());
                dispatchSolver(commandBuffer, PipelineRole.valueOf(step.stage().name()), step.bank(),
                        step.x(), step.y(), push);
                solverBarrier(commandBuffer);
            }
        }

        this.staticPreparationPending = true;
        return true;
    }

    public void submittedStatic() {
        if (!this.staticPreparationPending) {
            throw new IllegalStateException("No static atmosphere preparation is pending");
        }
        for (VulkanImage image : this.initialImages) {
            image.markInitialized();
        }
        this.history.staticSubmitted();
        this.solverRetired = true;
        this.context.defer(this::destroySolverResources);
        this.staticPreparationPending = false;
    }

    public void abandonStatic() {
        if (!this.staticPreparationPending) {
            throw new IllegalStateException("No static atmosphere preparation is pending");
        }
        this.staticPreparationPending = false;
    }

    public long prepare(
            VkCommandBuffer commandBuffer,
            SunShadowPipeline pipeline,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            boolean forceCompleteSunShadow) {
        FrameCamera camera = input.camera();
        SunDirection sunDirection = input.sunDirection();
        boolean shadowPrepared = false;
        float eyeRadiusKm;
        int eyeRadiusBits;
        int sunElevationBits;
        int changes;
        try {
            if (!this.history.staticPrepared()) {
                throw new IllegalStateException(
                        "Static atmosphere LUTs were not prepared during bootstrap");
            }
            shadowPrepared = this.sunShadow.prepare(
                    commandBuffer,
                    pipeline,
                    input,
                    scene,
                    forceCompleteSunShadow);
            updateSunShadowQuery(commandBuffer);
            eyeRadiusKm = AtmosphereCoordinates.eyeRadiusKm(camera.y(), input.atmosphere());
            eyeRadiusBits = Float.floatToIntBits(eyeRadiusKm);
            sunElevationBits = Float.floatToIntBits(sunDirection.y());
            fillAerialKey(
                    this.history.beginCandidate(),
                    this.aerialMatrix,
                    camera,
                    eyeRadiusBits,
                    sunDirection,
                    this.sunShadow.contentVersion());
            changes = this.history.prepareCandidate(
                    eyeRadiusBits, sunElevationBits);
        } catch (RuntimeException exception) {
            if (shadowPrepared) {
                this.sunShadow.abandon();
            }
            throw exception;
        }
        if (changes == 0) {
            if (shadowPrepared) {
                this.sunShadow.abandon();
                throw new IllegalStateException(
                        "Sun-shadow content changed without invalidating the aerial LUT");
            }
            return 0L;
        }
        // Sky-view azimuth is defined relative to the sun's horizontal projection, so rotating
        // that projection around world Y changes only the lookup orientation, not the table data.
        boolean prepareSky = (changes & AtmosphereLutHistory.SKY) != 0;
        boolean prepareAerial = (changes & AtmosphereLutHistory.AERIAL) != 0;
        long token = nextFrameToken();
        this.pendingFrameToken = token;
        this.pendingChanges = changes;
        try {
            VulkanImage[] overwritten = prepareSky && prepareAerial
                    ? this.dynamicImages
                    : prepareSky
                            ? this.skyImage
                            : this.aerialImages;
            shaderReadToComputeWriteBarrier(commandBuffer, overwritten);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer pushConstants = createPushConstants(
                        stack,
                        camera,
                        eyeRadiusKm,
                        sunDirection,
                        scene,
                        this.sunShadow);
                int epipoleXBits = pushConstants.getInt(72);
                int epipoleYBits = pushConstants.getInt(76);
                if (shadowPrepared) {
                    shaderReadToComputeWriteBarrier(
                            commandBuffer, this.sunShadowHierarchies);
                    // Epipolar profiles consume only the resolved leaf layer. Building the
                    // nine parent levels left over from per-ray traversal is pure overhead.
                    pushConstants.putInt(72, 0);
                    dispatch(
                            commandBuffer,
                            pipeline(PipelineRole.SUN_SHADOW_HIERARCHY),
                            SunShadowClipmap.RESOLUTION / 8,
                            SunShadowClipmap.RESOLUTION / 8,
                            SUN_SHADOW_HIERARCHY_COUNT,
                            pushConstants);
                    computeWriteBarrier(
                            commandBuffer,
                            this.sunShadowHierarchies,
                            VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                    pushConstants.putInt(72, epipoleXBits);
                    pushConstants.putInt(76, epipoleYBits);
                }
                if (prepareSky) {
                    dispatch(commandBuffer, pipeline(PipelineRole.SKY), 1, 256, 1, pushConstants);
                }
                if (prepareAerial) {
                    dispatch(
                            commandBuffer,
                            pipeline(PipelineRole.AERIAL_TRANSMITTANCE),
                            ShaderAbi.ATMOSPHERE_AERIAL_WIDTH,
                            ShaderAbi.ATMOSPHERE_AERIAL_HEIGHT,
                            1,
                            pushConstants);
                    dispatch(
                            commandBuffer,
                            pipeline(PipelineRole.AERIAL),
                            1,
                            ShaderAbi.ATMOSPHERE_AERIAL_EPIPOLAR_SLICES,
                            1,
                            pushConstants);
                }
            }
            VulkanImage[] written = prepareSky && prepareAerial
                    ? this.dynamicImages
                    : prepareSky
                            ? this.skyImage
                            : this.aerialImages;
            computeWriteBarrier(
                    commandBuffer,
                    written,
                    // Raygen consumes sky/transmittance while the post-NRD composite consumes the
                    // aerial-perspective volumes. Keep both destinations explicit: atmosphere and
                    // display composition deliberately straddle the denoiser boundary.
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            return token;
        } catch (RuntimeException exception) {
            this.pendingFrameToken = 0L;
            this.pendingChanges = 0;
            this.history.abandon();
            if (shadowPrepared) {
                this.sunShadow.abandon();
            }
            throw exception;
        }
    }

    /** Commits the LUT keys after the command buffer containing {@code token} is submitted. */
    public void submitted(long token) {
        if (token == 0L) {
            return;
        }
        requirePendingToken(token);
        this.history.commit();
        this.sunShadow.submitted();
        this.pendingFrameToken = 0L;
        this.pendingChanges = 0;
    }

    /** Discards keys recorded into a command buffer that was not submitted. */
    public void abandon(long token) {
        if (token == 0L) {
            return;
        }
        requirePendingToken(token);
        this.history.abandon();
        this.sunShadow.abandon();
        this.pendingFrameToken = 0L;
        this.pendingChanges = 0;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.descriptors.destroy();
            if (!this.solverRetired) destroySolverResources();
            VK12.vkDestroySampler(this.context.vkDevice(), this.sampler, null);
            for (int index = this.pipelines.length - 1; index >= 0; index--) {
                VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipelines[index], null);
            }
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
            this.sunShadowQuery.destroy();
            this.sunShadow.destroy();
            for (int index = this.sunShadowHierarchies.length - 1;
                    index >= 0;
                    index--) {
                this.sunShadowHierarchies[index].destroy();
            }
            for (int index = this.images.length - 1; index >= 0; index--) {
                this.images[index].destroy();
            }
            this.medium.destroy();
        }
    }

    private void dispatch(
            VkCommandBuffer commandBuffer,
            long pipeline,
            int groupsX,
            int groupsY,
            int groupsZ,
            ByteBuffer pushConstants) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptors.handle()),
                    null);
            if (pushConstants != null) {
                VK12.vkCmdPushConstants(
                        commandBuffer,
                        this.pipelineLayout,
                        COMPUTE_STAGE,
                        0,
                        pushConstants);
            }
            VK12.vkCmdDispatch(commandBuffer, groupsX, groupsY, groupsZ);
        }
    }

    private void updateSunShadowQuery(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.sunShadowQuery,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            ByteBuffer constants = stack.calloc(ShaderAbi.SUN_SHADOW_QUERY_CONSTANT_SIZE)
                    .order(ByteOrder.nativeOrder());
            this.sunShadow.writeQueryConstants(constants);
            VK12.vkCmdUpdateBuffer(
                    commandBuffer, this.sunShadowQuery.handle(), 0L, constants);
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.sunShadowQuery,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
        }
    }

    private void transitionAllToGeneral(VkCommandBuffer commandBuffer) {
        VulkanSync.imageBarriers(commandBuffer, this.initialImages,
                VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0L,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private long nextFrameToken() {
        long token = ++this.nextFrameToken;
        if (token == 0L) {
            token = ++this.nextFrameToken;
        }
        return token;
    }

    private void requirePendingToken(long token) {
        if (token != this.pendingFrameToken) {
            throw new IllegalArgumentException(
                    "Atmosphere frame token does not belong to this submission");
        }
    }

    private static void shaderReadToComputeWriteBarrier(
            VkCommandBuffer commandBuffer,
            VulkanImage[] images) {
        // Raygen and the post-NRD composite read these before the next atmosphere write.
        VulkanSync.imageBarriers(commandBuffer, images,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void computeWriteBarrier(
            VkCommandBuffer commandBuffer,
            VulkanImage[] images,
            long destinationStage) {
        VulkanSync.imageBarriers(commandBuffer, images,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                destinationStage, VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private static ByteBuffer createPushConstants(
            MemoryStack stack,
            FrameCamera camera,
            float eyeRadiusKm,
            SunDirection sunDirection,
            TerrainScene.ResidentSceneView scene,
            SunShadowClipmap sunShadow) {
        ByteBuffer buffer = stack.calloc(PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
        camera.inverseViewProjection().get(0, buffer);
        buffer.putFloat(64, eyeRadiusKm);
        buffer.putFloat(68, AERIAL_MAX_DISTANCE_KM);
        SunDirection shadowDirection = sunShadow.activeDirection(sunDirection);
        AerialEpipolarMapping.Epipole epipole =
                AerialEpipolarMapping.project(camera, shadowDirection);
        buffer.putFloat(72, epipole.x());
        buffer.putFloat(76, epipole.y());
        buffer.putFloat(80, sunDirection.x());
        buffer.putFloat(84, sunDirection.y());
        buffer.putFloat(88, sunDirection.z());
        buffer.putFloat(92, ShaderAbi.ATMOSPHERE_SPACE_SUN_INTENSITY);
        buffer.putFloat(96, shadowDirection.x());
        buffer.putFloat(100, shadowDirection.y());
        buffer.putFloat(104, shadowDirection.z());
        buffer.putFloat(108, sunShadow.activeBank());
        buffer.putFloat(112, (float) (camera.renderX() - scene.originX()));
        buffer.putFloat(116, (float) (camera.renderY() - scene.originY()));
        buffer.putFloat(120, (float) (camera.renderZ() - scene.originZ()));
        buffer.putFloat(124, sunShadow.activeValid() ? 1.0F : 0.0F);
        return buffer.position(0).limit(PUSH_CONSTANT_SIZE);
    }

    private static void fillAerialKey(
            int[] key,
            float[] matrix,
            FrameCamera camera,
            int eyeRadiusBits,
            SunDirection sunDirection,
            int sunShadowVersion) {
        camera.inverseViewProjection().get(matrix);
        for (int index = 0; index < matrix.length; index++) {
            key[index] = Float.floatToIntBits(matrix[index]);
        }
        key[16] = eyeRadiusBits;
        key[17] = Float.floatToIntBits(sunDirection.x());
        key[18] = Float.floatToIntBits(sunDirection.y());
        key[19] = Float.floatToIntBits(sunDirection.z());
        key[20] = sunShadowVersion;
    }

    private static long createDescriptorSetLayout(VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
        for (int index = 0; index < IMAGE_COUNT; index++) {
            int binding = IMAGE_BINDINGS[index];
            int type = index < 2 ? VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    : VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            VulkanDescriptors.layoutBinding(bindings.get(binding), binding, type, 1, COMPUTE_STAGE);
        }
        for (int binding = 25; binding <= 32; binding++) {
            int type = binding >= 29 && binding <= 31 ? VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    : VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            VulkanDescriptors.layoutBinding(bindings.get(binding), binding, type, 1, COMPUTE_STAGE);
        }
        VulkanDescriptors.layoutBinding(
                bindings.get(MEDIUM_BINDING), MEDIUM_BINDING,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, COMPUTE_STAGE);
        for (int index = 0;
                index < SunShadowClipmap.BANK_COUNT * SunShadowClipmap.CASCADE_COUNT;
                index++) {
            VulkanDescriptors.layoutBinding(
                    bindings.get(SUN_SHADOW_BINDING + index), SUN_SHADOW_BINDING + index,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, COMPUTE_STAGE);
        }
        for (int cascade = 0;
                cascade < SUN_SHADOW_HIERARCHY_COUNT;
                cascade++) {
            VulkanDescriptors.layoutBinding(
                    bindings.get(SUN_SHADOW_HIERARCHY_BINDING + cascade),
                    SUN_SHADOW_HIERARCHY_BINDING + cascade,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, COMPUTE_STAGE);
        }
        return VulkanDescriptors.createSetLayout(
                context,
                stack,
                bindings,
                "create Prime atmosphere descriptor set layout");
    }

    private static long createPipelineLayout(
            VulkanContext context,
            MemoryStack stack,
            long descriptorSetLayout) {
        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                .stageFlags(COMPUTE_STAGE)
                .offset(0)
                .size(PUSH_CONSTANT_SIZE);
        return VulkanDescriptors.createPipelineLayout(
                context,
                stack,
                descriptorSetLayout,
                pushRange,
                "create Prime atmosphere pipeline layout");
    }

    private static long[] createComputePipelines(
            VulkanContext context, long pipelineLayout) {
        PipelineRole[] roles = PipelineRole.values();
        long[] pipelines = new long[roles.length];
        try {
            ParallelPipelineCreation.run(
                    "atmosphere compute pipelines",
                    pipelines.length,
                    index -> {
                        PipelineRole role = roles[index];
                        pipelines[index] = createComputePipeline(
                                context, pipelineLayout, role.resourceName, role.label);
                    });
            return pipelines;
        } catch (RuntimeException exception) {
            for (int index = pipelines.length - 1; index >= 0; index--) {
                destroyPipeline(context, pipelines[index]);
            }
            throw exception;
        }
    }

    private static long createComputePipeline(
            VulkanContext context,
            long pipelineLayout,
            String resourceName,
            String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long module = VulkanShaderModules.create(context, stack, resourceName);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(COMPUTE_STAGE)
                        .module(module)
                        .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer createInfo =
                        VkComputePipelineCreateInfo.calloc(1, stack);
                createInfo.get(0)
                        .sType$Default()
                        .stage(stage)
                        .layout(pipelineLayout);
                LongBuffer pointer = stack.mallocLong(1);
                context.createComputePipeline(createInfo, pointer, label);
                long pipeline = pointer.get(0);
                context.device().instance().debug().setObjectName(
                        context.vkDevice(), VK12.VK_OBJECT_TYPE_PIPELINE, pipeline, label);
                return pipeline;
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), module, null);
            }
        }
    }

    private static VulkanDescriptors.BoundSet createDescriptors(
            VulkanContext context,
            MemoryStack stack,
            long descriptorSetLayout,
            VulkanImage[] images,
            VulkanImage[] sunShadowHierarchies,
            SunShadowClipmap sunShadow,
            VulkanBuffer medium, long sampler, VulkanImage[] spare, VulkanBuffer[] scratch, int bank) {
        VulkanDescriptors.Binding[] bindings =
                new VulkanDescriptors.Binding[BINDING_COUNT];
        for (int index = 0; index < IMAGE_COUNT; index++) {
            int binding = IMAGE_BINDINGS[index];
            VulkanImage image = images[index];
            if ((bank < 0 ? AtmospherePrecomputation.finalBank() : bank) == 1) {
                for (int field = 0; field < FIELD_IMAGES.length; field++) {
                    if (index == FIELD_IMAGES[field]) image = spare[field];
                }
            }
            bindings[binding] = index < 2
                    ? VulkanDescriptors.sampledImage(binding, image.view(), VK12.VK_IMAGE_LAYOUT_GENERAL, sampler)
                    : VulkanDescriptors.image(binding, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                            image.view(), VK12.VK_IMAGE_LAYOUT_GENERAL);
        }
        if (bank >= 0) {
            for (int field = 0; field < FIELD_IMAGES.length; field++) {
                VulkanImage image = bank == 0 ? spare[field] : images[FIELD_IMAGES[field]];
                bindings[25 + field] = VulkanDescriptors.image(25 + field,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, image.view(), VK12.VK_IMAGE_LAYOUT_GENERAL);
            }
            for (int index = 0; index < scratch.length; index++) {
                bindings[29 + index] = VulkanDescriptors.buffer(29 + index,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, scratch[index].handle(), 0L, scratch[index].size());
            }
            bindings[32] = VulkanDescriptors.image(32, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    images[ImageRole.OPTICAL_DEPTH.ordinal()].view(), VK12.VK_IMAGE_LAYOUT_GENERAL);
        }
        bindings[MEDIUM_BINDING] = VulkanDescriptors.buffer(
                MEDIUM_BINDING,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                medium.handle(),
                0L,
                medium.size());
        for (int shadowBank = 0; shadowBank < SunShadowClipmap.BANK_COUNT; shadowBank++) {
            for (int cascade = 0; cascade < SunShadowClipmap.CASCADE_COUNT; cascade++) {
                int index = shadowBank * SunShadowClipmap.CASCADE_COUNT + cascade;
                int binding = SUN_SHADOW_BINDING + index;
                bindings[binding] = VulkanDescriptors.image(
                        binding,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        sunShadow.depth(shadowBank, cascade).view(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL);
            }
        }
        for (int cascade = 0; cascade < SUN_SHADOW_HIERARCHY_COUNT; cascade++) {
            int binding = SUN_SHADOW_HIERARCHY_BINDING + cascade;
            bindings[binding] = VulkanDescriptors.image(
                    binding,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    sunShadowHierarchies[cascade].view(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL);
        }
        return VulkanDescriptors.bind(
                context, stack, descriptorSetLayout, "Prime atmosphere",
                java.util.Arrays.stream(bindings).filter(java.util.Objects::nonNull)
                        .toArray(VulkanDescriptors.Binding[]::new));
    }

    public int aerosolDensitySteps() {
        return this.aerosolDensitySteps;
    }

    private static VulkanBuffer createMedium(VulkanContext context, int aerosolDensitySteps) {
        byte[] bytes = AtmosphereMedium.load(aerosolDensitySteps);
        VulkanBuffer buffer = context.createBuffer(
                bytes.length,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true,
                "Prime atmosphere medium inputs");
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        try {
            data.put(bytes).flip();
            buffer.put(0L, data);
            return buffer;
        } catch (RuntimeException exception) {
            buffer.destroy();
            throw exception;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void destroyPipeline(VulkanContext context, long pipeline) {
        if (pipeline != 0L) {
            VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
        }
    }

    private static VulkanImage staticImage(VulkanContext context, int width, int height, int format, String label) {
        return context.createImage2D(width, height, format,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT, label);
    }

    private static long createSampler(VulkanContext context) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK12.VK_FILTER_LINEAR).minFilter(VK12.VK_FILTER_LINEAR)
                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxAnisotropy(1.0F);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(VK12.vkCreateSampler(context.vkDevice(), info, null, pointer),
                    "create atmosphere interpolation sampler");
            return pointer.get(0);
        }
    }

    private void destroySolverResources() {
        for (var set : this.solverDescriptors) set.destroy();
        for (var buffer : this.scratch) buffer.destroy();
        for (var image : this.spare) image.destroy();
    }

    private void dispatchSolver(VkCommandBuffer commandBuffer, PipelineRole role, int bank,
            int x, int y, ByteBuffer push) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline(role));
            VK12.vkCmdBindDescriptorSets(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout, 0, stack.longs(this.solverDescriptors[bank].handle()), null);
            VK12.vkCmdPushConstants(commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(commandBuffer, x, y, 1);
        }
    }

    private void solverBarrier(VkCommandBuffer commandBuffer) {
        // Includes WAR between batches and ping-pong rounds, as well as RAW producer edges.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barriers = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(commandBuffer, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barriers, null, null);
        }
    }

}
