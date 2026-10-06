package dev.primept;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            var api = VersionPredicate.parse(depends.get("fabric-api").getAsString());
            assertTrue(
                    api.test(Version.parse(System.getProperty("primept.test.fabricApiVersion"))));
            assertTrue(api.test(Version.parse("0.999.0")),
                       "Newer Fabric API releases remain usable");
            assertFalse(api.test(Version.parse("0.160.9")), "Older unverified API stays rejected");

            var loader = VersionPredicate.parse(depends.get("fabricloader").getAsString());
            assertTrue(
                    loader.test(Version.parse(System.getProperty("primept.test.loaderVersion"))));
            assertTrue(loader.test(Version.parse("0.20.0")), "Newer Loader releases remain usable");
            assertFalse(loader.test(Version.parse("0.18.99")));

            var minecraft = VersionPredicate.parse(depends.get("minecraft").getAsString());
            String host = System.getProperty("primept.test.minecraftVersion");
            assertTrue(minecraft.test(Version.parse(host)));
            assertFalse(minecraft.test(Version.parse(host.equals("26.2") ? "26.3" : "26.2")),
                        "Host bindings must not claim support for another Minecraft version");
        }
    }
}
