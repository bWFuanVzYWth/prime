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
import dev.primept.settings.RestirSettings;
import dev.primept.RestirVideoOptions;
import dev.primept.RestirSettingsScreen;
import net.minecraft.client.gui.components.EditBox;
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
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
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
                    "PRIME_SETTINGS_CPU_SMOKE_OK: Fabric-owned en/zh resources, ordered path tracing/offline/realtime controls, ReSTIR subpage navigation and temporal semantics, single option captions, toggle/slider types and widths, diagnostics placement, callback/resize, localized labels, real version key routing, modifiers, frozen extraction/hand cancellation, repeated reset/scroll/resize");
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
                    var restirSettings = RestirSettings.defaults();
                    for (var control : RestirSettings.Control.values())
                        restirSettings =
                                restirSettings.with(control, bound == 0   ? control.minimum
                                                             : bound == 1 ? control.maximum
                                                                          : control.initial);
                    settings = settings.withRestir(restirSettings);
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
                    var pathTracing =
                            (OptionInstance<Boolean>)field(PrimeVideoOptions.class, "pathTracing")
                                    .get(owner);
                    var pathTracingButton = (CycleButton<Boolean>)list.findOption(pathTracing);
                    for (var kind : List.of(RenderSettings.Renderer.RESTIR_PT,
                                            RenderSettings.Renderer.PATH_TRACE)) {
                        check(translations.containsKey("primept.settings.renderer." + kind.key),
                              "Missing renderer translation " + locale + ": " + kind.key);
                        rendererButton.onPress(new net.minecraft.client.input.KeyEvent(
                                InputConstants.KEY_RETURN, 0, 0));
                        check(renderer.get() == kind &&
                                      PrimeClient.settings().realtimeRenderer() == kind &&
                                      PrimeClient.settings().pathTracing(),
                              "Realtime renderer has only naive PT and ReSTIR choices");
                        assertSingleCaption(rendererButton,
                                            translations.get("primept.settings.renderer"));
                        PrimeClient.updateSettings(PrimeClient.settings().with(
                                RenderSettings.Control.BOUNCES,
                                settings.value(RenderSettings.Control.BOUNCES)));
                        check(PrimeClient.settings().renderer() == renderer.get(),
                              "Other option callbacks preserve the renderer");
                    }
                    pathTracingButton.onPress(new net.minecraft.client.input.KeyEvent(
                            InputConstants.KEY_RETURN, 0, 0));
                    check(!pathTracing.get() && !PrimeClient.settings().pathTracing() &&
                                  PrimeClient.settings().renderer() ==
                                          RenderSettings.Renderer.VANILLA,
                          "The separate path tracing toggle restores vanilla rendering");
                    rendererButton.onPress(new net.minecraft.client.input.KeyEvent(
                            InputConstants.KEY_RETURN, 0, 0));
                    check(renderer.get() == RenderSettings.Renderer.RESTIR_PT &&
                                  PrimeClient.settings().realtimeRenderer() == renderer.get() &&
                                  !PrimeClient.settings().pathTracing(),
                          "Changing the remembered realtime renderer keeps path tracing disabled");
                    pathTracingButton.onPress(new net.minecraft.client.input.KeyEvent(
                            InputConstants.KEY_RETURN, 0, 0));
                    check(pathTracing.get() && PrimeClient.settings().pathTracing() &&
                                  PrimeClient.settings().renderer() ==
                                          RenderSettings.Renderer.RESTIR_PT,
                          "Reenabling path tracing restores the selected realtime renderer");
                    PrimeClient.updateSettings(settings);
                    renderer.set(settings.realtimeRenderer());
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
                    var restirScreen = openRestirSubpage(screen, router, rebuild);
                    var restirList = optionsList(restirScreen);
                    var restirModule =
                            (RestirVideoOptions)field(RestirSettingsScreen.class, "restir")
                                    .get(restirScreen);
                    assertRestirOptions(restirScreen, translations, false);
                    var opacityMicromap = (net.minecraft.client.OptionInstance<Boolean>)field(
                                                  PrimeVideoOptions.class, "opacityMicromap")
                                                  .get(owner);
                    check(opacityMicromap.get(), "OMM default must be enabled");
                    var nativeNoisyOutput = (net.minecraft.client.OptionInstance<Boolean>)field(
                                                    PrimeVideoOptions.class, "nativeNoisyOutput")
                                                    .get(owner);
                    var dlssQuality =
                            (net.minecraft.client.OptionInstance<RenderSettings.DlssQuality>)field(
                                    PrimeVideoOptions.class, "dlssQuality")
                                    .get(owner);
                    var ignoreGlobalResets =
                            (OptionInstance<Boolean>)field(PrimeVideoOptions.class,
                                                           "ignoreGlobalHistoryResets")
                                    .get(owner);
                    var resetButton = (CycleButton<Boolean>)list.findOption(ignoreGlobalResets);
                    var temporalReuse = (OptionInstance<Boolean>)field(RestirVideoOptions.class,
                                                                       "temporalReuse")
                                                .get(restirModule);
                    var temporalButton = (CycleButton<Boolean>)restirList.findOption(temporalReuse);
                    check(temporalReuse.get() && !PrimeClient.settings().restirSpatialOnly(),
                          "Temporal reuse defaults on and means spatial-only is disabled");
                    check(translations.containsKey("primept.settings.restir_pt.temporal_reuse") &&
                                  translations.containsKey(
                                          "primept.settings.restir_pt.temporal_reuse.tooltip"),
                          "Temporal reuse is translated");
                    check(!temporalButton.active,
                          "Temporal reuse is inactive for the naive path tracer");
                    check(!ignoreGlobalResets.get(),
                          "Global history reset diagnostic is off by default");
                    check(translations.containsKey(
                                  "primept.settings.ignore_global_history_resets") &&
                                  translations.containsKey(
                                          "primept.settings.ignore_global_history_resets.tooltip"),
                          "The global reset diagnostic is translated");
                    check(PrimeClient.settings().lightSampling() ==
                                  RenderSettings.LightSampling.TREE,
                          "Power-distance tree is the only sampler");
                    check(!translations.containsKey("primept.settings.light_sampling") &&
                                  !translations.containsKey(
                                          "primept.settings.light_sampling.tree_sphere"),
                          "Retired sampler controls are absent");
                    check(!nativeNoisyOutput.get(), "Native noisy diagnostic must default off");
                    check(list.findOption(opacityMicromap) instanceof CycleButton<?> &&
                                  list.findOption(nativeNoisyOutput) instanceof CycleButton<?>,
                          "Diagnostic booleans use toggle buttons");
                    assertRootCaptions(owner, list, translations);
                    for (var control : RenderSettings.Control.values()) {
                        var widget = list.findOption(controls.get(control));
                        boolean toggle = control == RenderSettings.Control.HDR ||
                                         control == RenderSettings.Control.FRAME_GENERATION;
                        check(toggle ? widget instanceof CycleButton<?>
                                     : widget instanceof AbstractSliderButton,
                              "Toggle/slider widget type " + control);
                        check(widget.getWidth() == list.getRowWidth(),
                              "Full-width numeric/toggle option " + control);
                        assertOptionCaption(controls.get(control), widget,
                                            translations.get("primept.settings." + control.key));
                    }
                    var qualityButton =
                            (CycleButton<RenderSettings.DlssQuality>)list.findOption(dlssQuality);
                    String caption = translations.get("primept.settings.dlss_quality");
                    for (var quality : RenderSettings.DlssQuality.values()) {
                        qualityButton.setValue(quality);
                        assertSingleCaption(qualityButton, caption);
                    }
                    qualityButton.setValue(dlssQuality.get());
                    check(dlssQuality.get() == RenderSettings.DlssQuality.PERFORMANCE,
                          "DLSS default must be 2x Performance");
                    var offline = (OfflineMode)field(PrimeClient.class, "offline")
                                          .get(field(PrimeClient.class, "INSTANCE").get(null));
                    offline.request(true);
                    offline.committed(true);
                    screen.tick();
                    restirScreen.tick();
                    check(list.findOption(opacityMicromap).active,
                          "Frozen OMM control stays available");
                    check(list.findOption(ignoreGlobalResets).active,
                          "The reset diagnostic remains available while frozen");
                    check(!temporalButton.active,
                          "Temporal reuse does not control offline accumulation");
                    assertRestirOptions(restirScreen, translations, false);
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
                    check(!list.findOption(nativeNoisyOutput).active &&
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
                        for (int budget :
                             new int[] {1, 128,
                                        RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME.initial}) {
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
                        check(list.findOption(nativeNoisyOutput).active &&
                                      list.findOption(dlssQuality).active,
                              "Realtime reconstruction controls become available");
                        var originalSettings = PrimeClient.settings();
                        PrimeClient.updateSettings(
                                originalSettings.withRenderer(RenderSettings.Renderer.RESTIR_PT));
                        screen.tick();
                        restirScreen.tick();
                        check(temporalButton.active,
                              "Temporal reuse is active for realtime ReSTIR");
                        assertRestirOptions(restirScreen, translations, true);
                        for (boolean enabled : new boolean[] {false, true}) {
                            temporalButton.onPress(new net.minecraft.client.input.KeyEvent(
                                    InputConstants.KEY_RETURN, 0, 0));
                            check(temporalReuse.get() == enabled &&
                                          PrimeClient.settings().restirTemporalReuse() == enabled &&
                                          PrimeClient.settings().restirSpatialOnly() != enabled &&
                                          PrimeClient.settings().nativeNoisyOutput() ==
                                                  originalSettings.nativeNoisyOutput() &&
                                          PrimeClient.settings().dlssQuality() ==
                                                  originalSettings.dlssQuality() &&
                                          !offline.active() && !offline.requested(),
                                  "Temporal toggle has positive semantics and preserves reconstruction/offline");
                            assertSingleCaption(
                                    temporalButton,
                                    translations.get("primept.settings.restir_pt.temporal_reuse"));
                            check(SettingsFile.decode(SettingsFile.encode(PrimeClient.settings()))
                                          .settings()
                                          .equals(PrimeClient.settings()),
                                  "Temporal reuse callback persists its exact inverse ABI setting");
                        }
                        PrimeClient.updateSettings(PrimeClient.settings().withPathTracing(false));
                        restirScreen.tick();
                        check(!temporalButton.active,
                              "Disabling path tracing disables realtime ReSTIR controls");
                        assertRestirOptions(restirScreen, translations, false);
                        PrimeClient.updateSettings(PrimeClient.settings().withPathTracing(true));
                        offline.request(true);
                        offline.committed(true);
                        restirScreen.tick();
                        check(!temporalButton.active,
                              "Frozen ReSTIR keeps temporal controls inactive");
                        assertRestirOptions(restirScreen, translations, false);
                        offline.reset();
                        PrimeClient.updateSettings(originalSettings);
                        screen.tick();
                        restirScreen.tick();
                        check(!temporalButton.active,
                              "Naive PT leaves temporal reuse controls inactive");
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
                        nativeNoisyOutput.set(true);
                        screen.tick();
                        check(PrimeClient.settings().nativeNoisyOutput() &&
                                      !list.findOption(dlssQuality).active,
                              "Native noisy output is persisted and disables RR quality control");
                        nativeNoisyOutput.set(false);
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
                if (List.of("render", "lighting", "display", "diagnostics")
                            .stream()
                            .noneMatch(group
                                       -> widget.getMessage().getString().equals(
                                               Component.translatable("primept.settings." + group)
                                                       .getString())))
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
                          Component.translatable("primept.settings." + group).getString()),
                  "Prime groups precede vanilla: " + headers);
        }
        int vanilla = firstVanillaRow(list);
        var owner = videoOptions(screen);
        var omm = (OptionInstance<?>)field(PrimeVideoOptions.class, "opacityMicromap").get(owner);
        var rr = (OptionInstance<?>)field(PrimeVideoOptions.class, "nativeNoisyOutput").get(owner);
        var globalResets =
                (OptionInstance<?>)field(PrimeVideoOptions.class, "ignoreGlobalHistoryResets")
                        .get(owner);
        var pathTracing =
                (OptionInstance<?>)field(PrimeVideoOptions.class, "pathTracing").get(owner);
        var offline = (OptionInstance<?>)field(PrimeVideoOptions.class, "offline").get(owner);
        var renderer = (OptionInstance<?>)field(PrimeVideoOptions.class, "renderer").get(owner);
        for (int i = 0; i < 3; ++i)
            check(((ContainerEventHandler)list.children().get(2 + i))
                          .children()
                          .contains(
                                  list.findOption(List.of(pathTracing, offline, renderer).get(i))),
                  "Rendering starts with path tracing, offline, then realtime renderer");
        check(((ContainerEventHandler)list.children().get(5))
                      .children()
                      .stream()
                      .anyMatch(
                              child
                              -> child instanceof Button button &&
                                         button.getMessage().getString().equals(
                                                 Component
                                                         .translatable("primept.settings.restir_pt")
                                                         .getString())),
              "Rendering contains one ReSTIR settings subpage button");
        boolean diagnostics = false;
        for (int i = 0; i < vanilla; ++i) {
            Object row = list.children().get(i);
            if (row.getClass().getSimpleName().equals("HeaderEntry")) {
                diagnostics = ((AbstractWidget)((ContainerEventHandler)row).children().getFirst())
                                      .getMessage()
                                      .getString()
                                      .equals(Component.translatable("primept.settings.diagnostics")
                                                      .getString());
            }
            if (((ContainerEventHandler)row).children().contains(list.findOption(omm)) ||
                ((ContainerEventHandler)row).children().contains(list.findOption(rr)) ||
                ((ContainerEventHandler)row).children().contains(list.findOption(globalResets)))
                check(diagnostics, "RR and OMM belong only to the diagnostics group");
        }
        var controls = (java.util.EnumMap<?, ?>)field(PrimeVideoOptions.class, "controls").get(owner);
        var depthRange = (OptionInstance<?>)controls.get(RenderSettings.Control.DEPTH_RANGE);
        check(diagnostics &&
                      ((ContainerEventHandler)list.children().get(vanilla - 2))
                              .children()
                              .contains(list.findOption(depthRange)) &&
                      ((ContainerEventHandler)list.children().get(vanilla - 1))
                              .children()
                              .stream()
                              .anyMatch(
                                      child
                                      -> child instanceof Button button &&
                                                 button.getMessage().getString().equals(
                                                         Component
                                                                 .translatable(
                                                                         "primept.settings.github")
                                                                 .getString())),
              "GitHub finishes Prime diagnostics after its depth control and before vanilla options");
        check(list.findOption(Minecraft.getInstance().options.renderDistance()) != null,
              "Vanilla render-distance option remains present");
        int[] layoutWidgets = {0};
        screen.layout.visitWidgets(widget -> ++layoutWidgets[0]);
        check(layoutWidgets[0] == screen.children().size(),
              "Video layouts do not retain obsolete widget copies");
    }

    @SuppressWarnings("unchecked")
    private static void assertRestirOptions(RestirSettingsScreen screen,
                                            HashMap<String, String> translations, boolean callbacks)
            throws Exception {
        var list = optionsList(screen);
        var module = (RestirVideoOptions)field(RestirSettingsScreen.class, "restir").get(screen);
        var options =
                (List<OptionInstance<?>>)field(RestirVideoOptions.class, "controls").get(module);
        var seed = (EditBox)field(RestirVideoOptions.class, "seed").get(module);
        var before = PrimeClient.settings();
        check(options.size() == RestirSettings.Control.values().length,
              "Each native ReSTIR setting has exactly one control");
        for (var control : RestirSettings.Control.values()) {
            String key = "primept.settings." + control.key;
            check(translations.containsKey(key) && translations.containsKey(key + ".tooltip"),
                  "Missing ReSTIR translation: " + control);
            var option = (OptionInstance<Object>)options.get(control.ordinal());
            var widget = list.findOption(option);
            boolean cycle = control.kind == RestirSettings.Kind.BOOLEAN ||
                            control == RestirSettings.Control.DEBUG_VIEW ||
                            control == RestirSettings.Control.RR_MODE;
            check(cycle ? widget instanceof CycleButton<?> : widget instanceof AbstractSliderButton,
                  "ReSTIR toggle/mode/slider type: " + control);
            check(widget.getWidth() == list.getRowWidth(), "Full-width ReSTIR control: " + control);
            check(widget.active == callbacks, "ReSTIR controls follow renderer availability");
            assertOptionCaption(option, widget, translations.get(key));
            Object configured = restirOptionValue(control, before.restir().value(control));
            check(option.get().equals(configured), "Actual configured ReSTIR value: " + control);
            if (control == RestirSettings.Control.DEBUG_VIEW ||
                control == RestirSettings.Control.RR_MODE)
                for (int mode = 0; mode < 3; ++mode)
                    check(translations.containsKey(key + "." + mode), "Translated ReSTIR mode");
            if (control.kind == RestirSettings.Kind.FLOAT) {
                var range = new RestirVideoOptions.FloatRange(control);
                double previous = -Double.MAX_VALUE;
                for (double slider : new double[] {0, 0.125, 0.5, 0.875, 1}) {
                    double value = range.fromSliderValue(slider);
                    check(value >= control.minimum && value <= control.maximum && value >= previous,
                          "ReSTIR float sliders remain monotone and bounded");
                    check(Math.abs(range.toSliderValue(value) - slider) < 0.000001,
                          "ReSTIR float slider round trip");
                    previous = value;
                }
                check(range.fromSliderValue(range.toSliderValue(control.initial)) ==
                              control.initial,
                      "ReSTIR float slider preserves its default, including footprint 0.02");
            }
            if (callbacks) {
                for (double value :
                     new double[] {control.minimum, control.maximum, control.initial}) {
                    option.set(restirOptionValue(control, value));
                    check(PrimeClient.settings().restir().value(control) ==
                                  control.normalize(value),
                          "Actual ReSTIR callback persists its exact value: " + control);
                    check(PrimeClient.settings().renderer() == before.renderer() &&
                                  PrimeClient.settings().value(RenderSettings.Control.BOUNCES) ==
                                          before.value(RenderSettings.Control.BOUNCES),
                          "ReSTIR callback preserves unrelated settings");
                }
                option.set(configured);
            }
            assertSingleCaption(widget, translations.get(key));
            String label = widget.getMessage().getString();
            check(!label.contains("primept.settings."), "Unresolved ReSTIR label " + label);
            check(Minecraft.getInstance().font.width(widget.getMessage()) <= widget.getWidth() - 16,
                  "ReSTIR label exceeds its control: " + label);
        }
        check(seed.active == callbacks, "Seed availability follows ReSTIR controls");
        if (callbacks) {
            seed.setValue("4294967295");
            check(PrimeClient.settings().restir().seed() == 0xffffffffL,
                  "Actual seed field accepts the full unsigned 32-bit range");
            seed.setValue("4294967296");
            check(PrimeClient.settings().restir().seed() == 0xffffffffL,
                  "Invalid seed text preserves the last valid setting");
            seed.setValue("0");
            check(PrimeClient.settings().restir().seed() == 0, "Actual seed field accepts zero");
            seed.setValue(Long.toString(before.restir().seed()));
            check(PrimeClient.settings().equals(before), "ReSTIR callbacks restore all settings");
            check(SettingsFile.decode(SettingsFile.encode(before)).settings().equals(before),
                  "Every ReSTIR control persists through the real file codec");
        }
    }

    private static OptionsList optionsList(Screen screen) {
        return (OptionsList)screen.children()
                .stream()
                .filter(OptionsList.class ::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private static RestirSettingsScreen openRestirSubpage(VideoSettingsScreen parent,
                                                          ScreenRouter router,
                                                          java.lang.reflect.Method rebuild)
            throws Exception {
        var list = optionsList(parent);
        var buttons = ((List<?>)list.children())
                              .stream()
                              .flatMap(row -> ((ContainerEventHandler)row).children().stream())
                              .filter(Button.class ::isInstance)
                              .map(Button.class ::cast)
                              .filter(button
                                      -> button.getMessage().getString().equals(
                                              Component.translatable("primept.settings.restir_pt")
                                                      .getString()))
                              .toList();
        check(buttons.size() == 1, "All ReSTIR settings share one subpage entry");
        buttons.getFirst().onPress(null);
        check(router.selected instanceof RestirSettingsScreen &&
                      field(OptionsSubScreen.class, "lastScreen").get(router.selected) == parent,
              "The actual ReSTIR button opens the vanilla options subpage and keeps its parent");
        var child = (RestirSettingsScreen)router.selected;
        child.width = 480;
        child.height = 270;
        rebuild.invoke(child);
        int initialWidgets = child.children().size();
        for (int[] size : new int[][] {{320, 240}, {641, 361}, {480, 270}}) {
            child.resize(size[0], size[1]);
            check(child.children().size() == initialWidgets,
                  "ReSTIR subpage resize retains one list/title/footer");
            int[] widgets = {0};
            child.layout.visitWidgets(widget -> ++widgets[0]);
            check(widgets[0] == initialWidgets, "ReSTIR layout releases obsolete widgets");
        }
        var done = child.children()
                           .stream()
                           .filter(Button.class ::isInstance)
                           .map(Button.class ::cast)
                           .filter(button
                                   -> button.getMessage().getString().equals(
                                           Component.translatable("gui.done").getString()))
                           .findFirst()
                           .orElseThrow();
        done.onPress(null);
        check(router.selected == parent, "Native Done returns to the original video page");
        buttons.getFirst().onPress(null);
        check(router.selected instanceof RestirSettingsScreen, "ReSTIR subpage can reopen");
        child = (RestirSettingsScreen)router.selected;
        child.width = 480;
        child.height = 270;
        rebuild.invoke(child);
        child.onClose();
        check(router.selected == parent, "Native Escape close preserves the original parent");
        var github = ((ContainerEventHandler)list.children().get(firstVanillaRow(list) - 1))
                             .children()
                             .stream()
                             .filter(Button.class ::isInstance)
                             .map(Button.class ::cast)
                             .findFirst()
                             .orElseThrow();
        github.onPress(null);
        check(router.selected instanceof ConfirmLinkScreen &&
                      field(ConfirmLinkScreen.class, "url")
                              .get(router.selected)
                              .toString()
                              .equals("https://github.com/bWFuanVzYWth/prime"),
              "Actual GitHub button opens the vanilla confirmation for the repository URL");
        return child;
    }

    private static void assertRootCaptions(PrimeVideoOptions owner, OptionsList list,
                                           HashMap<String, String> translations) throws Exception {
        for (String[] entry :
             new String[][] {{"pathTracing", "path_tracing"},
                             {"offline", "offline"},
                             {"renderer", "renderer"},
                             {"opacityMicromap", "opacity_micromap"},
                             {"nativeNoisyOutput", "native_noisy_output"},
                             {"dlssQuality", "dlss_quality"},
                             {"performanceCapture", "performance_export"},
                             {"ignoreGlobalHistoryResets", "ignore_global_history_resets"},
                             {"view", "view"}}) {
            var option = (OptionInstance<?>)field(PrimeVideoOptions.class, entry[0]).get(owner);
            assertOptionCaption(option, list.findOption(option),
                                translations.get("primept.settings." + entry[1]));
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertOptionCaption(OptionInstance<?> option, AbstractWidget widget,
                                            String caption) {
        if (widget instanceof CycleButton<?> button && option.values() instanceof
                                                               OptionInstance.Enum<?> choices) {
            var cycle = (CycleButton<Object>)button;
            for (Object value : choices.values()) {
                cycle.setValue(value);
                assertSingleCaption(widget, caption);
            }
            cycle.setValue(option.get());
        } else {
            assertSingleCaption(widget, caption);
        }
    }

    private static void assertSingleCaption(AbstractWidget widget, String caption) {
        String message = widget.getMessage().getString();
        check(caption != null && !caption.isEmpty() && message.indexOf(caption) >= 0 &&
                      message.indexOf(caption) == message.lastIndexOf(caption),
              "Option caption occurs exactly once: " + message);
    }

    private static Object restirOptionValue(RestirSettings.Control control, double value) {
        if (control.kind == RestirSettings.Kind.BOOLEAN)
            return value != 0;
        if (control.kind == RestirSettings.Kind.FLOAT)
            return control.normalize(value);
        return (int)value / control.step;
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
