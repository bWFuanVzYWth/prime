package dev.primept;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SmokeSelectionTest {
    @Test
    void falseDoesNotEnableSectionOrConflictWithTheHostOnlySelector() {
        assertEquals(SmokeSelection.DEFAULT,
                     SmokeSelection.resolve(Map.of("primeptSectionSuite", "false")));
        var host = SmokeSelection.resolve(
                Map.of("primeptSectionSuite", "false", "primeptSmokeTargetResize", "true"));
        assertEquals(SmokeSelection.TARGET_RESIZE, host);
        assertFalse(host.nativeRequired());
        assertFalse(SmokeSelection.ROUTING_COST.nativeRequired());
        assertFalse(SmokeSelection.SAMPLING_REGISTRY.nativeRequired());
        assertTrue(SmokeSelection.DEFAULT.nativeRequired());
        assertTrue(SmokeSelection.SECTION.nativeRequired());
        assertTrue(SmokeSelection.FOREIGN.nativeRequired());
    }

    @Test
    void conflictingAndMalformedRequestsFailInsteadOfSilentlyRunningAnotherSuite() {
        assertThrows(IllegalArgumentException.class,
                     ()
                             -> SmokeSelection.resolve(Map.of("primeptSmokeTargetResize", "true",
                                                              "primeptSmokeForeign", "true")));
        assertThrows(
                IllegalArgumentException.class,
                ()
                        -> SmokeSelection.resolve(Map.of("primeptSectionSuite", "true",
                                                         "primeptSamplingRegistry", "out.json")));
        assertThrows(IllegalArgumentException.class,
                     () -> SmokeSelection.resolve(Map.of("primeptSectionSuite", "yes")));
        assertThrows(IllegalArgumentException.class,
                     () -> SmokeSelection.resolve(Map.of("primeptSectionBench", "true")));
    }

    @Test
    void reportRecordsActualCompletionAndRejectsAnUnexecutedRequest(@TempDir Path directory)
            throws Exception {
        Path path = directory.resolve("result.json");
        var report = SmokeSelection.TARGET_RESIZE.report(path, "26.2");
        assertEquals("running",
                     StrictTestJson.parse(StrictTestJson.read(path)).get("status").getAsString());
        assertThrows(IllegalStateException.class, report::passed);
        var calls = new AtomicInteger();
        report.run("target_resize", calls::incrementAndGet);
        report.passed();
        var document = StrictTestJson.parse(StrictTestJson.read(path));
        assertEquals(1, calls.get());
        assertEquals(1, document.get("count").getAsInt());
        assertEquals(document.get("requested"), document.get("executed"));
        assertEquals("passed", document.get("status").getAsString());
        assertThrows(IllegalArgumentException.class, () -> report.run("target_resize", () -> {}));

        var failed = SmokeSelection.TARGET_RESIZE.report(path, "26.3");
        Exception error = assertThrows(Exception.class, () -> failed.run("target_resize", () -> {
            throw new Exception("fixture failed");
        }));
        failed.failed(error);
        var failure = StrictTestJson.parse(StrictTestJson.read(path));
        assertEquals("failed", failure.get("status").getAsString());
        assertEquals(0, failure.get("count").getAsInt());
        assertTrue(failure.get("executed").getAsJsonArray().isEmpty());
    }
}
