package dev.primept.capture;

import static dev.primept.abi.PrimeAbi.*;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.NativeBridge;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Runs real Custom constructors, phase sorting, schedule hook, callbacks and Draw.append without a device. */
public final class CustomCpuSmoke {
    private static final RenderType TYPE = Sheets.cutoutBlockItemSheet(),
                                    OTHER = Sheets.cutoutItemSheet();
    private static final CustomFeatureRenderer RENDERER = new CustomFeatureRenderer();
    private static final CameraRenderState CAMERA = new CameraRenderState();
    private static final ArrayList<Map<String, Object>> products = new ArrayList<>();
    private static int callbacks;
    private static boolean bound = true;
    private static boolean exporting;
    private static boolean wrapped;
    private static long epochOverride;
    private static int textureId, materialFlags;
    private static NoGpuStaged current;
    private CustomCpuSmoke() {}

    public static void run() throws Exception {
        set(Class.forName("dev.primept.capture.ItemCpuSmoke"), "exclusive", null, true);
        try {
            DynamicCapture.close();
            var first = new Owner();
            var quad = new Quad();
            CAMERA.pos = Vec3.ZERO;
            var baseline = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(baseline.ids.size() == 1 && baseline.rawVertices == 0 && callbacks == 1,
                  "Real constructor/schedule/emission routes once");
            long id = baseline.ids.getFirst();
            var stable = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(stable.batch == null && stable.rawVertices == 0 && callbacks == 1,
                  "Unchanged named geometry emits zero typed bytes");
            quad.shift = .1f;
            CAMERA.pos = new Vec3(.25, 0, 0);
            var moved = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(moved.ids.getFirst() == id && moved.prototypeUpserts == 1 &&
                          moved.prototypeRemoves == 1,
                  "Camera-relative translation replaces bytes but preserves named identity");
            check(moved.origin[0] == .25 && moved.origin[1] == 0 && moved.origin[2] == 0,
                  "Actual camera origin, no owner/base double translation");
            var two =
                    frame(List.of(new Visit(first, List.of(quad, new SecondQuad()))), true, false);
            check(two.ids.size() == 2 && !two.ids.contains(id), "Count change resets namespace");
            var reversed =
                    frame(List.of(new Visit(first, List.of(new SecondQuad(), quad))), true, false);
            check(reversed.ids.size() == 2 && java.util.Collections.disjoint(two.ids, reversed.ids),
                  "Ordered signature change resets every raw ordinal");
            var duplicate =
                    frame(List.of(new Visit(first, List.of(quad, new Quad(), new SecondQuad()))),
                          true, false);
            check(duplicate.ids.isEmpty() && duplicate.rawVertices == 12 && callbacks == 3 &&
                          duplicate.prototypeRemoves == 2 && duplicate.instanceRemoves == 2,
                  "Duplicate signature keeps entire owner raw and retires named state");
            var recovered = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(!reversed.ids.contains(recovered.ids.getFirst()),
                  "Recovered identity starts fresh");
            var changedClass =
                    frame(List.of(new Visit(first, List.of(new SecondQuad()))), true, false);
            check(!changedClass.ids.equals(recovered.ids), "Callback class change resets identity");
            var changedType = new SecondQuad();
            changedType.renderType = OTHER;
            var type = frame(List.of(new Visit(first, List.of(changedType))), true, false);
            check(!type.ids.equals(changedClass.ids),
                  "Actual render type identity change resets identity");
            var visits = frame(List.of(new Visit(first, List.of(quad)),
                                       new Visit(first, List.of(new SecondQuad()))),
                               true, false);
            check(visits.ids.isEmpty() && visits.rawVertices == 8 && callbacks == 2,
                  "Repeated owner dispatcher scopes all retain raw");
            var repeated = frame(List.of(new Visit(first, List.of(quad))), true, true);
            check(repeated.ids.isEmpty() && repeated.rawVertices == 8 && callbacks == 2,
                  "Same real Submit reused in separate phases retains raw, not capture failure");
            var distinct = frame(List.of(new Visit(first, List.of(quad)),
                                         new Visit(new Owner(), List.of(new Quad()))),
                                 true, false);
            check(distinct.ids.size() == 2 && !distinct.ids.get(0).equals(distinct.ids.get(1)),
                  "Two actual owners sharing a draw retain distinct IDs");
            var anonymous = frame(List.of(new Visit(null, List.of(new Quad()))), true, false);
            check(anonymous.ids.isEmpty() && anonymous.rawVertices == 4,
                  "Anonymous output remains raw");
            var unbound = frame(List.of(new Visit(first, List.of(quad))), false, false);
            check(unbound.ids.isEmpty() && unbound.rawVertices == 0 && callbacks == 1,
                  "Unregistered material is not invented");
            var consumer =
                    frame(List.of(new Visit(first, List.of(new WrappedQuad()))), true, false);
            check(consumer.ids.isEmpty() && consumer.rawVertices == 4 && callbacks == 1,
                  "Actual custom consumer wrapper retains once-only original raw output");
            textureId = 7;
            materialFlags = 2;
            var material = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(material.ids.size() == 1, "Bound source texture and flags pass through instance");
            textureId = materialFlags = 0;
            quad.shift = 5000;
            var large = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(large.ids.isEmpty() && large.rawVertices == 4,
                  "Typed local bounds preserve raw fallback");
            quad.shift = 0;
            quad.badUv = true;
            var uv = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(uv.ids.isEmpty() && uv.rawVertices == 4,
                  "Nonfinite UV does not enter typed/excluded path");
            quad.badUv = false;
            quad.vertices = 3;
            var partial = frame(List.of(new Visit(first, List.of(quad))), true, false);
            check(partial.ids.isEmpty() && partial.rawVertices == 4,
                  "Unaligned emission retains complete original raw quad");
            quad.vertices = 4;
            var mixed = frame(List.of(new Visit(first, List.of(new MixedQuad()))), true, false);
            check(mixed.ids.size() == 1 && mixed.rawVertices == 8 && mixed.protoVertexCount == 8,
                  "Mixed mechanical hole is subtracted before one named prototype");
            check(mixed.protoXs.equals(List.of(4f, 5f, 6f, 7f, 12f, 13f, 14f, 15f)),
                  "Remaining named fragments preserve actual quad order");
            var full = frame(List.of(new Visit(first, List.of(new FullHole()))), true, false);
            check(full.ids.isEmpty() && full.rawVertices == 0 && full.prototypeRemoves == 1 &&
                          full.instanceRemoves == 1,
                  "Full mechanical hole releases cached named owner");
            var emptyQuad = new Quad();
            frame(List.of(new Visit(first, List.of(emptyQuad))), true, false);
            emptyQuad.vertices = 0;
            var empty = frame(List.of(new Visit(first, List.of(emptyQuad))), true, false);
            check(empty.ids.isEmpty() && empty.prototypeRemoves == 1 && empty.instanceRemoves == 1,
                  "Empty callback releases source ownership and visible instance");
            var reopen = frame(List.of(new Visit(first, List.of(new Quad(), new OtherQuad(),
                                                                new ThirdQuad()))),
                               true, false);
            check(reopen.ids.size() == 3 && reopen.rawVertices == 0 && reopen.original.size() == 3,
                  "Draw A to B to A flushes independent zero-based builder chunks: " + reopen.ids +
                          "/" + reopen.rawVertices + "/" + reopen.original.size());
            var flushing =
                    frame(List.of(new Visit(first, List.of(new FlushingQuad()))), true, false);
            check(flushing.ids.isEmpty() && flushing.rawVertices == 12 && callbacks == 1,
                  "Mid-callback builder flush keeps every actual chunk raw");
            var churn = new Quad();
            var initial = frame(List.of(new Visit(first, List.of(churn))), true, false);
            for (int i = 0; i < 64; ++i) {
                churn.shift = .2f + i;
                var changed = frame(List.of(new Visit(first, List.of(churn))), true, false);
                check(changed.ids.equals(initial.ids) && changed.prototypeUpserts == 1 &&
                              changed.prototypeRemoves == 1,
                      "Explicit prototype churn retires previous source owner without GC");
            }
            var absent = frame(List.of(), true, false);
            check(absent.prototypeRemoves == 1 && absent.instanceRemoves == 1,
                  "Unseen source is released at frame end");
            var reappeared = frame(List.of(new Visit(first, List.of(churn))), true, false);
            check(!reappeared.ids.equals(initial.ids),
                  "Unseen then returning source starts a fresh identity");
            weakOwnerRelease();
            failureOnce();
            lateSource();
            epochReset();
            closeRetainedSealedOwner();
            exportedProducts();
            System.out.println(
                    "PRIME_PT_CUSTOM_CPU_OK: actual sorted schedule, callback once, duplicate fallback, independent IDs, exact camera/raw bytes, mixed holes, draw reopen, weak release, native DTO replay");
        } finally {
            set(Class.forName("dev.primept.capture.ItemCpuSmoke"), "exclusive", null, false);
            DynamicCapture.close();
        }
    }

