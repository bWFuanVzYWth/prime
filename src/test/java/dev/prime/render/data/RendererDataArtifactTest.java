package dev.prime.render.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("artifact")
final class RendererDataArtifactTest {
    @Test
    void generatedContractLeavesAndMemoryLedgerArePublished() throws IOException {
        Path generated = Path.of("build", "generated", "sources", "rendererData", "slang");
        assertTrue(Files.size(generated.resolve("prime_coordinate_contract.slang")) > 0L);
        assertTrue(Files.size(generated.resolve("prime_color_contract.slang")) > 0L);

        Path ledger = Path.of("build", "reports", "renderer-data", "memory-ledger.csv");
        List<String> lines = Files.readAllLines(ledger);
        assertTrue(lines.size() > RendererDataContracts.MEMORY_PLANS.size());
        assertEquals(
                "plan,kind,render_bytes_per_pixel,display_bytes_per_pixel,fixed_bytes,"
                        + "item,semantic,debug_label,extent,bytes_per_element,elements_per_pixel",
                lines.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(
                "realtime-wavefront-current,buffer,648,0,112,")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(
                "nrd-prime-images-current,image,291,0,0,")));
    }
}
