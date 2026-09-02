package dev.prime.render.vulkan.dlss;

import dev.prime.render.FrameCamera;
import dev.prime.render.DisplaySettings;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.SunDirection;
import dev.prime.render.diagnostic.RrInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrResponsivity;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrame;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.SubmittedFrame;
import dev.prime.render.post.TemporalReconstructionState;
import dev.prime.render.post.ReconstructionFrameHistory;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.DisplayTransformPass;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.VulkanSync;
import dev.prime.render.vulkan.RendererImageDebugPass;
import dev.prime.render.post.nrd.NrdCameraTransform;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import java.util.Objects;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Prime's complete real-time path-tracing to DLSS Ray Reconstruction frame boundary. */
public final class DlssRrPostProcessor implements VulkanReconstructionProcessor {
    private final VulkanContext context;
    private final DlssRrNative.Context ngxContext;
    private final ReconstructionQualityMode quality;
    private final int renderWidth;
    private final int renderHeight;
    private final int displayWidth;
    private final int displayHeight;
    private final DlssRrTargets targets;
    private final DlssRrPreparePass preparePass;
    private final DlssRrNative.Feature feature;
    private final DisplayTransformPass displayTransform;
    private final VulkanImage displayOutput;
    private final VulkanImage stableRadiance;
    private DlssRrDebugPass debugPass;
    private RendererImageDebugPass rendererDebugPass;
    private final Matrix4f ngxProjection = new Matrix4f();
    private final ReconstructionFrameHistory history =
            new ReconstructionFrameHistory();
    private boolean destroyed;

    private DlssRrPostProcessor(
            VulkanContext context,
            DlssRrNative.Context ngxContext,
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            DlssRrTargets targets,
            DlssRrPreparePass preparePass,
            DlssRrNative.Feature feature,
            DisplayTransformPass displayTransform,
            VulkanImage displayOutput,
            VulkanImage stableRadiance) {
        this.context = context;
        this.ngxContext = ngxContext;
        this.quality = quality;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.targets = targets;
        this.preparePass = preparePass;
        this.feature = feature;
        this.displayTransform = displayTransform;
        this.displayOutput = displayOutput;
        this.stableRadiance = stableRadiance;
    }

