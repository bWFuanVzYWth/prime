package dev.primept;

import com.mojang.serialization.Codec;
import dev.primept.settings.RestirSettings;
import dev.primept.settings.RestirSettings.Control;
import dev.primept.settings.RenderSettings.Renderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** ReSTIR PT's point/Hybrid controls, shared by both version adapters. */
public final class RestirVideoOptions {
    private final OptionsList list;
    private final List<OptionInstance<?>> controls = new ArrayList<>();
    private final OptionInstance<Boolean> temporalReuse;
    private final EditBox seed;

    public RestirVideoOptions(OptionsList list) {
        this.list = list;
        temporalReuse = OptionInstance.createBoolean(
                "primept.settings.restir_pt.temporal_reuse",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "primept.settings.restir_pt.temporal_reuse.tooltip")),
                PrimeClient.settings().restirTemporalReuse(),
                value
                -> PrimeClient.updateSettings(
                        PrimeClient.settings().withRestirTemporalReuse(value)));
        list.addBig(temporalReuse);
        for (var control : Control.values()) {
            var option = control(control);
            controls.add(option);
            list.addBig(option);
        }
        var caption = Component.translatable("primept.settings.restir_pt.seed");
        seed = new EditBox(Minecraft.getInstance().font, 150, 20, caption);
        seed.setMaxLength(10);
        seed.setTooltip(
                Tooltip.create(Component.translatable("primept.settings.restir_pt.seed.tooltip")));
        seed.setValue(Long.toString(PrimeClient.settings().restir().seed()));
        seed.setResponder(text -> {
            try {
                long value = Long.parseLong(text);
                var changed = PrimeClient.settings().restir().withSeed(value);
                seed.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
                PrimeClient.updateSettings(PrimeClient.settings().withRestir(changed));
            } catch (IllegalArgumentException invalid) {
                seed.setTextColor(0xffff5555);
            }
        });
        list.addSmall(new StringWidget(caption, Minecraft.getInstance().font), seed);
    }

    public static OptionInstance<?> control(Control control) {
        String key = "primept.settings." + control.key;
        var settings = PrimeClient.settings().restir();
        if (control.kind == RestirSettings.Kind.BOOLEAN)
            return OptionInstance.createBoolean(
                    key,
                    OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                    settings.enabled(control),
                    value
                    -> PrimeClient.updateSettings(PrimeClient.settings().withRestir(
                            PrimeClient.settings().restir().with(control, value ? 1 : 0))));
        if (control.kind == RestirSettings.Kind.FLOAT)
            return new OptionInstance<>(
                    key,
                    OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                    (caption, value)
                            -> Options.genericValueLabel(
                                    caption,
                                    Component.literal(String.format(Locale.ROOT, "%.4g", value))),
                    new FloatRange(control), settings.value(control),
                    value
                    -> PrimeClient.updateSettings(PrimeClient.settings().withRestir(
                            PrimeClient.settings().restir().with(control, value))));
        int step = control.step;
        return new OptionInstance<>(
                key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                (caption, value)
                        -> control == Control.DEBUG_VIEW || control == Control.RR_MODE
                                   ? Component.translatable(key + "." + value)
                                   : Options.genericValueLabel(
                                             caption,
                                             Component.literal(Integer.toString(value * step))),
                control == Control.DEBUG_VIEW || control == Control.RR_MODE
                        ? new OptionInstance.Enum<>(List.of(0, 1, 2), Codec.INT)
                        : new OptionInstance.IntRange((int)control.minimum / step,
                                                      (int)control.maximum / step),
                (int)settings.value(control) / step,
                value
                -> PrimeClient.updateSettings(PrimeClient.settings().withRestir(
                        PrimeClient.settings().restir().with(control, value * step))));
    }

    /** The large Falcor distance range needs fine control around the default 0.02. */
    public record FloatRange(Control control) implements OptionInstance.SliderableValueSet<Double> {
        @Override
        public double toSliderValue(Double value) {
            return control == Control.DISTANCE_THRESHOLD
                    ? Math.log1p(value * 100.0) / Math.log1p(control.maximum * 100.0)
                    : (value - control.minimum) / (control.maximum - control.minimum);
        }
        @Override
        public Double fromSliderValue(double value) {
            double raw = control == Control.DISTANCE_THRESHOLD
                                 ? Math.expm1(value * Math.log1p(control.maximum * 100.0)) / 100.0
                                 : control.minimum + value * (control.maximum - control.minimum);
            return control.normalize(Math.clamp(raw, control.minimum, control.maximum));
        }
        @Override
        public Optional<Double> validateValue(Double value) {
            try {
                control.validate(value);
                return Optional.of(control.normalize(value));
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        }
        @Override
        public Codec<Double> codec() {
            return Codec.doubleRange(control.minimum, control.maximum);
        }
    }

    public void refresh() {
        boolean temporal = PrimeClient.settings().restirTemporalReuse();
        if (temporalReuse.get() != temporal) {
            temporalReuse.set(temporal);
            if (list.findOption(temporalReuse) instanceof CycleButton<?> button) {
                @SuppressWarnings("unchecked") var toggle = (CycleButton<Boolean>)button;
                toggle.setValue(temporal);
            }
        }
        boolean active = PrimeClient.controlsAvailable() && !PrimeClient.offlineRequested() &&
                         !PrimeClient.offlineActive() &&
                         PrimeClient.settings().renderer() == Renderer.RESTIR_PT;
        list.findOption(temporalReuse).active = active;
        for (var option : controls)
            list.findOption(option).active = active;
        seed.active = active;
        seed.setEditable(active);
    }
}
