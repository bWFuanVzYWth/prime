package dev.primept;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

final class FabricDependencyTest {
    @Test
    void packagedMetadataAllowsFabricUpdatesAndKeepsTheHostBoundary() throws Exception {
        try (var zip = new ZipFile(System.getProperty("primept.test.adapterJar"));
             var reader = new InputStreamReader(zip.getInputStream(zip.getEntry("fabric.mod.json")),
                                                StandardCharsets.UTF_8)) {
            var depends =
                    JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("depends");
            assertEquals(">=" + System.getProperty("primept.test.fabricApiVersion"),
                         depends.get("fabric-api").getAsString());
            var api = VersionPredicate.parse(depends.get("fabric-api").getAsString());
            assertTrue(
                    api.test(Version.parse(System.getProperty("primept.test.fabricApiVersion"))));
            assertTrue(api.test(Version.parse("0.999.0")),
                       "Newer Fabric API releases remain usable");
            assertFalse(api.test(Version.parse("0.160.9")), "Older unverified API stays rejected");

            assertEquals(">=0.19.0", depends.get("fabricloader").getAsString());
            var loader = VersionPredicate.parse(depends.get("fabricloader").getAsString());
            assertTrue(
                    loader.test(Version.parse(System.getProperty("primept.test.loaderVersion"))));
            assertTrue(loader.test(Version.parse("0.20.0")), "Newer Loader releases remain usable");
            assertFalse(loader.test(Version.parse("0.18.99")));

            String host = System.getProperty("primept.test.minecraftVersion");
            verifyHostBoundary(depends.get("minecraft").getAsString(), host);
        }
    }

    @Test
    void relaxedPatchAndFutureHostMetadataCannotSatisfyThePackagedPolicy() {
        assertThrows(AssertionError.class, () -> verifyHostBoundary(">=26.2 <26.3", "26.2"));
        assertThrows(AssertionError.class, () -> verifyHostBoundary(">=26.3 <26.4", "26.3"));
        assertThrows(AssertionError.class, () -> verifyHostBoundary(">=26.2", "26.2"));
    }

    private static void verifyHostBoundary(String declared, String host) throws Exception {
        assertEquals("=" + host, declared, "Each adapter declares one exact Minecraft host");
        var minecraft = VersionPredicate.parse(declared);
        assertTrue(minecraft.test(Version.parse(host)));
        for (String unsupported :
             new String[] {host + ".1", host + ".0.1", host + "-alpha.1", host + "-pre-1", "27.0",
                           host.equals("26.2") ? "26.3" : "26.2"})
            assertFalse(minecraft.test(Version.parse(unsupported)),
                        "Host bindings do not support unverified Minecraft " + unsupported);
    }
}
