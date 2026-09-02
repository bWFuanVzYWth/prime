package dev.prime.render.vulkan.reconstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import org.junit.jupiter.api.Test;

final class ReconstructionBackendRegistryTest {
    @Test
    void selectsRequestedBuiltInModeAndItsExtent() {
        Reporter reporter = new Reporter();
        ReconstructionBackendRegistry registry = registry(
                new StubDlss(new ReconstructionExtent(1280, 720)), reporter);

        ResolvedReconstruction resolved = registry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.QUALITY,
                1920,
                1080);

        assertEquals(PostProcessingMode.DLSS_RR, resolved.requestedMode());
        assertEquals(PostProcessingMode.DLSS_RR, resolved.effectiveMode());
        assertEquals(new ReconstructionExtent(1280, 720), resolved.extent());
        assertEquals(new ReconstructionExtent(1920, 1080), resolved.displayExtent());
        assertFalse(resolved.fellBack());
        assertEquals(0, reporter.total());
    }

    @Test
    void unavailableDlssFallsBackAndReportsOnlyOnce() {
        Reporter reporter = new Reporter();
        StubDlss dlss = new StubDlss(new ReconstructionExtent(1280, 720));
        dlss.unavailableReason = "missing capability";
        ReconstructionBackendRegistry registry = registry(dlss, reporter);

        ResolvedReconstruction first = registry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.PERFORMANCE,
                1920,
                1080);
        ResolvedReconstruction second = registry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.PERFORMANCE,
                1920,
                1080);

        assertEquals(PostProcessingMode.DLSS_RR, first.requestedMode());
        assertEquals(PostProcessingMode.NRD_FSR, first.effectiveMode());
        assertTrue(first.fellBack());
        assertEquals(first.extent(), second.extent());
        assertEquals(1, reporter.unavailable);
        assertEquals(0, reporter.failed);
    }

    @Test
    void queryAndFeatureFailuresShareTheSingleDlssFallbackPath() {
        Reporter queryReporter = new Reporter();
        StubDlss query = new StubDlss(new ReconstructionExtent(1280, 720));
        query.failure = new IllegalStateException("query");
        ReconstructionBackendRegistry queryRegistry = registry(query, queryReporter);
        assertEquals(
                PostProcessingMode.NRD_FSR,
                queryRegistry.resolve(
                                PostProcessingMode.DLSS_RR,
                                ReconstructionQualityMode.BALANCED,
                                1920,
                                1080)
                        .effectiveMode());
        assertEquals(1, queryReporter.failed);

        Reporter createReporter = new Reporter();
        ReconstructionBackendRegistry createRegistry = registry(
                new StubDlss(new ReconstructionExtent(1280, 720)), createReporter);
        ResolvedReconstruction selected = createRegistry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.BALANCED,
                1920,
                1080);
        ResolvedReconstruction fallback = createRegistry.recoverCreationFailure(
                selected, new IllegalStateException("feature"));
        assertEquals(PostProcessingMode.NRD_FSR, fallback.effectiveMode());
        assertEquals(1, createReporter.failed);
        createRegistry.recoverCreationFailure(selected, new IllegalStateException("again"));
        assertEquals(1, createReporter.failed);
    }

    @Test
    void nativeModesDoNotEnterDlssFallback() {
        Reporter reporter = new Reporter();
        ReconstructionBackendRegistry registry = registry(
                new StubDlss(new ReconstructionExtent(1280, 720)), reporter);
        ResolvedReconstruction nrd = registry.resolve(
                PostProcessingMode.NRD_FSR,
                ReconstructionQualityMode.QUALITY,
                1920,
                1080);
        ResolvedReconstruction noisy = registry.resolve(
                PostProcessingMode.DISABLED,
                ReconstructionQualityMode.QUALITY,
                1920,
                1080);

        assertEquals(PostProcessingMode.NRD_FSR, nrd.effectiveMode());
        assertEquals(PostProcessingMode.DISABLED, noisy.effectiveMode());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.resolve(
                        PostProcessingMode.NRD_FSR,
                        ReconstructionQualityMode.QUALITY,
                        0,
                        1080));
        assertEquals(0, reporter.total());
    }

    private static ReconstructionBackendRegistry registry(
            StubDlss dlss, Reporter reporter) {
        return new ReconstructionBackendRegistry(dlss, reporter);
    }

    private static final class Reporter
            implements ReconstructionBackendRegistry.FailureReporter {
        private int unavailable;
        private int failed;

        @Override public void unavailable(String reason) { this.unavailable++; }
        @Override public void failed(String operation, RuntimeException exception) { this.failed++; }
        int total() { return this.unavailable + this.failed; }
    }

    private static final class StubDlss
            implements ReconstructionBackendRegistry.DlssExtentResolver {
        private final ReconstructionExtent extent;
        private String unavailableReason;
        private RuntimeException failure;

        private StubDlss(ReconstructionExtent extent) {
            this.extent = extent;
        }

        @Override public String unavailableReason() { return this.unavailableReason; }

        @Override
        public ReconstructionExtent renderExtent(
                ReconstructionQualityMode quality, int displayWidth, int displayHeight) {
            if (this.failure != null) {
                throw this.failure;
            }
            return this.extent;
        }
    }
}
