// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.dlss;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.RrInputView;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.DisplayTransformPass;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.VulkanSync;
import dev.prime.render.post.nrd.NrdCameraTransform;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.ResolvedReconstruction;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Prime's complete real-time path-tracing to DLSS Ray Reconstruction frame boundary. */
public final class DlssRrPostProcessor extends VulkanReconstructionProcessor {
    private final DlssRrTargets targets;
    private final DlssRrPreparePass preparePass;
    private final DlssRrStarsPass starsPass;
    private final DlssRrNative.Feature feature;
    private final DisplayTransformPass displayTransform;
    private DlssRrDebugPass debugPass;
    private final Matrix4f ngxProjection = new Matrix4f();

    private DlssRrPostProcessor(
            VulkanContext context,
            ResolvedReconstruction selection,
            DlssRrTargets targets,
            DlssRrPreparePass preparePass,
            DlssRrStarsPass starsPass,
            DlssRrNative.Feature feature,
            DisplayTransformPass displayTransform,
            VulkanImage displayOutput,
            VulkanImage stableRadiance) {
        super(
                context,
                selection,
                stableRadiance,
                displayOutput);
        this.targets = targets;
        this.preparePass = preparePass;
        this.starsPass = starsPass;
        this.feature = feature;
        this.displayTransform = displayTransform;
    }

    public static DlssRrPostProcessor create(
            VulkanContext context,
            DlssRrNative.Context ngxContext,
            AtmospherePipeline atmosphere,
            VulkanImage starmap,
            long starmapSampler,
            VulkanImage accumulation,
            VulkanImage displayOutput,
            ResolvedReconstruction selection) {
        int renderWidth = selection.extent().width();
        int renderHeight = selection.extent().height();
        int displayWidth = selection.displayExtent().width();
        int displayHeight = selection.displayExtent().height();
        DlssRrTargets targets = null;
        DlssRrPreparePass preparePass = null;
        DlssRrStarsPass starsPass = null;
        DlssRrNative.Feature feature = null;
        DisplayTransformPass displayTransform = null;
        try {
            targets = DlssRrTargets.create(
                    context, renderWidth, renderHeight, displayWidth, displayHeight);
            preparePass = DlssRrPreparePass.create(context, targets, accumulation, atmosphere);
            starsPass = DlssRrStarsPass.create(context, targets.rrOutput(), atmosphere, starmap,
                    starmapSampler, targets.reconstructionControl());
            displayTransform = DisplayTransformPass.createRealtime(
                    context, targets.rrOutput(), targets, displayOutput);
            var encoder = context.commandEncoder();
            VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
            feature = ngxContext.createFeature(
                    commandBuffer,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    selection.quality());
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer), "end DLSS RR feature creation command buffer");
            encoder.execute(commandBuffer);
            context.awaitIdle();
            return new DlssRrPostProcessor(
                    context,
                    selection,
                    targets,
                    preparePass,
                    starsPass,
                    feature,
                    displayTransform,
                    displayOutput,
                    accumulation);
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.run(context::awaitIdle, exception);
            failure = ResourceCleanup.close(feature, failure);
            failure = ResourceCleanup.destroy(displayTransform, failure);
            failure = ResourceCleanup.destroy(preparePass, failure);
            failure = ResourceCleanup.destroy(starsPass, failure);
            failure = ResourceCleanup.destroy(targets, failure);
            throw failure;
        }
    }

    @Override public DlssRrTargets rawFrame() { return this.targets; }
    @Override public VulkanImage linearHdrOutput() { return this.targets.rrOutput(); }
    @Override public VulkanImage hdrDisplayOutput() { return this.displayTransform.hdrOutput(); }
    @Override public long displayExposureStateBuffer() {
        return this.displayTransform.exposureState().handle();
    }
    @Override
    public Frame beginFrame(
            ReconstructionFrameParameters parameters,
            ReconstructionDebugSettings debugSettings) {
        requireOpen();
        return new FrameToken(
                this,
                parameters,
                debugSettings.images().rr(),
                debugSettings.rrResponsivity());
    }

    public void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        requireOpen();
        this.targets.prepareForRayTrace(commandBuffer, initialization);
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            VulkanImageInitializationBatch initialization) {
        requireOpen();
        if (!(frame instanceof FrameToken token)) {
            throw new IllegalArgumentException("DLSS RR received another processor's frame token");
        }
        ReconstructionFrameParameters parameters = claimSubmittedFrame(token);
        this.preparePass.record(
                commandBuffer,
                parameters.camera(),
                parameters.historyCamera(),
                parameters.jitter(),
                parameters.sunDirection(),
                parameters.sunRadianceMultiplier(),
                token.responsivity);
        NrdCameraTransform.projectionForNrd(
                parameters.camera().projection(), this.ngxProjection);
        this.feature.evaluate(
                commandBuffer,
                new DlssRrNative.Evaluation(
                        renderWidth(),
                        renderHeight(),
                        parameters.jitter(),
                        renderWidth(),
                        renderHeight(),
                        parameters.reset(),
                        parameters.deltaMilliseconds(),
                        parameters.camera().viewRotation(),
                        this.ngxProjection,
                        this.targets.material(),
                        this.targets.specularMaterial(),
                        this.targets.rrNormalRoughness(),
                        this.targets.inputColor(),
                        this.targets.rrOutput(),
                        this.targets.viewZ(),
                        this.targets.motion(),
                        null,
                        this.targets.specularHitDistance(),
                        this.targets.responsivity()));
        this.starsPass.record(commandBuffer, parameters);
        this.displayTransform.record(
                commandBuffer,
                parameters.deltaMilliseconds() * 0.001F,
                parameters.reset(),
                false,
                parameters.display(),
                initialization);
        if (token.debugView.active()) {
            allCommandsToCompute(commandBuffer);
            this.debugPass().record(commandBuffer, token.debugView);
        }
    }

    private static void allCommandsToCompute(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private DlssRrDebugPass debugPass() {
        if (this.debugPass == null) {
            this.debugPass = DlssRrDebugPass.create(
                    context(),
                    this.targets,
                    displayOutput(),
                    this.displayTransform.hdrOutput());
        }
        return this.debugPass;
    }

    @Override
    public void destroy() {
        if (destroyed()) return;
        // Do not make a failed wait terminal: no child handle is safe to release until all NGX
        // work has retired, and a later caller must be able to retry this ownership boundary.
        context().awaitIdle();
        RuntimeException failure = ResourceCleanup.close(this.feature, null);
        failure = destroyRendererDiagnostic(failure);
        failure = ResourceCleanup.destroy(this.debugPass, failure);
        failure = ResourceCleanup.destroy(this.displayTransform, failure);
        failure = ResourceCleanup.destroy(this.preparePass, failure);
        failure = ResourceCleanup.destroy(this.starsPass, failure);
        failure = ResourceCleanup.destroy(this.targets, failure);
        finishDestroy(failure);
    }

    private static final class FrameToken extends SubmittedFrameToken {
        private final RrInputView debugView;
        private final float responsivity;

        private FrameToken(
                DlssRrPostProcessor owner,
                ReconstructionFrameParameters parameters,
                RrInputView debugView,
                float responsivity) {
            super(owner, parameters);
            this.debugView = debugView;
            this.responsivity = responsivity;
        }
    }
}
