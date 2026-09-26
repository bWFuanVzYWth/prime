package dev.primept.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Exercises the actual transformed dispatcher/predicate methods without a window, world tick or GPU. */
final class BlockEntityCandidatesCpuSmoke {
    static void run() throws Exception {
        var dispatcher = new BlockEntityRenderDispatcher(null, null, null, null, null, null, null);
        var camera = new Vec3(0.5, 0.5, 0.5);
        dispatcher.prepare(camera);
        var gate = new BlockEntityCandidates();
        var standard = new StandardRenderer();
        install(dispatcher, standard);
        gate.prepare(dispatcher, 1);

        var near = chest(new BlockPos(63, 0, 0));
        var far = chest(new BlockPos(64, 0, 0));
        var corner = chest(new BlockPos(50, 50, 0));
        check(gate.outsideDefaultRange(far, camera),
              "Default block-center 64 boundary rejected: " + gateState(gate));
        check(gate.outsideDefaultRange(chest(new BlockPos(-64, 0, 0)), camera),
              "Negative boundary rejected");
        check(gate.outsideDefaultRange(chest(new BlockPos(0, 0, 65)), camera),
              "Z broad phase rejected");
        check(!gate.outsideDefaultRange(near, camera), "Near center retained");
        check(!gate.outsideDefaultRange(corner, camera),
              "Cube survivor remains subject to real spherical predicate");
        check(standard.calls == 0, "Broad phase never replays extraction callbacks");
        check(dispatcher.tryExtractRenderState(far, 0.25f, null, false) == null,
              "Real dispatcher agrees at boundary");
        check(dispatcher.tryExtractRenderState(corner, 0.25f, null, false) == null,
              "Real spherical predicate remains effective");
        var overlay = new CrumblingOverlay(4, new PoseStack().last());
        var state = dispatcher.tryExtractRenderState(near, 0.25f, overlay, false);
        check(state != null && state.breakProgress == overlay && standard.calls == 1,
              "Survivor executes actual callback once with unchanged overlay");
        check(((SourceIdentity)state).primept$source() == near,
              "Production dispatcher source identity hook ran");

        // A real vanilla renderer inherits precisely the same default mechanical predicates.
        install(dispatcher, blank(ChestRenderer.class));
        gate.prepare(dispatcher, 2);
        check(gate.outsideDefaultRange(far, camera),
              "Actual vanilla ChestRenderer admitted by production gate");
        check(dispatcher.tryExtractRenderState(far, 0, null, false) == null,
              "Actual vanilla far predicate returns before renderer model dependencies");

        var extended = new ExtendedRenderer();
        install(dispatcher, extended);
        gate.prepare(dispatcher, 3);
        var farther = chest(new BlockPos(96, 0, 0));
        check(!gate.outsideDefaultRange(farther, camera),
              "Custom view distance is not clipped to 64");
        check(extended.distanceCalls == 0, "Gate did not call custom distance accessor");
        check(dispatcher.tryExtractRenderState(farther, 0, null, false) != null &&
                      extended.distanceCalls == 1 && extended.calls == 1,
              "Custom 128 range executes its real accessor/extraction once");

        var always = new AlwaysRenderer();
        install(dispatcher, always);
        gate.prepare(dispatcher, 4);
        var remote = chest(new BlockPos(10000, 0, 0));
        check(!gate.outsideDefaultRange(remote, camera) && always.predicates == 0,
              "Unknown visibility callback stays untouched");
        check(dispatcher.tryExtractRenderState(remote, 0, null, false) != null &&
                      always.predicates == 1,
              "Always-visible override remains visible beyond the default range");

        var global = new GlobalRenderer();
        install(dispatcher, global);
        gate.prepare(dispatcher, 5);
        check(!gate.outsideDefaultRange(far, camera) && global.predicates == 0,
              "Global override is a full fallback");
        check(dispatcher.tryExtractRenderState(near, 0, overlay, false) == null &&
                      global.predicates == 1,
              "Actual local/global exclusion preserved");
        check(dispatcher.tryExtractRenderState(near, 0, null, true) != null &&
                      global.predicates == 2,
              "Actual global path remains separate");

        install(dispatcher, standard);
        gate.prepare(dispatcher, 6);
        var changingGetter = new GetterChest(new BlockPos(96, 0, 0));
        changingGetter.reads = 0;
        check(!gate.outsideDefaultRange(changingGetter, camera) && changingGetter.reads == 0,
              "Overridden source getter rejects capability without invoking it");
        changingGetter.setLevel(blank(ClientLevel.class));
        dispatcher.tryExtractRenderState(changingGetter, 0, null, false);
        check(changingGetter.reads == 1, "Only actual dispatcher invokes custom source getter");

        var customDispatcher = new CountingDispatcher();
        customDispatcher.prepare(camera);
        install(customDispatcher, standard);
        gate.prepare(customDispatcher, 6);
        check(!gate.outsideDefaultRange(far, camera) && customDispatcher.calls == 0,
              "Unknown dispatcher fallback without callback replay");
        customDispatcher.tryExtractRenderState(far, 0, null, false);
        check(customDispatcher.calls == 1, "Actual custom dispatcher is still called once");

        gate.prepare(dispatcher, 7);
        check(gate.outsideDefaultRange(far, camera),
              "New dispatcher identity resets prior unsupported gate");
        install(dispatcher, extended);
        gate.prepare(dispatcher, 8);
        check(!gate.outsideDefaultRange(farther, camera),
              "Resource epoch invalidates formerly default type eligibility");
        gate.clear();
        check(!gate.outsideDefaultRange(far, camera),
              "Renderer retirement clears eligibility and owner references");
        System.out.println(
                "PRIME_PT_BE_CANDIDATES_CPU_OK: actual dispatcher and vanilla ChestRenderer; strict boundary/cube survivors; custom distance/visibility/global/getter/dispatcher fallback; callback and overlay identity; resource/owner reset; no GPU");
    }

