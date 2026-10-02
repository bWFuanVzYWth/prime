package dev.primept.settings;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Exact current schema, no historical field migration or mixed-version settings. */
public final class SettingsFile {
    public record Loaded(RenderSettings settings, String resetReason) {}
    private SettingsFile() {}
    public static Loaded load(Path path) {
        if (!Files.exists(path))
            return new Loaded(RenderSettings.defaults(), "");
        try {
            return decode(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            return new Loaded(RenderSettings.defaults(),
                              "Cannot read settings: " + failure.getMessage());
        }
    }
    public static Loaded decode(String text) {
        try {
            var properties = new Properties();
            properties.load(new StringReader(text));
            if (!Integer.toString(RenderSettings.VERSION).equals(properties.getProperty("version")))
                return new Loaded(RenderSettings.defaults(),
                                  "Settings version mismatch; defaults restored");
            String enabled = properties.getProperty("renderer.path_tracing");
            if (!"true".equals(enabled) && !"false".equals(enabled))
                throw new IllegalArgumentException("Invalid renderer.path_tracing");
            String opacityMicromap = properties.getProperty("render.opacity_micromap");
            if (!"true".equals(opacityMicromap) && !"false".equals(opacityMicromap))
                throw new IllegalArgumentException("Invalid render.opacity_micromap");
            var result = RenderSettings.defaults()
                                 .withPathTracing(Boolean.parseBoolean(enabled))
                                 .withOpacityMicromap(Boolean.parseBoolean(opacityMicromap));
            for (var control : RenderSettings.Control.values())
                result =
                        result.with(control, Integer.parseInt(properties.getProperty(control.key)));
            return new Loaded(result, "");
        } catch (IOException | IllegalArgumentException failure) {
            return new Loaded(RenderSettings.defaults(),
                              "Invalid settings; defaults restored: " + failure.getMessage());
        }
    }
    public static String encode(RenderSettings settings) {
        var text =
                new StringBuilder("version=" + RenderSettings.VERSION +
                                  "\nrenderer.path_tracing=" + settings.pathTracing() +
                                  "\nrender.opacity_micromap=" + settings.opacityMicromap() + "\n");
        for (var control : RenderSettings.Control.values())
            text.append(control.key).append('=').append(settings.value(control)).append('\n');
        return text.toString();
    }
    public static void save(Path path, RenderSettings settings) throws IOException {
        Path destination = path.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "primept-settings-", ".tmp");
        try {
            Files.writeString(temporary, encode(settings), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                           StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
