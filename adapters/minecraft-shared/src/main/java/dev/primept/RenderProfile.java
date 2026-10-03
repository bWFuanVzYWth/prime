package dev.primept;

import dev.primept.capture.DynamicCapture;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.BlockGeometryCache;
import dev.primept.capture.FabricMeshCapture;
import dev.primept.capture.ItemCapture;
import dev.primept.capture.ExclusiveTerrainCapture;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Coarse CPU clocks feed raw events. CSV exists only for the explicit legacy JVM property. */
final class RenderProfile {
    static final String CSV_HEADER =
            "sample,pt_interval_ns,mc_before_pt_ns,pt_hook_ns,width,height,native_submit_ns,drain_ns,prune_ns,dynamic_submit_ns,native_record_ns,capture_ns,refs_pose_ns,be_sources,entity_sources,model_submits,standard_leaves,fallback_leaves,refs_checks,geometry_vertices_read,prototype_upserts,instance_upserts,instance_removes,op7_bytes,raw_vertices,model_raw_vertices,particle_raw_vertices,raw_packet_bytes,section_batches,section_bytes,raster_skipped,geometry_hits,geometry_misses,geometry_null_keys,geometry_emits,geometry_emitted_bytes,geometry_avoided_copy_bytes,cube_vertices_skipped,mesh_submits,mesh_groups,mesh_geometry_vertices_read,mesh_vertices_skipped,mesh_fallbacks,gpu_last_ns,terrain_available_sources_total,item_submits,item_groups,item_checked_vertices,item_skipped_vertices,item_fallback_quads,item_created_vertices,item_shared_hits,terrain_dirty_events,terrain_entered_columns,terrain_loaded_columns,terrain_unloaded_columns,terrain_invalidations,terrain_requested_sources,terrain_available_sources,terrain_plan_ns,terrain_pack_ns,terrain_total_ns,terrain_missing_sources,terrain_light_engine_notifications,terrain_light_packet_notifications,extraction_ns,resource_submit_ns,terrain_accept_ns,terrain_source_bytes,terrain_tint_queries,terrain_tint_ns,offline\n";
    private final boolean detailed = Boolean.getBoolean("primept.profile");
    private final boolean csv = System.getProperty("primept.profile.csv") != null;
    private static final FabricMeshCapture.Stats NO_FABRIC =
            new FabricMeshCapture.Stats(0, 0, 0, 0, 0);
    private static final ItemCapture.Stats NO_ITEMS = new ItemCapture.Stats(0, 0, 0, 0, 0, 0, 0);
    private long lastStart, previousHook, extractionStart, extractionNanos, sampleIndex;
    private BufferedWriter samples;
    private boolean samplesOpened;
    private BlockGeometryCache.Stats cachePrevious = BlockGeometryCache.stats();
    void beginExtraction() {
        extractionStart = Diagnostics.clock();
        extractionNanos = 0;
    }
    void endExtraction() {
        extractionNanos = extractionStart == 0 ? 0 : Diagnostics.clock() - extractionStart;
    }
    private static final Frame NOOP = new Frame(0, 0, 0, 0);
    static final class Frame {
        final long start, interval, beforePT, extraction;
        long submit, resourceSubmit, drain, prune, nativeRender, dynamicSubmit;
        long batches, packets, bytes;
        Frame(long start, long interval, long beforePT, long extraction) {
            this.start = start;
            this.interval = interval;
            this.beforePT = beforePT;
            this.extraction = extraction;
        }
    }
    Frame begin(long worldRenderStart) {
        if (!Diagnostics.timingEnabled())
            return NOOP;
        long now = System.nanoTime();
        long interval = lastStart == 0 ? 0 : now - lastStart;
        lastStart = now;
        return new Frame(now, interval, worldRenderStart == 0 ? 0 : now - worldRenderStart,
                         extractionNanos);
    }
    void finish(Frame frame, int width, int height, HostVulkanRenderer renderer) {
        if (frame.start == 0)
            return;
        long duration = System.nanoTime() - frame.start;
        var terrain = ExclusiveTerrainCapture.takeStats();
        var timing = new FrameTimings(frame.interval, previousHook, frame.extraction,
                                      frame.beforePT, duration);
        previousHook = duration;
        if (!Diagnostics.capturing() && !csv)
            return;
        var raw = DynamicCapture.stats();
        var models = ModelCapture.stats();
        try (var event = Diagnostics.measuredSpan("frame", frame.start, duration)) {
            event.count("w", width);
            event.count("h", height);
            if (frame.interval > 0)
                event.count("interval", frame.interval);
            if (frame.extraction > 0)
                event.count("extract", frame.extraction);
            if (frame.beforePT > 0)
                event.count("before", frame.beforePT);
            if (timing.previousHook() > 0)
                event.count("prev_hook", timing.previousHook());
            event.count("slow", timing.slow() ? 1 : 0);
            event.count("offline", PrimeClient.offlineActive() ? 1 : 0);
            event.count("requested", terrain.requested());
            event.count("avail", terrain.available());
            event.count("missing", terrain.missing());
            event.count("dirty", terrain.dirty());
            event.count("loaded", terrain.loaded());
            event.count("unloaded", terrain.unloaded());
            event.count("entered", terrain.entered());
            event.count("invalidate", terrain.invalidations());
            event.count("light_engine", terrain.lightEngineEvents());
            event.count("light_packet", terrain.lightPacketEvents());
            event.count("src_bytes", terrain.sourceBytes());
            event.count("tints", terrain.tintQueries());
            if (!PrimeClient.offlineActive()) {
                event.count("be", models.blockEntities());
                event.count("entities", models.entities());
                event.count("submits", models.submissions());
                event.count("leaves", models.leaves());
                event.count("fallback", models.fallbackLeaves());
                event.count("refs", models.referenceChecks());
                event.count("verts_read", models.geometryVerticesRead());
                event.count("verts_skip", models.skippedVertices());
                event.count("raw_verts", raw.vertices());
                event.count("raw_bytes", raw.bytes());
                if (models.delta() != null) {
                    event.count("proto_up", models.delta().prototypeUpserts());
                    event.count("inst_up", models.delta().instanceUpserts());
                    event.count("inst_rm", models.delta().instanceRemoves());
                    event.count("inst_bytes", models.deltaBytes());
                }
            }
        }
        if (csv)
            writeSample(frame, duration, width, height, raw, models, terrain,
                        renderer.lastGpuTimeNanos());
    }
    /** PT hook cadence is a CPU frame interval, not a display-present or GPU-duration measurement. */
    private void writeSample(Frame f, long hook, int width, int height, DynamicCapture.Stats raw,
                             ModelCapture.Stats models, ExclusiveTerrainCapture.Stats terrain,
                             long gpuLast) {
        try {
            if (!samplesOpened) {
                samplesOpened = true;
                String target = System.getProperty("primept.profile.csv");
                if (target == null || target.isBlank())
                    return;
                Path path = Path.of(target).toAbsolutePath();
                if (path.getParent() != null)
                    Files.createDirectories(path.getParent());
                samples = Files.newBufferedWriter(path);
                samples.write(CSV_HEADER);
            }
            if (samples == null)
                return;
            boolean offline = PrimeClient.offlineActive();
            var delta = models.delta();
            var geometry = BlockGeometryCache.stats();
            var fabric = offline ? NO_FABRIC : FabricMeshCapture.stats();
            var items = offline ? NO_ITEMS : ItemCapture.stats();
            samples.write(
                    (++sampleIndex) + "," + f.interval + "," + f.beforePT + "," + hook + "," +
                    width + "," + height + "," + f.submit + "," + f.drain + "," + f.prune + "," +
                    f.dynamicSubmit + "," + f.nativeRender + "," +
                    (detailed ? raw.captureNanos() : -1) + "," +
                    (ModelCapture.leafTimingEnabled() ? models.referenceNanos() : -1) + "," +
                    models.blockEntities() + "," + models.entities() + "," + models.submissions() +
                    "," + models.leaves() + "," + models.fallbackLeaves() + "," +
                    models.referenceChecks() + "," + models.geometryVerticesRead() + "," +
                    (delta == null ? 0 : delta.prototypeUpserts()) + "," +
                    (delta == null ? 0 : delta.instanceUpserts()) + "," +
                    (delta == null ? 0 : delta.instanceRemoves()) + "," + models.deltaBytes() +
                    "," + raw.vertices() + "," + raw.modelRawVertices() + "," +
                    raw.particleRawVertices() + "," + raw.bytes() + "," + f.batches + "," +
                    f.bytes + "," + PrimeClient.skippedWorldRaster() + "," +
                    (geometry.hits() - cachePrevious.hits()) + "," +
                    (geometry.misses() - cachePrevious.misses()) + "," +
                    (geometry.nullKeys() - cachePrevious.nullKeys()) + "," +
                    (geometry.emits() - cachePrevious.emits()) + "," +
                    (geometry.emittedBytes() - cachePrevious.emittedBytes()) + "," +
                    (geometry.avoidedBytes() - cachePrevious.avoidedBytes()) + "," +
                    models.skippedVertices() + "," + fabric.submits() + "," + fabric.groups() +
                    "," + fabric.geometryVertices() + "," + fabric.skippedVertices() + "," +
                    fabric.fallbacks() + "," + gpuLast + "," +
                    dev.primept.capture.ExclusiveTerrainCapture.routedSections() + "," +
                    items.submits() + "," + items.groups() + "," + items.checkedVertices() + "," +
                    items.skippedVertices() + "," + items.fallbackQuads() + "," +
                    items.createdVertices() + "," + items.sharedHits() + "," + terrain.dirty() +
                    "," + terrain.entered() + "," + terrain.loaded() + "," + terrain.unloaded() +
                    "," + terrain.invalidations() + "," + terrain.requested() + "," +
                    terrain.available() + "," + terrain.planNanos() + "," + terrain.packNanos() +
                    "," + terrain.totalNanos() + "," + terrain.missing() + "," +
                    terrain.lightEngineEvents() + "," + terrain.lightPacketEvents() + "," +
                    f.extraction + "," + f.resourceSubmit + "," + terrain.acceptNanos() + "," +
                    terrain.sourceBytes() + "," + terrain.tintQueries() + "," +
                    terrain.tintNanos() + "," + offline + "\n");
            cachePrevious = geometry;
            if (sampleIndex % 120 == 0)
                samples.flush();
        } catch (IOException failure) {
            PrimeClient.LOGGER.warn("Could not write optional per-frame CPU profile", failure);
            closeSamples();
        }
    }
    void closeSamples() {
        if (samples != null) {
            try {
                samples.close();
            } catch (IOException failure) {
                PrimeClient.LOGGER.warn("Could not close CPU profile", failure);
            }
            samples = null;
        }
    }

    void reset() {
        lastStart = previousHook = 0;
    }
}
