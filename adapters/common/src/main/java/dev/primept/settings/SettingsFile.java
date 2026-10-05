package dev.primept.settings;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Current schema; v8/v9 retain inverse noisy-output migration, v8-v10 default spatial-only off. */
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
            String version = properties.getProperty("version");
            boolean beforeResetDiagnostic = "8".equals(version);
            boolean legacyReconstruction = beforeResetDiagnostic || "9".equals(version);
            boolean beforeSpatialDiagnostic = legacyReconstruction || "10".equals(version);
            if (!beforeSpatialDiagnostic &&
                !Integer.toString(RenderSettings.VERSION).equals(version))
                return new Loaded(RenderSettings.defaults(),
                                  "Settings version mismatch; defaults restored");
            var renderer = RenderSettings.Renderer.fromKey(properties.getProperty("renderer"));
            String opacityMicromap = properties.getProperty("render.opacity_micromap");
            if (!"true".equals(opacityMicromap) && !"false".equals(opacityMicromap))
                throw new IllegalArgumentException("Invalid render.opacity_micromap");
            String noisyKey = legacyReconstruction ? "render.ray_reconstruction"
                                                   : "diagnostics.native_noisy_output";
            String noisyValue = properties.getProperty(noisyKey);
            if (!"true".equals(noisyValue) && !"false".equals(noisyValue))
                throw new IllegalArgumentException("Invalid " + noisyKey);
            boolean nativeNoisyOutput = Boolean.parseBoolean(noisyValue) != legacyReconstruction;
            String ignoreGlobalResets =
                    properties.getProperty("diagnostics.ignore_global_history_resets",
                                           beforeResetDiagnostic ? "false" : null);
            if (!"true".equals(ignoreGlobalResets) && !"false".equals(ignoreGlobalResets))
                throw new IllegalArgumentException(
                        "Invalid diagnostics.ignore_global_history_resets");
            String spatialOnly = properties.getProperty("diagnostics.restir_spatial_only",
                                                        beforeSpatialDiagnostic ? "false" : null);
            if (!"true".equals(spatialOnly) && !"false".equals(spatialOnly))
                throw new IllegalArgumentException("Invalid diagnostics.restir_spatial_only");
            var result =
                    RenderSettings.defaults()
                            .withRenderer(renderer)
                            .withOpacityMicromap(Boolean.parseBoolean(opacityMicromap))
                            .withNativeNoisyOutput(nativeNoisyOutput)
                            .withIgnoreGlobalHistoryResets(Boolean.parseBoolean(ignoreGlobalResets))
                            .withRestirSpatialOnly(Boolean.parseBoolean(spatialOnly))
                            .withDlssQuality(RenderSettings.DlssQuality.valueOf(
                                    properties.getProperty("render.dlss_quality", "")))
                            .withLightSampling(RenderSettings.LightSampling.fromKey(
                                    properties.getProperty("render.light_sampling", "")));
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
        var text = new StringBuilder(
                "version=" + RenderSettings.VERSION + "\nrenderer=" + settings.renderer().key +
                "\nrender.opacity_micromap=" + settings.opacityMicromap() +
                "\ndiagnostics.native_noisy_output=" + settings.nativeNoisyOutput() +
                "\nrender.dlss_quality=" + settings.dlssQuality().name() +
                "\nrender.light_sampling=" + settings.lightSampling().name() +
                "\ndiagnostics.ignore_global_history_resets=" +
                settings.ignoreGlobalHistoryResets() +
                "\ndiagnostics.restir_spatial_only=" + settings.restirSpatialOnly() + "\n");
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
