package dev.prime.client;

import com.mojang.serialization.Codec;
import dev.prime.binding.streamline.ReflexMode;
import dev.prime.config.PrimeConfig;
import dev.prime.mixin.MinecraftAccessor;
import dev.prime.streamline.StreamlineFrameGeneration;
import dev.prime.streamline.StreamlineReflex;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.MaximumBounceSettings;
import dev.prime.render.MinimumBounceSettings;
import dev.prime.render.RendererSettings;
import dev.prime.render.SpecularBounceSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.TransparentNeeMode;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrInputView;
import dev.prime.render.diagnostic.RrResponsivity;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** Builds Prime's live controls shown in Minecraft's Video Settings screen. */
public final class PrimeVideoOptions {
    private static final List<PostProcessingMode> POST_PROCESSING_MODES =
            List.of(PostProcessingMode.NRD_FSR, PostProcessingMode.DLSS_RR);
    private static final List<ReconstructionQualityMode> QUALITY_MODES =
            List.of(ReconstructionQualityMode.values());
    private static final List<SurfaceDetailMode> SURFACE_DETAIL_MODES =
            List.of(SurfaceDetailMode.values());
    private static final List<TransparentNeeMode> TRANSPARENT_NEE_MODES =
            List.of(TransparentNeeMode.values());
    private static final List<RendererImageView> RENDERER_IMAGE_VIEWS =
            List.of(RendererImageView.values());
    private static final List<RrInputView> RR_INPUT_VIEWS = List.of(RrInputView.values());
    private static final List<NrdInputView> NRD_INPUT_VIEWS = List.of(NrdInputView.values());

    private PrimeVideoOptions() {
    }