    private static Snapshot frame(List<Visit> visits, boolean known, boolean repeat)
            throws Exception {
        callbacks = 0;
        bound = known;
        wrapped = visits.stream()
                          .flatMap(visit -> visit.callbacks.stream())
                          .anyMatch(value -> value instanceof WrappedQuad);
        DynamicCapture.begin(CAMERA);
        if (epochOverride != 0) {
            ModelCapture.begin(epochOverride);
            NamedRawCapture.origin(CAMERA.pos.x, CAMERA.pos.y, CAMERA.pos.z);
        }
        try (var staged = new NoGpuStaged()) {
            current = staged;
            var storage = new SubmitNodeStorage();
            for (Visit visit : visits) {
                var previous = visit.owner == null
                                       ? null
                                       : ModelCapture.beginSource(
                                                 visit.owner, 999, 888, 777,
                                                 new Matrix4f().translation(444, 555, 666), false);
                try {
                    int modelCursor = visit.owner == null ? 0 : visit.owner.source.cursor;
                    int order = 0;
                    ModelCapture.tagSubmission();
                    for (Quad callback : visit.callbacks) {
                        var submit = new CustomFeatureRenderer.Submit(new PoseStack().last().copy(),
                                                                      callback.type(), callback);
                        storage.order(visit.callbacks.stream().anyMatch(
                                              value -> value instanceof OtherQuad)
                                              ? order++
                                              : 0)
                                .solid.submit(submit);
                        if (repeat)
                            storage.order(1).solid.submit(submit);
                    }
                    var model = ModelCapture.tagSubmission();
                    if (visit.owner != null)
                        check(visit.owner.source.submits.get(modelCursor + 1) == model &&
                                      visit.owner.source.cursor == modelCursor + 2,
                              "Custom namespace does not advance model/item/FRAPI ordinal");
                } finally {
                    if (visit.owner != null)
                        ModelCapture.endSource(previous);
                }
            }
            prepare(staged, storage);
            check(DynamicCapture.healthy(), "Custom capture healthy");
            DynamicCapture.end();
            return snapshot(staged.original, !exporting);
        } finally {
            current = null;
            wrapped = false;
            if (DynamicCapture.active())
                DynamicCapture.end();
        }
    }

