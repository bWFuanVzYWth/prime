package dev.primept.capture;

import static dev.primept.abi.PrimeAbi.*;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.Window;
import dev.primept.NativeBridge;
import dev.primept.PresentationFps;
import dev.primept.PresentationFrameRate;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.LongSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import sun.misc.Unsafe;

/** Executes the actual transformed F3 consumer with SDK counters, without creating a window. */
public final class FpsDisplayCpuSmoke {
    public static boolean nativeVideoModeProbe;
    private static long now, epoch, total, sample;
    private static int status, active, valid, calls;
    private static String line;
    private FpsDisplayCpuSmoke() {}

    public static void run() throws Exception {
        var unsafe = (Unsafe)field(Unsafe.class, "theUnsafe").get(null);
        var instance = field(Minecraft.class, "instance");
        var fps = field(Minecraft.class, "fps");
        var binding = field(NativeBridge.class, "presentationStats");
        var clock = field(PresentationFps.class, "clock");
        var rate = field(PresentationFps.class, "rate");
        var polled = field(PresentationFps.class, "polled");
        var polledAt = field(PresentationFps.class, "polledAt");
        Object oldInstance = instance.get(null), oldBinding = binding.get(null),
               oldClock = clock.get(null), oldRate = rate.get(null);
        int oldFps = fps.getInt(null);
        boolean oldPolled = polled.getBoolean(null), oldProbe = nativeVideoModeProbe;
        long oldPolledAt = polledAt.getLong(null);
        Language oldLanguage = Language.getInstance();
        var minecraft = (Minecraft)unsafe.allocateInstance(Minecraft.class);
        var tracker = (FramerateLimitTracker)unsafe.allocateInstance(ActiveLimitTracker.class);
        field(FramerateLimitTracker.class, "framerateLimit").setInt(tracker, 260);
        field(Minecraft.class, "framerateLimitTracker").set(minecraft, tracker);
        field(Minecraft.class, "window").set(minecraft, unsafe.allocateInstance(Window.class));
        var surfaceField = field(Minecraft.class, "windowSurface");
        Class<?> surfaceType = surfaceField.getType();
        Class<?> modeType = Class.forName(surfaceType.getName() + "$PresentMode");
        Object fifo = java.util.Arrays.stream(modeType.getEnumConstants())
                              .filter(v -> ((Enum<?>)v).name().equals("FIFO"))
                              .findFirst()
                              .orElseThrow();
        Object configuration = Class.forName(surfaceType.getName() + "$Configuration")
                                       .getConstructor(int.class, int.class, modeType)
                                       .newInstance(1920, 1080, fifo);
        if (surfaceType.isInterface())
            surfaceField.set(
                    minecraft,
                    Proxy.newProxyInstance(surfaceType.getClassLoader(),
                                           new Class<?>[] {surfaceType}, (p, method, args) -> {
                                               if (method.getName().equals("currentConfiguration"))
                                                   return Optional.of(configuration);
                                               throw new AssertionError(method);
                                           }));
        else {
            Object surface = unsafe.allocateInstance(surfaceType);
            field(surfaceType, "currentConfiguration").set(surface, Optional.of(configuration));
            surfaceField.set(minecraft, surface);
        }
        var displayer = (DebugScreenDisplayer)Proxy.newProxyInstance(
                DebugScreenDisplayer.class.getClassLoader(),
                new Class<?>[] {DebugScreenDisplayer.class}, (p, method, args) -> {
                    if (!method.getName().equals("addPriorityLine") || line != null)
                        throw new AssertionError("Exactly one real F3 line: " + method);
                    line = (String)args[0];
                    return null;
                });
        try {
            instance.set(null, minecraft);
            fps.setInt(null, 60);
            binding.set(null, MethodHandles.lookup().findStatic(
                                      FpsDisplayCpuSmoke.class, "statistics",
                                      MethodType.methodType(int.class, MemorySegment.class)));
            clock.set(null, (LongSupplier)() -> now);
            nativeVideoModeProbe = true;
            var entry = new DebugEntryFps();
            for (String locale : java.util.List.of("en_us", "zh_cn")) {
                var translations = new HashMap<String, String>();
                var mod = FabricLoader.getInstance().getModContainer("primept").orElseThrow();
                try (var input = Files.newInputStream(
                             mod.findPath("assets/primept/lang/" + locale + ".json")
                                     .orElseThrow())) {
                    Language.loadFromJson(input, translations::put);
                }
                Language.inject(new Language() {
                    public String getOrDefault(String key, String fallback) {
                        return translations.getOrDefault(key,
                                                         oldLanguage.getOrDefault(key, fallback));
                    }
                    public boolean has(String key) {
                        return translations.containsKey(key) || oldLanguage.has(key);
                    }
                    public boolean isDefaultRightToLeft() {
                        return false;
                    }
                    public FormattedCharSequence getVisualOrder(FormattedText text) {
                        return oldLanguage.getVisualOrder(text);
                    }
                });
                rate.set(null, new PresentationFrameRate());
                polled.setBoolean(null, false);
                calls = 0;
                now = total = 0;
                epoch = sample = 1;
                status = -1;
                active = valid = 1;
                String vanilla = display(entry, displayer);
                check(vanilla.startsWith("60 fps T: inf (fifo)"), vanilla);
                check(surfaceType.isInterface() ? vanilla.endsWith(" @144Hz")
                                                : vanilla.equals("60 fps T: inf (fifo)"),
                      "Host refresh label: " + vanilla);
                now = 250_000_000;
                status = 0;
                check(display(entry, displayer).equals(vanilla), "Warmup must preserve vanilla");
                now = 1_250_000_000;
                total = 120;
                sample = 2;
                String suffix = locale.equals("en_us") ? " (rendered 60 fps)" : " (渲染 60 FPS)";
                String expected = "120" + vanilla.substring(2) + suffix;
                check(display(entry, displayer).equals(expected), "Actual SDK total and locale");
                int before = calls;
                for (int n = 0; n < 20; ++n)
                    check(display(entry, displayer).equals(expected), "Repeated consumer read");
                check(calls == before, "Repeated HUD reads must not poll native again");
                check(minecraft.getFps() == 60, "Global rendering FPS must remain unchanged");
                now = 1_500_000_000;
                status = 1;
                check(display(entry, displayer).equals(expected), "Busy retains fresh measurement");
                now = 3_000_000_000L;
                check(display(entry, displayer).equals(vanilla), "Busy sample must become stale");
                now = 3_250_000_000L;
                status = 0;
                total = 240;
                sample = 3;
                check(display(entry, displayer).equals(vanilla), "Stale source must rewarm");
                now = 4_250_000_000L;
                total = 330;
                sample = 4;
                check(display(entry, displayer).equals("90" + vanilla.substring(2) + suffix),
                      "Dropped interpolation must not be displayed as fixed 2x");
                now = 5_250_000_000L;
                sample = 5;
                check(display(entry, displayer).equals("0" + vanilla.substring(2) + suffix),
                      "Valid zero SDK presentations must remain observable");
                now = 5_500_000_000L;
                active = 0;
                check(display(entry, displayer).equals(vanilla), "FG off clears old rate");
                now = 5_750_000_000L;
                active = 1;
                valid = 0;
                check(display(entry, displayer).equals(vanilla),
                      "Unknown SDK state preserves host");
                now = 6_000_000_000L;
                valid = 1;
                ++epoch;
                total = 0;
                sample = 1;
                check(display(entry, displayer).equals(vanilla), "New slow stream warms once");
                now = 8_000_000_000L;
                total = 2;
                sample = 2;
                check(display(entry, displayer).equals("1" + vanilla.substring(2) + suffix),
                      "Fresh SDK samples every 2s must display actual elapsed-time FPS");
                now = 10_000_000_000L;
                total = 6;
                sample = 3;
                check(display(entry, displayer).equals("2" + vanilla.substring(2) + suffix),
                      "Continuous slow F3 consumer must not repeatedly rewarm");
            }
        } finally {
            Language.inject(oldLanguage);
            instance.set(null, oldInstance);
            fps.setInt(null, oldFps);
            binding.set(null, oldBinding);
            clock.set(null, oldClock);
            rate.set(null, oldRate);
            polled.setBoolean(null, oldPolled);
            polledAt.setLong(null, oldPolledAt);
            nativeVideoModeProbe = oldProbe;
        }
        System.out.println(
                "PRIME_FPS_DISPLAY_CPU_SMOKE_OK: real transformed F3 en/zh, actual SDK totals, dropped/zero frames, busy/stale/off/failure, rendering FPS and T/vsync/Hz preserved; no GPU/window");
    }

    private static String display(DebugEntryFps entry, DebugScreenDisplayer displayer) {
        line = null;
        entry.display(displayer, null, null, null);
        if (line == null)
            throw new AssertionError("Real F3 consumer did not publish a line");
        return line;
    }

    private static int statistics(MemorySegment output) {
        ++calls;
        var header = PrimePresentationStats.header(output);
        if (PrimeHeader.struct_size(header) != PrimePresentationStats.SIZE ||
            PrimeHeader.abi_version(header) != PRIME_ABI_VERSION)
            throw new AssertionError("Presentation snapshot header");
        if (status == 0) {
            PrimePresentationStats.epoch(output, epoch);
            PrimePresentationStats.total_presented(output, total);
            PrimePresentationStats.sample_id(output, sample);
            PrimePresentationStats.active(output, active);
            PrimePresentationStats.valid(output, valid);
        }
        return status;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Native focus/AFK queries are unrelated to the actual F3 metric consumer under test. */
    private static final class ActiveLimitTracker extends FramerateLimitTracker {
        private ActiveLimitTracker() {
            super(null, null);
        }
        @Override
        public FramerateThrottleReason getThrottleReason() {
            return FramerateThrottleReason.NONE;
        }
    }

    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message + ": " + line);
    }
}
