package dev.primept.capture;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
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
    private static final IdentityHashMap<StagedVertexBuffer.Draw, Material> DRAWS = new IdentityHashMap<>();
    private static DynamicFrame frame;
    private static long epoch, sequence;
    private static boolean active;
    private static RuntimeException failure;
    private static int modelMeshes, particleMeshes;
    private static long reportedFrames, captureNanos;
    private static final boolean PROFILE = Boolean.getBoolean("primept.profile");
    private static boolean sourceGroup = true;
    public record Stats(int models, int particles, int spans, int vertices, int bytes, int capacity, int growthCount, long captureNanos) { }
    public static Stats stats() {
        return new Stats(modelMeshes, particleMeshes, frame == null ? 0 : frame.spanCount(),
                frame == null ? 0 : frame.vertexCount(), frame == null ? 0 : frame.byteSize(),
                frame == null ? 0 : frame.capacity(), frame == null ? 0 : frame.growthCount(), captureNanos);
    }
    public static boolean sourceGroup(boolean enabled) { boolean previous = sourceGroup; sourceGroup = enabled; return previous; }
    private record Material(int texture, int flags, boolean particle) { }
    private DynamicCapture() { }

    public static void begin(CameraRenderState camera) {
        active = false;
        if (!PrimeClient.captureEnabled()) return;
        if (frame == null) frame = new DynamicFrame();
        epoch = PrimeClient.CAPTURE.epoch();
        frame.begin(epoch, ++sequence, camera.pos.x, camera.pos.y, camera.pos.z);
        DRAWS.clear();
        DynamicTextures.beginFrame();
        failure = null;
        modelMeshes = particleMeshes = 0;
        captureNanos = 0;
        sourceGroup = true;
        active = true;
    }
    public static void end() { active = false; }
    public static boolean active() { return active && failure == null; }

    public static void register(StagedVertexBuffer.Draw draw, RenderType type, PreparedRenderType prepared) {
        if (!active() || !sourceGroup || type.isOutline() || DRAWS.containsKey(draw)) return;
        String pipeline = type.pipeline().getLocation().getPath();
        // Screen-space overlays, line geometry and special shader effects are not surface materials.
        if (pipeline.contains("glint") || pipeline.contains("shadow") || pipeline.contains("text")
                || pipeline.contains("outline") || pipeline.contains("water_mask")) return;
        long captureStart = PROFILE ? System.nanoTime() : 0;
        try {
            int texture = 0;
            for (var binding : prepared.textures()) {
                if (binding.name().equals("Sampler0")) { texture = DynamicTextures.use(binding.textureView().texture()); break; }
            }
            int flags = type.hasBlending() ? 2 : type.pipeline().getShaderDefines().values().containsKey("ALPHA_CUTOUT") ? 1 : 0;
            DRAWS.put(draw, new Material(texture, flags, false));
        } catch (RuntimeException exception) { failure = exception; }
        finally { if (PROFILE) captureNanos += System.nanoTime() - captureStart; }
    }

    public static void particle(StagedVertexBuffer.Draw draw, SingleQuadParticle.Layer layer) {
        if (!active()) return;
        long captureStart = PROFILE ? System.nanoTime() : 0;
        try {
            var texture = Minecraft.getInstance().getTextureManager().getTexture(layer.textureAtlasLocation()).getTexture();
            DRAWS.put(draw, new Material(DynamicTextures.use(texture), layer.translucent() ? 2 : 1, true));
        } catch (RuntimeException exception) { failure = exception; }
        finally { if (PROFILE) captureNanos += System.nanoTime() - captureStart; }
    }

    public static void mesh(StagedVertexBuffer.Draw draw, MeshData data) {
        if (!active()) return;
        Material material = DRAWS.get(draw);
        if (material == null) return;
        long captureStart = PROFILE ? System.nanoTime() : 0;
        try {
            if (!appendMesh(frame, material.texture, material.flags, data)) return;
            if (material.particle) ++particleMeshes; else ++modelMeshes;
        } catch (RuntimeException exception) { failure = exception; }
        finally { if (PROFILE) captureNanos += System.nanoTime() - captureStart; }
    }

    static boolean appendMesh(DynamicFrame destination, int texture, int flags, MeshData data) {
        var state = data.drawState();
        int topology = switch (state.primitiveTopology()) { case QUADS -> 4; case TRIANGLES -> 3; default -> 0; };
        if (topology == 0) return false;
        var format = state.format();
        if (!format.contains("Position") || !format.contains("Color") || !format.contains("UV0")
                || format.getElement("Position").format() != GpuFormat.RGB32_FLOAT
                || format.getElement("Color").format() != GpuFormat.RGBA8_UNORM
                || format.getElement("UV0").format() != GpuFormat.RG32_FLOAT)
            throw new IllegalArgumentException("Unsupported dynamic source layout " + format);
        destination.append(texture, flags, topology, state.vertexCount(), format.getVertexSize(),
                format.getElement("Position").offset(), format.getElement("Color").offset(),
                format.getElement("UV0").offset(), data.vertexBuffer());
        return true;
    }
    public static void submit(long expectedEpoch, NativeBridge bridge) {
        DynamicTextures.throwIfFailed();
        if (failure != null) throw failure;
        if (frame == null || epoch != expectedEpoch) return;
        DynamicTextures.submit(epoch, bridge);
        bridge.submit(frame.seal());
        if (++reportedFrames == 1 || Boolean.getBoolean("primept.capture.audit") && reportedFrames % 120 == 0)
            PrimeClient.LOGGER.info("Prime PT dynamic capture: models={} particles={} spans={} vertices={} bytes={} retainedCapacity={} growths={}",
                    modelMeshes, particleMeshes, frame.spanCount(), frame.vertexCount(), frame.byteSize(), frame.capacity(), frame.growthCount());
    }

    public static void close() {
        active = false;
        DRAWS.clear();
        if (frame != null) { frame.close(); frame = null; }
        failure = null;
        reportedFrames = 0;
    }
}