    private static void prepare(NoGpuStaged staged, SubmitNodeStorage storage) throws Exception {
        // Only the constructor's unrelated renderer dependencies are omitted. The real dispatcher
        // sort/group/beginPrepare sequence and production schedule injection execute below.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Object unsafe = value(unsafeType, "theUnsafe", null);
        var dispatcher =
                (FeatureRenderDispatcher)unsafeType.getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, FeatureRenderDispatcher.class);
        var renderers = new FeatureRendererMap();
        @SuppressWarnings("unchecked")
        var renderer = (FeatureRenderer<CustomFeatureRenderer.Submit>)Proxy.newProxyInstance(
                FeatureRenderer.class.getClassLoader(), new Class<?>[] {FeatureRenderer.class},
                (proxy, call, arguments) -> {
                    if (call.getName().equals("prepareGroup")) {
                        var context = (FeatureFrameContext)arguments[0];
                        @SuppressWarnings("unchecked")
                        var submits = (List<CustomFeatureRenderer.Submit>)arguments[1];
                        try {
                            for (var submit : submits) {
                                bindGroup(staged, submit.renderType());
                                if (submit.customGeometryRenderer() instanceof Quad quad) {
                                    var consumer = staged.getVertexBuilder(
                                            staged.draw(submit.renderType()));
                                    if (quad.vertices == 3)
                                        vertex(consumer, -2, -1, 3, 0, false);
                                    if (quad instanceof MixedQuad)
                                        for (int i = 0; i < 4; ++i)
                                            vertex(consumer, i, 0, 3, i, false);
                                }
                                var build = CustomFeatureRenderer.class.getDeclaredMethod(
                                        "buildGroup", FeatureFrameContext.class, List.class);
                                build.setAccessible(true);
                                build.invoke(RENDERER, context, List.of(submit));
                                if (submit.customGeometryRenderer() instanceof MixedQuad) {
                                    var consumer = staged.getVertexBuilder(
                                            staged.draw(submit.renderType()));
                                    for (int i = 16; i < 20; ++i)
                                        vertex(consumer, i, 0, 3, i, false);
                                }
                            }
                        } catch (InvocationTargetException exception) {
                            if (exception.getCause() instanceof RuntimeException runtime)
                                throw runtime;
                            throw new IllegalStateException(exception.getCause());
                        } catch (ReflectiveOperationException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                    return null;
                });
        renderers.put(CustomFeatureRenderer.TYPE, renderer);
        set(FeatureRenderDispatcher.class, "featureRenderers", dispatcher, renderers);
        set(FeatureRenderDispatcher.class, "stagedVertexBuffer", dispatcher, staged);
        var constructor = FeatureRenderDispatcher.PreparedFrame.class.getDeclaredConstructor(
                FeatureRenderDispatcher.class);
        constructor.setAccessible(true);
        set(FeatureRenderDispatcher.class, "preparedFrame", dispatcher,
            constructor.newInstance(dispatcher));
        var context = new FeatureFrameContext(null, null, null, null, null, null, null, staged);
        var method = FeatureRenderDispatcher.class.getDeclaredMethod(
                "prepareFrameWithContext", FeatureFrameContext.class, SubmitNodeStorage.class);
        method.setAccessible(true);
        try {
            method.invoke(dispatcher, context, storage);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause)
                throw cause;
            throw exception;
        }
    }
    private static void bindGroup(NoGpuStaged staged, RenderType type)
            throws ReflectiveOperationException {
        Class<?> groupType = Class.forName(RenderTypeFeatureRenderer.class.getName() + "$Group");
        var constructor = groupType.getDeclaredConstructor(StagedVertexBuffer.class, boolean.class);
        constructor.setAccessible(true);
        Object group = constructor.newInstance(staged, true);
        set(groupType, "lastRenderType", group, type);
        set(groupType, "lastDraw", group, staged.draw(type));
        set(RenderTypeFeatureRenderer.class, "currentGroup", RENDERER, group);
    }

