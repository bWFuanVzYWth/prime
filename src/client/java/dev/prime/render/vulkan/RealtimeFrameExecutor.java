// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import dev.prime.streamline.StreamlineFrameGeneration;
import dev.prime.streamline.StreamlineReflex;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Sole device executor for one interactive frame.
 *
 * <p>This class binds captured asset/scene residency, records Vulkan work, submits it and commits
 * backend-owned GPU histories.
 */
public final class RealtimeFrameExecutor implements Destroyable {
    private final VulkanContext context;
    private final TraceBackend backend;
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();
    private StreamlineInputPass streamlineInputs;
    private boolean destroyed;

    public RealtimeFrameExecutor(VulkanContext context, TraceBackend backend) {
        this.context = Objects.requireNonNull(context, "context");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public void execute(
            String debugLabel,
            RealtimeTracePipeline pipeline,
            SunShadowPipeline sunShadow,
            AtmospherePipeline atmosphere,
            MaterialTexturePages materialTextures,
            TerrainScene.ResidentSceneView scene,
            IntegratorFrameInput integrator,
            ReconstructionFrameParameters reconstruction,
            VulkanReconstructionProcessor processor,
            VulkanReconstructionProcessor.Frame processorFrame,
            VulkanImage output,
            VulkanImage stableRadiance,
            ImageDiagnosticSelection diagnostics,
            DisplayExposureDiagnostics exposureDiagnostics,
            VulkanGpuTextureView atlasView,
            List<TraceBackend.SceneTexture> sceneTextures,
            VulkanGpuTexture mainColor) {
        requireOpen();
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(processorFrame, "processorFrame");
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
            Objects.requireNonNull(integrator, "integrator");
            Objects.requireNonNull(reconstruction, "reconstruction");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(stableRadiance, "stableRadiance");
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(exposureDiagnostics, "exposureDiagnostics");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(sceneTextures, "sceneTextures");
            Objects.requireNonNull(mainColor, "mainColor");
            validateExtents(integrator, processor, output, stableRadiance, mainColor);
            submission.begin();

            this.context.beginTiming("realtime");

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
            VulkanImageTransitions.prepareTraceTextures(
                    commandBuffer, atlasView.texture(), sceneTextures);
            MaterialTexturePages.FrameToken materialFrame =
                    materialTextures.prepareAnimations(commandBuffer);
            completion.onCommit(4, () -> materialTextures.submitted(materialFrame));
            completion.onAbandon(2, failure -> ResourceCleanup.run(
                    () -> materialTextures.abandon(materialFrame), failure));
            if (this.backend.prepareShadowInteractions(commandBuffer, materialTextures.changedBaseTextures(materialFrame))) {
                completion.onCommit(6, this.backend::submittedShadowInteractions);
                completion.onAbandon(6, failure -> ResourceCleanup.run(this.backend::abandonShadowInteractions, failure));
            }
            // Atmosphere preparation traces the sun cache through the shared RT descriptor set.
            // Every image named by that set must have its declared layout before this call.
            long atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    sunShadow,
                    integrator,
                    scene,
                    false);
            completion.onCommit(2, () -> atmosphere.submitted(atmosphereFrame));
            completion.onAbandon(1, failure -> ResourceCleanup.run(
                    () -> atmosphere.abandon(atmosphereFrame), failure));
            pipeline.trace(commandBuffer, integrator, scene);
            boolean prepareFrameGeneration = StreamlineFrameGeneration.publish(
                    StreamlineReflex.currentFrameIndex(),
                    integrator.camera(),
                    reconstruction.jitter(),
                    reconstruction.reset(),
                    processor.rawFrame(),
                    output,
                    processor.displayWidth(),
                    processor.displayHeight(),
                    output.format(),
                    0);
            StreamlineInputPass frameGenerationInputs = null;
            if (prepareFrameGeneration) {
                frameGenerationInputs = this.ensureStreamlineInputs(
                        processor.rawFrame().viewZ(),
                        processor.rawFrame().visibleHistoryPosition(),
                        processor.rawFrame().reconstructionControl(),
                        processor.rawFrame().hasExactTransmissiveVisibleHistory());
                prepareFrameGeneration = StreamlineFrameGeneration.recordInputs(
                        commandBuffer, frameGenerationInputs);
            }
            processor.captureRendererDiagnostic(
                    commandBuffer, this.imageInitialization, diagnostics.renderer());
            processor.record(
                    commandBuffer,
                    processorFrame,
                    this.imageInitialization);
            processor.presentRendererDiagnostic(commandBuffer, diagnostics.renderer());
            DisplayExposureDiagnostics.Capture exposureCapture = exposureDiagnostics.record(
                    commandBuffer, processor.displayExposureStateBuffer());
            if (exposureCapture != null) {
                completion.onCommit(1, () -> exposureDiagnostics.submitted(
                        exposureCapture));
                completion.onAbandon(5, failure -> ResourceCleanup.run(
                        () -> exposureDiagnostics.abandon(exposureCapture), failure));
            }
            VulkanImageTransitions.finishTraceTextureReads(
                    commandBuffer, atlasView.texture(), sceneTextures);
            submission.copyToMinecraft(
                    commandBuffer,
                    output,
                    mainColor,
                    processor.displayWidth(),
                    processor.displayHeight());
            if (prepareFrameGeneration) {
                if (StreamlineFrameGeneration.prepare(
                        commandBuffer, frameGenerationInputs, output)) {
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
            this.context.endTiming("realtime");
            HdrPresentation.publish(this.context, processor.hdrDisplayOutput(), output);
            // A normal return transfers command/resource ownership and advances Prime histories.
            completion.commit();
        } catch (RuntimeException exception) {
            throw completion.abandon(ResourceCleanup.run(
                    () -> this.context.abandonTiming("realtime"), exception));
        }
    }

    private StreamlineInputPass ensureStreamlineInputs(
            VulkanImage depth,
            VulkanImage visibleHistoryPosition,
            VulkanImage control,
            boolean exactTransmissiveHistory) {
        StreamlineInputPass current = this.streamlineInputs;
        if (current != null && current.matches(
                depth,
                visibleHistoryPosition,
                control,
                exactTransmissiveHistory)) {
            return current;
        }
        StreamlineInputPass replacement =
                StreamlineInputPass.create(
                        this.context,
                        depth,
                        visibleHistoryPosition,
                        control,
                        exactTransmissiveHistory);
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
        StreamlineInputPass current = this.streamlineInputs;
        this.streamlineInputs = null;
        this.destroyed = true;
        if (current != null) current.destroy();
    }

    private static void validateExtents(
            IntegratorFrameInput integrator,
            VulkanReconstructionProcessor processor,
            VulkanImage output,
            VulkanImage stableRadiance,
            VulkanGpuTexture mainColor) {
        if (integrator.width() != processor.renderWidth()
                || integrator.height() != processor.renderHeight()
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
