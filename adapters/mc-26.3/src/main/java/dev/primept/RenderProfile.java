package dev.primept;

import dev.primept.capture.CaptureInbox;
import dev.primept.capture.DynamicCapture;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.BlockGeometryCache;
import dev.primept.capture.FabricMeshCapture;
import dev.primept.capture.ItemCapture;
import java.util.Arrays;
import java.util.Locale;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in CPU timings. Recording is asynchronous; gpuLast is one completed GPU sample, not a window average. */
final class RenderProfile {
    private static final int WINDOW = 120;
    private final long[] hookTimes = new long[WINDOW];
    private long lastStart;
    private BufferedWriter samples;
    private boolean samplesOpened;
    private long sampleIndex;
    private int frames, intervals, rasterSkipped;
    private long interval, vanilla, submit, drain, prune, nativeRender, total;
    private long batches, packets, bytes;
    private long dynamicCapture, dynamicSubmit, dynamicSpans, dynamicVertices, dynamicBytes;
    private long modelMeshes, particleMeshes, modelRawVertices, particleRawVertices;
    private long beSources, entitySources, modelSubmits, instanceLeaves, fallbackLeaves,
            referenceChecks, geometryReads, referenceNanos;
    private long prototypes, instanceUpdates, instanceRemoves, instanceBytes;
    private long cubeSkipped, meshSubmits, meshGroups, meshRead, meshSkipped, meshFallback;
    private long itemSubmits, itemGroups, itemChecked, itemSkipped, itemFallback, itemCreated,
            itemShared;
    private BlockGeometryCache.Stats cachePrevious = BlockGeometryCache.stats(),
                                     cacheWindow = cachePrevious;

    static final class Frame {
        final long start, interval, beforePT;
        long submit, drain, prune, nativeRender, dynamicSubmit;
        long batches, packets, bytes;
        Frame(long start, long interval, long beforePT) {
            this.start = start;
            this.interval = interval;
            this.beforePT = beforePT;
        }
    }

    Frame begin(long worldRenderStart) {
        long now = System.nanoTime();
        long frameInterval = lastStart == 0 ? 0 : now - lastStart;
        if (lastStart != 0) {
            interval += frameInterval;
            ++intervals;
        }
        lastStart = now;
        long beforePT = worldRenderStart == 0 ? 0 : now - worldRenderStart;
        vanilla += beforePT;
        return new Frame(now, frameInterval, beforePT);
    }

