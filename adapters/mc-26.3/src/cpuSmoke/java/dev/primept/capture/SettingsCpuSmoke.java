package dev.primept.capture;

import com.mojang.blaze3d.platform.InputConstants;
import dev.primept.PrimeClient;
import dev.primept.PrimeVideoOptions;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.renderer.GpuWarnlistManager;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.OptionInstance;
import dev.primept.render.OfflineMode;
import dev.primept.settings.RenderSettings;
import dev.primept.settings.SettingsFile;
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
    public static boolean windowProbe;
    public static int pressedAlt;
    static void run() throws Exception {
        for (String name : List.of("net.minecraft.client.Minecraft",
                                   "net.minecraft.client.gui.screens.options.OptionsScreen",
                                   "net.minecraft.client.gui.screens.options.OptionsSubScreen",
                                   "net.minecraft.client.gui.screens.options.VideoSettingsScreen",
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
            hand.invoke(renderer, null, null, null);
            for (var control : RenderSettings.Control.values())
                if (PrimeVideoOptions.control(control).get() != control.initial)
                    throw new AssertionError("Default option " + control);
            offline.reset();
            inputAndSettings(unsafe, offline);
            System.out.println(
                    "PRIME_SETTINGS_CPU_SMOKE_OK: Fabric-owned en/zh resources, embedded video options before vanilla, toggle/slider types and full widths, diagnostics placement, single DLSS caption, callback/resize, localized label widths, real version key routing, modifiers, frozen extraction/hand cancellation, repeated reset/scroll/resize");
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
            windowProbe = true;
            var window = (Window)unsafe.allocateInstance(Window.class);
            window.setWidth(1920);
            window.setHeight(1080);
            field(Window.class, "preferredFullscreenVideoMode")
                    .set(window, java.util.Optional.empty());
            field(Minecraft.class, "window").set(minecraft, window);
            field(Minecraft.class, "gpuWarnlistManager").set(minecraft, new GpuWarnlistManager());
            var optionsDirectory = Files.createTempDirectory("primept-video-settings");
            field(Minecraft.class, "options")
                    .set(minecraft, new Options(minecraft, optionsDirectory.toFile()));
            minecraft.options.graphicsPreset().set(net.minecraft.client.GraphicsPreset.CUSTOM);
            field(Minecraft.class, "gui")
                    .set(minecraft, unsafe.allocateInstance(ScreenRouter.class));
            field(Minecraft.class, "running").setBoolean(minecraft, true);
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
            var client = field(PrimeClient.class, "INSTANCE").get(null);
            var requested = field(PrimeClient.class, "requested");
            for (var kind : RenderSettings.Renderer.values()) {
                PrimeClient.updateSettings(PrimeClient.settings().withRenderer(kind));
                check(requested.get(client).equals(kind.key),
                      "Renderer selection keeps its exact backend key " + kind.key);
                PrimeClient.updateSettings(
                        PrimeClient.settings().with(RenderSettings.Control.BOUNCES, 8));
                check(requested.get(client).equals(kind.key),
                      "Independent settings must not replace the selected renderer " + kind.key);
            }
            PrimeClient.updateSettings(PrimeClient.settings().withPathTracing(false));
            check(!PrimeClient.offlineShortcut(f2, true), "Vanilla must keep its key handling");
            PrimeClient.restoreSettings();
            minecraft.level = null;
            check(!PrimeClient.offlineShortcut(f2, true), "No scene to freeze on the title screen");
            localizedSettings(unsafe, minecraft);
        } finally {
            inputProbe = false;
            windowProbe = false;
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
                settingsRebuild();
                for (boolean inWorld : List.of(false, true)) {
                    minecraft.level =
                            inWorld ? (ClientLevel)unsafe.allocateInstance(ClientLevel.class)
                                    : null;
                    var root = new OptionsScreen(null, minecraft.options);
                    root.width = 480;
                    root.height = 270;
                    rebuild.invoke(root);
                    var buttons = root.children()
                                          .stream()
                                          .filter(Button.class ::isInstance)
                                          .map(Button.class ::cast)
                                          .toList();
                    check(buttons.stream().noneMatch(
                                  button
                                  -> button.getMessage().getString().equals(
                                          translations.get("primept.settings.title"))),
                          "The old separate Prime entry is removed");
                    var video = buttons.stream()
                                        .filter(button
                                                -> button.getMessage().getString().equals(
                                                        Component.translatable("options.video")
                                                                .getString()))
                                        .toList();
                    check(video.size() == 1, "One vanilla Video Settings entry remains");
                    video.getFirst().onPress(null);
                    check(router.selected instanceof VideoSettingsScreen &&
                                  field(OptionsSubScreen.class, "lastScreen")
                                                  .get(router.selected) == root,
                          "Vanilla entry opens the integrated Video Settings and retains its parent");
                    var screen = (VideoSettingsScreen)router.selected;
                    screen.width = 480;
                    screen.height = 270;
                    rebuild.invoke(screen);
                    for (int[] size : new int[][] {{480, 270}, {320, 240}, {641, 361}}) {
                        screen.resize(size[0], size[1]);
                        assertVideoGroups(screen);
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
                    var screen = new VideoSettingsScreen(null, Minecraft.getInstance(),
                                                         Minecraft.getInstance().options);
                    screen.width = 480;
                    screen.height = 270;
                    rebuild.invoke(screen);
                    assertVideoGroups(screen);
                    var owner = videoOptions(screen);
                    var list = (OptionsList)screen.children()
                                       .stream()
                                       .filter(OptionsList.class ::isInstance)
                                       .findFirst()
                                       .orElseThrow();
                    var controls = (java.util.EnumMap<RenderSettings.Control,
                                                      net.minecraft.client.OptionInstance<Integer>>)
                                           field(PrimeVideoOptions.class, "controls")
                                                   .get(owner);
                    var renderer = (OptionInstance<RenderSettings.Renderer>)field(
                                           PrimeVideoOptions.class, "renderer")
                                           .get(owner);
                    var rendererButton =
                            (CycleButton<RenderSettings.Renderer>)list.findOption(renderer);
                    for (var kind : RenderSettings.Renderer.values()) {
                        check(translations.containsKey("primept.settings.renderer." + kind.key),
                              "Missing renderer translation " + locale + ": " + kind.key);
                        rendererButton.onPress(new net.minecraft.client.input.KeyEvent(
                                InputConstants.KEY_RETURN, 0, 0));
                        check(PrimeClient.settings().renderer() == renderer.get(),
                              "Renderer cycle dispatches the exact persisted choice");
                        PrimeClient.updateSettings(PrimeClient.settings().with(
                                RenderSettings.Control.BOUNCES,
                                settings.value(RenderSettings.Control.BOUNCES)));
                        check(PrimeClient.settings().renderer() == renderer.get(),
                              "Other option callbacks preserve the renderer");
                    }
                    renderer.set(settings.renderer());
                    rendererButton.setValue(renderer.get());
                    for (var control : RenderSettings.Control.values())
                        check(controls.get(control).get() == settings.value(control),
                              "Each translated control preserves the actual configured bound: " +
                                      control);
                    var terrainBatches =
                            controls.get(RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME);
                    check(terrainBatches.get() ==
                                  settings.value(RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME),
                          "Terrain batch slider preserves each configured bound");
                    var opacityMicromap = (net.minecraft.client.OptionInstance<Boolean>)field(
                                                  PrimeVideoOptions.class, "opacityMicromap")
                                                  .get(owner);
                    check(opacityMicromap.get(), "OMM default must be enabled");
                    var rayReconstruction = (net.minecraft.client.OptionInstance<Boolean>)field(
                                                    PrimeVideoOptions.class, "rayReconstruction")
                                                    .get(owner);
                    var dlssQuality =
                            (net.minecraft.client.OptionInstance<RenderSettings.DlssQuality>)field(
                                    PrimeVideoOptions.class, "dlssQuality")
                                    .get(owner);
                    var lightSampling = (OptionInstance<RenderSettings.LightSampling>)field(
                                                PrimeVideoOptions.class, "lightSampling")
                                                .get(owner);
                    var lightButton = (CycleButton<RenderSettings.LightSampling>)list.findOption(
                            lightSampling);
                    var ignoreGlobalResets =
                            (OptionInstance<Boolean>)field(PrimeVideoOptions.class,
                                                           "ignoreGlobalHistoryResets")
                                    .get(owner);
                    var resetButton = (CycleButton<Boolean>)list.findOption(ignoreGlobalResets);
                    check(!ignoreGlobalResets.get(),
                          "Global history reset diagnostic is off by default");
                    check(translations.containsKey(
                                  "primept.settings.ignore_global_history_resets") &&
                                  translations.containsKey(
                                          "primept.settings.ignore_global_history_resets.tooltip"),
                          "The global reset diagnostic is translated");
                    check(lightSampling.get() == RenderSettings.LightSampling.TREE,
                          "Power-distance tree is the default sampler");
                    check(translations.containsKey("primept.settings.light_sampling.tree") &&
                                  translations.containsKey(
                                          "primept.settings.light_sampling.tree_sphere") &&
                                  !translations.containsKey("primept.settings.light_sampling.grid"),
                          "Both active samplers have translations and GRID is retired");
                    check(rayReconstruction.get(), "RR default must be enabled");
                    check(list.findOption(opacityMicromap) instanceof CycleButton<?> &&
                                  list.findOption(rayReconstruction) instanceof CycleButton<?>,
                          "Diagnostic booleans use toggle buttons");
                    for (var control : RenderSettings.Control.values()) {
                        var widget = list.findOption(controls.get(control));
                        boolean toggle = control == RenderSettings.Control.HDR ||
                                         control == RenderSettings.Control.FRAME_GENERATION;
                        check(toggle ? widget instanceof CycleButton<?>
                                     : widget instanceof AbstractSliderButton,
                              "Toggle/slider widget type " + control);
                        check(widget.getWidth() == list.getRowWidth(),
                              "Full-width numeric/toggle option " + control);
                    }
                    var qualityButton =
                            (CycleButton<RenderSettings.DlssQuality>)list.findOption(dlssQuality);
                    String caption = translations.get("primept.settings.dlss_quality");
                    for (var quality : RenderSettings.DlssQuality.values()) {
                        qualityButton.setValue(quality);
                        String message = qualityButton.getMessage().getString();
                        check(message.indexOf(caption) >= 0 &&
                                      message.indexOf(caption) == message.lastIndexOf(caption),
                              "DLSS caption occurs exactly once: " + message);
                    }
                    qualityButton.setValue(dlssQuality.get());
                    check(dlssQuality.get() == RenderSettings.DlssQuality.PERFORMANCE,
                          "DLSS default must be 2x Performance");
                    var offline = (OfflineMode)field(PrimeClient.class, "offline")
                                          .get(field(PrimeClient.class, "INSTANCE").get(null));
                    offline.request(true);
                    offline.committed(true);
                    screen.tick();
                    check(list.findOption(opacityMicromap).active,
                          "Frozen OMM control stays available");
                    check(list.findOption(ignoreGlobalResets).active,
                          "The reset diagnostic remains available while frozen");
                    check(list.findOption(terrainBatches).active,
                          "Frozen terrain scheduling control stays available");
                    check(!list.findOption(controls.get(RenderSettings.Control.STARS)).active,
                          "Frozen star lighting control stays fixed");
                    check(!list.findOption(controls.get(RenderSettings.Control.FRAME_GENERATION))
                                   .active,
                          "Frozen frame generation control is unavailable");
                    check(list.findOption(controls.get(RenderSettings.Control.AUTO_EXPOSURE))
                                          .active &&
                                  list.findOption(controls.get(RenderSettings.Control.HDR_WHITE))
                                          .active,
                          "Frozen display controls stay available without changing accumulation");
                    check(!list.findOption(rayReconstruction).active &&
                                  !list.findOption(dlssQuality).active,
                          "Offline accumulation disables realtime reconstruction controls");
                    // OptionInstance only dispatches value callbacks once the client is running.
                    var running = field(Minecraft.class, "running");
                    boolean previousRunning = running.getBoolean(minecraft);
                    running.setBoolean(minecraft, true);
                    try {
                        for (boolean enabled : new boolean[] {true, false}) {
                            resetButton.onPress(new net.minecraft.client.input.KeyEvent(
                                    InputConstants.KEY_RETURN, 0, 0));
                            check(ignoreGlobalResets.get() == enabled &&
                                          PrimeClient.settings().ignoreGlobalHistoryResets() ==
                                                  enabled &&
                                          offline.active() && offline.requested(),
                                  "The actual reset diagnostic button preserves the frozen scene");
                            check(SettingsFile.decode(SettingsFile.encode(PrimeClient.settings()))
                                          .settings()
                                          .equals(PrimeClient.settings()),
                                  "The reset diagnostic callback preserves all persisted settings");
                        }
                        for (var sampler : List.of(RenderSettings.LightSampling.TREE_SPHERE,
                                                   RenderSettings.LightSampling.TREE)) {
                            lightButton.onPress(new net.minecraft.client.input.KeyEvent(
                                    InputConstants.KEY_RETURN, 0, 0));
                            check(lightSampling.get() == sampler &&
                                          PrimeClient.settings().lightSampling() == sampler &&
                                          offline.active() && offline.requested(),
                                  "The actual sampler button cycles only the two trees without thawing");
                            check(SettingsFile.decode(SettingsFile.encode(PrimeClient.settings()))
                                          .settings()
                                          .equals(PrimeClient.settings()),
                                  "Sampler callbacks preserve all persisted settings");
                        }
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
                        for (var control : List.of(RenderSettings.Control.AUTO_EXPOSURE,
                                                   RenderSettings.Control.HDR,
                                                   RenderSettings.Control.HDR_WHITE)) {
                            for (int value :
                                 new int[] {control.minimum, control.maximum, control.initial}) {
                                controls.get(control).set(value);
                                check(PrimeClient.settings().value(control) == value &&
                                              offline.active() && offline.requested(),
                                      "Display callback preserves the frozen scene: " + control);
                            }
                            controls.get(control).set(settings.value(control));
                        }
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
                        for (var control : List.of(RenderSettings.Control.STARS,
                                                   RenderSettings.Control.FRAME_GENERATION)) {
                            for (int value :
                                 new int[] {control.minimum, control.maximum, control.initial}) {
                                controls.get(control).set(value);
                                check(PrimeClient.settings().value(control) == value,
                                      "Realtime display callback changes persisted settings: " +
                                              control);
                            }
                            controls.get(control).set(settings.value(control));
                        }
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
                    // Actual coded-boolean buttons dispatch into the persisted settings.
                    running.setBoolean(minecraft, true);
                    try {
                        for (var control : List.of(RenderSettings.Control.HDR,
                                                   RenderSettings.Control.FRAME_GENERATION)) {
                            var option = controls.get(control);
                            var button = (CycleButton<Integer>)list.findOption(option);
                            button.setValue(option.get());
                            int next = option.get() == 0 ? 1 : 0;
                            button.onPress(new net.minecraft.client.input.KeyEvent(
                                    InputConstants.KEY_RETURN, 0, 0));
                            check(option.get() == next &&
                                          PrimeClient.settings().value(control) == next,
                                  "Clicking the boolean button toggles the actual setting " +
                                          control);
                            option.set(settings.value(control));
                            button.setValue(option.get());
                        }
                    } finally {
                        running.setBoolean(minecraft, previousRunning);
                    }
                    for (Object row : list.children().subList(0, firstVanillaRow(list)))
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
        var screen = new VideoSettingsScreen(null, Minecraft.getInstance(),
                                             Minecraft.getInstance().options);
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
            assertVideoGroups(screen);
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
            var vanillaOption = Minecraft.getInstance().options.simulationDistance();
            var vanillaRange = (OptionInstance.IntRange)vanillaOption.values();
            var vanillaSlider = (AbstractSliderButton)list.findOption(vanillaOption);
            int previousVanilla = vanillaOption.get();
            int nextVanilla =
                    round % 2 == 0 ? vanillaRange.maxInclusive() : vanillaRange.minInclusive();
            var setValue = AbstractSliderButton.class.getDeclaredMethod("setValue", double.class);
            setValue.setAccessible(true);
            setValue.invoke(vanillaSlider, round % 2 == 0 ? 1.0 : 0.0);
            check(previousVanilla != nextVanilla && vanillaOption.get() == previousVanilla &&
                          field(vanillaSlider.getClass(), "delayedApplyAt").get(vanillaSlider) !=
                                  null,
                  "Vanilla slider adjustment is pending before reset");
            var reset = (Button)((ContainerEventHandler)firstEntry).children().getFirst();
            reset.onPress(null);
            check(vanillaOption.get() == nextVanilla,
                  "Prime reset commits pending vanilla slider changes before rebuilding");
            check(PrimeClient.settings().equals(RenderSettings.defaults()),
                  "Reset action restores defaults");
            check(PrimeClient.settings().value(RenderSettings.Control.SATURATION) == 20,
                  "Reset restores 20% saturation");
            assertVideoGroups(screen);
        }
    }

    private static PrimeVideoOptions videoOptions(VideoSettingsScreen screen) throws Exception {
        for (var member : VideoSettingsScreen.class.getDeclaredFields())
            if (member.getType() == PrimeVideoOptions.class) {
                member.setAccessible(true);
                return (PrimeVideoOptions)member.get(screen);
            }
        throw new AssertionError("Actual Video Settings mixin must own Prime options");
    }

    private static int firstVanillaRow(OptionsList list) {
        for (int i = 0; i < list.children().size(); ++i) {
            Object row = list.children().get(i);
            if (row.getClass().getSimpleName().equals("HeaderEntry")) {
                var widget = (AbstractWidget)((ContainerEventHandler)row).children().getFirst();
                if (!widget.getMessage().getString().startsWith("Prime PT"))
                    return i;
            }
        }
        throw new AssertionError("Vanilla video groups must remain after Prime options");
    }

    private static void assertVideoGroups(VideoSettingsScreen screen) throws Exception {
        var lists = screen.children().stream().filter(OptionsList.class ::isInstance).toList();
        check(lists.size() == 1, "One shared vanilla/Prime video list");
        var list = (OptionsList)lists.getFirst();
        var headers =
                ((List<?>)list.children())
                        .stream()
                        .filter(row -> row.getClass().getSimpleName().equals("HeaderEntry"))
                        .map(row
                             -> ((AbstractWidget)((ContainerEventHandler)row).children().getFirst())
                                        .getMessage()
                                        .getString())
                        .toList();
        check(headers.size() == 7, "Four Prime groups and three vanilla groups: " + headers);
        for (int i = 0; i < 4; ++i) {
            String group = List.of("render", "lighting", "display", "diagnostics").get(i);
            check(headers.get(i).equals(
                          Component.translatable("primept.settings." + group).getString()) &&
                          headers.get(i).startsWith("Prime PT"),
                  "Prime groups precede vanilla with their prefix: " + headers);
        }
        int vanilla = firstVanillaRow(list);
        var owner = videoOptions(screen);
        var omm = (OptionInstance<?>)field(PrimeVideoOptions.class, "opacityMicromap").get(owner);
        var rr = (OptionInstance<?>)field(PrimeVideoOptions.class, "rayReconstruction").get(owner);
        var globalResets =
                (OptionInstance<?>)field(PrimeVideoOptions.class, "ignoreGlobalHistoryResets")
                        .get(owner);
        boolean diagnostics = false;
        for (int i = 0; i < vanilla; ++i) {
            Object row = list.children().get(i);
            if (row.getClass().getSimpleName().equals("HeaderEntry"))
                diagnostics = ((AbstractWidget)((ContainerEventHandler)row).children().getFirst())
                                      .getMessage()
                                      .getString()
                                      .equals(Component.translatable("primept.settings.diagnostics")
                                                      .getString());
            if (((ContainerEventHandler)row).children().contains(list.findOption(omm)) ||
                ((ContainerEventHandler)row).children().contains(list.findOption(rr)) ||
                ((ContainerEventHandler)row).children().contains(list.findOption(globalResets)))
                check(diagnostics, "RR and OMM belong only to the diagnostics group");
        }
        check(list.findOption(Minecraft.getInstance().options.renderDistance()) != null,
              "Vanilla render-distance option remains present");
        int[] layoutWidgets = {0};
        screen.layout.visitWidgets(widget -> ++layoutWidgets[0]);
        check(layoutWidgets[0] == screen.children().size(),
              "Video layouts do not retain obsolete widget copies");
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
