package dev.primept.capture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

/** Invokes the actual transformed sort wrapper; no raster/device or source callback replay. */
final class TerrainRasterCpuSmoke {
    static void run() throws Exception {
        var compiler = blank(SectionCompiler.class);
        var mesh = blank(MeshData.class);
        var marker = blank(MeshData.SortState.class);
        var graph = new net.minecraft.client.renderer.chunk.VisGraph();
        var visibility = new net.minecraft.client.renderer.chunk.VisibilitySet();
        var visibilityMethod =
                java.util.Arrays.stream(SectionCompiler.class.getDeclaredMethods())
                        .filter(m -> m.getName().endsWith("primept$discardVisibility"))
                        .findFirst()
                        .orElseThrow();
        visibilityMethod.setAccessible(true);
        int[] visibilityCalls = {0};
        Operation<net.minecraft.client.renderer.chunk.VisibilitySet> resolve = arguments -> {
            visibilityCalls[0]++;
            return visibility;
        };
        var method = java.util.Arrays.stream(SectionCompiler.class.getDeclaredMethods())
                             .filter(m -> m.getName().endsWith("primept$discardSort"))
                             .findFirst()
                             .orElseThrow();
        method.setAccessible(true);
        int[] calls = {0};
        Operation<MeshData.SortState> original = arguments -> {
            calls[0]++;
            return marker;
        };
        try (var output = TerrainRasterOutput.open()) {
            check(visibilityMethod.invoke(compiler, graph, resolve, output.sorting) == visibility,
                  "No capture retains actual raster visibility");
            check(method.invoke(compiler, mesh, null, output.sorting, original) == marker,
                  "No capture keeps original sort");
            try (var capture = TerrainCapture.open(new CaptureInbox(true), SectionPos.of(0, 0, 0),
                                                   false)) {
                check(method.invoke(compiler, mesh, null, output.sorting, original) == null,
                      "Known discarded sort omitted");
                check(calls[0] == 1, "No original callback replay");
                check(visibilityMethod.invoke(compiler, graph, resolve, output.sorting) == null &&
                              visibilityCalls[0] == 1,
                      "Private result has no raster visibility consumer");
                check(visibilityMethod.invoke(compiler, graph, resolve,
                                              VertexSorting.byDistance(1, 2, 3)) == visibility,
                      "Foreign compiler invocation keeps visibility even inside an owner scope");
                check(method.invoke(compiler, mesh, null, VertexSorting.byDistance(1, 2, 3),
                                    original) == marker,
                      "Foreign sorting input retains original path");
            }
        }
        check(visibilityMethod.invoke(compiler, graph, resolve,
                                      VertexSorting.byDistance(0, 0, 0)) == visibility &&
                      visibilityCalls[0] == 3,
              "Visibility scope exit restores original behavior");
        check(method.invoke(compiler, mesh, null, VertexSorting.byDistance(0, 0, 0), original) ==
                              marker &&
                      calls[0] == 3,
              "Scope exit restores original behavior");
        System.out.println(
                "PRIME_PT_TERRAIN_RASTER_CPU_OK: real transformed sort hook; owned source-only compile omits sort; no capture/foreign sorting/outside scope call original once");
    }
    private static <T> T blank(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(
                unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
