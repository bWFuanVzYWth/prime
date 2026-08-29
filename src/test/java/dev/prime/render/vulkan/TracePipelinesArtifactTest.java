package dev.prime.render.vulkan;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("artifact")
final class TracePipelinesArtifactTest {
    @Test
    void realtimeTailAdmissionSeesOnlyCompactPathAndQueueStorage() throws IOException {
        TracePipelinesContractTest
                .realtimeTailAdmissionSeesOnlyCompactPathAndQueueStorage();
    }

    @Test
    void imageDiagnosticsUseOneIsolatedSourceAndTargetLayout() throws IOException {
        TracePipelinesContractTest.imageDiagnosticsUseOneIsolatedSourceAndTargetLayout();
    }

    @Test
    void setOneAbiDoesNotCrossRendererBoundary() throws IOException {
        TracePipelinesContractTest.setOneAbiDoesNotCrossRendererBoundary();
    }

    @Test
    void realtimeStbnDoesNotEnterTheOfflineShaderClosure() throws IOException {
        TracePipelinesContractTest.realtimeStbnDoesNotEnterTheOfflineShaderClosure();
    }

    @Test
    void optimizedModulesPreservePayloadAbi() throws IOException {
        TracePipelinesContractTest.optimizedModulesPreservePayloadAbi();
    }

    @Test
    void serReorderedPublishersDoNotReuseProducerSubgroups() throws IOException {
        TracePipelinesContractTest.serReorderedPublishersDoNotReuseProducerSubgroups();
    }

    @Test
    void fixedStagesCompactOnlyAtLandingAndScatter() throws IOException {
        TracePipelinesContractTest.fixedStagesCompactOnlyAtLandingAndScatter();
    }

    @Test
    void offlineStagesCompactOnlyAtScatter() throws IOException {
        TracePipelinesContractTest.offlineStagesCompactOnlyAtScatter();
    }

    @Test
    void compiledPathRecordsUseIndependentStrides() throws IOException {
        TracePipelinesContractTest.compiledPathRecordsUseIndependentStrides();
    }
}