    private static void weakOwnerRelease() throws Exception {
        var owner = new Owner();
        frame(List.of(new Visit(owner, List.of(new Quad()))), true, false);
        Map<?, ?> registry = value(NamedRawCapture.class, "owners", null);
        Reference<?> key = registry.keySet()
                                   .stream()
                                   .map(value -> (Reference<?>)value)
                                   .filter(value -> value.get() == owner.source)
                                   .findFirst()
                                   .orElseThrow();
        key.clear();
        check(key.enqueue(), "Explicit weak queue enqueue without nondeterministic GC");
        var retired = frame(List.of(), true, false);
        check(retired.prototypeRemoves == 1 && retired.instanceRemoves == 1,
              "Weak owner releases source prototype while endFrame retires instance");
    }
    private static void failureOnce() throws Exception {
        callbacks = 0;
        DynamicCapture.begin(CAMERA);
        try (var staged = new NoGpuStaged()) {
            current = staged;
            var previous = ModelCapture.beginSource(new Owner(), 0, 0, 0, new Matrix4f(), false);
            var storage = new SubmitNodeStorage();
            storage.order(0).solid.submit(new CustomFeatureRenderer.Submit(
                    new PoseStack().last(), TYPE, new ThrowingQuad()));
            ModelCapture.endSource(previous);
            boolean failed = false;
            try {
                prepare(staged, storage);
            } catch (IllegalStateException expected) {
                failed = true;
            }
            check(failed && callbacks == 1,
                  "Throwing source callback executes once and propagates");
            boolean rejected = false;
            try {
                // A null bridge makes any attempted native publication fail differently.
                DynamicCapture.submit(dev.primept.PrimeClient.CAPTURE.epoch(), null);
            } catch (IllegalStateException expected) {
                rejected = expected.getMessage().equals("Custom source callback did not complete");
            }
            check(rejected, "Poisoned frame rejects before any texture/typed/raw FFM call");
        } finally {
            current = null;
            DynamicCapture.end();
        }
    }
    private static void lateSource() throws Exception {
        DynamicCapture.begin(CAMERA);
        var previous = ModelCapture.beginSource(new Owner(), 0, 0, 0, new Matrix4f(), false);
        var submit = new CustomFeatureRenderer.Submit(new PoseStack().last(), TYPE, new Quad());
        ModelCapture.endSource(previous);
        NamedRawCapture.freeze(List.of(submit));
        previous = ModelCapture.beginSource(new Owner(), 0, 0, 0, new Matrix4f(), false);
        ModelCapture.endSource(previous);
        check(!DynamicCapture.healthy(), "Late source cannot reuse an already frozen observation");
        boolean rejected = false;
        try {
            DynamicCapture.submit(dev.primept.PrimeClient.CAPTURE.epoch(), null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().equals("Custom source collection after preparation");
        }
        check(rejected, "Late source stops before every native publication");
        DynamicCapture.end();
    }
    private static void epochReset() throws Exception {
        var owner = new Owner();
        frame(List.of(new Visit(owner, List.of(new Quad()))), true, false);
        var previous = owner.source;
        var context = context();
        epochOverride = dev.primept.PrimeClient.CAPTURE.epoch() + 99;
        try {
            var replacement = frame(List.of(new Visit(owner, List.of(new Quad()))), true, false);
            check(owner.source != previous && context() != context &&
                          ((Number)replacement.product.get("epoch")).longValue() == epochOverride,
                  "Resource epoch replaces Java owner namespace and typed epoch");
        } finally {
            epochOverride = 0;
            DynamicCapture.close();
        }
    }
    private static void closeRetainedSealedOwner() throws Exception {
        var owner = new Owner();
        exporting = true;
        try {
            var snapshot = frame(List.of(new Visit(owner, List.of(new Quad()))), true, false);
            check(snapshot.batch != null, "Fixture retains a sealed unacknowledged batch");
        } finally {
            exporting = false;
        }
        var retained = owner.source.customOwner;
        List<?> scopes = List.copyOf((List<?>)value(retained.getClass(), "scopes", retained));
        check(!scopes.isEmpty(), "Live source owns captured payload before close");
        DynamicCapture.close();
        for (Object scope : scopes)
            check(value(scope.getClass(), "bytes", scope) == null &&
                          value(scope.getClass(), "prototype", scope) == null &&
                          value(scope.getClass(), "instance", scope) == null,
                  "Closing sealed renderer clears payload/handles retained by a live Source");
        check(((List<?>)value(retained.getClass(), "scopes", retained)).isEmpty() &&
                      ((List<?>)value(retained.getClass(), "emissions", retained)).isEmpty(),
              "Live Source owner retains no frame emission/cache after close");
    }

    private static void exportedProducts() throws Exception {
        DynamicCapture.close();
        products.clear();
        var owner = new Owner();
        var quad = new Quad();
        CAMERA.pos = Vec3.ZERO;
        exporting = true;
        try (var bridge =
                     new NativeBridge(Path.of(System.getProperty("primept.smoke.nativeLibrary")))) {
            bridge.reset(dev.primept.PrimeClient.CAPTURE.epoch());
            for (int i = 0; i < 3; ++i) {
                if (i > 0) {
                    quad.shift = .1f;
                    CAMERA.pos = new Vec3(.25, 0, 0);
                }
                quad.deformed = i == 2;
                var snapshot = frame(List.of(new Visit(owner, List.of(quad)),
                                             new Visit(null, List.of(new DistantQuad()))),
                                     true, false);
                // Recreate the synchronous borrow for actual native parsing; no GPU session is created.
                var context = context();
                var batch = snapshot.batch;
                if (batch != null) {
                    bridge.submitInstances(batch);
                    context.acknowledge();
                }
                bridge.submitDynamic(snapshot.dynamic);
                products.add(snapshot.product);
            }
        } finally {
            exporting = false;
        }
        String directory = System.getProperty("primept.smoke.namedOutput", "");
        if (!directory.isEmpty()) {
            Path destination = Path.of(directory);
            Files.createDirectories(destination);
            Files.writeString(destination.resolve("named-custom-" +
                                                  System.getProperty("primept.smoke.mcVersion") +
                                                  ".json"),
                              new GsonBuilder().setPrettyPrinting().create().toJson(products));
        }
    }

    private static Snapshot snapshot(List<Map<String, Object>> original, boolean acknowledge)
            throws Exception {
        var context = context();
        MemorySegment batch = context.sealDelta();
        Snapshot result = new Snapshot();
        result.batch = batch;
        result.original = List.copyOf(original);
        var stats = context.stats();
        result.prototypeUpserts = stats.prototypeUpserts();
        result.prototypeRemoves = stats.prototypeRemoves();
        result.instanceRemoves = stats.instanceRemoves();
        result.rawVertices = DynamicCapture.stats().vertices();
        var out = result.product;
        out.put("original", original);
        out.put("camera_forward", new float[] {0, 0, 1});
        out.put("camera_right", new float[] {1, 0, 0});
        out.put("camera_up", new float[] {0, 1, 0});
        out.put("camera_fov", .25f);
        out.put("width", 17);
        out.put("height", 9);
        var prototypes = new ArrayList<Map<String, Object>>();
        var instances = new ArrayList<Map<String, Object>>();
        if (batch != null) {
            out.put("epoch", PrimeInstanceBatch.epoch(batch));
            out.put("sequence", PrimeInstanceBatch.sequence(batch));
            for (int i = 0; i < PrimeInstanceBatch.prototype_count(batch); ++i) {
                var prototype = element(PrimeInstanceBatch.prototypes(batch),
                                        PrimeInstanceBatch.prototype_count(batch), i,
                                        PrimePrototypeSource.SIZE);
                var span = element(PrimePrototypeSource.spans(prototype),
                                   PrimePrototypeSource.count(prototype), 0, PrimeMeshSpan.SIZE);
                var mesh = mesh(span);
                mesh.put("id", PrimePrototypeSource.id(prototype));
                mesh.put("revision", PrimePrototypeSource.revision(prototype));
                prototypes.add(mesh);
                result.protoVertexCount += PrimeMeshSpan.vertex_count(span);
                var bytes = vertices(span).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                for (int vertex = 0; vertex < PrimeMeshSpan.vertex_count(span); ++vertex)
                    result.protoXs.add(bytes.getFloat(vertex * PrimeMeshSpan.stride(span) +
                                                      PrimeMeshSpan.position_offset(span)));
            }
            for (int i = 0; i < PrimeInstanceBatch.instance_count(batch); ++i) {
                var instance = element(PrimeInstanceBatch.instances(batch),
                                       PrimeInstanceBatch.instance_count(batch), i,
                                       PrimeInstanceSource.SIZE);
                var value = new LinkedHashMap<String, Object>();
                result.ids.add(PrimeInstanceSource.id(instance));
                value.put("id", PrimeInstanceSource.id(instance));
                value.put("revision", PrimeInstanceSource.revision(instance));
                value.put("prototype_id", PrimeInstanceSource.prototype_id(instance));
                double[] origin = new double[3];
                float[] affine = new float[12], uv = new float[4];
                for (int axis = 0; axis < 3; ++axis)
                    origin[axis] = PrimeInstanceSource.origin(instance, axis);
                for (int at = 0; at < 12; ++at)
                    affine[at] = PrimeInstanceSource.transform(instance, at);
                for (int at = 0; at < 4; ++at)
                    uv[at] = PrimeInstanceSource.uv_transform(instance, at);
                result.origin = origin;
                check(java.util.Arrays.equals(affine,
                                              new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}) &&
                              java.util.Arrays.equals(uv, new float[] {1, 1, 0, 0}) &&
                              PrimeInstanceSource.rgba(instance) == -1 &&
                              PrimeInstanceSource.texture_id(instance) == textureId &&
                              PrimeInstanceSource.flags(instance) == materialFlags,
                      "Original source rgba/uv retained under actual material and identity instance");
                value.put("origin", origin);
                value.put("transform", affine);
                value.put("uv_transform", uv);
                value.put("rgba", Integer.toUnsignedLong(PrimeInstanceSource.rgba(instance)));
                value.put("texture_id", PrimeInstanceSource.texture_id(instance));
                value.put("flags", PrimeInstanceSource.flags(instance));
                instances.add(value);
            }
            out.put("prototype_removals",
                    removals(PrimeInstanceBatch.prototype_removals(batch),
                             PrimeInstanceBatch.prototype_removal_count(batch)));
            out.put("instance_removals",
                    removals(PrimeInstanceBatch.instance_removals(batch),
                             PrimeInstanceBatch.instance_removal_count(batch)));
        }
        out.put("prototypes", prototypes);
        out.put("instances", instances);
        DynamicFrame raw = value(DynamicCapture.class, "frame", null);
        var dynamic = raw.seal();
        result.dynamic = dynamic;
        out.put("raw_epoch", PrimeDynamicBatch.epoch(dynamic));
        out.put("raw_sequence", PrimeDynamicBatch.sequence(dynamic));
        double[] rawOrigin = new double[3];
        for (int at = 0; at < 3; ++at)
            rawOrigin[at] = PrimeDynamicBatch.origin(dynamic, at);
        out.put("raw_origin", rawOrigin);
        var spans = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < PrimeDynamicBatch.count(dynamic); ++i)
            spans.add(mesh(element(PrimeDynamicBatch.spans(dynamic),
                                   PrimeDynamicBatch.count(dynamic), i, PrimeMeshSpan.SIZE)));
        out.put("raw", spans);
        if (batch != null && acknowledge)
            context.acknowledge();
        return result;
    }
    private static List<Map<String, Object>> removals(MemorySegment pointer, long count) {
        var values = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; ++i) {
            var value = element(pointer, count, i, PrimeRemoval.SIZE);
            values.add(
                    Map.of("id", PrimeRemoval.id(value), "revision", PrimeRemoval.revision(value)));
        }
        return values;
    }
    private static Map<String, Object> mesh(MemorySegment span) {
        var out = new LinkedHashMap<String, Object>();
        out.put("topology", PrimeMeshSpan.topology(span));
        out.put("vertex_count", PrimeMeshSpan.vertex_count(span));
        out.put("stride", PrimeMeshSpan.stride(span));
        out.put("position_offset", PrimeMeshSpan.position_offset(span));
        out.put("color_offset", PrimeMeshSpan.color_offset(span));
        out.put("uv_offset", PrimeMeshSpan.uv_offset(span));
        out.put("texture_id", PrimeMeshSpan.texture_id(span));
        out.put("flags", PrimeMeshSpan.flags(span));
        out.put("hex", HexFormat.of().formatHex(vertices(span).toArray(ValueLayout.JAVA_BYTE)));
        return out;
    }
    private static MemorySegment vertices(MemorySegment span) {
        var bytes = PrimeMeshSpan.vertices(span);
        return PrimeByteSpan.data(bytes).reinterpret(PrimeByteSpan.count(bytes));
    }
    private static MemorySegment element(MemorySegment pointer, long count, long index, long size) {
        return pointer.reinterpret(count * size).asSlice(index * size, size);
    }
    private static InstanceCapture context() throws Exception {
        return value(ModelCapture.class, "instances", null);
    }
    private record Visit(Owner owner, List<? extends Quad> callbacks) {}
    private static final class Snapshot {
        MemorySegment batch;
        MemorySegment dynamic;
        List<Map<String, Object>> original;
        final Map<String, Object> product = new LinkedHashMap<>();
        final ArrayList<Long> ids = new ArrayList<>();
        final ArrayList<Float> protoXs = new ArrayList<>();
        int rawVertices, prototypeUpserts, prototypeRemoves, instanceRemoves, protoVertexCount;
        double[] origin;
    }
    private static final class Owner implements ModelSourceOwner {
        ModelCapture.Source source;
        public ModelCapture.Source primept$modelSource() {
            return source;
        }
        public void primept$modelSource(ModelCapture.Source value) {
            source = value;
        }
    }
    private static class Quad implements SubmitNodeCollector.CustomGeometryRenderer {
        float shift;
        int vertices = 4;
        boolean badUv, deformed;
        RenderType type() {
            return TYPE;
        }
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            ++callbacks;
            for (int i = 0; i < vertices; ++i) {
                float x = (i == 0 || i == 3 ? -1 : 1) + shift - (float)CAMERA.pos.x;
                if (deformed && i == 2)
                    x += .2f;
                vertex(consumer, x, i < 2 ? -1 : 1, 3, i, badUv);
            }
        }
    }
    private static final class SecondQuad extends Quad {
        RenderType renderType = TYPE;
        RenderType type() {
            return renderType;
        }
    }
    private static final class ThirdQuad extends Quad {}
    private static final class WrappedQuad extends Quad {}
    private static final class OtherQuad extends Quad {
        RenderType type() {
            return OTHER;
        }
    }
    private static final class DistantQuad extends Quad {
        DistantQuad() {
            shift = 20;
        }
    }
    private static final class MixedQuad extends Quad {
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            ++callbacks;
            for (int i = 4; i < 16; ++i)
                vertex(consumer, i, 0, 3, i, false);
            DynamicCapture.exclude((BufferBuilder)consumer, 8, 12);
        }
    }
    private static final class FullHole extends Quad {
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            super.render(pose, consumer);
            DynamicCapture.exclude((BufferBuilder)consumer, 0, 4);
        }
    }
    private static final class FlushingQuad extends Quad {
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            super.render(pose, consumer);
            VertexConsumer other = current.getVertexBuilder(current.draw(OTHER));
            for (int i = 0; i < 4; ++i)
                vertex(other, i, 0, 3, i, false);
            VertexConsumer reopened = current.getVertexBuilder(current.draw(TYPE));
            for (int i = 0; i < 4; ++i)
                vertex(reopened, i, 0, 3, i, false);
        }
    }
    private static final class ThrowingQuad extends Quad {
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            ++callbacks;
            throw new IllegalStateException("actual source callback failed");
        }
    }
    private static void vertex(VertexConsumer consumer, float x, float y, float z, int index,
                               boolean badUv) {
        consumer.addVertex(x, y, z)
                .setColor(0xff804020 + index)
                .setUv(badUv ? Float.NaN : index * .125f, .25f)
                .setOverlay(0)
                .setLight(0xf000f0)
                .setNormal(0, 0, -1);
    }
    private static final class NoGpuStaged extends StagedVertexBuffer {
        final IdentityHashMap<RenderType, Draw> draws = new IdentityHashMap<>();
        final ArrayList<Map<String, Object>> original = new ArrayList<>();
        final IdentityHashMap<Draw, ArrayList<Chunk>> chunks = new IdentityHashMap<>();
        final IdentityHashMap<VertexConsumer, VertexConsumer> wrappers = new IdentityHashMap<>();
        Draw last;
        NoGpuStaged() {
            super(() -> "actual named Custom CPU fixture", 65536);
        }
        Draw draw(RenderType type) {
            return draws.computeIfAbsent(type, key -> {
                Draw draw = appendDraw(DefaultVertexFormat.ENTITY, key.primitiveTopology());
                if (bound)
                    try {
                        Map<Draw, DynamicCapture.Material> materials =
                                value(DynamicCapture.class, "DRAWS", null);
                        materials.put(draw, new DynamicCapture.Material(textureId, materialFlags,
                                                                        false, true));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                return draw;
            });
        }
        @Override
        public VertexConsumer getVertexBuilder(Draw draw) {
            if (last != null && last != draw)
                audit(last);
            var consumer = super.getVertexBuilder(draw);
            last = draw;
            return wrapped ? wrappers.computeIfAbsent(
                                     consumer,
                                     delegate
                                     -> (VertexConsumer)Proxy.newProxyInstance(
                                             VertexConsumer.class.getClassLoader(),
                                             new Class<?>[] {VertexConsumer.class},
                                             (proxy, method, arguments) -> {
                                                 Object value = method.invoke(delegate, arguments);
                                                 return value == delegate ? proxy : value;
                                             }))
                           : consumer;
        }
        private void audit(Draw draw) {
            try {
                Map<Draw, List<NamedRawCapture.Token>> named =
                        value(DynamicCapture.class, "NAMED", null);
                Map<Draw, DynamicCapture.ExcludedRanges> excluded =
                        value(DynamicCapture.class, "EXCLUDED", null);
                var tokens = named.get(draw);
                var ranges = excluded.get(draw);
                var mechanical = new ArrayList<int[]>();
                if (ranges != null)
                    for (int i = 0; i < ranges.size; i += 2)
                        mechanical.add(new int[] {ranges.ranges[i], ranges.ranges[i + 1]});
                chunks.computeIfAbsent(draw, ignored -> new ArrayList<>())
                        .add(new Chunk(tokens == null ? List.of() : List.copyOf(tokens),
                                       mechanical));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
        @Override
        public void upload() {
            try {
                if (last != null) {
                    audit(last);
                    last = null;
                }
                var finish = StagedVertexBuffer.class.getDeclaredMethod("finishLastVertexBuilder");
                finish.setAccessible(true);
                finish.invoke(this);
                for (Draw draw : draws.values()) {
                    List<ByteBufferBuilder.Result> slices =
                            value(Draw.class, "vertexBufferSlices", draw);
                    int chunkIndex = 0;
                    for (var slice : slices) {
                        var bytes = slice.byteBuffer();
                        var format = DefaultVertexFormat.ENTITY;
                        var out = new LinkedHashMap<String, Object>();
                        out.put("topology", 4);
                        out.put("stride", format.getVertexSize());
                        out.put("vertex_count", bytes.remaining() / format.getVertexSize());
                        out.put("position_offset", format.getElement("Position").offset());
                        out.put("color_offset", format.getElement("Color").offset());
                        out.put("uv_offset", format.getElement("UV0").offset());
                        out.put("texture_id", textureId);
                        out.put("flags", materialFlags);
                        Chunk chunk = chunks.get(draw).get(chunkIndex++);
                        out.put("mechanical_ranges", chunk.mechanical);
                        var routed = new ArrayList<int[]>();
                        long frame = value(NamedRawCapture.class, "frame", null);
                        for (var token : chunk.tokens) {
                            Object scope = value(NamedRawCapture.Token.class, "scope", token);
                            long seen = value(scope.getClass(), "seen", scope);
                            if (token.completed && !token.flushed && token.end > token.start &&
                                seen == frame)
                                routed.add(new int[] {token.start, token.end});
                        }
                        out.put("routed_ranges", routed);
                        byte[] copy = new byte[bytes.remaining()];
                        bytes.duplicate().get(copy);
                        out.put("hex", HexFormat.of().formatHex(copy));
                        original.add(out);
                    }
                }
                ((CaptureStagedBuffer)(Object)this).primept$drainCapturedSource();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
        private record Chunk(List<NamedRawCapture.Token> tokens, List<int[]> mechanical) {}
    }
    @SuppressWarnings("unchecked")
    private static <T> T value(Class<?> type, String name, Object target) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (T)field.get(target);
    }
    private static void set(Class<?> type, String name, Object target, Object value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
