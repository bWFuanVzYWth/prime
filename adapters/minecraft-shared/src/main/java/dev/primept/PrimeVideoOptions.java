package dev.primept;

import com.mojang.serialization.Codec;
import dev.primept.settings.RenderSettings;
import dev.primept.settings.RenderSettings.Control;
import dev.primept.settings.RenderSettings.View;
import dev.primept.settings.RenderSettings.DlssQuality;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.network.chat.Component;

/** Live Prime controls embedded in the host Video Settings list. */
public final class PrimeVideoOptions {
    private final OptionsList list;
    private final Runnable rebuildScreen;
    private final EnumMap<Control, OptionInstance<Integer>> controls = new EnumMap<>(Control.class);
    private OptionInstance<Boolean> enabled, offline, opacityMicromap, rayReconstruction;
    private OptionInstance<View> view;
    private OptionInstance<DlssQuality> dlssQuality;
    private PrimeVideoOptions(OptionsList list, Runnable rebuildScreen) {
        this.list = list;
        this.rebuildScreen = rebuildScreen;
    }

    /** Append once before vanilla options; each host rebuild creates a fresh option owner. */
    public static PrimeVideoOptions addTo(OptionsList list, Runnable rebuildScreen) {
        var options = new PrimeVideoOptions(list, rebuildScreen);
        options.addOptions();
        return options;
    }

