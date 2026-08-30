package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Thin FFM binding for the Streamline base API exported by sl.interposer.dll.
 * All feature ids are caller-provided raw {@code int} values; no feature filtering is applied.
 * All native memory (including strings, arrays and Vulkan handles wrapped as address-carrying
 * segments) is owned by the caller; {@link #close()} only releases the library arena and does
 * not call {@code slShutdown()}.
 */
@SuppressWarnings("restricted")
public final class Streamline implements AutoCloseable {
    public static final long SDK_VERSION = 0x0002_000C_0000_FEDCL;
    public static final int RESULT_OK = 0;

    private static final FunctionDescriptor INIT_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor SHUTDOWN_DESC = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor SET_VULKAN_INFO_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor IS_FEATURE_SUPPORTED_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor IS_FEATURE_LOADED_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor SET_FEATURE_LOADED_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_BOOLEAN);
    private static final FunctionDescriptor GET_FEATURE_REQUIREMENTS_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor GET_FEATURE_VERSION_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor GET_NEW_FRAME_TOKEN_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor SET_CONSTANTS_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor SET_TAG_FOR_FRAME_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor ALLOCATE_RESOURCES_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor FREE_RESOURCES_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor EVALUATE_FEATURE_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor GET_FEATURE_FUNCTION_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);

    private final Arena arena;
    private final Linker linker;
    private final MethodHandle slInit;
    private final MethodHandle slShutdown;
    private final MethodHandle slSetVulkanInfo;
    private final MethodHandle slIsFeatureSupported;
    private final MethodHandle slIsFeatureLoaded;
    private final MethodHandle slSetFeatureLoaded;
    private final MethodHandle slGetFeatureRequirements;
    private final MethodHandle slGetFeatureVersion;
    private final MethodHandle slGetNewFrameToken;
    private final MethodHandle slSetConstants;
    private final MethodHandle slSetTagForFrame;
    private final MethodHandle slAllocateResources;
    private final MethodHandle slFreeResources;
    private final MethodHandle slEvaluateFeature;
    private final MethodHandle slGetFeatureFunction;

    private Streamline(Arena arena, Linker linker, SymbolLookup lookup) {
        this.arena = arena;
        this.linker = linker;
        this.slInit = downcall(linker, lookup, "slInit", INIT_DESC);
        this.slShutdown = downcall(linker, lookup, "slShutdown", SHUTDOWN_DESC);
        this.slSetVulkanInfo = downcall(linker, lookup, "slSetVulkanInfo", SET_VULKAN_INFO_DESC);
        this.slIsFeatureSupported = downcall(linker, lookup, "slIsFeatureSupported", IS_FEATURE_SUPPORTED_DESC);
        this.slIsFeatureLoaded = downcall(linker, lookup, "slIsFeatureLoaded", IS_FEATURE_LOADED_DESC);
        this.slSetFeatureLoaded = downcall(linker, lookup, "slSetFeatureLoaded", SET_FEATURE_LOADED_DESC);
        this.slGetFeatureRequirements = downcall(linker, lookup, "slGetFeatureRequirements", GET_FEATURE_REQUIREMENTS_DESC);
        this.slGetFeatureVersion = downcall(linker, lookup, "slGetFeatureVersion", GET_FEATURE_VERSION_DESC);
        this.slGetNewFrameToken = downcall(linker, lookup, "slGetNewFrameToken", GET_NEW_FRAME_TOKEN_DESC);
        this.slSetConstants = downcall(linker, lookup, "slSetConstants", SET_CONSTANTS_DESC);
        this.slSetTagForFrame = downcall(linker, lookup, "slSetTagForFrame", SET_TAG_FOR_FRAME_DESC);
        this.slAllocateResources = downcall(linker, lookup, "slAllocateResources", ALLOCATE_RESOURCES_DESC);
        this.slFreeResources = downcall(linker, lookup, "slFreeResources", FREE_RESOURCES_DESC);
        this.slEvaluateFeature = downcall(linker, lookup, "slEvaluateFeature", EVALUATE_FEATURE_DESC);
        this.slGetFeatureFunction = downcall(linker, lookup, "slGetFeatureFunction", GET_FEATURE_FUNCTION_DESC);
    }

    public static Streamline open(Path interposerDll) {
        Arena arena = Arena.ofShared();
        boolean ok = false;
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(interposerDll, arena);
            Streamline streamline = new Streamline(arena, Linker.nativeLinker(), lookup);
            ok = true;
            return streamline;
        } finally {
            if (!ok) {
                arena.close();
            }
        }
    }

    public int init(Preferences preferences) {
        try {
            return (int) this.slInit.invokeExact(preferences.segment(), SDK_VERSION);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int shutdown() {
        try {
            return (int) this.slShutdown.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int setVulkanInfo(VulkanInfo info) {
        try {
            return (int) this.slSetVulkanInfo.invokeExact(info.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int isFeatureSupported(int featureId, AdapterInfo adapterInfo) {
        try {
            return (int) this.slIsFeatureSupported.invokeExact(featureId, adapterInfo.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int isFeatureLoaded(int featureId, MemorySegment loadedOut) {
        try {
            return (int) this.slIsFeatureLoaded.invokeExact(featureId, loadedOut);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int setFeatureLoaded(int featureId, boolean loaded) {
        try {
            return (int) this.slSetFeatureLoaded.invokeExact(featureId, loaded);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int getFeatureRequirements(int featureId, FeatureRequirements requirementsOut) {
        try {
            return (int) this.slGetFeatureRequirements.invokeExact(featureId, requirementsOut.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int getFeatureVersion(int featureId, FeatureVersion versionOut) {
        try {
            return (int) this.slGetFeatureVersion.invokeExact(featureId, versionOut.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** @param tokenOut caller-provided 8-byte segment receiving the {@code sl::FrameToken*} */
    public int getNewFrameToken(MemorySegment tokenOut, MemorySegment frameIndex) {
        try {
            return (int) this.slGetNewFrameToken.invokeExact(tokenOut, frameIndex);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int setConstants(Constants constants, MemorySegment frameToken, ViewportHandle viewport) {
        try {
            return (int) this.slSetConstants.invokeExact(constants.segment(), frameToken, viewport.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int setTagForFrame(MemorySegment frameToken, ViewportHandle viewport, MemorySegment tags, int numTags, MemorySegment commandBuffer) {
        try {
            return (int) this.slSetTagForFrame.invokeExact(frameToken, viewport.segment(), tags, numTags, commandBuffer);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int allocateResources(MemorySegment commandBuffer, int featureId, ViewportHandle viewport) {
        try {
            return (int) this.slAllocateResources.invokeExact(commandBuffer, featureId, viewport.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int freeResources(int featureId, ViewportHandle viewport) {
        try {
            return (int) this.slFreeResources.invokeExact(featureId, viewport.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int evaluateFeature(int featureId, MemorySegment frameToken, MemorySegment inputs, int numInputs, MemorySegment commandBuffer) {
        try {
            return (int) this.slEvaluateFeature.invokeExact(featureId, frameToken, inputs, numInputs, commandBuffer);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Resolves a feature-specific function through {@code slGetFeatureFunction} and links it. */
    public MethodHandle getFeatureFunction(int featureId, String name, FunctionDescriptor descriptor) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment functionName = temp.allocateFrom(name, StandardCharsets.UTF_8);
            MemorySegment functionOut = temp.allocate(ADDRESS);
            int result;
            try {
                result = (int) this.slGetFeatureFunction.invokeExact(featureId, functionName, functionOut);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            MemorySegment function = functionOut.get(ADDRESS, 0);
            if (result != RESULT_OK || function.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("slGetFeatureFunction failed for feature " + featureId + ", function '" + name + "' (result " + result + ")");
            }
            return this.linker.downcallHandle(function, descriptor);
        }
    }

    @Override
    public void close() {
        this.arena.close();
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name).orElseThrow();
        return linker.downcallHandle(symbol, descriptor);
    }
}
