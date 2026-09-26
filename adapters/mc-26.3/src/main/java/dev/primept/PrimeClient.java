package dev.primept;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.primept.capture.CaptureInbox;
import dev.primept.capture.Packets;
import dev.primept.capture.DynamicCapture;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrimeClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("PrimePT");
    public static final CaptureInbox CAPTURE = new CaptureInbox();
    private static final PrimeClient INSTANCE = new PrimeClient();
    private final boolean enabled = Boolean.getBoolean("primept.enabled");
    private final RenderProfile profile = Boolean.getBoolean("primept.profile") ? new RenderProfile() : null;
    private long worldRenderStart;
    private HostVulkanRenderer renderer;
    private long sentEpoch, sentAtlas;
    private int sample;
    private int framesUntilPrune;
    private int submittedSections;
    private boolean reportedFrame;
    private boolean reportedProjectionWait;
    private boolean failed;
    private boolean skippedWorldRaster;

    @Override public void onInitializeClient() {
        LOGGER.info("Prime PT 26.3: Java thin capture → FFM → Rust → Vulkan/Slang; enabled={}", enabled);
        if (enabled) LOGGER.warn("Experimental PT: terrain/fluid source capture and batched entity/block-entity/particle meshes; transparency uses alpha coverage, no refraction/media; animated atlases use their first frame; special shader effects remain unsupported");
    }

    public static void render(CameraRenderState camera, RenderTarget destination) {
        INSTANCE.renderFrame(camera, destination);
    }

    public static boolean captureEnabled() { return INSTANCE.enabled && !INSTANCE.failed; }

    public static void beginWorldRender() {
        INSTANCE.skippedWorldRaster = false;
        if (INSTANCE.profile != null) INSTANCE.worldRenderStart = System.nanoTime();
    }

    public static boolean skipWorldRaster() {
        boolean ready = INSTANCE.enabled && !INSTANCE.failed && INSTANCE.reportedFrame
                && INSTANCE.renderer != null && INSTANCE.renderer.hasCompletedWorldFrame()
                && INSTANCE.sentEpoch == CAPTURE.epoch() && CAPTURE.failure() == null && DynamicCapture.healthy();
        INSTANCE.skippedWorldRaster = ready;
        return ready;
    }

    static boolean skippedWorldRaster() { return INSTANCE.skippedWorldRaster; }

    private void renderFrame(CameraRenderState camera, RenderTarget destination) {
        if (!enabled || failed) return;
        try {
            RuntimeException captureFailure = CAPTURE.failure();
            if (captureFailure != null) throw captureFailure;
            if (!camera.initialized || destination.width <= 0 || destination.height <= 0) return;
            float fov = (float) (2 * Math.atan(1.0 / Math.abs(camera.projectionMatrix.m11())));
            // initialized describes the camera entity; its perspective matrix can still be zero during world entry.
            if (!Float.isFinite(fov) || fov < 0.01f || fov >= 3.0f) {
                if (!reportedProjectionWait) LOGGER.info("Waiting for Minecraft's perspective camera before native rendering");
                reportedProjectionWait = true;
                return;
            }
            var atlas = CAPTURE.atlas();
            if (atlas == null) return;
            RenderProfile.Frame timing = profile == null ? null : profile.begin(worldRenderStart);
            if (renderer == null) {
                renderer = new HostVulkanRenderer();
                LOGGER.info("Rust renderer attached to Minecraft's Vulkan device; direct storage-image output, no CPU readback");
            }
            long epoch = CAPTURE.epoch();
            if (sentEpoch != epoch) {
                submit(Packets.reset(epoch), timing);
                sentEpoch = epoch;
                sentAtlas = 0;
                sample = 0;
                framesUntilPrune = 0;
                submittedSections = 0;
                reportedFrame = false;
                renderer.resetReadiness();
            }
            if (sentAtlas != atlas.version()) {
                submit(Packets.texture(epoch, atlas.width(), atlas.height(), atlas.rgba()), timing);
                sentAtlas = atlas.version();
                sample = 0;
            }
            // A section replacement is drained atomically relative to rendering.
            long drained = 0;
            long phaseStart = timing == null ? 0 : System.nanoTime();
            CaptureInbox.Batch batch;
            while (drained < (16L << 20) && (batch = CAPTURE.poll()) != null) {
                if (batch.epoch() != epoch) continue;
                for (byte[] packet : batch.packets()) submit(packet, timing);
                if (batch.packets().size() > 1) ++submittedSections;
                drained += batch.bytes();
                if (timing != null) ++timing.batches;
                sample = 0;
            }
            if (timing != null) timing.drain = System.nanoTime() - phaseStart;
            phaseStart = timing == null ? 0 : System.nanoTime();
            // Scene changes reset accumulation samples, but must not restart the housekeeping cadence.
            if (framesUntilPrune-- <= 0) {
                pruneUnloadedSections();
                framesUntilPrune = 119;
            }
            if (timing != null) timing.prune = System.nanoTime() - phaseStart;
            int width = destination.width, height = destination.height;
            phaseStart = timing == null ? 0 : System.nanoTime();
            renderer.submitDynamic(epoch);
            if (timing != null) timing.dynamicSubmit = System.nanoTime() - phaseStart;
            var inverse = new Matrix4f(camera.viewRotationMatrix).invert();
            var forward = inverse.transformDirection(new Vector3f(0, 0, -1)).normalize();
            var right = inverse.transformDirection(new Vector3f(1, 0, 0)).normalize();
            var up = inverse.transformDirection(new Vector3f(0, 1, 0)).normalize();
            Packets.writeFrame(renderer.frameBuffer(), epoch, camera.pos.x, camera.pos.y, camera.pos.z,
                    components(forward), components(right), components(up), fov, width, height, sample++);
            phaseStart = timing == null ? 0 : System.nanoTime();
            renderer.record(destination);
            if (timing != null) timing.nativeRender = System.nanoTime() - phaseStart;
            if (!reportedFrame) {
                LOGGER.info("Prime PT first world frame recorded: {}x{}, {} captured section batches, resource epoch {}",
                        width, height, submittedSections, epoch);
                reportedFrame = true;
                renderer.enableWorldReplacementAfterCompletion();
            }
            if (timing != null) profile.finish(timing, CAPTURE, width, height, renderer);
        } catch (Exception | LinkageError exception) {
            failed = true;
            CAPTURE.disable();
            LOGGER.error(skippedWorldRaster
                    ? "Prime PT disabled after world raster was skipped; this frame may be incomplete, full vanilla rendering resumes next frame"
                    : "Prime PT disabled; full vanilla rendering resumes next frame", exception);
            closeResources();
        }
    }

    private void submit(byte[] packet, RenderProfile.Frame timing) {
        long start = timing == null ? 0 : System.nanoTime();
        renderer.submit(packet);
        if (timing != null) {
            timing.submit += System.nanoTime() - start;
            ++timing.packets;
            timing.bytes += packet.length;
        }
    }

    private static float[] components(Vector3f vector) { return new float[] { vector.x, vector.y, vector.z }; }

    private void pruneUnloadedSections() {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        for (long section : CAPTURE.sections()) {
            int x = SectionPos.x(section), z = SectionPos.z(section);
            if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) CAPTURE.dropChunk(x, z);
        }
    }

    public static void resetWorld() {
        CAPTURE.reset();
        INSTANCE.closeResources();
        INSTANCE.sentEpoch = 0;
        INSTANCE.sentAtlas = 0;
        INSTANCE.worldRenderStart = 0;
        INSTANCE.framesUntilPrune = 0;
        INSTANCE.reportedProjectionWait = false;
        if (INSTANCE.profile != null) INSTANCE.profile.reset();
    }
    public static void close() {
        CAPTURE.disable(); INSTANCE.closeResources();
        if (INSTANCE.profile != null) INSTANCE.profile.closeSamples();
    }

    private void closeResources() {
        DynamicCapture.close();
        try { if (renderer != null) renderer.close(); }
        catch (RuntimeException exception) { LOGGER.error("Native renderer shutdown failed", exception); }
        finally { renderer = null; }
    }
}
