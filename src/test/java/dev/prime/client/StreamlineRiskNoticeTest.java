package dev.prime.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.util.GsonHelper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class StreamlineRiskNoticeTest {
    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void frameGenerationIsSeparatedAndWarnsAboutDeviceLost(String locale)
            throws Exception {
        String resource = "/assets/prime/lang/" + locale + ".json";
        try (var input = StreamlineRiskNoticeTest.class.getResourceAsStream(resource)) {
            assertTrue(input != null, "missing language resource " + resource);
            JsonObject translations = GsonHelper.parse(new InputStreamReader(
                    input, StandardCharsets.UTF_8));
            String header = translations.get("prime.options.header.high_risk").getAsString();
            String warning = translations.get(
                    "prime.options.streamline.dlss_frame_generation.tooltip").getAsString();

            assertFalse(header.isBlank());
            assertTrue(warning.contains("VK_ERROR_DEVICE_LOST"));
        }
    }
}
