package dev.primept;

import dev.primept.capture.DynamicCapture;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.BlockGeometryCache;
import dev.primept.capture.FabricMeshCapture;
import dev.primept.capture.ItemCapture;
import dev.primept.capture.ExclusiveTerrainCapture;
import java.util.Arrays;
import java.util.Locale;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Coarse CPU timings and >=50 ms warnings are permanent. Window summaries/CSV remain optional. */
final class RenderProfile {
    private static final int WINDOW = 120;
    private static final DynamicCapture.Stats NO_RAW =
            new DynamicCapture.Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private static final ModelCapture.Stats NO_MODELS =
            new ModelCapture.Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0);
    private static final FabricMeshCapture.Stats NO_FABRIC =
            new FabricMeshCapture.Stats(0, 0, 0, 0, 0);
    private static final ItemCapture.Stats NO_ITEMS = new ItemCapture.Stats(0, 0, 0, 0, 0, 0, 0);
    private final long[] hookTimes = new long[WINDOW];
    private final boolean detailed = Boolean.getBoolean("primept.profile");
    private final boolean csv = System.getProperty("primept.profile.csv") != null;
    private long lastStart, previousHook, extractionStart, extractionNanos, frameNumber;

    void beginExtraction() {
        extractionStart = System.nanoTime();
        extractionNanos = 0;
    }
    void endExtraction() {
        extractionNanos = extractionStart == 0 ? 0 : System.nanoTime() - extractionStart;
    }
    private BufferedWriter samples;
    private boolean samplesOpened;
    private long sampleIndex;
    private int frames, intervals, rasterSkipped;
    private long interval, vanilla, submit, drain, prune, nativeRender, total;
    private long batches, packets, bytes;
    private long terrainDirty, terrainEntered, terrainLoaded, terrainUnloaded, terrainInvalidations,
            terrainSelected, terrainEmptyPublished, terrainEmptyRetained, terrainRouted,
            terrainDeferred, terrainWaiting, terrainPlan, terrainPack, terrainTotal, terrainMax,
            terrainAccept, terrainSourceBytes, terrainTintQueries, terrainTint;
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
        long now = System.nanoTime();
        long frameInterval = lastStart == 0 ? 0 : now - lastStart;
        if (lastStart != 0) {
            interval += frameInterval;
            ++intervals;
        }
        lastStart = now;
        long beforePT = worldRenderStart == 0 ? 0 : now - worldRenderStart;
        vanilla += beforePT;
        return new Frame(now, frameInterval, beforePT, extractionNanos);
    }

    void finish(Frame frame, int width, int height, HostVulkanRenderer renderer) {
        long duration = System.nanoTime() - frame.start;
        var terrain = ExclusiveTerrainCapture.takeStats();
        var timing = new FrameTimings(frame.interval, previousHook, frame.extraction,
                                      frame.beforePT, duration);
        previousHook = duration;
        ++frameNumber;
        if (timing.slow())
            warnSlow(frame, timing, terrain, width, height, renderer);
        if (!detailed && !csv)
            return;
        if (PrimeClient.offlineActive()) {
            if (csv)
                writeSample(frame, duration, width, height, NO_RAW, NO_MODELS, terrain,
                            renderer.lastGpuTimeNanos());
            if (detailed && frameNumber % WINDOW == 0)
                PrimeClient.LOGGER.info("Prime PT native profile offline=true {}",
                                        nativeStages(renderer));
            return;
        }
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
        terrainDirty += terrain.dirty();
        terrainEntered += terrain.entered();
        terrainLoaded += terrain.loaded();
        terrainUnloaded += terrain.unloaded();
        terrainInvalidations += terrain.invalidations();
        terrainSelected += terrain.selected();
        terrainEmptyPublished += terrain.emptyPublished();
        terrainEmptyRetained += terrain.emptyRetained();
        terrainRouted += terrain.routed();
        terrainDeferred += terrain.deferred();
        terrainWaiting = terrain.waiting();
        terrainPlan += terrain.planNanos();
        terrainPack += terrain.packNanos();
        terrainTotal += terrain.totalNanos();
        terrainAccept += terrain.acceptNanos();
        terrainTintQueries += terrain.tintQueries();
        terrainTint += terrain.tintNanos();
        terrainSourceBytes += terrain.sourceBytes();
        terrainMax = Math.max(terrainMax, terrain.totalNanos());
        if (csv)
            writeSample(frame, duration, width, height, dynamic, models, terrain,
                        renderer.lastGpuTimeNanos());
        if (!detailed) {
            clearWindow();
            return;
        }
        if (frames != WINDOW)
            return;
        PrimeClient.LOGGER.info("Prime PT native profile offline=false {}", nativeStages(renderer));
        long[] sorted = hookTimes.clone();
        Arrays.sort(sorted);
        PrimeClient.LOGGER.info(String.format(
                Locale.ROOT,
                "Prime PT CPU profile frames=%d size=%dx%d interval=%.3fms mcBeforePT=%.3fms hook=%.3fms p95=%.3fms max=%.3fms nativeSubmit=%.3fms drainIncludingSubmit=%.3fms prune=%.3fms nativeRecord=%.3fms gpuLast=%.3fms outputCpuBytes=0 rasterSkipped=%d batches=%d packets=%d bytes=%d dynamicCapture=%.3fms dynamicSubmit=%.3fms dynamicSpansTotal=%d dynamicVerticesTotal=%d dynamicBytesTotal=%d modelMeshesTotal=%d particleMeshesTotal=%d dynamicCapacity=%d dynamicGrowthsTotal=%d",
                frames, width, height, intervals == 0 ? 0 : interval / (intervals * 1_000_000.0),
                mean(vanilla), mean(total), sorted[(int)(WINDOW * .95) - 1] / 1_000_000.0,
                sorted[WINDOW - 1] / 1_000_000.0, mean(submit), mean(drain), mean(prune),
                mean(nativeRender), renderer.lastGpuTimeNanos() / 1_000_000.0, rasterSkipped,
                batches, packets, bytes, mean(dynamicCapture), mean(dynamicSubmit), dynamicSpans,
                dynamicVertices, dynamicBytes, modelMeshes, particleMeshes, dynamic.capacity(),
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
        PrimeClient.LOGGER.info(String.format(
                Locale.ROOT,
                "Prime PT terrain source frames=%d dirtyEvents=%d enteredColumns=%d loadedColumns=%d unloadedColumns=%d invalidations=%d selected=%d emptyPublished=%d emptyRetained=%d routed=%d deferred=%d waitingNow=%d sourcePlan=%.3fms sourcePack=%.3fms sourceAccept=%.3fms sectionSourceBytes=%d tintQueries=%d tintCallback=%.3fms total=%.3fms max=%.3fms",
                frames, terrainDirty, terrainEntered, terrainLoaded, terrainUnloaded,
                terrainInvalidations, terrainSelected, terrainEmptyPublished, terrainEmptyRetained,
                terrainRouted, terrainDeferred, terrainWaiting, mean(terrainPlan),
                mean(terrainPack), mean(terrainAccept), terrainSourceBytes, terrainTintQueries,
                mean(terrainTint), mean(terrainTotal), terrainMax / 1_000_000.0));
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

    private void warnSlow(Frame frame, FrameTimings time, ExclusiveTerrainCapture.Stats terrain,
                          int width, int height, HostVulkanRenderer renderer) {
        boolean offline = PrimeClient.offlineActive();
        var models = ModelCapture.stats();
        var raw = DynamicCapture.stats();
        PrimeClient.LOGGER.warn(slowMessage(frameNumber, renderer.lastCpuSerial(), width, height,
                                            offline, frame, time, terrain, models, raw,
                                            nativeStages(renderer), renderer.lastGpuTimeNanos()));
    }
    private static String nativeStages(HostVulkanRenderer renderer) {
        try {
            return renderer.cpuDiagnostics();
        } catch (RuntimeException failure) {
            // Diagnostics must not retire a healthy renderer.
            return "unavailable=" + failure.getMessage();
        }
    }
    static String slowMessage(long frameNumber, long serial, int width, int height, boolean offline,
                              Frame frame, FrameTimings time, ExclusiveTerrainCapture.Stats terrain,
                              ModelCapture.Stats models, DynamicCapture.Stats raw,
                              String nativeStages, long gpuLast) {
        var delta = models.delta();
        return String.format(
                Locale.ROOT,
                "Prime slow frame frame=%d serial=%d size=%dx%d offline=%s threshold=50ms currentWork=%.3fms interval=%.3fms extraction=%.3fms beforePT=%.3fms terrain=%.3fms sourcePlan=%.3fms sourcePack=%.3fms sourceAccept=%.3fms sectionSourceBytes=%d tintQueries=%d tintCallback=%.3fms extractionOther=%.3fms hook=%.3fms drainIncludingSubmit=%.3fms nativeSubmit=%.3fms resourceSubmit=%.3fms dynamicSubmit=%.3fms nativeRecord=%.3fms prevHook=%.3fms outsideInterval=%.3fms gpuLastDelayed=%.3fms dirtyEvents=%d lightEngineNotifications=%d lightPacketNotifications=%d enteredColumns=%d loadedColumns=%d unloadedColumns=%d fullInvalidations=%d selected=%d routed=%d emptyPublished=%d emptyRetained=%d deferred=%d waiting=%d packets=%d sourceBytes=%d beSources=%d entitySources=%d rawVertices=%d rawBytes=%d instanceUpserts=%d instanceRemoves=%d instanceBytes=%d native={%s}",
                frameNumber, serial, width, height, offline, ms(time.currentWork()),
                ms(time.interval()), ms(time.extraction()), ms(time.beforePT()),
                ms(terrain.totalNanos()), ms(terrain.planNanos()), ms(terrain.packNanos()),
                ms(terrain.acceptNanos()), terrain.sourceBytes(), terrain.tintQueries(),
                ms(terrain.tintNanos()), ms(Math.max(0, time.extraction() - terrain.totalNanos())),
                ms(time.hook()), ms(frame.drain), ms(frame.submit), ms(frame.resourceSubmit),
                ms(frame.dynamicSubmit), ms(frame.nativeRender), ms(time.previousHook()),
                time.outside() < 0 ? -1 : ms(time.outside()), ms(gpuLast), terrain.dirty(),
                terrain.lightEngineEvents(), terrain.lightPacketEvents(), terrain.entered(),
                terrain.loaded(), terrain.unloaded(), terrain.invalidations(), terrain.selected(),
                terrain.routed(), terrain.emptyPublished(), terrain.emptyRetained(),
                terrain.deferred(), terrain.waiting(), frame.packets, frame.bytes,
                offline ? 0 : models.blockEntities(), offline ? 0 : models.entities(),
                offline ? 0 : raw.vertices(), offline ? 0 : raw.bytes(),
                offline || delta == null ? 0 : delta.instanceUpserts(),
                offline || delta == null ? 0 : delta.instanceRemoves(),
                offline ? 0 : models.deltaBytes(), nativeStages);
    }
    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
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
                samples.write(
                        "sample,pt_interval_ns,mc_before_pt_ns,pt_hook_ns,width,height,native_submit_ns,drain_ns,prune_ns,dynamic_submit_ns,native_record_ns,capture_ns,refs_pose_ns,be_sources,entity_sources,model_submits,standard_leaves,fallback_leaves,refs_checks,geometry_vertices_read,prototype_upserts,instance_upserts,instance_removes,op7_bytes,raw_vertices,model_raw_vertices,particle_raw_vertices,raw_packet_bytes,section_batches,section_bytes,raster_skipped,geometry_hits,geometry_misses,geometry_null_keys,geometry_emits,geometry_emitted_bytes,geometry_avoided_copy_bytes,cube_vertices_skipped,mesh_submits,mesh_groups,mesh_geometry_vertices_read,mesh_vertices_skipped,mesh_fallbacks,gpu_last_ns,terrain_routed_total,terrain_pending,item_submits,item_groups,item_checked_vertices,item_skipped_vertices,item_fallback_quads,item_created_vertices,item_shared_hits,terrain_dirty_events,terrain_entered_columns,terrain_loaded_columns,terrain_unloaded_columns,terrain_invalidations,terrain_selected,terrain_empty_published,terrain_empty_retained,terrain_routed,terrain_plan_ns,terrain_pack_ns,terrain_total_ns,terrain_deferred,terrain_waiting,terrain_light_engine_notifications,terrain_light_packet_notifications,extraction_ns,resource_submit_ns,terrain_accept_ns,terrain_source_bytes,terrain_tint_queries,terrain_tint_ns,offline\n");
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
                    dev.primept.capture.ExclusiveTerrainCapture.pendingSections() + "," +
                    items.submits() + "," + items.groups() + "," + items.checkedVertices() + "," +
                    items.skippedVertices() + "," + items.fallbackQuads() + "," +
                    items.createdVertices() + "," + items.sharedHits() + "," + terrain.dirty() +
                    "," + terrain.entered() + "," + terrain.loaded() + "," + terrain.unloaded() +
                    "," + terrain.invalidations() + "," + terrain.selected() + "," +
                    terrain.emptyPublished() + "," + terrain.emptyRetained() + "," +
                    terrain.routed() + "," + terrain.planNanos() + "," + terrain.packNanos() + "," +
                    terrain.totalNanos() + "," + terrain.deferred() + "," + terrain.waiting() +
                    "," + terrain.lightEngineEvents() + "," + terrain.lightPacketEvents() + "," +
                    f.extraction + "," + f.resourceSubmit + "," + terrain.acceptNanos() + "," +
                    terrain.sourceBytes() + "," + terrain.tintQueries() + "," +
                    terrain.tintNanos() + "," + offline + "\n");
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
        terrainDirty = terrainEntered = terrainLoaded = terrainUnloaded = terrainInvalidations = 0;
        terrainSelected = terrainEmptyPublished = terrainEmptyRetained = terrainRouted = 0;
        terrainDeferred = terrainWaiting = 0;
        terrainPlan = terrainPack = terrainTotal = terrainMax = terrainAccept = terrainSourceBytes =
                terrainTintQueries = terrainTint = 0;
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
        lastStart = previousHook = 0;
        clearWindow();
    }
}
