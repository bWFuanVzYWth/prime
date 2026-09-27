package dev.primept.capture;

import com.mojang.blaze3d.platform.InputConstants;
import dev.primept.PrimeClient;
import dev.primept.PrimeSettingsScreen;
import dev.primept.render.OfflineMode;
import dev.primept.settings.RenderSettings;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import sun.misc.Unsafe;

/** Real Mixin cancellation with unusable downstream objects: no window, world or device is created. */
public final class SettingsCpuSmoke {
    // CPU fixture replaces only the native key-state query; production key routing remains intact.
    public static boolean inputProbe;
    public static int pressedAlt;
    static void run() throws Exception {
        for (String name : List.of("net.minecraft.client.Minecraft",
                                   "net.minecraft.client.gui.screens.options.VideoSettingsScreen",
                                   "net.minecraft.client.gui.screens.options.OptionsSubScreen",
                                   "net.minecraft.client.renderer.LevelRenderer"))
            Class.forName(name, false, SettingsCpuSmoke.class.getClassLoader());
        var unsafe = (Unsafe)field(Unsafe.class, "theUnsafe").get(null);
        var client = field(PrimeClient.class, "INSTANCE").get(null);
        var offline = (OfflineMode)field(PrimeClient.class, "offline").get(client);
        if (offline.active() || offline.requested())
            throw new AssertionError("Fixture requires inactive session");
        String previous = System.getProperty("primept.smoke.realCaptureGates");
        try {
            System.setProperty("primept.smoke.realCaptureGates", "true");
            offline.request(true);
            offline.committed(true);
            if (PrimeClient.captureEnabled() || !PrimeClient.captureResourcesEnabled())
                throw new AssertionError(
                        "World capture must stop while resource upload observation remains active");
            var extractor = (LevelExtractor)unsafe.allocateInstance(LevelExtractor.class);
            var state = new net.minecraft.client.renderer.state.level.LevelRenderState();
            state.cameraRenderState.initialized = true;
            state.entityRenderStates.add(null);
            state.blockEntityRenderStates.add(null);
            field(LevelExtractor.class, "levelRenderState").set(extractor, state);
            extractor.extract(null, null, 0);
            if (!state.entityRenderStates.isEmpty() || !state.blockEntityRenderStates.isEmpty() ||
                !state.cameraRenderState.initialized)
                throw new AssertionError(
                        "Frozen extraction must release old world lists but preserve the host camera");
            var renderer = unsafe.allocateInstance(GameRenderer.class);
            var hand = java.util.Arrays.stream(GameRenderer.class.getDeclaredMethods())
                               .filter(m -> m.getName().equals("renderItemInHand"))
                               .findFirst()
                               .orElseThrow();
            hand.setAccessible(true);
            hand.invoke(renderer, null, 0f, null);
            for (var control : RenderSettings.Control.values())
                if (PrimeSettingsScreen.control(control).get() != control.initial)
                    throw new AssertionError("Default option " + control);
            offline.reset();
            inputAndSettings(unsafe, offline);
            System.out.println(
                    "PRIME_SETTINGS_CPU_SMOKE_OK: real version key routing, modifiers, frozen extraction/hand cancellation, repeated reset/scroll/resize");
        } finally {
            offline.reset();
            if (previous == null)
                System.clearProperty("primept.smoke.realCaptureGates");
            else
                System.setProperty("primept.smoke.realCaptureGates", previous);
        }
    }
    private static void inputAndSettings(Unsafe unsafe, OfflineMode offline) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var instance = field(Minecraft.class, "instance");
        Object previous = instance.get(null);
        var minecraft = (Minecraft)unsafe.allocateInstance(Minecraft.class);
        field(Minecraft.class, "options").set(minecraft, unsafe.allocateInstance(Options.class));
        field(Minecraft.class, "font").set(minecraft, fixtureFont());
        field(Minecraft.class, "lastInputType")
                .set(minecraft, net.minecraft.client.InputType.MOUSE);
        minecraft.level = (ClientLevel)unsafe.allocateInstance(ClientLevel.class);
        try {
            instance.set(null, minecraft);
            inputProbe = true;
            var f2 = InputConstants.getKey("key.keyboard.f2");
            var escape = InputConstants.getKey("key.keyboard.escape");
            pressedAlt = 0;
            check(!PrimeClient.offlineShortcut(f2, true),
                  "Ctrl+F2 must reach the screenshot binding");
            pressedAlt = InputConstants.KEY_LALT;
            check(!PrimeClient.offlineShortcut(f2, false), "Alt+F2 must not toggle");
            // The injected global handler must consume the actual version's F2 before screenshots.
            check(minecraft.handleGlobalKeyPress(f2, true) && offline.requested(),
                  "Ctrl+left Alt+F2 must request offline and consume the event; key=" +
                          f2.getValue());
            offline.committed(true);
            check(!PrimeClient.offlineShortcut(escape, true) && offline.requested() &&
                          offline.active(),
                  "Escape must preserve offline mode");
            pressedAlt = InputConstants.KEY_RALT;
            check(minecraft.handleGlobalKeyPress(f2, true) && !offline.requested() &&
                          offline.active(),
                  "Ctrl+right Alt+F2 requests realtime without changing ownership before the boundary");
            offline.reset();
            PrimeClient.updateSettings(PrimeClient.settings().withPathTracing(false));
            check(!PrimeClient.offlineShortcut(f2, true), "Vanilla must keep its key handling");
            PrimeClient.restoreSettings();
            minecraft.level = null;
            check(!PrimeClient.offlineShortcut(f2, true), "No scene to freeze on the title screen");
            settingsRebuild();
        } finally {
            inputProbe = false;
            pressedAlt = 0;
            instance.set(null, previous);
            offline.reset();
            PrimeClient.restoreSettings();
        }
    }

    private static void settingsRebuild() throws Exception {
        var screen = new PrimeSettingsScreen(null);
        screen.width = 640;
        screen.height = 240;
        var rebuild = Screen.class.getDeclaredMethod("rebuildWidgets");
        rebuild.setAccessible(true);
        rebuild.invoke(screen);
        int initialWidgets = screen.children().size();
        for (int round = 0; round < 4; ++round) {
            var lists = screen.children().stream().filter(OptionsList.class ::isInstance).toList();
            check(lists.size() == 1, "Exactly one rendered/input list after reset, round=" + round +
                                             ", actual=" + lists.size());
            check(screen.children().size() == initialWidgets,
                  "Reset must not duplicate title/footer");
            int[] layoutWidgets = {0};
            screen.layout.visitWidgets(widget -> ++layoutWidgets[0]);
            check(layoutWidgets[0] == initialWidgets, "Layout must release obsolete widgets");
            var list = (OptionsList)lists.getFirst();
            list.setScrollAmount(0);
            var firstEntry = (LayoutElement)list.children().getFirst();
            int firstY = firstEntry.getY();
            list.setScrollAmount(Double.MAX_VALUE);
            check(list.scrollAmount() > 0 && firstEntry.getY() < firstY,
                  "The live list moves when scrolled");
            screen.resize(641 + round, 241 + round);
            check(screen.children().size() == initialWidgets,
                  "Resize preserves one set of controls");
            PrimeClient.updateSettings(
                    PrimeClient.settings().with(RenderSettings.Control.EXPOSURE_EV, 8));
            var reset = (Button)((ContainerEventHandler)firstEntry).children().getFirst();
            reset.onPress(null);
            check(PrimeClient.settings().equals(RenderSettings.defaults()),
                  "Reset action restores defaults");
        }
    }

    private static Font fixtureFont() {
        var glyph = new EmptyGlyph(6).bake(null);
        var source = new GlyphSource() {
            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getGlyph(int codepoint) {
                return glyph;
            }
            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getRandomGlyph(
                    RandomSource random, int width) {
                return glyph;
            }
        };
        return new Font(new Font.Provider() {
            @Override
            public GlyphSource glyphs(FontDescription description) {
                return source;
            }
            @Override
            public EffectGlyph effect() {
                throw new AssertionError("Settings layout fixture must not render glyphs");
            }
        });
    }
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
