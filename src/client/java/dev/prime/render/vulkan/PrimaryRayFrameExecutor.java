package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.FrameCamera;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Records and submits one primary-ray frame without reconstruction or lighting passes. */
public final class PrimaryRayFrameExecutor implements Destroyable {
    private final VulkanContext context;
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();
    private boolean destroyed;

    public PrimaryRayFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            PrimaryRayTracingPipeline pipeline,
            TerrainScene.ResidentSceneView scene,
            FrameCamera camera,
            MaterialTexturePages materialTextures,
            VulkanGpuTextureView atlasView,
            List<TraceBackend.SceneTexture> sceneTextures,
            VulkanImage output,
            VulkanGpuTexture mainColor) {
        if (this.destroyed) {
            throw new IllegalStateException("Primary-ray frame executor is destroyed");
        }
        VulkanFrameSubmission submission =
                new VulkanFrameSubmission(this.imageInitialization);
        FrameCompletion completion = new FrameCompletion();
        completion.onCommit(0, submission::submitted);
        completion.onAbandon(0, submission::abandon);
        try {
            submission.begin();
            var encoder = this.context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> "Prime textured primary rays");
            VulkanImageTransitions.preparePrimaryRayOutput(
                    commandBuffer, this.imageInitialization, output);
            VulkanImageTransitions.prepareTraceTextures(
                    commandBuffer, atlasView.texture(), sceneTextures);
            MaterialTexturePages.FrameToken materialFrame =
                    materialTextures.prepareAnimations(commandBuffer);
            completion.onCommit(1, () -> materialTextures.submitted(materialFrame));
            completion.onAbandon(1, failure -> ResourceCleanup.run(
                    () -> materialTextures.abandon(materialFrame), failure));
            pipeline.trace(commandBuffer, camera, scene);
            VulkanImageTransitions.finishTraceTextureReads(
                    commandBuffer, atlasView.texture(), sceneTextures);
            submission.copyPrimaryRayToMinecraft(
                    commandBuffer,
                    output,
                    mainColor,
                    output.width(),
                    output.height());
            this.context.device().instance().debug().endDebugGroup(commandBuffer);
            submission.submit(
                    encoder,
                    commandBuffer,
                    "end Prime primary-ray command buffer");
            completion.acceptedBySubmission();
            completion.commit();
        } catch (RuntimeException exception) {
            throw completion.abandon(exception);
        }
    }

    @Override
    public void destroy() {
        this.destroyed = true;
    }
}
