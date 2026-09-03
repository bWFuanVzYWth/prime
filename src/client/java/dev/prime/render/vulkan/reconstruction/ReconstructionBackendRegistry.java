package dev.prime.render.vulkan.reconstruction;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.NoisyPostProcessor;
import dev.prime.render.vulkan.NrdFsrPostProcessor;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import dev.prime.render.vulkan.dlss.DlssRrPostProcessor;
import java.util.Objects;
import java.util.Optional;

/** Resolves Prime's three fixed reconstruction products and owns DLSS fallback policy. */
public final class ReconstructionBackendRegistry {
    private final VulkanContext context;
    private final DlssRrNative.Context ngxContext;
    private final DlssExtentResolver dlss;
    private final FailureReporter failureReporter;
    private boolean dlssFallbackReported;

    public ReconstructionBackendRegistry(
            VulkanContext context, DlssRrNative.Context ngxContext) {
        this.context = Objects.requireNonNull(context, "context");
        this.ngxContext = ngxContext;
        this.dlss = new NativeDlssExtentResolver(ngxContext);
        this.failureReporter = new DefaultFailureReporter();
    }

    ReconstructionBackendRegistry(
            DlssExtentResolver dlss, FailureReporter failureReporter) {
        this.context = null;
        this.ngxContext = null;
        this.dlss = Objects.requireNonNull(dlss, "dlss");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    public ResolvedReconstruction resolve(
            PostProcessingMode requestedMode,
            ReconstructionQualityMode quality,
            int displayWidth,
            int displayHeight) {
        Objects.requireNonNull(requestedMode, "requestedMode");
        Objects.requireNonNull(quality, "quality");
        ReconstructionExtent display = new ReconstructionExtent(displayWidth, displayHeight);
        if (requestedMode == PostProcessingMode.DLSS_RR) {
            String unavailable = this.dlss.unavailableReason();
            if (unavailable != null) {
                return this.fallback(quality, display, unavailable, null);
            }
        }
        try {
            ReconstructionExtent render = switch (requestedMode) {
                case NRD_FSR -> quality.renderExtent(displayWidth, displayHeight);
                case DLSS_RR -> this.dlss.renderExtent(quality, displayWidth, displayHeight);
                case DISABLED -> display;
            };
            return new ResolvedReconstruction(
                    requestedMode,
                    requestedMode,
                    quality,
                    render,
                    display,
                    Optional.empty());
        } catch (RuntimeException exception) {
            if (requestedMode != PostProcessingMode.DLSS_RR) {
                throw exception;
            }
            return this.fallback(quality, display, "optimal-size query failed", exception);
        }
    }

    public VulkanReconstructionResources createResources(
            AtmospherePipeline atmosphere, ResolvedReconstruction selection) {
        if (this.context == null) {
            throw new IllegalStateException(
                    "The selection-only reconstruction registry cannot create Vulkan resources");
        }
        VulkanImage output = null;
        VulkanImage stableRadiance = null;
        try {
            output = this.context.createOutputImage(
                    selection.displayExtent().width(), selection.displayExtent().height());
            stableRadiance = this.context.createAccumulationImage(
                    selection.extent().width(), selection.extent().height());
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(stableRadiance, exception);
            ResourceCleanup.destroy(output, exception);
            throw exception;
        }

        try {
            VulkanReconstructionProcessor processor = this.createProcessor(
                    atmosphere, stableRadiance, output, selection);
            return new VulkanReconstructionResources(
                    output, stableRadiance, processor, selection);
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.destroy(stableRadiance, exception);
            failure = ResourceCleanup.destroy(output, failure);
            if (selection.effectiveMode() != PostProcessingMode.DLSS_RR) {
                throw failure;
            }
            return this.createResources(
                    atmosphere, this.recoverCreationFailure(selection, exception));
        }
    }

    private VulkanReconstructionProcessor createProcessor(
            AtmospherePipeline atmosphere,
            VulkanImage stableRadiance,
            VulkanImage output,
            ResolvedReconstruction selection) {
        int width = selection.extent().width();
        int height = selection.extent().height();
        return switch (selection.effectiveMode()) {
            case NRD_FSR -> NrdFsrPostProcessor.create(
                    this.context,
                    atmosphere,
                    stableRadiance,
                    output,
                    width,
                    height,
                    output.width(),
                    output.height(),
                    selection.quality());
            case DLSS_RR -> {
                if (this.ngxContext == null) {
                    throw new IllegalStateException(
                            "DLSS RR was selected without an initialized NGX context");
                }
                yield DlssRrPostProcessor.create(
                        this.context,
                        this.ngxContext,
                        atmosphere,
                        stableRadiance,
                        output,
                        width,
                        height,
                        output.width(),
                        output.height(),
                        selection.quality());
            }
            case DISABLED -> NoisyPostProcessor.create(
                    this.context,
                    atmosphere,
                    stableRadiance,
                    output,
                    width,
                    height,
                    selection.quality());
        };
    }

    ResolvedReconstruction recoverCreationFailure(
            ResolvedReconstruction selection, RuntimeException exception) {
        return this.fallback(
                selection.quality(),
                selection.displayExtent(),
                "feature creation failed",
                exception);
    }

    private ResolvedReconstruction fallback(
            ReconstructionQualityMode quality,
            ReconstructionExtent display,
            String reason,
            RuntimeException exception) {
        if (!this.dlssFallbackReported) {
            if (exception == null) {
                this.failureReporter.unavailable(reason);
            } else {
                this.failureReporter.failed(reason, exception);
            }
            this.dlssFallbackReported = true;
        }
        return new ResolvedReconstruction(
                PostProcessingMode.DLSS_RR,
                PostProcessingMode.NRD_FSR,
                quality,
                quality.renderExtent(display.width(), display.height()),
                display,
                Optional.of(reason));
    }

    interface DlssExtentResolver {
        /** Null means supported; otherwise contains the stable user-facing failure reason. */
        String unavailableReason();

        ReconstructionExtent renderExtent(
                ReconstructionQualityMode quality, int displayWidth, int displayHeight);
    }

    interface FailureReporter {
        void unavailable(String reason);

        void failed(String operation, RuntimeException exception);
    }

    private static final class NativeDlssExtentResolver implements DlssExtentResolver {
        private final DlssRrNative.Context context;
        private DlssRrNative.OptimalSettings optimal;
        private ReconstructionQualityMode quality;
        private int displayWidth;
        private int displayHeight;

        private NativeDlssExtentResolver(DlssRrNative.Context context) {
            this.context = context;
        }

        @Override
        public String unavailableReason() {
            return this.context != null && DlssRrBootstrap.deviceReady()
                    ? null
                    : DlssRrBootstrap.unavailableReason();
        }

        @Override
        public ReconstructionExtent renderExtent(
                ReconstructionQualityMode quality, int displayWidth, int displayHeight) {
            if (this.context == null) {
                throw new IllegalStateException("DLSS RR is unavailable");
            }
            if (this.optimal == null
                    || this.displayWidth != displayWidth
                    || this.displayHeight != displayHeight
                    || this.quality != quality) {
                this.optimal = this.context.optimalSettings(displayWidth, displayHeight, quality);
                this.displayWidth = displayWidth;
                this.displayHeight = displayHeight;
                this.quality = quality;
            }
            return new ReconstructionExtent(
                    this.optimal.renderWidth(), this.optimal.renderHeight());
        }
    }

    private static final class DefaultFailureReporter implements FailureReporter {
        @Override
        public void unavailable(String reason) {
            PrimeInfo.LOGGER.warn(
                    "DLSS RR selected but unavailable; using NRD-FSR for this session: {}",
                    reason);
        }

        @Override
        public void failed(String operation, RuntimeException exception) {
            DlssRrBootstrap.failSession(
                    "DLSS RR " + operation + "; using NRD-FSR", exception);
        }
    }
}