    public static OptionSet create(Runnable diagnosticChanged) {
        PrimeRuntime runtime = PrimeRuntime.instance();
        RendererSettings settings = PrimeConfig.rendererSettings();
        OptionInstance<Boolean> hdr = hdr();
        OptionInstance<Integer> referenceWhite = referenceWhiteNits();
        Diagnostics diagnostics = new Diagnostics(
                booleanOption(
                        "prime.options.debug.renderer_diagnostics",
                        runtime.rendererDiagnostics(),
                        runtime::setRendererDiagnostics),
                booleanOption(
                        "prime.options.debug.raw_output",
                        runtime.rawOutput(),
                        runtime::setRawOutput),
                diagnosticView(
                        "prime.options.debug.renderer_image",
                        RENDERER_IMAGE_VIEWS,
                        Codec.STRING.xmap(RendererImageView::fromId, RendererImageView::id),
                        runtime.rendererImageView(),
                        runtime::setRendererImageView,
                        RendererImageView::id,
                        diagnosticChanged),
                diagnosticView(
                        "prime.options.debug.rr_input",
                        RR_INPUT_VIEWS,
                        Codec.STRING.xmap(RrInputView::fromId, RrInputView::id),
                        runtime.rrInputView(),
                        runtime::setRrInputView,
                        RrInputView::id,
                        diagnosticChanged),
                diagnosticView(
                        "prime.options.debug.nrd_input",
                        NRD_INPUT_VIEWS,
                        Codec.STRING.xmap(NrdInputView::fromId, NrdInputView::id),
                        runtime.nrdInputView(),
                        runtime::setNrdInputView,
                        NrdInputView::id,
                        diagnosticChanged));
        Streamline streamline = new Streamline(
                reflexMode(),
                booleanOption(
                        "prime.options.streamline.dlss_frame_generation",
                        PrimeConfig.dlssFrameGenerationEnabled(),
                        PrimeConfig::setDlssFrameGenerationEnabled),
                dlssFrameGenerationMultiplier(),
                booleanOption(
                        "prime.options.streamline.dlss_frame_generation_ui_recomposition",
                        PrimeConfig.dlssFrameGenerationUiRecomposition(),
                        PrimeConfig::setDlssFrameGenerationUiRecomposition));
        return new OptionSet(
                List.of(
                        new Section("prime.options.header.rendering", List.of(
                                big(booleanOption(
                                "prime.options.path_tracing",
                                settings.pathTracingEnabled(),
                                PrimeVideoOptions::setPathTracingEnabled)),
                                big(booleanOption(
                                        "prime.options.screenshot_mode",
                                        runtime.screenshotRequested(),
                                        runtime::requestScreenshot)),
                                big(integerOption(
                                "prime.options.additional_specular_bounces",
                                settings.additionalSpecularBounces(),
                                SpecularBounceSettings.MINIMUM_COUNT,
                                SpecularBounceSettings.MAXIMUM_COUNT,
                                "",
                                PrimeConfig::setAdditionalSpecularBounces)),
                                big(integerOption(
                                "prime.options.minimum_bounces",
                                settings.minimumBounces(),
                                MinimumBounceSettings.MINIMUM_COUNT,
                                MinimumBounceSettings.MAXIMUM_COUNT,
                                "",
                                PrimeConfig::setMinimumBounces)),
                                big(integerOption(
                                "prime.options.maximum_bounces",
                                settings.maximumBounces(),
                                MaximumBounceSettings.MINIMUM_COUNT,
                                MaximumBounceSettings.MAXIMUM_COUNT,
                                "",
                                PrimeConfig::setMaximumBounces)),
                                big(integerOption(
                                "prime.options.terrain_worker_percentage",
                                settings.terrainWorkerPercentage(),
                                TerrainWorkerSettings.MINIMUM_PERCENTAGE,
                                TerrainWorkerSettings.MAXIMUM_PERCENTAGE,
                                "%",
                                PrimeConfig::setTerrainWorkerPercentage)),
                                small(enumOption(
                                "prime.options.material.surface_detail",
                                new OptionInstance.Enum<>(
                                        SURFACE_DETAIL_MODES,
                                        Codec.STRING.xmap(
                                                id -> SurfaceDetailMode.findById(id)
                                                        .orElse(SurfaceDetailMode.DEFAULT),
                                                SurfaceDetailMode::id)),
                                settings.surfaceDetailMode(),
                                (caption, mode) -> Component.translatable(
                                        "prime.options.material.surface_detail." + mode.id()),
                                PrimeVideoOptions::setSurfaceDetailMode),
                                integerOption(
                                "prime.options.material.displacement_height",
                                settings.voxelTextureSurfaceStrengthSteps(),
                                VoxelSurfaceSettings.MINIMUM_STEPS,
                                VoxelSurfaceSettings.MAXIMUM_STEPS,
                                "%",
                                PrimeVideoOptions::setVoxelTextureSurfaceStrengthSteps)),
                                small(enumOption(
                                "prime.options.post_processing.mode",
                                new OptionInstance.Enum<>(
                                        POST_PROCESSING_MODES,
                                        Codec.STRING.xmap(
                                                PostProcessingMode::fromId,
                                                PostProcessingMode::id)),
                                settings.postProcessingMode(),
                                (caption, mode) -> Component.translatable(
                                        "prime.options.post_processing.mode." + mode.id()),
                                PrimeConfig::setPostProcessingMode),
                                enumOption(
                                "prime.options.post_processing.quality",
                                new OptionInstance.SliderableEnum<>(
                                        QUALITY_MODES,
                                        Codec.STRING.xmap(
                                                ReconstructionQualityMode::fromId,
                                                ReconstructionQualityMode::id)),
                                settings.reconstructionQuality(),
                                (caption, mode) -> Options.genericValueLabel(
                                        caption,
                                        Component.translatable(
                                                "prime.options.post_processing.quality."
                                                        + mode.id())),
                                PrimeConfig::setReconstructionQualityMode)),
                                big(rrResponsivity()))),
                        new Section("prime.options.header.lighting", List.of(
                                small(intOption(
                                "prime.options.astronomy.latitude",
                                settings.astronomy().latitudeDegrees(),
                                AstronomySettings.MINIMUM_LATITUDE_DEGREES,
                                AstronomySettings.MAXIMUM_LATITUDE_DEGREES,
                                (caption, value) -> Options.genericValueLabel(
                                        caption, formatLatitude(value)),
                                PrimeConfig::setLatitudeDegrees),
                                intOption(
                                "prime.options.astronomy.season",
                                settings.astronomy().solarLongitudeDegrees(),
                                AstronomySettings.MINIMUM_SOLAR_LONGITUDE_DEGREES,
                                AstronomySettings.MAXIMUM_SOLAR_LONGITUDE_DEGREES,
                                (caption, value) -> Options.genericValueLabel(
                                        caption, formatSolarLongitude(value)),
                                PrimeConfig::setSolarLongitudeDegrees)),
                                big(exposureOption(
                                "prime.options.lighting.sun_ev",
                                settings.lighting().sunQuarterSteps(),
                                LightingSettings.MINIMUM_QUARTER_STEPS,
                                LightingSettings.MAXIMUM_QUARTER_STEPS,
                                PrimeConfig::setSunQuarterSteps)),
                                big(exposureOption(
                                "prime.options.lighting.star_ev",
                                settings.lighting().starQuarterSteps(),
                                LightingSettings.MINIMUM_QUARTER_STEPS,
                                LightingSettings.MAXIMUM_QUARTER_STEPS,
                                PrimeConfig::setStarQuarterSteps)),
                                big(exposureOption(
                                "prime.options.lighting.block_light_ev",
                                settings.lighting().blockLightQuarterSteps(),
                                LightingSettings.MINIMUM_QUARTER_STEPS,
                                LightingSettings.MAXIMUM_QUARTER_STEPS,
                                PrimeConfig::setBlockLightQuarterSteps)),
                                big(enumOption(
                                "prime.options.lighting.transparent_nee_mode",
                                new OptionInstance.Enum<>(
                                        TRANSPARENT_NEE_MODES,
                                        Codec.STRING.xmap(
                                                TransparentNeeMode::fromId,
                                                TransparentNeeMode::id)),
                                settings.lighting().transparentNeeMode(),
                                (caption, mode) -> Component.translatable(
                                        "prime.options.lighting.transparent_nee_mode."
                                                + mode.id()),
                                PrimeConfig::setTransparentNeeMode)))),
                        new Section("prime.options.header.display", List.of(
                                big(hdr),
                                big(referenceWhite),
                                big(integerOption(
                                "prime.options.display.auto_exposure_compensation",
                                settings.display().autoExposureCompensationSteps(),
                                DisplaySettings.MINIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS,
                                DisplaySettings.MAXIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS,
                                "%",
                                PrimeConfig::setAutoExposureCompensationSteps)),
                                big(exposureOption(
                                "prime.options.display.final_exposure_ev",
                                settings.display().finalExposureQuarterSteps(),
                                DisplaySettings.MINIMUM_FINAL_EXPOSURE_QUARTER_STEPS,
                                DisplaySettings.MAXIMUM_FINAL_EXPOSURE_QUARTER_STEPS,
                                PrimeConfig::setFinalExposureQuarterSteps)))),
                        new Section("prime.options.header.material", List.of(
                                small(intOption(
                                "prime.options.material.default_roughness",
                                settings.material().roughnessSteps(),
                                MaterialSettings.MINIMUM_ROUGHNESS_STEPS,
                                MaterialSettings.MAXIMUM_ROUGHNESS_STEPS,
                                (caption, value) -> Options.genericValueLabel(
                                        caption, Component.literal(formatRoughness(value))),
                                PrimeConfig::setDefaultRoughnessSteps),
                                booleanOption(
                                "prime.options.material.seamless_glass",
                                settings.material().seamlessGlass(),
                                PrimeConfig::setSeamlessGlass)),
                                small(booleanOption(
                                "prime.options.material.air_gap",
                                settings.material().airGap(),
                                PrimeConfig::setAirGap),
                                booleanOption(
                                "prime.options.material.vanilla_pbr_presets",
                                settings.material().vanillaPbrPresets(),
                                PrimeConfig::setVanillaPbrPresets)))),
                        new Section("prime.options.header.streamline", List.of(
                                big(streamline.reflexMode()))),
                        new Section("prime.options.header.high_risk", List.of(
                                big(streamline.dlssFrameGenerationEnabled()),
                                big(streamline.dlssFrameGenerationMultiplier()),
                                big(streamline.dlssFrameGenerationUiRecomposition()))),
                        new Section("prime.options.header.diagnostics", List.of(
                                big(diagnostics.rendererDiagnostics()),
                                big(diagnostics.rawOutput()),
                                big(diagnostics.rendererImageView()),
                                big(diagnostics.rrInputView()),
                                big(diagnostics.nrdInputView())))),
                hdr,
                referenceWhite,
                diagnostics,
                streamline);
    }

