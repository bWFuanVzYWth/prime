// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.AerialEpipolarMapping;
import dev.prime.render.AtmosphereCoordinates;
import dev.prime.render.FrameCamera;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.SunDirection;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
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
 * model and are generated once. The sky table changes with eye altitude and sun elevation;
 * aerial perspective also changes with the relative camera projection and complete sun direction.
 * Atmosphere dispatch data travels through push constants. A separately synchronized 48-byte
 * uniform publishes the active directional-shadow bank and basis to later transport dispatches.
 */
public final class AtmospherePipeline implements Destroyable {
    public static final float AERIAL_MAX_DISTANCE_KM = ShaderAbi.ATMOSPHERE_AERIAL_MAX_DISTANCE_KM;

    private static final int PUSH_CONSTANT_SIZE = 128;
    private enum ImageRole {
        TRANSMITTANCE_LOW,
        TRANSMITTANCE_HIGH,
        MULTI_SCATTERING_LOW,
        MULTI_SCATTERING_HIGH,
        SKY_VIEW,
        AERIAL_RADIANCE,
        AERIAL_TRANSMITTANCE,
        CAMERA_TRANSMITTANCE
    }
    private static final int IMAGE_COUNT = ImageRole.values().length;
    private static final int PHASE_LUT_BINDING = 7;
    private static final int SUN_SHADOW_BINDING = 8;
    private static final int SUN_SHADOW_HIERARCHY_BINDING =
            SUN_SHADOW_BINDING
                    + SunShadowClipmap.BANK_COUNT * SunShadowClipmap.CASCADE_COUNT;
    private static final int SUN_SHADOW_HIERARCHY_COUNT = SunShadowClipmap.CASCADE_COUNT;
    private static final int SUN_SHADOW_HIERARCHY_WIDTH = SunShadowClipmap.RESOLUTION;
    private static final int SUN_SHADOW_HIERARCHY_HEIGHT = SunShadowClipmap.RESOLUTION;
    private static final int CAMERA_TRANSMITTANCE_BINDING =
            SUN_SHADOW_HIERARCHY_BINDING + SUN_SHADOW_HIERARCHY_COUNT;
    private static final int BINDING_COUNT = CAMERA_TRANSMITTANCE_BINDING + 1;
    private static final int PHASE_LUT_BYTE_SIZE = 131_072;
    private static final int AERIAL_KEY_SIZE = 21;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private enum PipelineRole {
        TRANSMITTANCE(
                GeneratedShaderPrograms.resource("atmosphere_transmittance"),
                "Prime atmosphere transmittance pipeline"),
        MULTI_SCATTERING(
                GeneratedShaderPrograms.resource("atmosphere_multi_scattering"),
                "Prime atmosphere multiple scattering pipeline"),
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
    private final VulkanImage[] images;
    private final SunShadowClipmap sunShadow;
    private final VulkanImage[] sunShadowHierarchies;
    private final VulkanBuffer sunShadowQuery;
    private final VulkanBuffer phaseLut;
    private final long descriptorSetLayout;
    private final VulkanDescriptors.BoundSet descriptors;
    private final long pipelineLayout;
    private final long[] pipelines;
    private final VulkanImage[] initialImages;
    private final VulkanImage[] transmittanceImages;
    private final VulkanImage[] multiScatteringImages;
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

    public AtmospherePipeline(VulkanContext context) {
        this.context = context;
        VulkanImage[] images = new VulkanImage[IMAGE_COUNT];
        VulkanImage[] sunShadowHierarchies =
                new VulkanImage[SUN_SHADOW_HIERARCHY_COUNT];
        SunShadowClipmap newSunShadow = null;
        VulkanBuffer newSunShadowQuery = null;
        VulkanBuffer newPhaseLut = null;
        long newDescriptorSetLayout = 0L;
        VulkanDescriptors.BoundSet newDescriptors = null;
        long newPipelineLayout = 0L;
        long[] pipelines = new long[PipelineRole.values().length];
        try {
            images[ImageRole.TRANSMITTANCE_LOW.ordinal()] = context.createAtmosphereImage2D(
                    256, 64, "Prime atmosphere transmittance low");
            images[ImageRole.TRANSMITTANCE_HIGH.ordinal()] = context.createAtmosphereImage2D(
                    256, 64, "Prime atmosphere transmittance high");
            images[ImageRole.MULTI_SCATTERING_LOW.ordinal()] = context.createAtmosphereImage2D(
                    64, 64, "Prime atmosphere multiple scattering low");
            images[ImageRole.MULTI_SCATTERING_HIGH.ordinal()] = context.createAtmosphereImage2D(
                    64, 64, "Prime atmosphere multiple scattering high");
            images[ImageRole.SKY_VIEW.ordinal()] = context.createAtmosphereImage2D(
                    256, 256, "Prime atmosphere sky view");
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
            newPhaseLut = createPhaseLut(context);
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
                        newPhaseLut);
            }
            this.images = images;
            this.sunShadow = newSunShadow;
            this.sunShadowHierarchies = sunShadowHierarchies;
            this.sunShadowQuery = newSunShadowQuery;
            this.phaseLut = newPhaseLut;
            this.descriptorSetLayout = newDescriptorSetLayout;
            this.descriptors = newDescriptors;
            this.pipelineLayout = newPipelineLayout;
            this.pipelines = pipelines;
            this.initialImages = new VulkanImage[
                    IMAGE_COUNT + SUN_SHADOW_HIERARCHY_COUNT];
            System.arraycopy(images, 0, this.initialImages, 0, IMAGE_COUNT);
            System.arraycopy(
                    sunShadowHierarchies,
                    0,
                    this.initialImages,
                    IMAGE_COUNT,
                    SUN_SHADOW_HIERARCHY_COUNT);
            this.transmittanceImages = new VulkanImage[] {
                image(ImageRole.TRANSMITTANCE_LOW), image(ImageRole.TRANSMITTANCE_HIGH)
            };
            this.multiScatteringImages = new VulkanImage[] {
                image(ImageRole.MULTI_SCATTERING_LOW), image(ImageRole.MULTI_SCATTERING_HIGH)
            };
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
            for (int index = pipelines.length - 1; index >= 0; index--) {
                destroyPipeline(context, pipelines[index]);
            }
            if (newPipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), newPipelineLayout, null);
            }
            if (newDescriptorSetLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), newDescriptorSetLayout, null);
            }
            if (newPhaseLut != null) {
                newPhaseLut.destroy();
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
        dispatch(commandBuffer, pipeline(PipelineRole.TRANSMITTANCE), 32, 8, 1, null);
        computeWriteBarrier(
                commandBuffer,
                this.transmittanceImages,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        // Split the two spectral groups along dispatch Z to keep the one-time invocation below
        // Windows GPU-timeout risk while preserving the reference's 256 directions × 128 steps.
        dispatch(commandBuffer, pipeline(PipelineRole.MULTI_SCATTERING), 8, 8, 2, null);
        computeWriteBarrier(
                commandBuffer,
                this.multiScatteringImages,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
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
            eyeRadiusKm = AtmosphereCoordinates.eyeRadiusKm(camera.y());
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
                    dispatch(commandBuffer, pipeline(PipelineRole.SKY), 32, 32, 1, pushConstants);
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
            this.phaseLut.destroy();
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
            int binding = index == ImageRole.CAMERA_TRANSMITTANCE.ordinal()
                    ? CAMERA_TRANSMITTANCE_BINDING : index;
            VulkanDescriptors.layoutBinding(
                    bindings.get(binding), binding,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, COMPUTE_STAGE);
        }
        VulkanDescriptors.layoutBinding(
                bindings.get(PHASE_LUT_BINDING), PHASE_LUT_BINDING,
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
            VulkanBuffer phaseLut) {
        VulkanDescriptors.Binding[] bindings =
                new VulkanDescriptors.Binding[BINDING_COUNT];
        for (int index = 0; index < IMAGE_COUNT; index++) {
            int binding = index == ImageRole.CAMERA_TRANSMITTANCE.ordinal()
                    ? CAMERA_TRANSMITTANCE_BINDING : index;
            bindings[binding] = VulkanDescriptors.image(
                    binding,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    images[index].view(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL);
        }
        bindings[PHASE_LUT_BINDING] = VulkanDescriptors.buffer(
                PHASE_LUT_BINDING,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                phaseLut.handle(),
                0L,
                phaseLut.size());
        for (int bank = 0; bank < SunShadowClipmap.BANK_COUNT; bank++) {
            for (int cascade = 0; cascade < SunShadowClipmap.CASCADE_COUNT; cascade++) {
                int index = bank * SunShadowClipmap.CASCADE_COUNT + cascade;
                int binding = SUN_SHADOW_BINDING + index;
                bindings[binding] = VulkanDescriptors.image(
                        binding,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        sunShadow.depth(bank, cascade).view(),
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
                context, stack, descriptorSetLayout, "Prime atmosphere", bindings);
    }

    private static VulkanBuffer createPhaseLut(VulkanContext context) {
        // The immutable payload is the reference engine's little-endian AoS table:
        // 2048 entries × four species × RGBA wavelengths. Keeping it compressed textual source
        // avoids platform-dependent generation while the hash test guards its physical meaning.
        byte[] bytes;
        try (InputStream encoded = AtmospherePipeline.class.getResourceAsStream(
                        "/prime/atmosphere/phase_lut.bin.gz.b64")) {
            if (encoded == null) {
                throw new IllegalStateException("Missing Prime atmosphere phase LUT");
            }
            try (InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                    GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
                bytes = decompressed.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Prime atmosphere phase LUT", exception);
        }
        if (bytes.length != PHASE_LUT_BYTE_SIZE) {
            throw new IllegalStateException(
                    "Unexpected Prime atmosphere phase LUT size " + bytes.length);
        }
        VulkanBuffer buffer = context.createBuffer(
                bytes.length,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true,
                "Prime atmosphere phase LUT");
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

}
