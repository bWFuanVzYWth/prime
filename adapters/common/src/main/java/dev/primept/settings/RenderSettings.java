package dev.primept.settings;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Immutable client settings. Version adapters own widgets; native consumes the validated wire. */
public final class RenderSettings {
    public static final int VERSION = 1;
    public static final int WIRE_BYTES = 48;
    public enum Control {
        BOUNCES("render.bounces", 1, 64, 4),
        OFFLINE_SAMPLES("render.offline_samples", 1, 64, 1),
        SUN_EV("lighting.sun_ev_quarters", -32, 32, 0),
        SKY_EV("lighting.sky_ev_quarters", -32, 32, 0),
        EXPOSURE_EV("display.exposure_ev_quarters", -48, 48, 0),
        HUE("display.hue_percent", 0, 100, 75),
        SATURATION("display.saturation_percent", 0, 50, 8),
        DEPTH_RANGE("diagnostics.depth_range", 1, 4096, 128);

        public final String key;
        public final int minimum, maximum, initial;
        Control(String key, int minimum, int maximum, int initial) {
            this.key = key;
            this.minimum = minimum;
            this.maximum = maximum;
            this.initial = initial;
        }
        void validate(int value) {
            if (value < minimum || value > maximum)
                throw new IllegalArgumentException(key + " out of range");
        }
    }
    public enum View { OUTPUT, NOISY_COLOR, LINEAR_DEPTH, NORMAL }
    private final boolean pathTracing;
    private final int[] values;

    private RenderSettings(boolean pathTracing, int[] values) {
        this.pathTracing = pathTracing;
        this.values = values;
    }
    public static RenderSettings defaults() {
        return new RenderSettings(
                true, Arrays.stream(Control.values()).mapToInt(c -> c.initial).toArray());
    }
    public boolean pathTracing() {
        return pathTracing;
    }
    public int value(Control control) {
        return values[control.ordinal()];
    }
    public RenderSettings withPathTracing(boolean value) {
        return new RenderSettings(value, values);
    }
    public RenderSettings with(Control control, int value) {
        control.validate(value);
        int[] next = values.clone();
        next[control.ordinal()] = value;
        return new RenderSettings(pathTracing, next);
    }
    /** Little-endian, borrowed only for prime_configure; offline and view are session controls. */
    public void write(ByteBuffer target, boolean offline, View view) {
        target.clear();
        target.putInt(VERSION)
                .putInt(offline ? 1 : 0)
                .putInt(value(Control.BOUNCES))
                .putInt(value(Control.OFFLINE_SAMPLES))
                .putFloat(multiplier(Control.EXPOSURE_EV))
                .putFloat(value(Control.HUE) / 100.0f)
                .putFloat(value(Control.SATURATION) / 100.0f)
                .putInt(view.ordinal())
                .putFloat(multiplier(Control.SUN_EV))
                .putFloat(multiplier(Control.SKY_EV))
                .putFloat(value(Control.DEPTH_RANGE))
                .putInt(0x13572468);
    }
    private float multiplier(Control control) {
        return (float)Math.pow(2.0, value(control) / 4.0);
    }
    @Override
    public boolean equals(Object other) {
        return other instanceof RenderSettings settings && pathTracing == settings.pathTracing &&
                Arrays.equals(values, settings.values);
    }
    @Override
    public int hashCode() {
        return 31 * Boolean.hashCode(pathTracing) + Arrays.hashCode(values);
    }
}
