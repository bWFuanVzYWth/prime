package dev.primept.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/** Executes the actual Fabric-overwritten wrapper and cached/original submit paths, without graphics. */
final class GeometryCacheCpuSmoke {
    static void run() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Renderer.register(IndigoRenderer.INSTANCE);
        check(BlockGeometryCapabilities.supported(), "Known transformed wrapper/state capability");
        BlockGeometryCache.resourceReload();
        BlockGeometryCache.environment(false);
        var state = new BlockModelRenderState();
        var source = new Source(new Key(1), .25f);
        int[] tint = {0xff112233}, calls = {0};
        var transform = new Matrix4f().translation(3, 4, 5);
        var wrapper = new BlockStateModelWrapper(source, List.of(block -> {
            ++calls[0];
            return tint[0];
        }),
                                                 transform);
        var first = update(wrapper, state);
        check(first.mesh != null && first.mesh.size() == 1 && source.emits == 1,
              "First real emit creates immutable geometry");
        tint[0] = 0xff998877;
        transform.translate(1, 0, 0);
        var second = update(wrapper, state);
        check(second.mesh == first.mesh && source.emits == 1 && calls[0] == 2,
              "Hit keeps actual tint callback, no emit/copy");
        check(second.tints[0] == tint[0] && second.pose.m30() == 4,
              "Per-submit tint and transform remain live");
        check(second.light == 0x400020 && second.overlay == 13 && second.outline == 17,
              "Actual light/overlay/outline forwarded");
        check(source.flags == 2, "Material flags observed on every call");
        var equal = new Source(new Key(1), .25f);
        check(update(new BlockStateModelWrapper(equal, List.of(), new Matrix4f()), state).mesh ==
                              first.mesh &&
                      equal.emits == 0,
              "Equal keys from distinct models share source geometry");
        var collision = new Source(new Key(2), .75f);
        var different =
                update(new BlockStateModelWrapper(collision, List.of(), new Matrix4f()), state);
        check(different.mesh != first.mesh && collision.emits == 1,
              "Hash collision does not alias geometry");
        var unknown = new Source(null, .5f);
        var nullWrapper = new BlockStateModelWrapper(unknown, List.of(), new Matrix4f());
        check(update(nullWrapper, state).mesh != null, "Null key uses real Fabric mesh submit");
        update(nullWrapper, state);
        check(unknown.emits == 2, "Unknown key is never memoized");
        update(wrapper, state);
        var emitter = state.setupMesh(new Matrix4f(), false);
        quad(emitter, .9f);
        var mutable = submit(state);
        check(mutable.mesh != first.mesh && x(mutable.mesh) == .9f,
              "setupMesh replaces cached state with actual mutable source");
        update(wrapper, state);
        state.setupModel(new Matrix4f(), false);
        check(submit(state).mesh == null, "setupModel clears cached geometry");
        state.clear();
        check(state.isEmpty(), "clear retires borrowed geometry");
        var cachedAgain = update(wrapper, state).mesh;
        BlockGeometryCache.environment(true);
        check(update(wrapper, state).mesh != cachedAgain,
              "Leaves option changes resource geometry epoch");
        cachedAgain = update(wrapper, state).mesh;
        BlockGeometryCache.resourceReload();
        check(update(wrapper, state).mesh != cachedAgain,
              "Resource upload invalidation does not retain old mesh");
        var randomized = new Source(new Key(123), 0) {
            @Override
            public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos,
                                            BlockState block, RandomSource random) {
                expectedRandom = random.nextInt();
                return new Key(expectedRandom);
            }
            @Override
            public void emitQuads(QuadEmitter output, BlockAndTintGetter level, BlockPos pos,
                                  BlockState block, RandomSource random,
                                  Predicate<Direction> cull) {
                check(random.nextInt() == expectedRandom,
                      "Key and emission see identical initial random state");
                super.emitQuads(output, level, pos, block, random, cull);
            }
        };
        var randomizedWrapper = new BlockStateModelWrapper(randomized, List.of(), new Matrix4f());
        var randomMesh = update(randomizedWrapper, state).mesh;
        check(update(randomizedWrapper, state).mesh == randomMesh && randomized.emits == 1,
              "Random-key hit uses same selected geometry");
        var subclassSource = new Source(new Key(999), .6f);
        var customWrapper =
                new BlockStateModelWrapper(subclassSource, List.of(), new Matrix4f()) {};
        update(customWrapper, state);
        update(customWrapper, state);
        check(subclassSource.emits == 2,
              "Unknown wrapper subclass preserves original implementation");
        int[] setupCalls = {0};
        var customOutput = new BlockModelRenderState() {
            @Override
            public QuadEmitter setupMesh(org.joml.Matrix4fc matrix, boolean translucent) {
                ++setupCalls[0];
                return super.setupMesh(matrix, translucent);
            }
        };
        update(wrapper, customOutput);
        update(wrapper, customOutput);
        check(setupCalls[0] == 2, "Unknown output subclass retains actual setupMesh callback");
        // One reused state switches in both directions; the actual collector must see only the new source.
        var cached = update(wrapper, state).mesh;
        check(update(nullWrapper, state).mesh != cached && x(submit(state).mesh) == .5f,
              "Cached to null-key output has no old geometry");
        check(update(wrapper, state).mesh == cached,
              "Mutable to cached output has no old Fabric mesh");
        for (int i = 0; i < 4100; ++i)
            update(new BlockStateModelWrapper(new Source(new Key(20_000 + i), 0), List.of(),
                                              new Matrix4f()),
                   state);
        check(BlockGeometryCache.stats().entries() <= 4096,
              "Resource cache has bounded entry count");
        var borrowed = update(wrapper, state).mesh;
        BlockGeometryCache.releaseToVanilla();
        check(BlockGeometryCache.stats().entries() == 0 &&
                      BlockGeometryCache.stats().retainedBytes() == 0,
              "Retiring PT releases its entire geometry lookup ownership");
        check(submit(state).mesh == borrowed,
              "A host state keeps its already evaluated source until its own release");
        System.out.println(
                "PRIME_PT_GEOMETRY_CACHE_CPU_OK: transformed wrapper/state, equal keys, collision, null fallback, random, live tint/pose/material, mutable transitions, reload/options, bounded eviction " +
                BlockGeometryCache.stats());
        BlockGeometryCache.resourceReload();
    }
    static void foreign() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Renderer.register(IndigoRenderer.INSTANCE);
        check(!BlockGeometryCapabilities.supported(),
              "Foreign wrapper injection disables only this optimization");
        var source = new Source(new Key(1), .5f);
        var wrapper = new BlockStateModelWrapper(source, List.of(), new Matrix4f());
        var state = new BlockModelRenderState();
        update(wrapper, state);
        update(wrapper, state);
        check(source.emits == 2 && BlockGeometryCache.stats().hits() == 0,
              "Foreign implementation uses actual uncached output");
        check(ForeignHookProbe.calls == 2, "Foreign callback executes exactly once per update");
        System.out.println(
                "PRIME_PT_GEOMETRY_FOREIGN_CPU_OK: real foreign Mixin callback retained, uncached actual Fabric output");
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
    private static Captured update(BlockStateModelWrapper wrapper, BlockModelRenderState output) {
        output.clear();
        wrapper.update(output, Blocks.STONE.defaultBlockState(), BlockDisplayContext.create(), 42L);
        return submit(output);
    }
    private static Captured submit(BlockModelRenderState output) {
        var captured = new Captured();
        SubmitNodeCollector collector = (SubmitNodeCollector)Proxy.newProxyInstance(
                GeometryCacheCpuSmoke.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submitBlockModel") && args.length == 9) {
                        check(captured.mesh == null, "No duplicated cached/vanilla draw");
                        captured.pose = new Matrix4f(((PoseStack)args[0]).last().pose());
                        captured.mesh = (Mesh)args[4];
                        captured.tints = (int[])args[5];
                        captured.light = (int)args[6];
                        captured.overlay = (int)args[7];
                        captured.outline = (int)args[8];
                    }
                    return null;
                });
        output.submitWithZOffset(new PoseStack(), collector, 0x400020, 13, 17);
        return captured;
    }
    private static float x(Mesh mesh) {
        float[] x = {Float.NaN};
        mesh.forEach(q -> x[0] = q.x(0));
        return x[0];
    }
    private static void quad(QuadEmitter emitter, float x) {
        emitter.pos(0, x, 0, 0).pos(1, x + 1, 0, 0).pos(2, x + 1, 1, 0).pos(3, x, 1, 0);
        for (int i = 0; i < 4; ++i)
            emitter.uv(i, i & 1, i >> 1).color(i, -1);
        emitter.tintIndex(0).emit();
    }
    private record Key(int value) {
        @Override
        public int hashCode() {
            return 7;
        }
    }
    private static class Source implements BlockStateModel {
        final Object key;
        final float x;
        int emits, flags, expectedRandom;
        Source(Object key, float x) {
            this.key = key;
            this.x = x;
        }
        @Override
        public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                        RandomSource random) {
            return key;
        }
        @Override
        public void emitQuads(QuadEmitter output, BlockAndTintGetter level, BlockPos pos,
                              BlockState state, RandomSource random, Predicate<Direction> cull) {
            ++emits;
            quad(output, x);
        }
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
            throw new AssertionError("Must use FRAPI source");
        }
        @Override
        public Material.Baked particleMaterial() {
            return null;
        }
        @Override
        public int materialFlags() {
            ++flags;
            return 0;
        }
    }
    private static final class Captured {
        Mesh mesh;
        Matrix4f pose;
        int[] tints;
        int light, overlay, outline;
    }
}
