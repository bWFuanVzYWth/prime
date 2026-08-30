package dev.prime.render.runtime;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.FrameCamera;
import dev.prime.render.vulkan.MaterialTexturePages;
import dev.prime.render.vulkan.RendererDataRangeDiagnostics;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanMemorySnapshot;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/** Render-thread-owned, opt-in aggregate recorder for stage-2 renderer-data decisions. */
final class RendererDataMeasurementRecorder {
    static final String ENABLE_PROPERTY = "prime.renderer.measure";
    static final String INTERVAL_PROPERTY = "prime.renderer.measure.intervalFrames";
    static final int DEFAULT_INTERVAL_FRAMES = 120;
    static final int MAX_INTERVAL_FRAMES = 3_600;

    private final Path output;
    private final int intervalFrames;
    private final String deviceName;
    private long frameCount;
    private long nextWriteFrame;
    private double previousX;
    private double previousY;
    private double previousZ;
    private boolean hasPreviousCamera;
    private double maximumCameraStep;
    private int maximumRenderWidth;
    private int maximumRenderHeight;
    private int maximumDisplayWidth;
    private int maximumDisplayHeight;
    private int maximumTlasInstances;
    private long maximumUniqueTriangles;
    private long maximumInstancedTriangles;
    private int maximumAreaLights;
    private int maximumLightTreeNodes;
    private TerrainScene.MediumIdStatistics mediumIds;
    private MaterialTexturePages.MeasurementSnapshot textures;
    private VulkanMemorySnapshot memory;
    private long textureGenerationCount;
    private long previousTextureGeneration = Long.MIN_VALUE;
    private String reconstruction = "unknown";
    private String quality = "unknown";
    private RealtimeRenderer.DiagnosticSnapshot latestRenderer;
    private boolean warned;
    private boolean dirty;

    private RendererDataMeasurementRecorder() {
        this.output = null;
        this.intervalFrames = Integer.MAX_VALUE;
        this.deviceName = "disabled";
        this.nextWriteFrame = Long.MAX_VALUE;
    }

    RendererDataMeasurementRecorder(Path output, int intervalFrames, String deviceName) {
        this.output = java.util.Objects.requireNonNull(output, "output");
        if (intervalFrames < 1 || intervalFrames > MAX_INTERVAL_FRAMES) {
            throw new IllegalArgumentException("Measurement interval must be in [1, 3600]");
        }
        this.intervalFrames = intervalFrames;
        this.deviceName = java.util.Objects.requireNonNull(deviceName, "deviceName");
        this.nextWriteFrame = intervalFrames;
    }

