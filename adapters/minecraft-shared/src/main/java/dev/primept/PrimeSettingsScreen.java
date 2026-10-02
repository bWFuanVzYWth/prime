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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Version-owned widgets; persistence and the wire contract have no Minecraft dependency. */
public final class PrimeSettingsScreen extends OptionsSubScreen {
    private final EnumMap<Control, OptionInstance<Integer>> controls = new EnumMap<>(Control.class);
    private OptionInstance<Boolean> enabled, offline, opacityMicromap, rayReconstruction;
    private OptionInstance<View> view;
    private OptionInstance<DlssQuality> dlssQuality;
    public PrimeSettingsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
              Component.translatable("primept.settings.title"));
    }
    public static OptionInstance<Integer> control(Control control) {
        String key = "primept.settings." + control.key;
        return new OptionInstance<>(
                key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                (caption, value)
                        -> Options.genericValueLabel(caption, Component.literal(switch (control) {
                    case SUN_EV, SKY_EV, EXPOSURE_EV ->
                        String.format(Locale.ROOT, "%+.2f EV", value / 4.0);
                    case LATITUDE, SOLAR_LONGITUDE -> value + "°";
                    case HUE, SATURATION -> value + "%";
                    default -> Integer.toString(value);
                })),
                new OptionInstance.IntRange(control.minimum, control.maximum),
                PrimeClient.settings().value(control),
                value -> PrimeClient.updateSettings(PrimeClient.settings().with(control, value)));
    }
    @Override
    protected void init() {
        // Screen.rebuildWidgets clears its registries, but OptionsSubScreen retains this layout.
        layout.removeChildren();
        super.init();
    }
    @Override
    protected void addOptions() {
        controls.clear();
        for (var control : Control.values())
            controls.put(control, control(control));
        list.addBig(Button.builder(Component.translatable("primept.settings.reset"), button -> {
                              PrimeClient.restoreSettings();
                              rebuildWidgets();
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
        list.addSmall(enabled, offline);
        opacityMicromap = OptionInstance.createBoolean(
                "primept.settings.opacity_micromap",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.opacity_micromap.tooltip")),
                PrimeClient.settings().opacityMicromap(),
                value
                -> PrimeClient.updateSettings(PrimeClient.settings().withOpacityMicromap(value)));
        list.addBig(opacityMicromap);
        rayReconstruction = OptionInstance.createBoolean(
                "primept.settings.ray_reconstruction",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.ray_reconstruction.tooltip")),
                PrimeClient.settings().rayReconstruction(),
                value
                -> PrimeClient.updateSettings(PrimeClient.settings().withRayReconstruction(value)));
        list.addBig(rayReconstruction);
        dlssQuality = new OptionInstance<>(
                "primept.settings.dlss_quality",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("primept.settings.dlss_quality.tooltip")),
                (caption, value)
                        -> Options.genericValueLabel(
                                caption,
                                Component.translatable("primept.settings.dlss_quality." +
                                                       value.name().toLowerCase(Locale.ROOT))),
                new OptionInstance.Enum<>(
                        List.of(DlssQuality.values()),
                        Codec.STRING.xmap(DlssQuality::valueOf, DlssQuality::name)),
                PrimeClient.settings().dlssQuality(),
                value -> PrimeClient.updateSettings(PrimeClient.settings().withDlssQuality(value)));
        list.addBig(dlssQuality);
        list.addSmall(controls.get(Control.BOUNCES), controls.get(Control.OFFLINE_SAMPLES));
        list.addHeader(Component.translatable("primept.settings.lighting"));
        list.addSmall(controls.get(Control.SUN_EV), controls.get(Control.SKY_EV));
        list.addSmall(controls.get(Control.LATITUDE), controls.get(Control.SOLAR_LONGITUDE));
        list.addHeader(Component.translatable("primept.settings.display"));
        list.addBig(controls.get(Control.EXPOSURE_EV));
        list.addSmall(controls.get(Control.HUE), controls.get(Control.SATURATION));
        list.addHeader(Component.translatable("primept.settings.diagnostics"));
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
    @Override
    public void tick() {
        super.tick();
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
        list.findOption(dlssQuality).active = PrimeClient.controlsAvailable() && !frozen &&
                                              PrimeClient.settings().rayReconstruction();
        for (var control : List.of(Control.BOUNCES, Control.SUN_EV, Control.SKY_EV,
                                   Control.LATITUDE, Control.SOLAR_LONGITUDE))
            list.findOption(controls.get(control)).active = !frozen;
        list.findOption(view).active = !frozen;
        list.findOption(controls.get(Control.DEPTH_RANGE)).active = !frozen;
    }
    @Override
    public void removed() {
        super.removed();
        PrimeClient.saveSettings();
    }
}