    private static ChestBlockEntity chest(BlockPos pos) throws Exception {
        var entity = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        entity.setLevel(blank(ClientLevel.class));
        return entity;
    }
    private static void install(BlockEntityRenderDispatcher dispatcher,
                                BlockEntityRenderer<?, ?> renderer) throws Exception {
        field(BlockEntityRenderDispatcher.class, "renderers")
                .set(dispatcher, Map.of(BlockEntityTypes.CHEST, renderer));
    }
    private static class StandardRenderer
            implements BlockEntityRenderer<BlockEntity, BlockEntityRenderState> {
        int calls;
        @Override
        public BlockEntityRenderState createRenderState() {
            return new BlockEntityRenderState();
        }
        @Override
        public void extractRenderState(BlockEntity source, BlockEntityRenderState state,
                                       float partial, Vec3 camera, CrumblingOverlay overlay) {
            ++calls;
            state.blockPos = source.getBlockPos();
            state.breakProgress = overlay;
        }
        @Override
        public void submit(BlockEntityRenderState state, PoseStack pose,
                           SubmitNodeCollector collector, CameraRenderState camera) {
            throw new AssertionError("CPU extraction must not submit rendering");
        }
    }
    private static final class ExtendedRenderer extends StandardRenderer {
        int distanceCalls;
        @Override
        public int getViewDistance() {
            ++distanceCalls;
            return 128;
        }
    }
    private static final class AlwaysRenderer extends StandardRenderer {
        int predicates;
        @Override
        public boolean shouldRender(BlockEntity source, Vec3 camera) {
            ++predicates;
            return true;
        }
    }
    private static final class GlobalRenderer extends StandardRenderer {
        int predicates;
        @Override
        public boolean shouldRenderOffScreen() {
            ++predicates;
            return true;
        }
    }
    private static final class GetterChest extends ChestBlockEntity {
        int reads;
        GetterChest(BlockPos pos) {
            super(pos, Blocks.CHEST.defaultBlockState());
        }
        @Override
        public net.minecraft.world.level.block.state.BlockState getBlockState() {
            ++reads;
            return super.getBlockState();
        }
    }
    private static final class CountingDispatcher extends BlockEntityRenderDispatcher {
        int calls;
        CountingDispatcher() {
            super(null, null, null, null, null, null, null);
        }
        @Override
        public <E extends BlockEntity, S extends BlockEntityRenderState> S
        tryExtractRenderState(E entity, float partial, CrumblingOverlay overlay, boolean global) {
            ++calls;
            return super.tryExtractRenderState(entity, partial, overlay, global);
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static String gateState(BlockEntityCandidates gate) throws Exception {
        var result = new StringBuilder();
        for (String name : new String[] {"supported", "entityClasses", "rendererClasses", "types"})
            result.append(name)
                    .append('=')
                    .append(field(BlockEntityCandidates.class, name).get(gate))
                    .append(';');
        for (String name :
             new String[] {"getBlockPos", "getBlockState", "getType", "hasLevel", "isRemoved"})
            result.append(name)
                    .append('@')
                    .append(ChestBlockEntity.class.getMethod(name)
                                    .getDeclaringClass()
                                    .getSimpleName())
                    .append(';');
        for (Class<?> next = ChestBlockEntity.class.getSuperclass();
             next != null && next != BlockEntity.class; next = next.getSuperclass())
            for (var method : next.getDeclaredMethods())
                for (var annotation : method.getDeclaredAnnotations())
                    if (annotation.annotationType().getName().endsWith("MixinMerged"))
                        result.append(next.getSimpleName())
                                .append('.')
                                .append(method.getName())
                                .append(':')
                                .append(annotation)
                                .append(';');
        for (Class<?> type :
             new Class<?>[] {BlockEntity.class,
                             net.minecraft.world.level.block.entity.BlockEntityType.class,
                             BlockEntityRenderDispatcher.class, BlockEntityRenderer.class,
                             ChestBlockEntity.class})
            for (var method : type.getDeclaredMethods())
                for (var annotation : method.getDeclaredAnnotations())
                    if (annotation.annotationType().getName().endsWith("MixinMerged"))
                        result.append(type.getSimpleName())
                                .append('.')
                                .append(method.getName())
                                .append(':')
                                .append(annotation)
                                .append(';');
        return result.toString();
    }
    private static <T> T blank(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object allocator = field(unsafe, "theUnsafe").get(null);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(allocator, type));
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