    void finish(Frame frame, CaptureInbox inbox, int width, int height,
                HostVulkanRenderer renderer) {
        long duration = System.nanoTime() - frame.start;
        hookTimes[frames++] = duration;
        if (PrimeClient.skippedWorldRaster())
            ++rasterSkipped;
        total += duration;
        submit += frame.submit;
        drain += frame.drain;
        prune += frame.prune;
        nativeRender += frame.nativeRender;
        batches += frame.batches;
        packets += frame.packets;
        bytes += frame.bytes;
        var dynamic = DynamicCapture.stats();
        dynamicCapture += dynamic.captureNanos();
        dynamicSubmit += frame.dynamicSubmit;
        dynamicSpans += dynamic.spans();
        dynamicVertices += dynamic.vertices();
        dynamicBytes += dynamic.bytes();
        modelMeshes += dynamic.models();
        particleMeshes += dynamic.particles();
        modelRawVertices += dynamic.modelRawVertices();
        particleRawVertices += dynamic.particleRawVertices();
        var models = ModelCapture.stats();
        beSources += models.blockEntities();
        entitySources += models.entities();
        modelSubmits += models.submissions();
        instanceLeaves += models.leaves();
        fallbackLeaves += models.fallbackLeaves();
        referenceChecks += models.referenceChecks();
        geometryReads += models.geometryVerticesRead();
        referenceNanos += models.referenceNanos();
        cubeSkipped += models.skippedVertices();
        var fabric = FabricMeshCapture.stats();
        meshSubmits += fabric.submits();
        meshGroups += fabric.groups();
        meshRead += fabric.geometryVertices();
        meshSkipped += fabric.skippedVertices();
        meshFallback += fabric.fallbacks();
        var items = ItemCapture.stats();
        itemSubmits += items.submits();
        itemGroups += items.groups();
        itemChecked += items.checkedVertices();
        itemSkipped += items.skippedVertices();
        itemFallback += items.fallbackQuads();
        itemCreated += items.createdVertices();
        itemShared += items.sharedHits();
        if (models.delta() != null) {
            prototypes += models.delta().prototypeUpserts();
            instanceUpdates += models.delta().instanceUpserts();
            instanceRemoves += models.delta().instanceRemoves();
            instanceBytes += models.deltaBytes();
        }
        writeSample(frame, duration, width, height, dynamic, models, renderer.lastGpuTimeNanos());
        if (frames != WINDOW)
            return;
        long[] sorted = hookTimes.clone();
        Arrays.sort(sorted);
        var capture = inbox.profileSnapshot();
        PrimeClient.LOGGER.info(String.format(
                Locale.ROOT,
                "Prime PT CPU profile frames=%d size=%dx%d interval=%.3fms mcBeforePT=%.3fms hook=%.3fms p95=%.3fms max=%.3fms nativeSubmit=%.3fms drainIncludingSubmit=%.3fms prune=%.3fms nativeRecord=%.3fms gpuLast=%.3fms outputCpuBytes=0 rasterSkipped=%d batches=%d packets=%d bytes=%d trackedSections=%d queuedBatches=%d queuedBytes=%d dynamicCapture=%.3fms dynamicSubmit=%.3fms dynamicSpansTotal=%d dynamicVerticesTotal=%d dynamicBytesTotal=%d modelMeshesTotal=%d particleMeshesTotal=%d dynamicCapacity=%d dynamicGrowthsTotal=%d",
                frames, width, height, intervals == 0 ? 0 : interval / (intervals * 1_000_000.0),
                mean(vanilla), mean(total), sorted[(int)(WINDOW * .95) - 1] / 1_000_000.0,
                sorted[WINDOW - 1] / 1_000_000.0, mean(submit), mean(drain), mean(prune),
                mean(nativeRender), renderer.lastGpuTimeNanos() / 1_000_000.0, rasterSkipped,
                batches, packets, bytes, capture.sections(), capture.batches(), capture.bytes(),
                mean(dynamicCapture), mean(dynamicSubmit), dynamicSpans, dynamicVertices,
                dynamicBytes, modelMeshes, particleMeshes, dynamic.capacity(),
                dynamic.growthCount()));
        PrimeClient.LOGGER.info(String.format(
                Locale.ROOT,
                "Prime PT instance profile frames=%d beSources=%d entitySources=%d modelSubmits=%d standardLeaves=%d fallbackLeaves=%d refsChecks=%d geometryVerticesRead=%d refsPose=%.3fms prototypeUpserts=%d instanceUpserts=%d instanceRemoves=%d op7Bytes=%d rawFallbackVertices=%d modelRawVertices=%d particleRawVertices=%d activeInstances=%d",
                frames, beSources, entitySources, modelSubmits, instanceLeaves, fallbackLeaves,
                referenceChecks, geometryReads,
                ModelCapture.leafTimingEnabled() ? mean(referenceNanos) : -1, prototypes,
                instanceUpdates, instanceRemoves, instanceBytes, dynamicVertices, modelRawVertices,
                particleRawVertices,
                models.delta() == null ? 0 : models.delta().activeInstances()));
        var geometry = BlockGeometryCache.stats();
        PrimeClient.LOGGER.info(
                "Prime PT geometry cache frames={} hits={} misses={} nullKeys={} emits={} emittedBytes={} avoidedCopyBytes={} retainedBytes={} entries={} resets={}",
                frames, geometry.hits() - cacheWindow.hits(),
                geometry.misses() - cacheWindow.misses(),
                geometry.nullKeys() - cacheWindow.nullKeys(),
                geometry.emits() - cacheWindow.emits(),
                geometry.emittedBytes() - cacheWindow.emittedBytes(),
                geometry.avoidedBytes() - cacheWindow.avoidedBytes(), geometry.retainedBytes(),
                geometry.entries(), geometry.resets() - cacheWindow.resets());
        cacheWindow = geometry;
        PrimeClient.LOGGER.info(
                "Prime PT exclusive source frames={} cubeVerticesSkipped={} meshSubmits={} meshGroups={} meshVerticesRead={} meshVerticesSkipped={} meshFallbacks={}",
                frames, cubeSkipped, meshSubmits, meshGroups, meshRead, meshSkipped, meshFallback);
        PrimeClient.LOGGER.info(
                "Prime PT item source frames={} submits={} groups={} checkedVertices={} skippedVertices={} fallbackQuads={} createdVertices={} sharedHits={}",
                frames, itemSubmits, itemGroups, itemChecked, itemSkipped, itemFallback,
                itemCreated, itemShared);
        clearWindow();
    }