    private static OptionInstance<Float> rrResponsivity() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return new OptionInstance<>(
                "prime.options.post_processing.rr_responsivity",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable(
                                "prime.options.post_processing.rr_responsivity.tooltip")),
                (caption, value) -> Options.genericValueLabel(
                        caption,
                        Component.literal(String.format(Locale.ROOT, "%+.2f", value))),
                OptionInstance.UnitDouble.INSTANCE.xmap(
                        RrResponsivity::fromSlider,
                        RrResponsivity::toSlider),
                runtime.rrResponsivity(),
                runtime::setRrResponsivity);
    }

    private static OptionInstance<Integer> referenceWhiteNits() {
        HdrOutput.Capability capability = HdrOutput.capability();
        int maximumNits = Math.max(
                1,
                capability.maximumSelectableReferenceWhiteNits());
        int configuredNits = Math.min(PrimeConfig.referenceWhiteNits(), maximumNits);
        Component automaticLabel = capability.supported()
                ? Component.translatable(
                        "prime.options.display.reference_white.auto_measured",
                        Math.round(Math.min(
                                capability.systemReferenceWhiteNits(),
                                capability.maximumNits())))
                : Component.translatable("prime.options.display.reference_white.auto");
        return intOption(
                "prime.options.display.reference_white",
                configuredNits,
                0,
                maximumNits,
                (caption, nits) -> Options.genericValueLabel(
                        caption,
                        nits == HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS
                                ? automaticLabel
                                : Component.literal(nits + " nit")),
                PrimeConfig::setReferenceWhiteNits);
    }

    private static OptionInstance<Boolean> booleanOption(
            String key,
            boolean initial,
            OptionInstance.ValueUpdateListener<Boolean> listener) {
        return OptionInstance.createBoolean(
                key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                initial,
                listener);
    }

    private static Row big(OptionInstance<?> option) {
        return new Row(option, null);
    }

    private static Row small(OptionInstance<?> first, OptionInstance<?> second) {
        return new Row(first, second);
    }

    private static OptionInstance<Integer> intOption(
            String key,
            int initial,
            int minimum,
            int maximum,
            OptionInstance.CaptionBasedToString<Integer> caption,
            OptionInstance.ValueUpdateListener<Integer> listener) {
        return option(
                key,
                caption,
                new OptionInstance.IntRange(minimum, maximum),
                initial,
                listener);
    }

    private static OptionInstance<Integer> integerOption(
            String key,
            int initial,
            int minimum,
            int maximum,
            String suffix,
            OptionInstance.ValueUpdateListener<Integer> listener) {
        return intOption(
                key,
                initial,
                minimum,
                maximum,
                (caption, value) -> Options.genericValueLabel(
                        caption, Component.literal(value + suffix)),
                listener);
    }

    private static <T> OptionInstance<T> enumOption(
            String key,
            OptionInstance.ValueSet<T> values,
            T initial,
            OptionInstance.CaptionBasedToString<T> caption,
            OptionInstance.ValueUpdateListener<T> listener) {
        return option(key, caption, values, initial, listener);
    }

    private static <T> OptionInstance<T> diagnosticView(
            String key,
            List<T> values,
            Codec<T> codec,
            T initial,
            OptionInstance.ValueUpdateListener<T> listener,
            Function<T, String> id,
            Runnable changed) {
        return enumOption(
                key,
                new OptionInstance.SliderableEnum<>(values, codec),
                initial,
                (caption, value) -> Component.translatable(
                        "prime.options.debug.image_view." + id.apply(value)),
                value -> {
                    listener.valueChanged(value);
                    changed.run();
                });
    }

    private static <T> OptionInstance<T> option(
            String key,
            OptionInstance.CaptionBasedToString<T> caption,
            OptionInstance.ValueSet<T> values,
            T initial,
            OptionInstance.ValueUpdateListener<T> listener) {
        return new OptionInstance<>(
                key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                caption,
                values,
                initial,
                listener);
    }

    private static OptionInstance<Integer> exposureOption(
            String key,
            int initialQuarterSteps,
            int minimumQuarterSteps,
            int maximumQuarterSteps,
            OptionInstance.ValueUpdateListener<Integer> listener) {
        return intOption(
                key,
                initialQuarterSteps,
                minimumQuarterSteps,
                maximumQuarterSteps,
                (caption, quarterSteps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(formatExposure(quarterSteps))),
                listener);
    }

    private static void setPathTracingEnabled(boolean enabled) {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.setPathTracingEnabled(enabled);
        RendererSettings current = PrimeConfig.rendererSettings();
        if (previous.pathTracingEnabled() != current.pathTracingEnabled()) {
            PrimeRuntime.instance().pathTracingChanged(current.pathTracingEnabled());
        }
    }

    private static void setSurfaceDetailMode(SurfaceDetailMode mode) {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.setSurfaceDetailMode(mode);
        RendererSettings current = PrimeConfig.rendererSettings();
        if (previous.surfaceDetailMode() != current.surfaceDetailMode()) {
            PrimeRuntime.instance().surfaceDetailModeChanged();
        }
    }

    private static void setVoxelTextureSurfaceStrengthSteps(int steps) {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.setVoxelTextureSurfaceStrengthSteps(steps);
        RendererSettings current = PrimeConfig.rendererSettings();
        if (previous.voxelTextureSurfaceStrengthSteps()
                != current.voxelTextureSurfaceStrengthSteps()) {
            PrimeRuntime.instance().voxelTextureSurfaceStrengthChanged(
                    current.usesGeometryDisplacement(),
                    current.voxelTextureSurfaceStrengthSteps());
        }
    }

    static String formatExposure(int quarterSteps) {
        float ev = LightingSettings.exposureValue(quarterSteps);
        if (quarterSteps == 0) {
            return "0 EV";
        }
        return String.format(Locale.ROOT, "%+.2f EV", ev);
    }

    private static Component formatLatitude(int degrees) {
        if (degrees == 0) {
            return Component.translatable("prime.options.astronomy.latitude.equator");
        }
        return Component.translatable(
                degrees > 0
                        ? "prime.options.astronomy.latitude.north"
                        : "prime.options.astronomy.latitude.south",
                Math.abs(degrees));
    }

    private static Component formatSolarLongitude(int degrees) {
        String event = switch (degrees) {
            case 0 -> "march_equinox";
            case 90 -> "june_solstice";
            case 180 -> "september_equinox";
            case 270 -> "december_solstice";
            default -> null;
        };
        if (event != null) {
            return Component.translatable(
                    "prime.options.astronomy.season." + event);
        }
        String interval = switch (degrees / 90) {
            case 0 -> "march_to_june";
            case 1 -> "june_to_september";
            case 2 -> "september_to_december";
            default -> "december_to_march";
        };
        return Component.translatable(
                "prime.options.astronomy.season.progress",
                degrees,
                Component.translatable(
                        "prime.options.astronomy.season." + interval));
    }

    static String formatRoughness(int steps) {
        return String.format(Locale.ROOT, "%.2f", MaterialSettings.linearRoughness(steps));
    }

    private static OptionInstance<ReflexMode> reflexMode() {
        boolean available = StreamlineReflex.available();
        return new OptionInstance<>(
                "prime.options.low_latency.reflex_mode",
                OptionInstance.cachedConstantTooltip(Component.translatable(available
                        ? "prime.options.low_latency.reflex_mode.tooltip"
                        : "prime.options.low_latency.reflex_mode.unavailable.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.low_latency.reflex_mode." + switch (mode) {
                            case OFF -> "off";
                            case LOW_LATENCY -> "on";
                            case LOW_LATENCY_WITH_BOOST -> "boost";
                        }),
                new OptionInstance.Enum<>(
                        List.of(ReflexMode.values()),
                        Codec.STRING.xmap(
                                id -> ReflexMode.valueOf(id.toUpperCase(Locale.ROOT)),
                                mode -> mode.name().toLowerCase(Locale.ROOT))),
                PrimeConfig.reflexMode(),
                PrimeConfig::setReflexMode);
    }

    private static OptionInstance<Integer> dlssFrameGenerationMultiplier() {
        int maximumMultiplier = StreamlineFrameGeneration.maximumMultiplier();
        int effectiveMultiplier = Math.min(
                PrimeConfig.dlssFrameGenerationMultiplier(), maximumMultiplier);
        return integerOption(
                "prime.options.streamline.dlss_frame_generation_multiplier",
                effectiveMultiplier,
                2,
                maximumMultiplier,
                "x",
                PrimeConfig::setDlssFrameGenerationMultiplier);
    }

    private static OptionInstance<Boolean> hdr() {
        return OptionInstance.createBoolean(
                "prime.options.display.hdr",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        HdrOutput.capability().supported()
                                ? "prime.options.display.hdr.tooltip"
                                : "prime.options.display.hdr.unavailable.tooltip")),
                PrimeConfig.hdrEnabled(),
                PrimeVideoOptions::setHdrEnabled);
    }

    private static void setHdrEnabled(boolean enabled) {
        PrimeConfig.setHdrEnabled(enabled);
        Minecraft minecraft = Minecraft.getInstance();
        ((MinecraftAccessor) minecraft).prime$setWindowSurfaceNeedsReconfiguring(true);
    }

    public record OptionSet(
            List<Section> sections,
            OptionInstance<Boolean> hdr,
            OptionInstance<Integer> referenceWhiteNits,
            Diagnostics diagnostics,
            Streamline streamline) {
        public OptionSet {
            sections = List.copyOf(sections);
        }

        public List<OptionInstance<?>> options() {
            return this.sections.stream()
                    .flatMap(section -> section.rows().stream())
                    .flatMap(row -> row.second() == null
                            ? java.util.stream.Stream.of(row.first())
                            : java.util.stream.Stream.of(row.first(), row.second()))
                    .toList();
        }
    }

    public record Section(String titleKey, List<Row> rows) {
        public Section {
            rows = List.copyOf(rows);
        }
    }

    public record Row(OptionInstance<?> first, OptionInstance<?> second) {}

    public record Diagnostics(
            OptionInstance<Boolean> rendererDiagnostics,
            OptionInstance<Boolean> rawOutput,
            OptionInstance<RendererImageView> rendererImageView,
            OptionInstance<RrInputView> rrInputView,
            OptionInstance<NrdInputView> nrdInputView) {
    }

    public record Streamline(
            OptionInstance<ReflexMode> reflexMode,
            OptionInstance<Boolean> dlssFrameGenerationEnabled,
            OptionInstance<Integer> dlssFrameGenerationMultiplier,
            OptionInstance<Boolean> dlssFrameGenerationUiRecomposition) {
    }
}
