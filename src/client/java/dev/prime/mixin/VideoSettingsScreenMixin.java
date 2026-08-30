package dev.prime.mixin;

import dev.prime.binding.streamline.ReflexMode;
import dev.prime.client.PrimeVideoOptions;
import dev.prime.config.PrimeConfig;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.MaximumBounceSettings;
import dev.prime.render.MinimumBounceSettings;
import dev.prime.client.PrimeRuntime;
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
import dev.prime.streamline.StreamlineFrameGeneration;
import dev.prime.streamline.StreamlineReflex;
import java.net.URI;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Prime's live controls to the vanilla Video Settings screen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    private static final URI PRIME$REPOSITORY =
            URI.create("https://github.com/bWFuanVzYWth/prime");
    private static final Component PRIME$HEADER =
            Component.translatable("prime.options.header");
    private static final Component PRIME$RENDERING_HEADER =
            Component.translatable("prime.options.header.rendering");
    private static final Component PRIME$LIGHTING_HEADER =
            Component.translatable("prime.options.header.lighting");
    private static final Component PRIME$DISPLAY_HEADER =
            Component.translatable("prime.options.header.display");
    private static final Component PRIME$MATERIAL_HEADER =
            Component.translatable("prime.options.header.material");
    private static final Component PRIME$STREAMLINE_HEADER =
            Component.translatable("prime.options.header.streamline");
    private static final Component PRIME$HIGH_RISK_HEADER =
            Component.translatable("prime.options.header.high_risk");
    private static final Component PRIME$DIAGNOSTICS_HEADER =
            Component.translatable("prime.options.header.diagnostics");
    @Unique private PrimeVideoOptions.OptionSet prime$options;
    @Unique private boolean prime$refreshingDiagnostics;

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void prime$addOptions(CallbackInfo callbackInfo) {
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        if (list != null) {
            this.prime$options = PrimeVideoOptions.create(
                    this::prime$refreshDiagnosticOptions);
            list.addHeader(PRIME$HEADER);
            list.addBig(Button.builder(
                            Component.translatable("prime.options.restore_defaults"),
                            button -> this.prime$restoreDefaults())
                    .build());
            list.addHeader(PRIME$RENDERING_HEADER);
            list.addBig(this.prime$options.rendering().pathTracingEnabled());
            list.addBig(this.prime$options.rendering().screenshotMode());
            list.addBig(this.prime$options.rendering().additionalSpecularBounces());
            list.addBig(this.prime$options.rendering().minimumBounces());
            list.addBig(this.prime$options.rendering().maximumBounces());
            list.addBig(this.prime$options.rendering().terrainWorkerPercentage());
            list.addSmall(
                    this.prime$options.rendering().surfaceDetailMode(),
                    this.prime$options.rendering().voxelTextureSurfaceStrength());
            list.addSmall(this.prime$options.rendering().postProcessingMode(), this.prime$options.rendering().qualityMode());
            list.addBig(this.prime$options.rendering().rrResponsivity());
            list.addHeader(PRIME$LIGHTING_HEADER);
            list.addSmall(this.prime$options.lighting().latitude(), this.prime$options.lighting().season());
            list.addBig(this.prime$options.lighting().sunExposure());
            list.addBig(this.prime$options.lighting().starExposure());
            list.addBig(this.prime$options.lighting().blockLightExposure());
            list.addBig(this.prime$options.lighting().transparentNeeMode());
            list.addHeader(PRIME$DISPLAY_HEADER);
            list.addBig(this.prime$options.display().hdr());
            AbstractWidget hdrWidget = list.findOption(this.prime$options.display().hdr());
            if (hdrWidget != null) {
                hdrWidget.active = HdrOutput.capability().supported();
            }
            list.addBig(this.prime$options.display().referenceWhiteNits());
            AbstractWidget referenceWhiteWidget =
                    list.findOption(this.prime$options.display().referenceWhiteNits());
            if (referenceWhiteWidget != null) {
                referenceWhiteWidget.active = HdrOutput.capability().supported();
            }
            list.addBig(this.prime$options.display().autoExposureCompensation());
            list.addBig(this.prime$options.display().finalExposure());
            list.addHeader(PRIME$MATERIAL_HEADER);
            list.addSmall(this.prime$options.material().defaultRoughness(), this.prime$options.material().seamlessGlass());
            list.addSmall(this.prime$options.material().airGap(), this.prime$options.material().vanillaPbrPresets());
            list.addHeader(PRIME$STREAMLINE_HEADER);
            list.addBig(this.prime$options.streamline().reflexMode());
            list.addHeader(PRIME$HIGH_RISK_HEADER);
            list.addBig(this.prime$options.streamline().dlssFrameGenerationEnabled());
            list.addBig(this.prime$options.streamline().dlssFrameGenerationMultiplier());
            list.addBig(this.prime$options.streamline().dlssFrameGenerationUiRecomposition());
            this.prime$refreshStreamlineAvailability(list);
            list.addHeader(PRIME$DIAGNOSTICS_HEADER);
            list.addBig(this.prime$options.diagnostics().rendererDiagnostics());
            list.addBig(this.prime$options.diagnostics().rawOutput());
            list.addBig(this.prime$options.diagnostics().rendererImageView());
            list.addBig(this.prime$options.diagnostics().rrInputView());
            list.addBig(this.prime$options.diagnostics().nrdInputView());
            list.addBig(Button.builder(
                            Component.translatable("prime.options.open_repository"),
                            ConfirmLinkScreen.confirmLink(
                                    (VideoSettingsScreen) (Object) this,
                                    PRIME$REPOSITORY))
                    .build());
        }
    }

    @Unique
    private void prime$restoreDefaults() {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.restoreDefaults();
        RendererSettings current = PrimeConfig.rendererSettings();
        PrimeRuntime runtime = PrimeRuntime.instance();
        runtime.restoreSessionDefaults();
        if (previous.pathTracingEnabled() != current.pathTracingEnabled()) {
            runtime.pathTracingChanged(current.pathTracingEnabled());
        }
        if (previous.surfaceDetailMode() != current.surfaceDetailMode()) {
            runtime.surfaceDetailModeChanged();
        } else if (previous.voxelTextureSurfaceStrengthSteps()
                != current.voxelTextureSurfaceStrengthSteps()) {
            runtime.voxelTextureSurfaceStrengthChanged(
                    current.usesGeometryDisplacement(),
                    current.voxelTextureSurfaceStrengthSteps());
        }
        this.prime$refresh(this.prime$options.rendering().pathTracingEnabled(), true);
        this.prime$refresh(
                this.prime$options.rendering().additionalSpecularBounces(),
                SpecularBounceSettings.DEFAULT_COUNT);
        this.prime$refresh(
                this.prime$options.rendering().minimumBounces(),
                MinimumBounceSettings.DEFAULT_COUNT);
        this.prime$refresh(
                this.prime$options.rendering().maximumBounces(),
                MaximumBounceSettings.DEFAULT_COUNT);
        this.prime$refresh(
                this.prime$options.rendering().terrainWorkerPercentage(),
                TerrainWorkerSettings.DEFAULT_PERCENTAGE);
        this.prime$refresh(
                this.prime$options.rendering().surfaceDetailMode(),
                SurfaceDetailMode.DEFAULT);
        this.prime$refresh(
                this.prime$options.rendering().voxelTextureSurfaceStrength(),
                VoxelSurfaceSettings.DEFAULT_STEPS);
        this.prime$refresh(this.prime$options.rendering().screenshotMode(), false);
        this.prime$refresh(this.prime$options.rendering().postProcessingMode(), PostProcessingMode.DEFAULT);
        this.prime$refresh(this.prime$options.rendering().qualityMode(), ReconstructionQualityMode.DEFAULT);
        this.prime$refresh(
                this.prime$options.rendering().rrResponsivity(),
                RrResponsivity.DEFAULT);
        this.prime$refresh(
                this.prime$options.lighting().latitude(),
                AstronomySettings.DEFAULT_LATITUDE_DEGREES);
        this.prime$refresh(
                this.prime$options.lighting().season(),
                AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES);
        this.prime$refresh(
                this.prime$options.lighting().sunExposure(),
                LightingSettings.DEFAULT_SUN_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$options.lighting().starExposure(),
                LightingSettings.DEFAULT_STAR_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$options.lighting().blockLightExposure(),
                LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$options.lighting().transparentNeeMode(),
                TransparentNeeMode.DEFAULT);
        this.prime$refresh(
                this.prime$options.display().finalExposure(),
                DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$options.display().autoExposureCompensation(),
                DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS);
        this.prime$refresh(
                this.prime$options.display().referenceWhiteNits(),
                HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS);
        this.prime$refresh(this.prime$options.display().hdr(), false);
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        AbstractWidget hdrWidget = list.findOption(this.prime$options.display().hdr());
        if (hdrWidget != null) {
            hdrWidget.active = HdrOutput.capability().supported();
        }
        AbstractWidget referenceWhiteWidget =
                list.findOption(this.prime$options.display().referenceWhiteNits());
        if (referenceWhiteWidget != null) {
            referenceWhiteWidget.active = HdrOutput.capability().supported();
        }
        this.prime$refresh(
                this.prime$options.material().defaultRoughness(),
                MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
        this.prime$refresh(
                this.prime$options.material().seamlessGlass(),
                MaterialSettings.DEFAULT_SEAMLESS_GLASS);
        this.prime$refresh(this.prime$options.material().airGap(), MaterialSettings.DEFAULT_AIR_GAP);
        this.prime$refresh(
                this.prime$options.material().vanillaPbrPresets(),
                MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS);
        this.prime$refresh(this.prime$options.diagnostics().rendererDiagnostics(), false);
        this.prime$refresh(this.prime$options.diagnostics().rawOutput(), false);
        this.prime$refresh(this.prime$options.diagnostics().rendererImageView(), RendererImageView.OFF);
        this.prime$refresh(this.prime$options.diagnostics().rrInputView(), RrInputView.OFF);
        this.prime$refresh(this.prime$options.diagnostics().nrdInputView(), NrdInputView.OFF);
        this.prime$refresh(this.prime$options.streamline().reflexMode(), ReflexMode.OFF);
        this.prime$refresh(
                this.prime$options.streamline().dlssFrameGenerationEnabled(), false);
        this.prime$refresh(
                this.prime$options.streamline().dlssFrameGenerationMultiplier(), 2);
        this.prime$refresh(
                this.prime$options.streamline().dlssFrameGenerationUiRecomposition(), true);
        this.prime$refreshStreamlineAvailability(list);
    }

    @Unique
    private void prime$refreshStreamlineAvailability(OptionsList list) {
        AbstractWidget reflexWidget =
                list.findOption(this.prime$options.streamline().reflexMode());
        if (reflexWidget != null) {
            reflexWidget.active = StreamlineReflex.available()
                    || PrimeConfig.reflexMode() != ReflexMode.OFF;
        }
        boolean frameGenerationAvailable =
                StreamlineReflex.available() && StreamlineFrameGeneration.available();
        boolean frameGenerationEnabled = PrimeConfig.dlssFrameGenerationEnabled();
        AbstractWidget enableWidget = list.findOption(
                this.prime$options.streamline().dlssFrameGenerationEnabled());
        if (enableWidget != null) {
            // An unavailable saved option must remain switchable so users can turn it off.
            enableWidget.active = frameGenerationAvailable || frameGenerationEnabled;
        }
        for (OptionInstance<?> option : new OptionInstance<?>[] {
            this.prime$options.streamline().dlssFrameGenerationMultiplier(),
            this.prime$options.streamline().dlssFrameGenerationUiRecomposition()
        }) {
            AbstractWidget widget = list.findOption(option);
            if (widget != null) {
                widget.active = frameGenerationAvailable;
            }
        }
    }

    @Unique
    private void prime$refreshDiagnosticOptions() {
        if (this.prime$options == null || this.prime$refreshingDiagnostics) return;
        this.prime$refreshingDiagnostics = true;
        try {
            PrimeRuntime runtime = PrimeRuntime.instance();
            this.prime$refresh(
                    this.prime$options.diagnostics().rendererImageView(),
                    runtime.rendererImageView());
            this.prime$refresh(
                    this.prime$options.diagnostics().rrInputView(),
                    runtime.rrInputView());
            this.prime$refresh(
                    this.prime$options.diagnostics().nrdInputView(),
                    runtime.nrdInputView());
        } finally {
            this.prime$refreshingDiagnostics = false;
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private <T> void prime$refresh(OptionInstance<T> option, T value) {
        option.set(value);
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        if (list == null) {
            return;
        }
        AbstractWidget widget = list.findOption(option);
        if (widget instanceof CycleButton<?> cycleButton) {
            ((CycleButton<T>) cycleButton).setValue(value);
        } else {
            ((VideoSettingsScreen) (Object) this).resetOption(option);
        }
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void prime$saveOptions(CallbackInfo callbackInfo) {
        PrimeConfig.save();
    }
}