    /** PT hook cadence is a CPU frame interval, not a display-present or GPU-duration measurement. */
    private void writeSample(Frame f, long hook, int width, int height, DynamicCapture.Stats raw,
                             ModelCapture.Stats models, long gpuLast) {
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
                samples.write(
                        "sample,pt_interval_ns,mc_before_pt_ns,pt_hook_ns,width,height,native_submit_ns,drain_ns,prune_ns,dynamic_submit_ns,native_record_ns,capture_ns,refs_pose_ns,be_sources,entity_sources,model_submits,standard_leaves,fallback_leaves,refs_checks,geometry_vertices_read,prototype_upserts,instance_upserts,instance_removes,op7_bytes,raw_vertices,model_raw_vertices,particle_raw_vertices,raw_packet_bytes,section_batches,section_bytes,raster_skipped,geometry_hits,geometry_misses,geometry_null_keys,geometry_emits,geometry_emitted_bytes,geometry_avoided_copy_bytes,cube_vertices_skipped,mesh_submits,mesh_groups,mesh_geometry_vertices_read,mesh_vertices_skipped,mesh_fallbacks,gpu_last_ns,terrain_compilations,terrain_pending,item_submits,item_groups,item_checked_vertices,item_skipped_vertices,item_fallback_quads,item_created_vertices,item_shared_hits\n");
            }
            if (samples == null)
                return;
            var delta = models.delta();
            var geometry = BlockGeometryCache.stats();
            var fabric = FabricMeshCapture.stats();
            var items = ItemCapture.stats();
            samples.write(
                    (++sampleIndex) + "," + f.interval + "," + f.beforePT + "," + hook + "," +
                    width + "," + height + "," + f.submit + "," + f.drain + "," + f.prune + "," +
                    f.dynamicSubmit + "," + f.nativeRender + "," + raw.captureNanos() + "," +
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
                    dev.primept.capture.ExclusiveTerrainCapture.compilations() + "," +
                    dev.primept.capture.ExclusiveTerrainCapture.pendingSections() + "," +
                    items.submits() + "," + items.groups() + "," + items.checkedVertices() + "," +
                    items.skippedVertices() + "," + items.fallbackQuads() + "," +
                    items.createdVertices() + "," + items.sharedHits() + "\n");
            cachePrevious = geometry;
            if (sampleIndex % WINDOW == 0)
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

    private double mean(long nanos) {
        return nanos / (frames * 1_000_000.0);
    }
    private void clearWindow() {
        frames = intervals = rasterSkipped = 0;
        interval = vanilla = submit = drain = prune = nativeRender = total = 0;
        batches = packets = bytes = 0;
        dynamicCapture = dynamicSubmit = dynamicSpans = dynamicVertices = dynamicBytes = 0;
        modelMeshes = particleMeshes = modelRawVertices = particleRawVertices = 0;
        beSources = entitySources = modelSubmits = instanceLeaves = fallbackLeaves =
                referenceChecks = geometryReads = referenceNanos = 0;
        prototypes = instanceUpdates = instanceRemoves = instanceBytes = 0;
        cubeSkipped = meshSubmits = meshGroups = meshRead = meshSkipped = meshFallback = 0;
        itemSubmits = itemGroups = itemChecked = itemSkipped = itemFallback = itemCreated =
                itemShared = 0;
    }
    void reset() {
        lastStart = 0;
        clearWindow();
    }
}