    public static DlssRrPostProcessor create(
            VulkanContext context,
            DlssRrNative.Context ngxContext,
            AtmospherePipeline atmosphere,
            VulkanImage accumulation,
            VulkanImage displayOutput,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            ReconstructionQualityMode quality) {
        DlssRrTargets targets = null;
        DlssRrPreparePass preparePass = null;
        DlssRrNative.Feature feature = null;
        DisplayTransformPass displayTransform = null;
        try {
            targets = DlssRrTargets.create(
                    context, renderWidth, renderHeight, displayWidth, displayHeight);
            preparePass = DlssRrPreparePass.create(context, targets, accumulation, atmosphere);
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
                    quality);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer), "end DLSS RR feature creation command buffer");
            encoder.execute(commandBuffer);
            context.awaitIdle();
            return new DlssRrPostProcessor(
                    context,
                    ngxContext,
                    quality,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    targets,
                    preparePass,
                    feature,
                    displayTransform,
                    displayOutput,
                    accumulation);
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.run(context::awaitIdle, exception);
            failure = ResourceCleanup.close(feature, failure);
            failure = ResourceCleanup.destroy(displayTransform, failure);
            failure = ResourceCleanup.destroy(preparePass, failure);
            failure = ResourceCleanup.destroy(targets, failure);
            throw failure;
        }
    }

    @Override public PostProcessingMode mode() { return PostProcessingMode.DLSS_RR; }
    @Override public DlssRrTargets rawFrame() { return this.targets; }
    @Override public VulkanImage linearHdrOutput() { return this.targets.rrOutput(); }
    @Override public VulkanImage hdrDisplayOutput() { return this.displayTransform.hdrOutput(); }
    @Override public long displayExposureStateBuffer() {
        return this.displayTransform.exposureState().handle();
    }
    @Override
    public ReconstructionQualityMode quality() { return this.quality; }
    @Override
    public int renderWidth() { return this.renderWidth; }
    @Override
    public int renderHeight() { return this.renderHeight; }
    @Override
    public int displayWidth() { return this.displayWidth; }
    @Override
    public int displayHeight() { return this.displayHeight; }

    public void requestReset() {
        requireOpen();
        this.history.requestReset();
    }

    public FrameToken beginFrame(
            FrameCamera camera,
            long frameTimeNanos,
            long sceneRevision,
            long textureRevision,
            boolean forceRestart,
            RrInputView debugView,
            float responsivity) {
        requireOpen();
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(debugView, "debugView");
        responsivity = RrResponsivity.requireValid(responsivity);
        SubmittedFrame<TemporalReconstructionState.Plan> temporal = this.history.plan(
                new TemporalReconstructionState.Input(
                        camera,
                        frameTimeNanos,
                        sceneRevision,
                        forceRestart));
        SubpixelJitter jitter = DlssRrProfile.jitter(
                this.quality, temporal.plan().frameIndex());
        return new FrameToken(
                this,
                temporal,
                jitter,
                debugView,
                responsivity);
    }

    @Override
    public FrameToken beginFrame(
            ReconstructionFrameParameters parameters,
            ReconstructionDebugSettings debugSettings) {
        return this.beginFrame(
                parameters.camera(),
                parameters.frameTimeNanos(),
                parameters.sceneRevision(),
                parameters.textureRevision(),
                parameters.forceRestart(),
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
    public void captureRendererDiagnostic(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RendererImageView view) {
        if (view.active() && view != RendererImageView.DENOISED_OUTPUT) {
            this.rendererDebugPass().capture(commandBuffer, initialization, view);
        }
    }

    public void record(
            VkCommandBuffer commandBuffer,
            FrameToken token,
            SunDirection sunDirection,
            float sunRadianceMultiplier,
            DisplaySettings.Snapshot display,
            VulkanImageInitializationBatch initialization) {
        requireOpen();
        if (token.owner != this
                || token.recorded
                || token.submitted
                || token.abandoned) {
            throw new IllegalArgumentException("DLSS RR frame token does not belong to this recording");
        }
        token.recorded = true;
        TemporalReconstructionState.Plan temporal =
                token.temporal.claimForExecution();
        this.preparePass.record(
                commandBuffer,
                temporal.camera(),
                temporal.historyCamera(),
                token.jitter,
                sunDirection,
                sunRadianceMultiplier,
                token.responsivity);
        NrdCameraTransform.projectionForNrd(
                temporal.camera().projection(), this.ngxProjection);
        this.feature.evaluate(
                commandBuffer,
                new DlssRrNative.Evaluation(
                        this.renderWidth,
                        this.renderHeight,
                        token.jitter,
                        this.renderWidth,
                        this.renderHeight,
                        temporal.restart(),
                        temporal.deltaMilliseconds(),
                        temporal.camera().viewRotation(),
                        this.ngxProjection,
                        this.targets.material(),
                        this.targets.specularMaterial(),
                        this.targets.rrNormalRoughness(),
                        this.targets.inputColor(),
                        this.targets.rrOutput(),
                        this.targets.viewZ(),
                        this.targets.motion(),
                        this.targets.specularMotion(),
                        this.targets.specularHitDistance(),
                        this.targets.responsivity()));
        allCommandsToCompute(commandBuffer);
        this.displayTransform.record(
                commandBuffer,
                temporal.deltaMilliseconds() * 0.001F,
                temporal.restart(),
                false,
                display,
                initialization);
        if (token.debugView.active()) {
            allCommandsToCompute(commandBuffer);
            this.debugPass().record(commandBuffer, token.debugView);
        }
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            ReconstructionFrameParameters parameters,
            VulkanImageInitializationBatch initialization) {
        if (!(frame instanceof FrameToken token)) {
            throw new IllegalArgumentException("DLSS RR received another processor's frame token");
        }
        this.record(
                commandBuffer,
                token,
                parameters.sunDirection(),
                parameters.sunRadianceMultiplier(),
                parameters.display(),
                initialization);
    }

    @Override
    public void presentRendererDiagnostic(
            VkCommandBuffer commandBuffer, RendererImageView view) {
        if (view.active()) this.rendererDebugPass().present(commandBuffer, view);
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
                    this.context,
                    this.targets,
                    this.displayOutput,
                    this.displayTransform.hdrOutput());
        }
        return this.debugPass;
    }

    private RendererImageDebugPass rendererDebugPass() {
        if (this.rendererDebugPass == null) {
            this.rendererDebugPass = RendererImageDebugPass.create(
                    this.context,
                    this.targets,
                    this.stableRadiance,
                    this.targets.rrOutput(),
                    this.displayOutput,
                    this.displayTransform.hdrOutput());
        }
        return this.rendererDebugPass;
    }

    public void submitted(FrameToken token) {
        requireOpen();
        if (token.owner != this
                || !token.recorded
                || token.submitted
                || token.abandoned) {
            throw new IllegalArgumentException("DLSS RR frame token does not belong to this submission");
        }
        token.submitted = true;
        this.history.submitted(token.temporal);
    }

    public void abandon(FrameToken token) {
        requireOpen();
        if (token.owner != this || token.submitted || token.abandoned) {
            throw new IllegalArgumentException(
                    "DLSS RR frame token does not belong to this processor");
        }
        token.abandoned = true;
        this.history.abandon(token.temporal);
    }

    @Override
    public void submitted(Frame frame) {
        if (!(frame instanceof FrameToken token)) {
            throw new IllegalArgumentException("DLSS RR received another processor's frame token");
        }
        this.submitted(token);
    }

    @Override
    public void abandon(Frame frame) {
        if (!(frame instanceof FrameToken token)) {
            throw new IllegalArgumentException(
                    "DLSS RR received another processor's frame token");
        }
        this.abandon(token);
    }

    private void requireOpen() {
        if (this.destroyed) throw new IllegalStateException("DLSS RR post-processor is destroyed");
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        // Do not make a failed wait terminal: no child handle is safe to release until all NGX
        // work has retired, and a later caller must be able to retry this ownership boundary.
        this.context.awaitIdle();
        RuntimeException failure = ResourceCleanup.close(this.feature, null);
        failure = ResourceCleanup.destroy(this.rendererDebugPass, failure);
        failure = ResourceCleanup.destroy(this.debugPass, failure);
        failure = ResourceCleanup.destroy(this.displayTransform, failure);
        failure = ResourceCleanup.destroy(this.preparePass, failure);
        failure = ResourceCleanup.destroy(this.targets, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    public static final class FrameToken implements Frame {
        private final DlssRrPostProcessor owner;
        private final SubmittedFrame<TemporalReconstructionState.Plan> temporal;
        private final SubpixelJitter jitter;
        private final ReconstructionFrame semantic;
        private final RrInputView debugView;
        private final float responsivity;
        private boolean recorded;
        private boolean submitted;
        private boolean abandoned;

        private FrameToken(
                DlssRrPostProcessor owner,
                SubmittedFrame<TemporalReconstructionState.Plan> temporal,
                SubpixelJitter jitter,
                RrInputView debugView,
                float responsivity) {
            this.owner = owner;
            this.temporal = temporal;
            this.jitter = jitter;
            this.semantic = new ReconstructionFrame(
                    temporal.plan().frameIndex(), jitter, temporal.plan().restart());
            this.debugView = debugView;
            this.responsivity = responsivity;
        }

        @Override public ReconstructionFrame semantic() {
            return this.semantic;
        }
    }
}
