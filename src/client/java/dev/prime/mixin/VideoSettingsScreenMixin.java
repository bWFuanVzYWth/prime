// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.binding.streamline.ReflexMode;
import dev.prime.client.PrimeVideoOptions;
import dev.prime.config.PrimeConfig;
import dev.prime.render.HdrOutput;
import dev.prime.client.PrimeRuntime;
import dev.prime.render.RendererSettings;
import dev.prime.streamline.StreamlineFrameGeneration;
import dev.prime.streamline.StreamlineReflex;
import java.net.URI;
import java.util.List;
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
            for (PrimeVideoOptions.Section section : this.prime$options.sections()) {
                list.addHeader(Component.translatable(section.titleKey()));
                for (PrimeVideoOptions.Row row : section.rows()) {
                    if (row.second() == null) {
                        list.addBig(row.first());
                    } else {
                        list.addSmall(row.first(), row.second());
                    }
                }
            }
            this.prime$refreshAvailability(list);
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
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        List<OptionInstance<?>> options = this.prime$options.options();
        List<OptionInstance<?>> defaults = PrimeVideoOptions.create(() -> {}).options();
        if (options.size() != defaults.size()) {
            throw new IllegalStateException("Prime video option layout changed while open");
        }
        this.prime$refreshingDiagnostics = true;
        try {
            for (int index = 0; index < options.size(); index++) {
                this.prime$refreshFrom(options.get(index), defaults.get(index));
            }
        } finally {
            this.prime$refreshingDiagnostics = false;
        }
        this.prime$refreshAvailability(list);
    }

    @Unique
    private void prime$refreshAvailability(OptionsList list) {
        boolean hdrAvailable = HdrOutput.capability().supported();
        for (OptionInstance<?> option : List.of(
                this.prime$options.hdr(), this.prime$options.referenceWhiteNits())) {
            AbstractWidget widget = list.findOption(option);
            if (widget != null) {
                widget.active = hdrAvailable;
            }
        }
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
    private void prime$refreshFrom(
            OptionInstance<?> option, OptionInstance<?> source) {
        this.prime$refresh(
                (OptionInstance<Object>) option,
                source.get());
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
