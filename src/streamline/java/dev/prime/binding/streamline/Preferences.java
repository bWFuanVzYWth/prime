// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** sl::Preferences — {1CA10965-BF8E-432B-8DA1-6716D879FB14}, kStructVersion1 */
public final class Preferences {
    private static final int DEFAULT_LOG_LEVEL = 1;
    private static final int VULKAN_RENDER_API = 2;

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

    private static final VarHandle LOG_LEVEL = LAYOUT.varHandle(groupElement("logLevel"));
    private static final VarHandle PATHS_TO_PLUGINS = LAYOUT.varHandle(groupElement("pathsToPlugins"));
    private static final VarHandle NUM_PATHS_TO_PLUGINS = LAYOUT.varHandle(groupElement("numPathsToPlugins"));
    private static final VarHandle PATH_TO_LOGS_AND_DATA = LAYOUT.varHandle(groupElement("pathToLogsAndData"));
    private static final VarHandle FLAGS = LAYOUT.varHandle(groupElement("flags"));
    private static final VarHandle FEATURES_TO_LOAD = LAYOUT.varHandle(groupElement("featuresToLoad"));
    private static final VarHandle NUM_FEATURES_TO_LOAD = LAYOUT.varHandle(groupElement("numFeaturesToLoad"));
    private static final VarHandle ENGINE_VERSION = LAYOUT.varHandle(groupElement("engineVersion"));
    private static final VarHandle PROJECT_ID = LAYOUT.varHandle(groupElement("projectId"));
    private static final VarHandle RENDER_API = LAYOUT.varHandle(groupElement("renderAPI"));

    private final MemorySegment segment;

    private Preferences(MemorySegment segment) {
        this.segment = segment;
    }

    public static Preferences prime(
            Arena arena,
            Path pluginDirectory,
            Path logDirectory,
            String engineVersion,
            String projectId,
            long flags) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x1ca10965, (short) 0xbf8e, (short) 0x432b, 0x14FB79D81667A18DL, 1);
        // Zero initialization also selects showConsole=false and EngineType::eCustom.
        LOG_LEVEL.set(segment, 0L, DEFAULT_LOG_LEVEL);
        MemorySegment pluginPath = arena.allocateFrom(
                pluginDirectory.toString(), StandardCharsets.UTF_16LE);
        PATHS_TO_PLUGINS.set(segment, 0L, arena.allocateFrom(ADDRESS, pluginPath));
        NUM_PATHS_TO_PLUGINS.set(segment, 0L, 1);
        PATH_TO_LOGS_AND_DATA.set(segment, 0L, arena.allocateFrom(
                logDirectory.toString(), StandardCharsets.UTF_16LE));
        FEATURES_TO_LOAD.set(segment, 0L, arena.allocateFrom(
                JAVA_INT, FrameGeneration.FEATURE_ID, Pcl.FEATURE_ID, Reflex.FEATURE_ID));
        NUM_FEATURES_TO_LOAD.set(segment, 0L, 3);
        FLAGS.set(segment, 0L, flags);
        ENGINE_VERSION.set(segment, 0L, arena.allocateFrom(engineVersion));
        PROJECT_ID.set(segment, 0L, arena.allocateFrom(projectId));
        RENDER_API.set(segment, 0L, VULKAN_RENDER_API);
        return new Preferences(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

}
