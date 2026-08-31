package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.RealtimeFramePlan;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import dev.prime.streamline.StreamlineFrameGeneration;
import dev.prime.streamline.StreamlineReflex;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Sole device executor for one planned interactive frame.
 *
 * <p>All frame-scalar semantics are already fixed by {@link RealtimeFramePlan}. This class only
 * binds captured asset/scene residency, records Vulkan work, submits it and commits backend-owned
 * GPU histories.
 */
public final class RealtimeFrameExecutor implements Destroyable {
    private final VulkanContext context;
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();
    private StreamlineInputFlipPass streamlineInputs;
    private boolean destroyed;

    public RealtimeFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            String debugLabel,
            RealtimeIntegratorPipeline pipeline,
            SunShadowPipeline sunShadow,
            AtmospherePipeline atmosphere,
            MaterialTexturePages materialTextures,
            TerrainScene.ResidentSceneView scene,
            RealtimeFramePlan plan,
            VulkanReconstructionProcessor processor,
            VulkanReconstructionProcessor.Frame processorFrame,
            VulkanImage output,
            VulkanImage stableRadiance,
            ImageDiagnosticSelection diagnostics,
            DisplayExposureDiagnostics exposureDiagnostics,
            RendererDataRangeDiagnostics rangeDiagnostics,
            VulkanGpuTextureView atlasView,
            List<TraceBackend.SceneTexture> sceneTextures,
            long textureRevision,
            VulkanGpuTexture mainColor) {
        requireOpen();
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(processorFrame, "processorFrame");
        long atmosphereFrame = 0L;
        MaterialTexturePages.FrameToken materialFrame = null;
        DisplayExposureDiagnostics.Capture exposureCapture = null;
        RendererDataRangeDiagnostics.Capture rangeCapture = null;
        VulkanFrameSubmission submission =
                new VulkanFrameSubmission(this.imageInitialization);
        FrameCompletion completion = new FrameCompletion();
        completion.onCommit(0, submission::submitted);
        completion.onAbandon(0, submission::abandon);
        completion.onCommit(3, () -> processor.submitted(processorFrame));
        completion.onAbandon(3, failure -> ResourceCleanup.run(
                () -> processor.abandon(processorFrame), failure));
        try {
            Objects.requireNonNull(debugLabel, "debugLabel");
            Objects.requireNonNull(pipeline, "pipeline");
            Objects.requireNonNull(sunShadow, "sunShadow");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(materialTextures, "materialTextures");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(stableRadiance, "stableRadiance");
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(exposureDiagnostics, "exposureDiagnostics");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(sceneTextures, "sceneTextures");
            Objects.requireNonNull(mainColor, "mainColor");
            plan.requireSceneRevision(scene.revision());
            plan.requireTextureRevision(textureRevision);
            validateExtents(plan, processor, output, stableRadiance, mainColor);
            submission.begin();

            var encoder = this.context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> debugLabel);
            VulkanImageTransitions.prepareOutputForComposite(
                    commandBuffer, this.imageInitialization, output);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, this.imageInitialization, stableRadiance);
            processor.prepareForRayTrace(
                    commandBuffer, this.imageInitialization);
            VulkanImageTransitions.prepareAtlasForTrace(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.prepareSceneTexturesForTrace(
                    commandBuffer, sceneTextures);
            materialFrame = materialTextures.prepareAnimations(commandBuffer);
            MaterialTexturePages.FrameToken trackedMaterialFrame = materialFrame;
            completion.onCommit(4, () -> materialTextures.submitted(trackedMaterialFrame));
            completion.onAbandon(2, failure -> ResourceCleanup.run(
                    () -> materialTextures.abandon(trackedMaterialFrame), failure));
            // Atmosphere preparation traces the sun cache through the shared RT descriptor set.
            // Every image named by that set must have its declared layout before this call.
            atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    sunShadow,
                    plan.integrator(),
                    scene,
                    false);
            long trackedAtmosphereFrame = atmosphereFrame;
            completion.onCommit(2, () -> atmosphere.submitted(trackedAtmosphereFrame));
            completion.onAbandon(1, failure -> ResourceCleanup.run(
                    () -> atmosphere.abandon(trackedAtmosphereFrame), failure));
            pipeline.trace(commandBuffer, plan.integrator(), scene);
            boolean prepareFrameGeneration = StreamlineFrameGeneration.publish(
                    StreamlineReflex.currentFrameIndex(),
                    plan.integrator().camera(),
                    plan.jitter(),
                    plan.reconstructionReset(),
                    processor.rawFrame(),
                    output,
                    processor.displayWidth(),
                    processor.displayHeight(),
                    output.format(),
                    0);
            StreamlineInputFlipPass frameGenerationInputs = null;
            if (prepareFrameGeneration) {
                frameGenerationInputs = this.ensureStreamlineInputs(
                        processor.rawFrame().viewZ(),
                        processor.rawFrame().transportScratch(),
                        processor.rawFrame().reconstructionControl(),
                        output);
                prepareFrameGeneration = StreamlineFrameGeneration.recordInputs(
                        commandBuffer, frameGenerationInputs);
            }
            processor.captureRendererDiagnostic(
                    commandBuffer, this.imageInitialization, diagnostics.renderer());
            processor.record(
                    commandBuffer,
                    processorFrame,
                    plan.reconstruction(),
                    this.imageInitialization);
            if (rangeDiagnostics != null
                    && processor.mode()
                            != dev.prime.render.post.PostProcessingMode.DISABLED) {
                rangeCapture = rangeDiagnostics.record(
                        commandBuffer,
                        processor.rawFrame().viewZ(),
                        processor.rawFrame().reconstructionMotion(),
                        plan.reconstructionReset());
                RendererDataRangeDiagnostics.Capture trackedRangeCapture = rangeCapture;
                completion.onCommit(6, () -> rangeDiagnostics.submitted(
                        trackedRangeCapture));
                completion.onAbandon(6, failure -> ResourceCleanup.run(
                        () -> rangeDiagnostics.abandon(trackedRangeCapture), failure));
            }
            processor.presentRendererDiagnostic(commandBuffer, diagnostics.renderer());
            exposureCapture = exposureDiagnostics.record(
                    commandBuffer, processor.displayExposureStateBuffer());
            DisplayExposureDiagnostics.Capture trackedExposureCapture = exposureCapture;
            completion.onCommit(1, () -> exposureDiagnostics.submitted(
                    trackedExposureCapture));
            completion.onAbandon(5, failure -> ResourceCleanup.run(
                    () -> exposureDiagnostics.abandon(trackedExposureCapture), failure));
            VulkanImageTransitions.finishAtlasRead(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.finishSceneTextureReads(
                    commandBuffer, sceneTextures);
            submission.copyToMinecraft(
                    commandBuffer,
                    output,
                    mainColor,
                    processor.displayWidth(),
                    processor.displayHeight());
            if (prepareFrameGeneration) {
                frameGenerationInputs.recordColor(commandBuffer);
                if (StreamlineFrameGeneration.prepare(commandBuffer, frameGenerationInputs)) {
                    int frameGenerationIndex = StreamlineReflex.currentFrameIndex();
                    completion.onCommit(5, () -> StreamlineFrameGeneration.submitted(
                            frameGenerationIndex));
                    completion.onAbandon(4, failure -> ResourceCleanup.run(
                            () -> StreamlineFrameGeneration.abandon(frameGenerationIndex),
                            failure));
                }
            }
            this.context.device().instance().debug().endDebugGroup(
                    commandBuffer);
            submission.submit(
                    encoder,
                    commandBuffer,
                    "end Prime realtime command buffer");
            completion.acceptedBySubmission();
            HdrPresentation.publish(this.context, processor.hdrDisplayOutput(), output);
            // A normal return transfers command/resource ownership and advances Prime histories.
            completion.commit();
        } catch (RuntimeException exception) {
            throw completion.abandon(exception);
        }
    }

    private StreamlineInputFlipPass ensureStreamlineInputs(
            VulkanImage depth,
            VulkanImage visibleDelta,
            VulkanImage control,
            VulkanImage color) {
        StreamlineInputFlipPass current = this.streamlineInputs;
        if (current != null && current.matches(depth, visibleDelta, control, color)) {
            return current;
        }
        StreamlineInputFlipPass replacement =
                StreamlineInputFlipPass.create(
                        this.context, depth, visibleDelta, control, color);
        this.streamlineInputs = replacement;
        if (current != null) {
            this.context.defer(current);
        }
        return replacement;
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("Realtime frame executor is destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        StreamlineInputFlipPass current = this.streamlineInputs;
        this.streamlineInputs = null;
        this.destroyed = true;
        if (current != null) current.destroy();
    }

    private static void validateExtents(
            RealtimeFramePlan plan,
            VulkanReconstructionProcessor processor,
            VulkanImage output,
            VulkanImage stableRadiance,
            VulkanGpuTexture mainColor) {
        if (plan.integrator().width() != processor.renderWidth()
                || plan.integrator().height() != processor.renderHeight()
                || stableRadiance.width() != processor.renderWidth()
                || stableRadiance.height() != processor.renderHeight()
                || output.width() != processor.displayWidth()
                || output.height() != processor.displayHeight()
                || mainColor.getWidth(0) != processor.displayWidth()
                || mainColor.getHeight(0) != processor.displayHeight()) {
            throw new IllegalArgumentException(
                    "Realtime device resources do not match the semantic frame extents");
        }
    }
}