    public static OptionInstance<Integer> control(Control control) {
        String key = "primept.settings." + control.key;
        boolean toggle = control == Control.HDR || control == Control.FRAME_GENERATION;
        return new OptionInstance<>(
                key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                (caption, value)
                        -> {
                    if (toggle)
                        return Component.translatable(value == 0 ? "options.off" : "options.on");
                    String formatted = switch (control) {
                        case SUN_EV, SKY_EV, EXPOSURE_EV ->
                            String.format(Locale.ROOT, "%+.2f EV", value / 4.0);
                        case LATITUDE, SOLAR_LONGITUDE -> value + "°";
                        case HUE, SATURATION, STARS, AUTO_EXPOSURE -> value + "%";
                        case HDR_WHITE ->
                            value == 0 ? Component.translatable("primept.settings.automatic")
                                                 .getString()
                                       : value + " nit";
                        default -> Integer.toString(value);
                    };
                    return Options.genericValueLabel(caption, Component.literal(formatted));
                },
                toggle ? new OptionInstance.Enum<>(List.of(0, 1), Codec.INT)
                       : new OptionInstance.IntRange(control.minimum, control.maximum),
                PrimeClient.settings().value(control),
                value -> PrimeClient.updateSettings(PrimeClient.settings().with(control, value)));
    }
    private void addOptions() {
        for (var control : Control.values())
            controls.put(control, control(control));
        list.addBig(Button.builder(Component.translatable("primept.settings.reset"), button -> {
                              list.applyUnsavedChanges();
                              PrimeClient.restoreSettings();
                              rebuildScreen.run();
                          }).build());
        list.addHeader(Component.translatable("primept.settings.render"));
        enabled = OptionInstance.createBoolean(
                "primept.settings.enabled",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.enabled.tooltip")),
                PrimeClient.settings().pathTracing(),
                value -> PrimeClient.updateSettings(PrimeClient.settings().withPathTracing(value)));
        offline = OptionInstance.createBoolean(
                "primept.settings.offline",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.offline.tooltip")),
                PrimeClient.offlineRequested(), PrimeClient::requestOffline);
        list.addBig(enabled);
        list.addBig(offline);
        opacityMicromap = OptionInstance.createBoolean(
                "primept.settings.opacity_micromap",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.opacity_micromap.tooltip")),
                PrimeClient.settings().opacityMicromap(),
                value
                -> PrimeClient.updateSettings(PrimeClient.settings().withOpacityMicromap(value)));
        rayReconstruction = OptionInstance.createBoolean(
                "primept.settings.ray_reconstruction",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.ray_reconstruction.tooltip")),
                PrimeClient.settings().rayReconstruction(),
                value
                -> PrimeClient.updateSettings(PrimeClient.settings().withRayReconstruction(value)));
        dlssQuality = new OptionInstance<>(
                "primept.settings.dlss_quality",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.dlss_quality.tooltip")),
                (caption, value)
                        -> Component.translatable("primept.settings.dlss_quality." +
                                                  value.name().toLowerCase(Locale.ROOT)),
                new OptionInstance.Enum<>(
                        List.of(DlssQuality.values()),
                        Codec.STRING.xmap(DlssQuality::valueOf, DlssQuality::name)),
                PrimeClient.settings().dlssQuality(),
                value -> PrimeClient.updateSettings(PrimeClient.settings().withDlssQuality(value)));
        list.addBig(dlssQuality);
        list.addBig(controls.get(Control.FRAME_GENERATION));
        list.addBig(controls.get(Control.BOUNCES));
        list.addBig(controls.get(Control.OFFLINE_SAMPLES));
        list.addBig(controls.get(Control.TERRAIN_BATCHES_PER_FRAME));
        list.addHeader(Component.translatable("primept.settings.lighting"));
        list.addBig(controls.get(Control.SUN_EV));
        list.addBig(controls.get(Control.SKY_EV));
        list.addBig(controls.get(Control.STARS));
        list.addBig(controls.get(Control.LATITUDE));
        list.addBig(controls.get(Control.SOLAR_LONGITUDE));
        list.addHeader(Component.translatable("primept.settings.display"));
        list.addBig(controls.get(Control.EXPOSURE_EV));
        list.addBig(controls.get(Control.AUTO_EXPOSURE));
        list.addBig(controls.get(Control.HDR));
        list.addBig(controls.get(Control.HDR_WHITE));
        list.addBig(controls.get(Control.HUE));
        list.addBig(controls.get(Control.SATURATION));
        list.addHeader(Component.translatable("primept.settings.diagnostics"));
        list.addBig(opacityMicromap);
        list.addBig(rayReconstruction);
        view = new OptionInstance<>(
                "primept.settings.view",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.view.tooltip")),
                (caption, value)
                        -> Component.translatable("primept.settings.view." +
                                                  value.name().toLowerCase(Locale.ROOT)),
                new OptionInstance.Enum<>(List.of(View.values()),
                                          Codec.STRING.xmap(View::valueOf, View::name)),
                PrimeClient.diagnosticView(), PrimeClient::setDiagnosticView);
        list.addBig(view);
        list.addBig(controls.get(Control.DEPTH_RANGE));
        refresh();
    }
    public void tick() {
        if (offline.get() != PrimeClient.offlineRequested()) {
            offline.set(PrimeClient.offlineRequested());
            if (list.findOption(offline) instanceof CycleButton<?> button) {
                @SuppressWarnings("unchecked") var toggle = (CycleButton<Boolean>)button;
                toggle.setValue(offline.get());
            }
        }
        refresh();
    }
    private void refresh() {
        boolean frozen = PrimeClient.offlineRequested() || PrimeClient.offlineActive();
        list.findOption(enabled).active = PrimeClient.controlsAvailable();
        list.findOption(offline).active = PrimeClient.controlsAvailable() &&
                                          PrimeClient.settings().pathTracing() &&
                                          Minecraft.getInstance().level != null;
        list.findOption(opacityMicromap).active = PrimeClient.controlsAvailable();
        list.findOption(rayReconstruction).active = PrimeClient.controlsAvailable() && !frozen;
        list.findOption(controls.get(Control.FRAME_GENERATION)).active =
                PrimeClient.controlsAvailable() && !frozen &&
                PrimeClient.settings().rayReconstruction();
        list.findOption(dlssQuality).active = PrimeClient.controlsAvailable() && !frozen &&
                                              PrimeClient.settings().rayReconstruction();
        for (var control : List.of(Control.BOUNCES, Control.SUN_EV, Control.SKY_EV, Control.STARS,
                                   Control.LATITUDE, Control.SOLAR_LONGITUDE))
            list.findOption(controls.get(control)).active = !frozen;
        list.findOption(view).active = !frozen;
        list.findOption(controls.get(Control.DEPTH_RANGE)).active = !frozen;
    }
    public void removed() {
        PrimeClient.saveSettings();
    }
}
