package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** sl::Preferences — {1CA10965-BF8E-432B-8DA1-6716D879FB14}, kStructVersion1 */
public final class Preferences {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_BOOLEAN.withName("showConsole"),
            paddingLayout(3),
            JAVA_INT.withName("logLevel"),
            ADDRESS.withName("pathsToPlugins"),
            JAVA_INT.withName("numPathsToPlugins"),
            paddingLayout(4),
            ADDRESS.withName("pathToLogsAndData"),
            ADDRESS.withName("allocateCallback"),
            ADDRESS.withName("releaseCallback"),
            ADDRESS.withName("logMessageCallback"),
            JAVA_LONG.withName("flags"),
            ADDRESS.withName("featuresToLoad"),
            JAVA_INT.withName("numFeaturesToLoad"),
            JAVA_INT.withName("applicationId"),
            JAVA_INT.withName("engine"),
            paddingLayout(4),
            ADDRESS.withName("engineVersion"),
            ADDRESS.withName("projectId"),
            JAVA_INT.withName("renderAPI"),
            paddingLayout(4));

    private static final VarHandle SHOW_CONSOLE = LAYOUT.varHandle(groupElement("showConsole"));
    private static final VarHandle LOG_LEVEL = LAYOUT.varHandle(groupElement("logLevel"));
    private static final VarHandle PATHS_TO_PLUGINS = LAYOUT.varHandle(groupElement("pathsToPlugins"));
    private static final VarHandle NUM_PATHS_TO_PLUGINS = LAYOUT.varHandle(groupElement("numPathsToPlugins"));
    private static final VarHandle PATH_TO_LOGS_AND_DATA = LAYOUT.varHandle(groupElement("pathToLogsAndData"));
    private static final VarHandle FLAGS = LAYOUT.varHandle(groupElement("flags"));
    private static final VarHandle FEATURES_TO_LOAD = LAYOUT.varHandle(groupElement("featuresToLoad"));
    private static final VarHandle NUM_FEATURES_TO_LOAD = LAYOUT.varHandle(groupElement("numFeaturesToLoad"));
    private static final VarHandle ENGINE = LAYOUT.varHandle(groupElement("engine"));
    private static final VarHandle ENGINE_VERSION = LAYOUT.varHandle(groupElement("engineVersion"));
    private static final VarHandle PROJECT_ID = LAYOUT.varHandle(groupElement("projectId"));
    private static final VarHandle RENDER_API = LAYOUT.varHandle(groupElement("renderAPI"));

    private final MemorySegment segment;

    private Preferences(MemorySegment segment) {
        this.segment = segment;
    }

    public static Preferences allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x1ca10965, (short) 0xbf8e, (short) 0x432b, 0x14FB79D81667A18DL, 1);
        LOG_LEVEL.set(segment, 0L, LogLevel.DEFAULT.value);
        Preferences preferences = new Preferences(segment);
        preferences.flags(PreferenceFlag.DISABLE_CL_STATE_TRACKING.mask | PreferenceFlag.ALLOW_OTA.mask | PreferenceFlag.LOAD_DOWNLOADED_PLUGINS.mask);
        preferences.renderApi(RenderApi.D3D12);
        return preferences;
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public Preferences showConsole(boolean value) {
        SHOW_CONSOLE.set(this.segment, 0L, value);
        return this;
    }

    /** const wchar_t** — caller-managed pointer to an array of UTF-16 path strings */
    public Preferences pathsToPlugins(MemorySegment value) {
        PATHS_TO_PLUGINS.set(this.segment, 0L, value);
        return this;
    }

    public Preferences numPathsToPlugins(int value) {
        NUM_PATHS_TO_PLUGINS.set(this.segment, 0L, value);
        return this;
    }

    /** const wchar_t* — caller-managed UTF-16 path string, null disables logging to a file */
    public Preferences pathToLogsAndData(MemorySegment value) {
        PATH_TO_LOGS_AND_DATA.set(this.segment, 0L, value);
        return this;
    }

    /** Raw uint64_t mask built from {@link PreferenceFlag} bits */
    public Preferences flags(long value) {
        FLAGS.set(this.segment, 0L, value);
        return this;
    }

    /** const sl::Feature* — caller-managed uint32 feature id array */
    public Preferences featuresToLoad(MemorySegment value) {
        FEATURES_TO_LOAD.set(this.segment, 0L, value);
        return this;
    }

    public Preferences numFeaturesToLoad(int value) {
        NUM_FEATURES_TO_LOAD.set(this.segment, 0L, value);
        return this;
    }

    public Preferences engine(EngineType value) {
        ENGINE.set(this.segment, 0L, value.value);
        return this;
    }

    /** const char* — caller-managed UTF-8 string */
    public Preferences engineVersion(MemorySegment value) {
        ENGINE_VERSION.set(this.segment, 0L, value);
        return this;
    }

    /** const char* — caller-managed UTF-8 GUID string */
    public Preferences projectId(MemorySegment value) {
        PROJECT_ID.set(this.segment, 0L, value);
        return this;
    }

    public Preferences renderApi(RenderApi value) {
        RENDER_API.set(this.segment, 0L, value.value);
        return this;
    }
}
