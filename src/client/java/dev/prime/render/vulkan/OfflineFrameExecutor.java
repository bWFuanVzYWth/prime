package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.DisplaySettings;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Device side effects for one native offline sample. */
public final class OfflineFrameExecutor {
    private final VulkanContext context;
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();

    public OfflineFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            OfflineRayTracingPipeline pipeline,
            SunShadowPipeline sunShadow,
            AtmospherePipeline atmosphere,
            MaterialTexturePages materialTextures,
            TerrainScene.ResidentSceneView scene,
            IntegratorFrameInput integrator,
            DisplaySettings.Snapshot displaySettings,
            VulkanImage displayOutput,
            VulkanImage runningMean,
            DisplayTransformPass display,
            VulkanGpuTextureView atlasView,
            List<TraceBackend.SceneTexture> sceneTextures,
            VulkanGpuTexture mainColor) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(sunShadow, "sunShadow");
        Objects.requireNonNull(atmosphere, "atmosphere");
        Objects.requireNonNull(materialTextures, "materialTextures");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(integrator, "integrator");
        Objects.requireNonNull(displaySettings, "displaySettings");
        Objects.requireNonNull(displayOutput, "displayOutput");
        Objects.requireNonNull(runningMean, "runningMean");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(atlasView, "atlasView");
        Objects.requireNonNull(sceneTextures, "sceneTextures");
        Objects.requireNonNull(mainColor, "mainColor");
        validateExtents(
                integrator,
                displayOutput,
                runningMean,
                mainColor);

        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer =
                encoder.allocateAndBeginTransientCommandBuffer();
        VulkanFrameSubmission submission =
                new VulkanFrameSubmission(this.imageInitialization);
        FrameCompletion completion = new FrameCompletion();
        completion.onCommit(0, submission::submitted);
        completion.onAbandon(0, submission::abandon);
        try {
            submission.begin();
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer,
                    () -> "Prime offline path accumulation");
            VulkanImageTransitions.prepareOutputForComposite(
                    commandBuffer, this.imageInitialization, displayOutput);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, this.imageInitialization, runningMean);
            VulkanImageTransitions.prepareTraceTextures(
                    commandBuffer, atlasView.texture(), sceneTextures);
            MaterialTexturePages.FrameToken materialFrame =
                    materialTextures.prepareAnimations(commandBuffer);
            completion.onCommit(2, () -> materialTextures.submitted(materialFrame));
            completion.onAbandon(2, failure -> ResourceCleanup.run(
                    () -> materialTextures.abandon(materialFrame), failure));
            // The sun-cache raygen borrows the shared scene descriptor set prepared above.
            long atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    sunShadow,
                    integrator,
                    scene,
                    true);
            completion.onCommit(1, () -> atmosphere.submitted(atmosphereFrame));
            completion.onAbandon(1, failure -> ResourceCleanup.run(
                    () -> atmosphere.abandon(atmosphereFrame), failure));
            pipeline.trace(
                    commandBuffer, integrator, scene);
            VulkanImageTransitions.prepareOfflineDisplay(
                    commandBuffer, runningMean);
            display.recordFrozen(
                    commandBuffer, displaySettings, this.imageInitialization);
            VulkanImageTransitions.finishTraceTextureReads(
                    commandBuffer, atlasView.texture(), sceneTextures);
            submission.copyToMinecraft(
                    commandBuffer,
                    displayOutput,
                    mainColor,
                    integrator.width(),
                    integrator.height());
            this.context.device().instance().debug().endDebugGroup(
                    commandBuffer);
            submission.submit(
                    encoder,
                    commandBuffer,
                    "end Prime offline accumulation command buffer");
            completion.acceptedBySubmission();
            HdrPresentation.publish(this.context, display.hdrOutput(), displayOutput);
            // Prime histories advance only after Minecraft's open host submission accepts it.
            completion.commit();
        } catch (RuntimeException exception) {
            throw completion.abandon(exception);
        }
    }

    private static void validateExtents(
            IntegratorFrameInput integrator,
            VulkanImage displayOutput,
            VulkanImage runningMean,
            VulkanGpuTexture mainColor) {
        int width = integrator.width();
        int height = integrator.height();
        if (displayOutput.width() != width
                || displayOutput.height() != height
                || runningMean.width() != width
                || runningMean.height() != height
                || mainColor.getWidth(0) != width
                || mainColor.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Offline device resources do not match the semantic frame extent");
        }
    }
}
