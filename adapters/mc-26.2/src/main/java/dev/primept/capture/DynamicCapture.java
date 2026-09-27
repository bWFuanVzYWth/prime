package dev.primept.capture;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import com.mojang.blaze3d.GpuFormat;
import dev.primept.NativeBridge;
import dev.primept.PrimeClient;
import java.util.IdentityHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** One owned native packet per actual world preparation, before Minecraft frees staged mesh bytes. */
public final class DynamicCapture {
    private static final IdentityHashMap<StagedVertexBuffer.Draw, Material> DRAWS =
            new IdentityHashMap<>();
    private static final IdentityHashMap<BufferBuilder, StagedVertexBuffer.Draw> BUILDERS =
            new IdentityHashMap<>();
    private static final IdentityHashMap<StagedVertexBuffer.Draw, ExcludedRanges> EXCLUDED =
            new IdentityHashMap<>();
    private static boolean sentRaw, previousRawNonempty;
    private static DynamicFrame frame;
    private static long epoch, sequence;
    private static boolean active;
    private static RuntimeException failure;
    private static int modelMeshes, particleMeshes, modelRawVertices, particleRawVertices;
    private static long reportedFrames, captureNanos;
    private static final boolean PROFILE = Boolean.getBoolean("primept.profile");
    private static boolean sourceGroup = true;
    public record Stats(int models, int particles, int spans, int vertices, int bytes, int capacity,
                        int growthCount, long captureNanos, int modelRawVertices,
                        int particleRawVertices) {}
    public static Stats stats() {
        return new Stats(modelMeshes, particleMeshes, frame == null ? 0 : frame.spanCount(),
                         frame == null ? 0 : frame.vertexCount(),
                         frame == null ? 0 : frame.byteSize(), frame == null ? 0 : frame.capacity(),
                         frame == null ? 0 : frame.growthCount(), captureNanos, modelRawVertices,
                         particleRawVertices);
    }
    public static boolean sourceGroup(boolean enabled) {
        boolean previous = sourceGroup;
        sourceGroup = enabled;
        return previous;
    }
    public record Material(int texture, int flags, boolean particle, boolean quads) {}
    private DynamicCapture() {}

    public static void begin(CameraRenderState camera) {
        active = false;
        if (!PrimeClient.captureEnabled())
            return;
        if (frame == null)
            frame = new DynamicFrame();
        long nextEpoch = PrimeClient.CAPTURE.epoch();
        if (epoch != nextEpoch)
            sentRaw = previousRawNonempty = false;
        epoch = nextEpoch;
        ModelCapture.begin(epoch);
        frame.begin(epoch, ++sequence, camera.pos.x, camera.pos.y, camera.pos.z);
        DRAWS.clear();
        BUILDERS.clear();
        EXCLUDED.clear();
        DynamicTextures.beginFrame();
        failure = null;
        modelMeshes = particleMeshes = modelRawVertices = particleRawVertices = 0;
        captureNanos = 0;
        sourceGroup = true;
        active = true;
    }
    public static void end() {
        ModelCapture.end();
        active = false;
    }
    public static boolean active() {
        return active && failure == null;
    }

    public static void register(StagedVertexBuffer.Draw draw, RenderType type,
                                PreparedRenderType prepared) {
        if (!active() || !sourceGroup || type.isOutline() || DRAWS.containsKey(draw))
            return;
        String pipeline = type.pipeline().getLocation().getPath();
        // Screen-space overlays, line geometry and special shader effects are not surface materials.
        if (pipeline.contains("glint") || pipeline.contains("shadow") ||
            pipeline.contains("text") || pipeline.contains("outline") ||
            pipeline.contains("water_mask"))
            return;
        long captureStart = PROFILE ? System.nanoTime() : 0;
        try {
            int texture = 0;
            for (var binding : prepared.textures()) {
                if (binding.name().equals("Sampler0")) {
                    texture = DynamicTextures.use(binding.textureView().texture());
                    break;
                }
            }
            int flags = type.hasBlending() ? 2
                        : type.pipeline().getShaderDefines().values().containsKey("ALPHA_CUTOUT")
                                ? 1
                                : 0;
            DRAWS.put(draw, new Material(texture, flags, false,
                                         type.primitiveTopology().name().equals("QUADS")));
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            if (PROFILE)
                captureNanos += System.nanoTime() - captureStart;
        }
    }

    public static void particle(StagedVertexBuffer.Draw draw, SingleQuadParticle.Layer layer) {
        if (!active())
            return;
        long captureStart = PROFILE ? System.nanoTime() : 0;
        try {
            var texture = Minecraft.getInstance()
                                  .getTextureManager()
                                  .getTexture(layer.textureAtlasLocation())
                                  .getTexture();
            DRAWS.put(draw, new Material(DynamicTextures.use(texture), layer.translucent() ? 2 : 1,
                                         true, true));
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            if (PROFILE)
                captureNanos += System.nanoTime() - captureStart;
        }
    }

    public static boolean
    particles(net.minecraft.client.renderer.state.level.QuadParticleRenderState state,
              SingleQuadParticle.Layer layer, VertexConsumer consumer) {
        if (!active())
            return false;
        if (!(consumer instanceof BufferBuilder builder))
            throw new IllegalStateException("Particle routing requires a bound source material");
        Material material = material(builder);
        if (material == null || !material.particle())
            throw new IllegalStateException("Missing particle source material");
        int before = frame.vertexCount();
        frame.beginParticles(material.texture(), material.flags());
        ((ParticleSource)state).primept$route(layer, frame::particle);
        frame.endSpan();
        if (frame.vertexCount() != before)
            ++particleMeshes;
        return true;
    }

