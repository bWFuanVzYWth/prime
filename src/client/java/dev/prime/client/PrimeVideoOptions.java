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
        return new OptionSet(
                new Rendering(
                        pathTracingEnabled(),
                        additionalSpecularBounces(),
                        minimumBounces(),
                        maximumBounces(),
                        terrainWorkerPercentage(),
                        surfaceDetailMode(),
                        voxelTextureSurfaceStrength(),
                        screenshotMode(),
                        postProcessingMode(),
                        qualityMode(),
                        rrResponsivity()),
                new Lighting(
                        latitude(),
                        season(),
                        sunExposure(),
                        starExposure(),
                        blockLightExposure(),
                        transparentNeeMode()),
                new Display(
                        hdr(),
                        referenceWhiteNits(),
                        autoExposureCompensation(),
                        finalExposure()),
                new Material(
                        defaultRoughness(),
                        seamlessGlass(),
                        airGap(),
                        vanillaPbrPresets()),
                new Diagnostics(
                        rendererDiagnostics(),
                        rawOutput(),
                        rendererImageView(diagnosticChanged),
                        rrInputView(diagnosticChanged),
                        nrdInputView(diagnosticChanged)),
                new Streamline(
                        reflexMode(),
                        dlssFrameGenerationEnabled(),
                        dlssFrameGenerationMultiplier(),
                        dlssFrameGenerationUiRecomposition()));
    }

    private static OptionInstance<Boolean> pathTracingEnabled() {
        return OptionInstance.createBoolean(
                "prime.options.path_tracing",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.path_tracing.tooltip")),
                PrimeConfig.settings().pathTracingEnabled(),
                PrimeVideoOptions::setPathTracingEnabled);
    }

    private static OptionInstance<Integer> additionalSpecularBounces() {
        return new OptionInstance<>(
                "prime.options.additional_specular_bounces",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.additional_specular_bounces.tooltip")),
                (caption, bounces) -> Options.genericValueLabel(
                        caption, Component.literal(Integer.toString(bounces))),
                new OptionInstance.IntRange(
                        SpecularBounceSettings.MINIMUM_COUNT,
                        SpecularBounceSettings.MAXIMUM_COUNT),
                PrimeConfig.additionalSpecularBounces(),
                PrimeConfig::setAdditionalSpecularBounces);
    }

    private static OptionInstance<Integer> minimumBounces() {
        return new OptionInstance<>(
                "prime.options.minimum_bounces",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.minimum_bounces.tooltip")),
                (caption, bounces) -> Options.genericValueLabel(
                        caption, Component.literal(Integer.toString(bounces))),
                new OptionInstance.IntRange(
                        MinimumBounceSettings.MINIMUM_COUNT,
                        MinimumBounceSettings.MAXIMUM_COUNT),
                PrimeConfig.minimumBounces(),
                PrimeConfig::setMinimumBounces);
    }

    private static OptionInstance<Integer> maximumBounces() {
        return new OptionInstance<>(
                "prime.options.maximum_bounces",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.maximum_bounces.tooltip")),
                (caption, bounces) -> Options.genericValueLabel(
                        caption, Component.literal(Integer.toString(bounces))),
                new OptionInstance.IntRange(
                        MaximumBounceSettings.MINIMUM_COUNT,
                        MaximumBounceSettings.MAXIMUM_COUNT),
                PrimeConfig.maximumBounces(),
                PrimeConfig::setMaximumBounces);
    }

    private static OptionInstance<Integer> terrainWorkerPercentage() {
        return new OptionInstance<>(
                "prime.options.terrain_worker_percentage",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.terrain_worker_percentage.tooltip")),
                (caption, percentage) -> Options.genericValueLabel(
                        caption, Component.literal(percentage + "%")),
                new OptionInstance.IntRange(
                        TerrainWorkerSettings.MINIMUM_PERCENTAGE,
                        TerrainWorkerSettings.MAXIMUM_PERCENTAGE),
                PrimeConfig.terrainWorkerPercentage(),
                PrimeConfig::setTerrainWorkerPercentage);
    }

    private static OptionInstance<SurfaceDetailMode> surfaceDetailMode() {
        return new OptionInstance<>(
                "prime.options.material.surface_detail",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.surface_detail.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.material.surface_detail." + mode.id()),
                new OptionInstance.Enum<>(
                        SURFACE_DETAIL_MODES,
                        Codec.STRING.xmap(
                                id -> SurfaceDetailMode.findById(id)
                                        .orElse(SurfaceDetailMode.DEFAULT),
                                SurfaceDetailMode::id)),
                PrimeConfig.settings().surfaceDetailMode(),
                PrimeVideoOptions::setSurfaceDetailMode);
    }

    private static OptionInstance<Integer> voxelTextureSurfaceStrength() {
        return new OptionInstance<>(
                "prime.options.material.displacement_height",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.displacement_height.tooltip")),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(steps + "%")),
                new OptionInstance.IntRange(
                        VoxelSurfaceSettings.MINIMUM_STEPS,
                        VoxelSurfaceSettings.MAXIMUM_STEPS),
                PrimeConfig.settings().voxelTextureSurfaceStrengthSteps(),
                PrimeVideoOptions::setVoxelTextureSurfaceStrengthSteps);
    }

    private static OptionInstance<Boolean> screenshotMode() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return OptionInstance.createBoolean(
                "prime.options.screenshot_mode",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.screenshot_mode.tooltip")),
                runtime.screenshotRequested(),
                runtime::requestScreenshot);
    }

    private static OptionInstance<PostProcessingMode> postProcessingMode() {
        return new OptionInstance<>(
                "prime.options.post_processing.mode",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.post_processing.mode.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.post_processing.mode." + mode.id()),
                new OptionInstance.Enum<>(
                        POST_PROCESSING_MODES,
                        Codec.STRING.xmap(PostProcessingMode::fromId, PostProcessingMode::id)),
                PrimeConfig.settings().postProcessingMode(),
                PrimeConfig::setPostProcessingMode);
    }

    private static OptionInstance<Boolean> rendererDiagnostics() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return OptionInstance.createBoolean(
                "prime.options.debug.renderer_diagnostics",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.debug.renderer_diagnostics.tooltip")),
                runtime.rendererDiagnostics(),
                runtime::setRendererDiagnostics);
    }

    private static OptionInstance<Boolean> rawOutput() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return OptionInstance.createBoolean(
                "prime.options.debug.raw_output",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.debug.raw_output.tooltip")),
                runtime.rawOutput(),
                runtime::setRawOutput);
    }

    private static OptionInstance<ReconstructionQualityMode> qualityMode() {
        return new OptionInstance<>(
                "prime.options.post_processing.quality",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.post_processing.quality.tooltip")),
                (caption, mode) -> Options.genericValueLabel(
                        caption,
                        Component.translatable("prime.options.post_processing.quality." + mode.id())),
                new OptionInstance.SliderableEnum<>(
                        QUALITY_MODES,
                        Codec.STRING.xmap(
                                ReconstructionQualityMode::fromId,
                                ReconstructionQualityMode::id)),
                PrimeConfig.settings().reconstructionQuality(),
                PrimeConfig::setReconstructionQualityMode);
    }

    private static OptionInstance<Integer> latitude() {
        return new OptionInstance<>(
                "prime.options.astronomy.latitude",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.astronomy.latitude.tooltip")),
                (caption, degrees) -> Options.genericValueLabel(
                        caption, formatLatitude(degrees)),
                new OptionInstance.IntRange(
                        AstronomySettings.MINIMUM_LATITUDE_DEGREES,
                        AstronomySettings.MAXIMUM_LATITUDE_DEGREES),
                PrimeConfig.settings().astronomy().latitudeDegrees(),
                PrimeConfig::setLatitudeDegrees);
    }

    private static OptionInstance<Integer> season() {
        return new OptionInstance<>(
                "prime.options.astronomy.season",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.astronomy.season.tooltip")),
                (caption, degrees) -> Options.genericValueLabel(
                        caption, formatSolarLongitude(degrees)),
                new OptionInstance.IntRange(
                        AstronomySettings.MINIMUM_SOLAR_LONGITUDE_DEGREES,
                        AstronomySettings.MAXIMUM_SOLAR_LONGITUDE_DEGREES),
                PrimeConfig.settings().astronomy().solarLongitudeDegrees(),
                PrimeConfig::setSolarLongitudeDegrees);
    }

    private static OptionInstance<RendererImageView> rendererImageView(Runnable changed) {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return new OptionInstance<>(
                "prime.options.debug.renderer_image",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.debug.renderer_image.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.debug.image_view." + mode.id()),
                new OptionInstance.SliderableEnum<>(
                        RENDERER_IMAGE_VIEWS,
                        Codec.STRING.xmap(RendererImageView::fromId, RendererImageView::id)),
                runtime.rendererImageView(),
                value -> {
                    runtime.setRendererImageView(value);
                    changed.run();
                });
    }

    private static OptionInstance<RrInputView> rrInputView(Runnable changed) {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return new OptionInstance<>(
                "prime.options.debug.rr_input",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.debug.rr_input.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.debug.image_view." + mode.id()),
                new OptionInstance.SliderableEnum<>(
                        RR_INPUT_VIEWS,
                        Codec.STRING.xmap(RrInputView::fromId, RrInputView::id)),
                runtime.rrInputView(),
                value -> {
                    runtime.setRrInputView(value);
                    changed.run();
                });
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

    private static OptionInstance<Integer> sunExposure() {
        return exposureOption(
                "prime.options.lighting.sun_ev",
                "prime.options.lighting.sun_ev.tooltip",
                PrimeConfig.settings().sunQuarterSteps(),
                LightingSettings.MINIMUM_QUARTER_STEPS,
                LightingSettings.MAXIMUM_QUARTER_STEPS,
                PrimeConfig::setSunQuarterSteps);
    }

    private static OptionInstance<Integer> starExposure() {
        return exposureOption(
                "prime.options.lighting.star_ev",
                "prime.options.lighting.star_ev.tooltip",
                PrimeConfig.settings().starQuarterSteps(),
                LightingSettings.MINIMUM_STAR_QUARTER_STEPS,
                LightingSettings.MAXIMUM_STAR_QUARTER_STEPS,
                PrimeConfig::setStarQuarterSteps);
    }

    private static OptionInstance<Integer> blockLightExposure() {
        return exposureOption(
                "prime.options.lighting.block_light_ev",
                "prime.options.lighting.block_light_ev.tooltip",
                PrimeConfig.settings().blockLightQuarterSteps(),
                LightingSettings.MINIMUM_QUARTER_STEPS,
                LightingSettings.MAXIMUM_QUARTER_STEPS,
                PrimeConfig::setBlockLightQuarterSteps);
    }

    private static OptionInstance<TransparentNeeMode> transparentNeeMode() {
        return new OptionInstance<>(
                "prime.options.lighting.transparent_nee_mode",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.lighting.transparent_nee_mode.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.lighting.transparent_nee_mode." + mode.id()),
                new OptionInstance.Enum<>(
                        TRANSPARENT_NEE_MODES,
                        Codec.STRING.xmap(TransparentNeeMode::fromId, TransparentNeeMode::id)),
                PrimeConfig.settings().transparentNeeMode(),
                PrimeConfig::setTransparentNeeMode);
    }

    private static OptionInstance<Integer> finalExposure() {
        return exposureOption(
                "prime.options.display.final_exposure_ev",
                "prime.options.display.final_exposure_ev.tooltip",
                PrimeConfig.settings().finalExposureQuarterSteps(),
                DisplaySettings.MINIMUM_FINAL_EXPOSURE_QUARTER_STEPS,
                DisplaySettings.MAXIMUM_FINAL_EXPOSURE_QUARTER_STEPS,
                PrimeConfig::setFinalExposureQuarterSteps);
    }

    private static OptionInstance<Integer> autoExposureCompensation() {
        return new OptionInstance<>(
                "prime.options.display.auto_exposure_compensation",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.display.auto_exposure_compensation.tooltip")),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(steps + "%")),
                new OptionInstance.IntRange(
                        DisplaySettings.MINIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS,
                        DisplaySettings.MAXIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS),
                PrimeConfig.settings().autoExposureCompensationSteps(),
                PrimeConfig::setAutoExposureCompensationSteps);
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
        return new OptionInstance<>(
                "prime.options.display.reference_white",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.display.reference_white.tooltip")),
                (caption, nits) -> Options.genericValueLabel(
                        caption,
                        nits == HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS
                                ? automaticLabel
                                : Component.literal(nits + " nit")),
                new OptionInstance.IntRange(0, maximumNits),
                configuredNits,
                PrimeConfig::setReferenceWhiteNits);
    }

    private static OptionInstance<Integer> defaultRoughness() {
        return new OptionInstance<>(
                "prime.options.material.default_roughness",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.default_roughness.tooltip")),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(formatRoughness(steps))),
                new OptionInstance.IntRange(
                        MaterialSettings.MINIMUM_ROUGHNESS_STEPS,
                        MaterialSettings.MAXIMUM_ROUGHNESS_STEPS),
                PrimeConfig.settings().defaultRoughnessSteps(),
                PrimeConfig::setDefaultRoughnessSteps);
    }

    private static OptionInstance<Boolean> seamlessGlass() {
        return OptionInstance.createBoolean(
                "prime.options.material.seamless_glass",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.seamless_glass.tooltip")),
                PrimeConfig.settings().seamlessGlass(),
                PrimeConfig::setSeamlessGlass);
    }

    private static OptionInstance<Boolean> airGap() {
        return OptionInstance.createBoolean(
                "prime.options.material.air_gap",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.air_gap.tooltip")),
                PrimeConfig.settings().airGap(),
                PrimeConfig::setAirGap);
    }

    private static OptionInstance<Boolean> vanillaPbrPresets() {
        return OptionInstance.createBoolean(
                "prime.options.material.vanilla_pbr_presets",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.vanilla_pbr_presets.tooltip")),
                PrimeConfig.settings().vanillaPbrPresets(),
                PrimeConfig::setVanillaPbrPresets);
    }

    private static OptionInstance<NrdInputView> nrdInputView(Runnable changed) {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return new OptionInstance<>(
                "prime.options.debug.nrd_input",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.debug.nrd_input.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.debug.image_view." + mode.id()),
                new OptionInstance.SliderableEnum<>(
                        NRD_INPUT_VIEWS,
                        Codec.STRING.xmap(NrdInputView::fromId, NrdInputView::id)),
                runtime.nrdInputView(),
                value -> {
                    runtime.setNrdInputView(value);
                    changed.run();
                });
    }

    private static OptionInstance<Integer> exposureOption(
            String captionKey,
            String tooltipKey,
            int initialQuarterSteps,
            int minimumQuarterSteps,
            int maximumQuarterSteps,
            OptionInstance.ValueUpdateListener<Integer> listener) {
        return new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(tooltipKey)),
                (caption, quarterSteps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(formatExposure(quarterSteps))),
                new OptionInstance.IntRange(
                        minimumQuarterSteps,
                        maximumQuarterSteps),
                initialQuarterSteps,
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

    private static OptionInstance<Boolean> dlssFrameGenerationEnabled() {
        return OptionInstance.createBoolean(
                "prime.options.streamline.dlss_frame_generation",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.streamline.dlss_frame_generation.tooltip")),
                PrimeConfig.dlssFrameGenerationEnabled(),
                PrimeConfig::setDlssFrameGenerationEnabled);
    }

    private static OptionInstance<Integer> dlssFrameGenerationMultiplier() {
        int maximumMultiplier = StreamlineFrameGeneration.maximumMultiplier();
        int effectiveMultiplier = Math.min(
                PrimeConfig.dlssFrameGenerationMultiplier(), maximumMultiplier);
        return new OptionInstance<>(
                "prime.options.streamline.dlss_frame_generation_multiplier",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.streamline.dlss_frame_generation_multiplier.tooltip")),
                (caption, multiplier) -> Options.genericValueLabel(
                        caption, Component.literal(multiplier + "x")),
                new OptionInstance.IntRange(2, maximumMultiplier),
                effectiveMultiplier,
                PrimeConfig::setDlssFrameGenerationMultiplier);
    }

    private static OptionInstance<Boolean> dlssFrameGenerationUiRecomposition() {
        return OptionInstance.createBoolean(
                "prime.options.streamline.dlss_frame_generation_ui_recomposition",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.streamline.dlss_frame_generation_ui_recomposition.tooltip")),
                PrimeConfig.dlssFrameGenerationUiRecomposition(),
                PrimeConfig::setDlssFrameGenerationUiRecomposition);
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
            Rendering rendering,
            Lighting lighting,
            Display display,
            Material material,
            Diagnostics diagnostics,
            Streamline streamline) {
    }

    public record Rendering(
            OptionInstance<Boolean> pathTracingEnabled,
            OptionInstance<Integer> additionalSpecularBounces,
            OptionInstance<Integer> minimumBounces,
            OptionInstance<Integer> maximumBounces,
            OptionInstance<Integer> terrainWorkerPercentage,
            OptionInstance<SurfaceDetailMode> surfaceDetailMode,
            OptionInstance<Integer> voxelTextureSurfaceStrength,
            OptionInstance<Boolean> screenshotMode,
            OptionInstance<PostProcessingMode> postProcessingMode,
            OptionInstance<ReconstructionQualityMode> qualityMode,
            OptionInstance<Float> rrResponsivity) {
    }

    public record Lighting(
            OptionInstance<Integer> latitude,
            OptionInstance<Integer> season,
            OptionInstance<Integer> sunExposure,
            OptionInstance<Integer> starExposure,
            OptionInstance<Integer> blockLightExposure,
            OptionInstance<TransparentNeeMode> transparentNeeMode) {
    }

    public record Display(
            OptionInstance<Boolean> hdr,
            OptionInstance<Integer> referenceWhiteNits,
            OptionInstance<Integer> autoExposureCompensation,
            OptionInstance<Integer> finalExposure) {
    }

    public record Material(
            OptionInstance<Integer> defaultRoughness,
            OptionInstance<Boolean> seamlessGlass,
            OptionInstance<Boolean> airGap,
            OptionInstance<Boolean> vanillaPbrPresets) {
    }

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