    static RendererDataMeasurementRecorder fromSystemProperties(String deviceName) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            // Preserve the disabled contract: do not query Fabric's game directory.
            return new RendererDataMeasurementRecorder();
        }
        int interval = (int) Math.max(
                1L,
                Math.min(
                        Long.getLong(INTERVAL_PROPERTY, DEFAULT_INTERVAL_FRAMES),
                        MAX_INTERVAL_FRAMES));
        return new RendererDataMeasurementRecorder(
                FabricLoader.getInstance()
                        .getGameDir()
                        .resolve("prime-renderer-measurements")
                        .resolve("latest.json"),
                interval,
                deviceName);
    }

    void recordFrame(
            VulkanContext context,
            MaterialTexturePages.MeasurementSnapshot textures,
            TerrainScene.SceneStatistics scene,
            TerrainScene.MediumIdStatistics mediumIds,
            FrameCamera camera,
            RealtimeRenderer.DiagnosticSnapshot renderer) {
        if (!this.accumulate(textures, scene, mediumIds, camera, renderer)) {
            return;
        }
        this.captureAndWrite(context);
        this.nextWriteFrame = Math.addExact(this.frameCount, this.intervalFrames);
    }

    void recordFrame(
            MaterialTexturePages.MeasurementSnapshot textures,
            TerrainScene.SceneStatistics scene,
            TerrainScene.MediumIdStatistics mediumIds,
            FrameCamera camera,
            RealtimeRenderer.DiagnosticSnapshot renderer,
            VulkanMemorySnapshot memory) {
        if (this.accumulate(textures, scene, mediumIds, camera, renderer)) {
            this.memory = mergeMemory(this.memory, memory);
            try {
                writeAtomically(this.output, this.json());
                this.dirty = false;
            } catch (IOException failure) {
                this.warnOnce(failure);
            }
            this.nextWriteFrame = Math.addExact(this.frameCount, this.intervalFrames);
        }
    }

    private boolean accumulate(
            MaterialTexturePages.MeasurementSnapshot textures,
            TerrainScene.SceneStatistics scene,
            TerrainScene.MediumIdStatistics mediumIds,
            FrameCamera camera,
            RealtimeRenderer.DiagnosticSnapshot renderer) {
        if (this.output == null) {
            return false;
        }
        this.frameCount++;
        this.textures = textures;
        if (textures != null && textures.sourceGeneration() != this.previousTextureGeneration) {
            this.previousTextureGeneration = textures.sourceGeneration();
            this.textureGenerationCount++;
        }
        this.mediumIds = mediumIds;
        this.maximumRenderWidth = Math.max(this.maximumRenderWidth, renderer.renderWidth());
        this.maximumRenderHeight = Math.max(this.maximumRenderHeight, renderer.renderHeight());
        this.maximumDisplayWidth = Math.max(this.maximumDisplayWidth, renderer.displayWidth());
        this.maximumDisplayHeight = Math.max(this.maximumDisplayHeight, renderer.displayHeight());
        this.reconstruction = renderer.postProcessingMode().name();
        this.quality = renderer.quality().name();
        this.latestRenderer = renderer;
        this.maximumTlasInstances = Math.max(
                this.maximumTlasInstances, scene.tlasInstanceCount());
        this.maximumUniqueTriangles = Math.max(
                this.maximumUniqueTriangles, scene.uniqueBlasTriangleCount());
        this.maximumInstancedTriangles = Math.max(
                this.maximumInstancedTriangles, scene.instancedTriangleCount());
        this.maximumAreaLights = Math.max(
                this.maximumAreaLights, scene.areaLightEmitterCount());
        this.maximumLightTreeNodes = Math.max(
                this.maximumLightTreeNodes, scene.topLevelLightTreeNodeCount());
        if (this.hasPreviousCamera) {
            double dx = camera.renderX() - this.previousX;
            double dy = camera.renderY() - this.previousY;
            double dz = camera.renderZ() - this.previousZ;
            this.maximumCameraStep = Math.max(
                    this.maximumCameraStep, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        this.previousX = camera.renderX();
        this.previousY = camera.renderY();
        this.previousZ = camera.renderZ();
        this.hasPreviousCamera = true;
        this.dirty = true;
        return this.frameCount >= this.nextWriteFrame;
    }

    void finish(VulkanContext context) {
        if (this.output != null && this.dirty) {
            this.captureAndWrite(context);
        }
    }

    private void captureAndWrite(VulkanContext context) {
        try {
            this.memory = mergeMemory(this.memory, context.memorySnapshot());
            writeAtomically(this.output, this.json());
            this.dirty = false;
        } catch (IOException | RuntimeException failure) {
            this.warnOnce(failure);
        }
    }

    String json() {
        StringBuilder json = new StringBuilder(4_096);
        json.append("{\n  \"schema\": \"prime-renderer-measurement-v1\",");
        field(json, "device", this.deviceName);
        field(json, "frames", this.frameCount);
        field(json, "reconstruction", this.reconstruction);
        field(json, "quality", this.quality);
        field(json, "maximumCameraStepBlocks", this.maximumCameraStep);
        field(json, "textureGenerationCount", this.textureGenerationCount);
        json.append("\n  \"extent\": {");
        field(json, "maximumRenderWidth", this.maximumRenderWidth);
        field(json, "maximumRenderHeight", this.maximumRenderHeight);
        field(json, "maximumDisplayWidth", this.maximumDisplayWidth);
        field(json, "maximumDisplayHeight", this.maximumDisplayHeight);
        trimComma(json).append("\n  },");
        json.append("\n  \"scene\": {");
        field(json, "maximumTlasInstances", this.maximumTlasInstances);
        field(json, "maximumUniqueBlasTriangles", this.maximumUniqueTriangles);
        field(json, "maximumInstancedTriangles", this.maximumInstancedTriangles);
        field(json, "maximumAreaLightEmitters", this.maximumAreaLights);
        field(json, "maximumTopLevelLightTreeNodes", this.maximumLightTreeNodes);
        trimComma(json).append("\n  },");
        appendMediumIds(json, this.mediumIds);
        appendTextures(json, this.textures);
        appendRanges(json, this.latestRanges());
        appendMemory(json, this.memory);
        trimComma(json).append("\n}\n");
        return json.toString();
    }

    private static void appendMediumIds(
            StringBuilder json, TerrainScene.MediumIdStatistics value) {
        json.append("\n  \"mediumIds\": ");
        if (value == null) {
            json.append("null,");
            return;
        }
        json.append('{');
        field(json, "assignedCount", value.assignedCount());
        field(json, "highWaterId", value.highWaterId());
        trimComma(json).append("\n  },");
    }

    private static void appendTextures(
            StringBuilder json, MaterialTexturePages.MeasurementSnapshot value) {
        json.append("\n  \"textures\": ");
        if (value == null) {
            json.append("null,");
            return;
        }
        json.append('{');
        field(json, "sourceGeneration", value.sourceGeneration());
        field(json, "atlasWidth", value.atlasWidth());
        field(json, "atlasHeight", value.atlasHeight());
        field(json, "mipLevels", value.mipLevels());
        field(json, "textureCount", value.textureCount());
        field(json, "maximumTextureId", value.maximumTextureId());
        field(json, "unusedTextureIdsBelowHighWater", value.unusedTextureIdsBelowHighWater());
        field(json, "animatedSpriteCount", value.animatedSpriteCount());
        field(json, "maximumContentWidth", value.maximumContentWidth());
        field(json, "maximumContentHeight", value.maximumContentHeight());
        field(json, "maximumPadding", value.maximumPadding());
        field(json, "baseAtlasRgba8Bytes", value.baseAtlasRgba8Bytes());
        field(json, "textureRecordBytes", value.textureRecordBytes());
        field(json, "animationFrameBytes", value.animationFrameBytes());
        appendChannel(json, "normal", value.normal());
        appendChannel(json, "optical", value.optical());
        trimComma(json).append("\n  },");
    }

    private static void appendChannel(
            StringBuilder json, String name, MaterialTexturePages.ChannelMeasurement value) {
        json.append("\n    ").append(quote(name)).append(": {");
        field(json, "sourceCount", value.sourceCount());
        field(json, "missingCount", value.missingCount());
        field(json, "animatedSourceCount", value.animatedSourceCount());
        field(json, "sourceTexels", value.sourceTexels());
        field(json, "maximumFrameCount", value.maximumFrameCount());
        field(json, "pageCount", value.pageCount());
        field(json, "pageBytes", value.pageBytes());
        field(json, "pageBaseTexels", value.pageBaseTexels());
        field(json, "occupiedBaseTexels", value.occupiedBaseTexels());
        field(json, "maximumPageWidth", value.maximumPageWidth());
        field(json, "maximumPageHeight", value.maximumPageHeight());
        field(json, "maximumPackedX", value.maximumPackedX());
        field(json, "maximumPackedY", value.maximumPackedY());
        appendRange(json, "alpha", value.alpha());
        appendRange(json, "red", value.red());
        appendRange(json, "green", value.green());
        appendRange(json, "blue", value.blue());
        trimComma(json).append("\n    },");
    }

    private static void appendRange(
            StringBuilder json, String name, MaterialTexturePages.ByteRange value) {
        json.append("\n      ").append(quote(name)).append(": {");
        field(json, "minimum", value.minimum());
        field(json, "maximum", value.maximum());
        field(json, "distinctCount", value.distinctCount());
        trimComma(json).append("\n      },");
    }

    private static void appendMemory(StringBuilder json, VulkanMemorySnapshot value) {
        json.append("\n  \"maximumSampledMemory\": ");
        if (value == null) {
            json.append("null,");
            return;
        }
        json.append('{');
        field(json, "blockCount", value.blockCount());
        field(json, "allocationCount", value.allocationCount());
        field(json, "blockBytes", value.blockBytes());
        field(json, "allocationBytes", value.allocationBytes());
        json.append("\n    \"heaps\": [");
        for (VulkanMemorySnapshot.Heap heap : value.heaps()) {
            json.append("\n      {");
            field(json, "index", heap.index());
            field(json, "allocatorBlockBytes", heap.allocatorBlockBytes());
            field(json, "allocatorAllocationBytes", heap.allocatorAllocationBytes());
            field(json, "estimatedUsageBytes", heap.estimatedUsageBytes());
            field(json, "estimatedBudgetBytes", heap.estimatedBudgetBytes());
            trimComma(json).append("\n      },");
        }
        if (!value.heaps().isEmpty()) {
            trimComma(json);
        }
        json.append("\n    ],");
        trimComma(json).append("\n  },");
    }

    private RendererDataRangeDiagnostics.Snapshot latestRanges() {
        // The latest immutable aggregate belongs to the realtime renderer snapshot already
        // consumed on the render thread; no image readback or synchronization happens here.
        return this.latestRenderer == null ? null : this.latestRenderer.ranges();
    }

    private static void appendRanges(
            StringBuilder json, RendererDataRangeDiagnostics.Snapshot value) {
        json.append("\n  \"gpuRanges\": ");
        if (value == null) {
            json.append("null,");
            return;
        }
        json.append('{');
        field(json, "captureCount", value.captureCount());
        field(json, "resetCaptureCount", value.resetCaptureCount());
        field(json, "sampledPixels", value.sampledPixels());
        field(json, "motionFiniteCount", value.motionFiniteCount());
        field(json, "motionNonfiniteCount", value.motionNonfiniteCount());
        field(json, "motionOutsideUnitCount", value.motionOutsideUnitCount());
        field(json, "motionNonzeroCount", value.motionNonzeroCount());
        field(json, "maximumAbsoluteMotionUvX", value.maximumAbsoluteMotionUvX());
        field(json, "maximumAbsoluteMotionUvY", value.maximumAbsoluteMotionUvY());
        field(json, "maximumMotionPixels", value.maximumMotionPixels());
        field(json, "motionPixelsP50Upper", value.motionPixelsPercentile(0.50));
        field(json, "motionPixelsP95Upper", value.motionPixelsPercentile(0.95));
        field(json, "motionPixelsP99Upper", value.motionPixelsPercentile(0.99));
        field(json, "depthSurfaceCount", value.depthSurfaceCount());
        field(json, "depthInvalidCount", value.depthInvalidCount());
        field(json, "depthSkyCount", value.depthSkyCount());
        field(json, "minimumSurfaceViewZ",
                value.depthSurfaceCount() == 0L ? 0.0 : value.minimumSurfaceViewZ());
        field(json, "maximumSurfaceViewZ", value.maximumSurfaceViewZ());
        field(json, "surfaceViewZP50Upper", value.surfaceViewZPercentile(0.50));
        field(json, "surfaceViewZP95Upper", value.surfaceViewZPercentile(0.95));
        field(json, "surfaceViewZP99Upper", value.surfaceViewZPercentile(0.99));
        trimComma(json).append("\n  },");
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append("\n    ").append(quote(name)).append(": ")
                .append(quote(value)).append(',');
    }

    private static void field(StringBuilder json, String name, long value) {
        json.append("\n    ").append(quote(name)).append(": ")
                .append(value).append(',');
    }

    private static void field(StringBuilder json, String name, double value) {
        json.append("\n    ").append(quote(name)).append(": ")
                .append(String.format(Locale.ROOT, "%.9g", value)).append(',');
    }

    private static VulkanMemorySnapshot mergeMemory(
            VulkanMemorySnapshot previous, VulkanMemorySnapshot sample) {
        if (previous == null) {
            return sample;
        }
        HashMap<Integer, VulkanMemorySnapshot.Heap> heaps = new HashMap<>();
        for (VulkanMemorySnapshot.Heap heap : previous.heaps()) {
            heaps.put(heap.index(), heap);
        }
        for (VulkanMemorySnapshot.Heap heap : sample.heaps()) {
            VulkanMemorySnapshot.Heap old = heaps.get(heap.index());
            heaps.put(
                    heap.index(),
                    old == null
                            ? heap
                            : new VulkanMemorySnapshot.Heap(
                                    heap.index(),
                                    Math.max(old.allocatorBlockBytes(), heap.allocatorBlockBytes()),
                                    Math.max(
                                            old.allocatorAllocationBytes(),
                                            heap.allocatorAllocationBytes()),
                                    Math.max(old.estimatedUsageBytes(), heap.estimatedUsageBytes()),
                                    Math.max(old.estimatedBudgetBytes(), heap.estimatedBudgetBytes())));
        }
        ArrayList<VulkanMemorySnapshot.Heap> ordered = new ArrayList<>(heaps.values());
        ordered.sort(java.util.Comparator.comparingInt(VulkanMemorySnapshot.Heap::index));
        return new VulkanMemorySnapshot(
                Math.max(previous.blockCount(), sample.blockCount()),
                Math.max(previous.allocationCount(), sample.allocationCount()),
                Math.max(previous.blockBytes(), sample.blockBytes()),
                Math.max(previous.allocationBytes(), sample.allocationBytes()),
                ordered);
    }

    private static StringBuilder trimComma(StringBuilder value) {
        if (!value.isEmpty() && value.charAt(value.length() - 1) == ',') {
            value.setLength(value.length() - 1);
        }
        return value;
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void writeAtomically(Path output, String contents) throws IOException {
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".pending-", ".json");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void warnOnce(Throwable failure) {
        if (!this.warned) {
            this.warned = true;
            PrimeInfo.LOGGER.warn(
                    "Unable to export Prime renderer-data measurements; rendering continues",
                    failure);
        }
    }
}
