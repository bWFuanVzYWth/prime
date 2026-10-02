package dev.primept.capture;

import com.mojang.blaze3d.platform.InputConstants;
import dev.primept.PrimeClient;
import dev.primept.PrimeSettingsScreen;
import dev.primept.render.OfflineMode;
import dev.primept.settings.RenderSettings;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
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
                                   "net.minecraft.client.gui.screens.options.OptionsScreen",
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
                    "PRIME_SETTINGS_CPU_SMOKE_OK: Fabric-owned en/zh resources, root options entry/callback/resize, localized label widths, real version key routing, modifiers, frozen extraction/hand cancellation, repeated reset/scroll/resize");
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
            localizedSettings(unsafe, minecraft);
        } finally {
            inputProbe = false;
            pressedAlt = 0;
            instance.set(null, previous);
            offline.reset();
            PrimeClient.restoreSettings();
        }
    }

    @SuppressWarnings("unchecked")
    private static void localizedSettings(Unsafe unsafe, Minecraft minecraft) throws Exception {
        var mod = FabricLoader.getInstance().getModContainer("primept").orElseThrow();
        Language previous = Language.getInstance();
        boolean previousIde = net.minecraft.SharedConstants.IS_RUNNING_IN_IDE;
        var router = (ScreenRouter)unsafe.allocateInstance(ScreenRouter.class);
        field(Minecraft.class, "gui").set(minecraft, router);
        field(Options.class, "fov")
                .set(minecraft.options,
                     new net.minecraft.client.OptionInstance<>(
                             "options.fov", net.minecraft.client.OptionInstance.noTooltip(),
                             (caption, value)
                                     -> Options.genericValueLabel(
                                             caption, Component.literal(Integer.toString(value))),
                             new net.minecraft.client.OptionInstance.IntRange(30, 110), 70,
                             value -> {}));
        var rebuild = Screen.class.getDeclaredMethod("rebuildWidgets");
        rebuild.setAccessible(true);
        try {
            net.minecraft.SharedConstants.IS_RUNNING_IN_IDE = true;
            for (String locale : List.of("en_us", "zh_cn")) {
                var translations = new HashMap<String, String>();
                // Read from the actual Fabric mod, not the general Java classpath:
                // the former failed in development even though the latter found the files.
                var path =
                        mod.findPath("assets/primept/lang/" + locale + ".json")
                                .orElseThrow(
                                        () -> new AssertionError("Missing mod language " + locale));
                try (var input = Files.newInputStream(path)) {
                    Language.loadFromJson(input, translations::put);
                }
                Language.inject(new Language() {
                    @Override
                    public String getOrDefault(String key, String fallback) {
                        return translations.getOrDefault(key, previous.getOrDefault(key, fallback));
                    }
                    @Override
                    public boolean has(String key) {
                        return translations.containsKey(key) || previous.has(key);
                    }
                    @Override
                    public boolean isDefaultRightToLeft() {
                        return false;
                    }
                    @Override
                    public FormattedCharSequence getVisualOrder(FormattedText text) {
                        return previous.getVisualOrder(text);
                    }
                });
                for (boolean inWorld : List.of(false, true)) {
                    var root = new OptionsScreen(null, minecraft.options, inWorld);
                    root.width = 480;
                    root.height = 270;
                    rebuild.invoke(root);
                    for (int[] size : new int[][] {{480, 270}, {320, 240}, {641, 361}}) {
                        root.resize(size[0], size[1]);
                        var buttons = root.children()
                                              .stream()
                                              .filter(Button.class ::isInstance)
                                              .map(Button.class ::cast)
                                              .toList();
                        var entries =
                                buttons.stream()
                                        .filter(button
                                                -> button.getMessage().getString().equals(
                                                        translations.get("primept.settings.title")))
                                        .toList();
                        check(entries.size() == 1, "Exactly one translated Prime root entry");
                        var entry = entries.getFirst();
                        check(entry.getX() >= 0 && entry.getRight() <= root.width &&
                                      entry.getY() >= 0 && entry.getBottom() <= root.height,
                              "Root entry must remain on screen at each GUI scale");
                        for (var other : buttons)
                            if (other != entry)
                                check(entry.getRight() <= other.getX() ||
                                              other.getRight() <= entry.getX() ||
                                              entry.getBottom() <= other.getY() ||
                                              other.getBottom() <= entry.getY(),
                                      "Root entry overlaps " + other.getMessage().getString());
                        entry.onPress(null);
                        check(router.selected instanceof PrimeSettingsScreen &&
                                      field(OptionsSubScreen.class, "lastScreen")
                                                      .get(router.selected) == root,
                              "Root button opens Prime and retains the return destination");
                    }
                }
                for (int bound = 0; bound < 3; bound++) {
                    var settings = RenderSettings.defaults();
                    for (var control : RenderSettings.Control.values()) {
                        check(translations.containsKey("primept.settings." + control.key) &&
                                      translations.containsKey("primept.settings." + control.key +
                                                               ".tooltip"),
                              "Missing control translation " + locale + ": " + control);
                        settings = settings.with(control, bound == 0   ? control.minimum
                                                          : bound == 1 ? control.maximum
                                                                       : control.initial);
                    }
                    PrimeClient.updateSettings(settings);
                    var screen = new PrimeSettingsScreen(null);
                    screen.width = 480;
                    screen.height = 270;
                    rebuild.invoke(screen);
                    check(screen.getTitle().getString().equals(
                                  translations.get("primept.settings.title")),
                          "Settings title must resolve in " + locale);
                    var list = (OptionsList)screen.children()
                                       .stream()
                                       .filter(OptionsList.class ::isInstance)
                                       .findFirst()
                                       .orElseThrow();
                    var controls = (java.util.EnumMap<RenderSettings.Control,
                                                      net.minecraft.client.OptionInstance<Integer>>)
                                           field(PrimeSettingsScreen.class, "controls")
                                                   .get(screen);
                    var terrainBatches =
                            controls.get(RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME);
                    check(terrainBatches.get() ==
                                  settings.value(RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME),
                          "Terrain batch slider preserves each configured bound");
                    var opacityMicromap = (net.minecraft.client.OptionInstance<Boolean>)field(
                                                  PrimeSettingsScreen.class, "opacityMicromap")
                                                  .get(screen);
                    check(opacityMicromap.get(), "OMM default must be enabled");
                    var rayReconstruction = (net.minecraft.client.OptionInstance<Boolean>)field(
                                                    PrimeSettingsScreen.class, "rayReconstruction")
                                                    .get(screen);
                    var dlssQuality =
                            (net.minecraft.client.OptionInstance<RenderSettings.DlssQuality>)field(
                                    PrimeSettingsScreen.class, "dlssQuality")
                                    .get(screen);
                    check(rayReconstruction.get(), "RR default must be enabled");
                    check(dlssQuality.get() == RenderSettings.DlssQuality.PERFORMANCE,
                          "DLSS default must be 2x Performance");
                    var offline = (OfflineMode)field(PrimeClient.class, "offline")
                                          .get(field(PrimeClient.class, "INSTANCE").get(null));
                    offline.request(true);
                    offline.committed(true);
                    screen.tick();
                    check(list.findOption(opacityMicromap).active,
                          "Frozen OMM control stays available");
                    check(list.findOption(terrainBatches).active,
                          "Frozen terrain scheduling control stays available");
                    check(!list.findOption(rayReconstruction).active &&
                                  !list.findOption(dlssQuality).active,
                          "Offline accumulation disables realtime reconstruction controls");
                    // OptionInstance only dispatches value callbacks once the client is running.
                    var running = field(Minecraft.class, "running");
                    boolean previousRunning = running.getBoolean(minecraft);
                    running.setBoolean(minecraft, true);
                    try {
                        for (int budget : new int[] {1, 128, 8}) {
                            terrainBatches.set(budget);
                            check(PrimeClient.settings().value(
                                          RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME) ==
                                                  budget &&
                                          offline.active() && offline.requested(),
                                  "Terrain batch callback changes the persisted setting without thawing");
                        }
                        terrainBatches.set(
                                settings.value(RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME));
                        opacityMicromap.set(false);
                        check(!PrimeClient.settings().opacityMicromap() && offline.active() &&
                                      offline.requested(),
                              "OMM callback preserves frozen scene and changes the persisted setting");
                        opacityMicromap.set(true);
                        offline.reset();
                        screen.tick();
                        check(list.findOption(rayReconstruction).active &&
                                      list.findOption(dlssQuality).active,
                              "Realtime reconstruction controls become available");
                        rayReconstruction.set(false);
                        screen.tick();
                        check(!PrimeClient.settings().rayReconstruction() &&
                                      !list.findOption(dlssQuality).active,
                              "RR toggle is persisted and disables its quality control");
                        rayReconstruction.set(true);
                        for (var quality : RenderSettings.DlssQuality.values()) {
                            dlssQuality.set(quality);
                            check(PrimeClient.settings().dlssQuality() == quality,
                                  "DLSS quality callback changes persisted settings");
                        }
                        dlssQuality.set(RenderSettings.DlssQuality.PERFORMANCE);
                    } finally {
                        running.setBoolean(minecraft, previousRunning);
                    }
                    offline.reset();
                    for (Object row : list.children())
                        if (row instanceof ContainerEventHandler container)
                            for (var child : container.children())
                                if (child instanceof AbstractWidget widget) {
                                    String label = widget.getMessage().getString();
                                    check(!label.contains("primept.settings."),
                                          "Unresolved label " + label);
                                    if (widget instanceof net.minecraft.client.gui.components
                                                                  .AbstractButton ||
                                        widget instanceof net.minecraft.client.gui.components
                                                                  .AbstractSliderButton)
                                        check(minecraft.font.width(widget.getMessage()) <=
                                                      widget.getWidth() - 16,
                                              "Label exceeds its control: " + locale + " " + label +
                                                      " (" +
                                                      minecraft.font.width(widget.getMessage()) +
                                                      " > " + (widget.getWidth() - 16) + ")");
                                }
                }
            }
        } finally {
            Language.inject(previous);
            net.minecraft.SharedConstants.IS_RUNNING_IN_IDE = previousIde;
            PrimeClient.restoreSettings();
        }
    }

    private static final class ScreenRouter extends Gui {
        Screen selected;
        private ScreenRouter() {
            super(null, null, null);
        }
        @Override
        public void setScreen(Screen screen) {
            selected = screen;
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
        var wideGlyph = new EmptyGlyph(9).bake(null);
        var source = new GlyphSource() {
            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getGlyph(int codepoint) {
                return codepoint >= 0x2e80 ? wideGlyph : glyph;
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