    public static void mesh(StagedVertexBuffer.Draw draw, MeshData data) {
        if (!active())
            return;
        Material material = DRAWS.get(draw);
        if (material == null)
            return;
        long captureStart = PROFILE ? System.nanoTime() : 0;
        try {
            int before = frame.vertexCount();
            if (!appendMesh(frame, material.texture, material.flags, data, EXCLUDED.remove(draw)))
                return;
            int added = frame.vertexCount() - before;
            if (material.particle) {
                ++particleMeshes;
                particleRawVertices += added;
            } else {
                ++modelMeshes;
                modelRawVertices += added;
            }
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            if (PROFILE)
                captureNanos += System.nanoTime() - captureStart;
        }
    }

    static boolean appendMesh(DynamicFrame destination, int texture, int flags, MeshData data) {
        return appendMesh(destination, texture, flags, data, null);
    }
    static boolean appendMesh(DynamicFrame destination, int texture, int flags, MeshData data,
                              ExcludedRanges excluded) {
        var state = data.drawState();
        int topology = switch (state.primitiveTopology()) {
            case QUADS -> 4;
            case TRIANGLES -> 3;
            default -> 0;
        };
        if (topology == 0)
            return false;
        var format = state.format();
        if (!format.contains("Position") || !format.contains("Color") || !format.contains("UV0") ||
            format.getElement("Position").format() != GpuFormat.RGB32_FLOAT ||
            format.getElement("Color").format() != GpuFormat.RGBA8_UNORM ||
            format.getElement("UV0").format() != GpuFormat.RG32_FLOAT)
            throw new IllegalArgumentException("Unsupported dynamic source layout " + format);
        int cursor = 0;
        if (excluded != null)
            for (int i = 0; i < excluded.size; i += 2) {
                int start = excluded.ranges[i], end = excluded.ranges[i + 1];
                if (start < cursor || end > state.vertexCount())
                    throw new IllegalStateException("Invalid model exclusion range");
                if (start != cursor)
                    appendRange(destination, texture, flags, topology, data, cursor, start);
                cursor = end;
            }
        if (cursor < state.vertexCount())
            appendRange(destination, texture, flags, topology, data, cursor, state.vertexCount());
        return true;
    }
    private static void appendRange(DynamicFrame destination, int texture, int flags, int topology,
                                    MeshData data, int start, int end) {
        var format = data.drawState().format();
        int stride = format.getVertexSize();
        ByteBuffer source = data.vertexBuffer().slice(start * stride, (end - start) * stride);
        destination.append(texture, flags, topology, end - start, stride,
                           format.getElement("Position").offset(),
                           format.getElement("Color").offset(), format.getElement("UV0").offset(),
                           source);
    }
    public static void fail(RuntimeException exception) {
        if (failure == null)
            failure = exception;
    }
    public static boolean healthy() {
        return failure == null;
    }
    public static void builder(StagedVertexBuffer.Draw draw, VertexConsumer consumer) {
        if (active() && consumer instanceof BufferBuilder buffer)
            BUILDERS.put(buffer, draw);
    }
    public static Material material(BufferBuilder builder) {
        Material material = DRAWS.get(BUILDERS.get(builder));
        return material != null && material.quads ? material : null;
    }
    public static void exclude(BufferBuilder builder, int start, int end) {
        var draw = BUILDERS.get(builder);
        if (draw == null)
            throw new IllegalStateException("Unbound model buffer");
        EXCLUDED.computeIfAbsent(draw, ignored -> new ExcludedRanges()).add(start, end);
    }
    static final class ExcludedRanges {
        int[] ranges = new int[16];
        int size;
        void add(int start, int end) {
            if (start % 4 != 0 || end <= start || end % 4 != 0)
                throw new IllegalArgumentException("Non-quad model range");
            if (size > 0 && ranges[size - 1] == start) {
                ranges[size - 1] = end;
                return;
            }
            if (size + 2 > ranges.length)
                ranges = java.util.Arrays.copyOf(ranges, ranges.length * 2);
            ranges[size++] = start;
            ranges[size++] = end;
        }
    }
    public static void submit(long expectedEpoch, NativeBridge bridge) {
        DynamicTextures.throwIfFailed();
        if (failure != null)
            throw failure;
        if (frame == null || epoch != expectedEpoch)
            return;
        DynamicTextures.submit(epoch, bridge);
        ModelCapture.submit(bridge);
        boolean rawNonempty = frame.vertexCount() != 0;
        if (!sentRaw || rawNonempty || previousRawNonempty) {
            bridge.submit(frame.seal());
            sentRaw = true;
            previousRawNonempty = rawNonempty;
        }
        if (++reportedFrames == 1 ||
            Boolean.getBoolean("primept.capture.audit") && reportedFrames % 120 == 0)
            PrimeClient.LOGGER.info(
                    "Prime PT dynamic capture: models={} particles={} spans={} vertices={} bytes={} retainedCapacity={} growths={}",
                    modelMeshes, particleMeshes, frame.spanCount(), frame.vertexCount(),
                    frame.byteSize(), frame.capacity(), frame.growthCount());
    }

    public static void close() {
        active = false;
        ModelCapture.close();
        sentRaw = previousRawNonempty = false;
        DRAWS.clear();
        BUILDERS.clear();
        EXCLUDED.clear();
        if (frame != null) {
            frame.close();
            frame = null;
        }
        failure = null;
        reportedFrames = 0;
    }
}
