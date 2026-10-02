package dev.primept.capture;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.lang.foreign.MemorySegment;
import dev.primept.abi.PrimeAbi.*;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Epoch-scoped source dictionaries. No shape joins, tessellation or world queries. */
final class SectionResources {
    private static final Field FACES =
            field(BlockBehaviour.BlockStateBase.class, "occlusionShapesByFace");
    private static final Field CACHE = field(BlockBehaviour.BlockStateBase.class, "cache"),
                               STURDY = field(CACHE.getType(), "faceSturdy");
    private static final Field VOXELS = field(VoxelShape.class, "shape");
    private final IdentityHashMap<VoxelShape, int[]> faces = new IdentityHashMap<>();
    private final IdentityHashMap<FluidModel, Integer> fluids = new IdentityHashMap<>();
    private final FluidStateModelSet fluidModels;
    private final SourceSprites sprites;
    private int nextFace = 2;
    SectionResources(FluidStateModelSet fluidModels, SourceSprites sprites) {
        this.sprites = sprites;
        this.fluidModels = fluidModels;
    }
    record StateSource(int[] faces, int support, String fluid, int level, boolean falling,
                       int material, int emission) {
        void write(McSourceBatch out, MemorySegment value) {
            for (int i = 0; i < 6; i++)
                PrimeMcState.faces(value, i, faces[i]);
            PrimeMcState.support(value, support);
            out.text(fluid).write(PrimeMcState.fluid_name(value));
            PrimeMcState.fluid_level(value, level);
            PrimeMcState.fluid_falling(value, falling ? 1 : 0);
            PrimeMcState.fluid_material(value, material);
            PrimeMcState.emission(value, emission);
        }
    }
    StateSource prepare(McSourceBatch out, BlockState state) {
        var shapes = (VoxelShape[])get(FACES, state);
        int[] ids = new int[6];
        for (var face : Direction.values())
            ids[face.ordinal()] = face(out, shapes[face.ordinal()], face.getAxis());
        var fluid = state.getFluidState();
        int material = fluid.isEmpty() ? 0 : fluid(out, fluidModels.get(fluid));
        Object cache = get(CACHE, state);
        int support = 0x80000000;
        if (cache != null) {
            support = 0;
            boolean[] raw = (boolean[])get(STURDY, cache);
            for (int i = 0; i < raw.length; ++i)
                if (raw[i])
                    support |= 1 << i;
        }
        return new StateSource(
                ids, support, BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),
                fluid.hasProperty(FlowingFluid.LEVEL) ? fluid.getValue(FlowingFluid.LEVEL) : 0,
                fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING),
                material, state.getLightEmission());
    }
    static int flags(BlockState state) {
        return (state.isSolid() ? 32 : 0) |
                ((state.getBlock() instanceof HalfTransparentBlock || state.getBlock() instanceof
                                                                              LeavesBlock)
                         ? 64
                         : 0) |
                (state.getBlock() instanceof net.minecraft.world.level.block.IceBlock ? 128 : 0) |
                ((state.getBlock() == net.minecraft.world.level.block.Blocks.GLASS ||
                  state.getBlock() instanceof net.minecraft.world.level.block.StainedGlassBlock ||
                  state.getBlock() instanceof net.minecraft.world.level.block.TintedGlassBlock)
                         ? 256
                         : 0) |
                ((state.getBlock() == net.minecraft.world.level.block.Blocks.GLASS_PANE ||
                  state.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock)
                         ? 512
                         : 0) |
                (state.getBlock() instanceof LeavesBlock ? 1024 : 0);
    }
    private int fluid(McSourceBatch out, FluidModel model) {
        Integer known = fluids.get(model);
        if (known != null)
            return known;
        int id = fluids.size() + 1;
        fluids.put(model, id);
        sprites.prepare(out, model.stillMaterial().sprite());
        sprites.prepare(out, model.flowingMaterial().sprite());
        if (model.overlayMaterial() != null)
            sprites.prepare(out, model.overlayMaterial().sprite());
        var value = out.fluids.add();
        PrimeMcFluid.id(value, id);
        PrimeMcFluid.layer(value, model.layer().ordinal());
        PrimeMcFluid.flags(value, (model.tintSource() != null ? 1 : 0) |
                                          (model.overlayMaterial() != null ? 2 : 0));
        int index = 0;
        for (var material : new net.minecraft.client.resources.model.sprite.Material.Baked[] {
                     model.stillMaterial(), model.flowingMaterial(),
                     model.overlayMaterial() != null ? model.overlayMaterial()
                                                     : model.flowingMaterial()}) {
            var s = material.sprite();
            PrimeMcFluid.identities(value, index, sprites.prepare(out, s));
            float[] bounds = {s.getU0(), s.getV0(), s.getU1(), s.getV1()};
            for (int i = 0; i < 4; i++)
                PrimeMcFluid.bounds(value, index * 4 + i, bounds[i]);
            index++;
        }
        return id;
    }
    private int face(McSourceBatch out, VoxelShape shape, Direction.Axis normal) {
        if (shape == Shapes.empty())
            return 0;
        if (shape == Shapes.block())
            return 1;
        int[] ids = faces.computeIfAbsent(shape, k -> new int[3]);
        if (ids[normal.ordinal()] != 0)
            return ids[normal.ordinal()];
        int id = nextFace++;
        ids[normal.ordinal()] = id;
        var axes = Direction.Axis.values();
        int u = normal == Direction.Axis.X ? 2 : 0, v = normal == Direction.Axis.Y ? 2 : 1;
        var us = shape.getCoords(axes[u]);
        var vs = shape.getCoords(axes[v]);
        var grid = (DiscreteVoxelShape)get(VOXELS, shape);
        int nu = us.size() - 1, nv = vs.size() - 1;
        var bits = new BitSet(nu * nv);
        int[] xyz = new int[3];
        // Read the already-cached face's occupancy; merging and coverage belong to Rust.
        for (int a = 0; a < nu; ++a)
            for (int b = 0; b < nv; ++b) {
                xyz[u] = a;
                xyz[v] = b;
                if (grid.isFull(xyz[0], xyz[1], xyz[2]))
                    bits.set(a * nv + b);
            }
        long[] words = bits.toLongArray();
        long firstU = out.coordinates.count();
        for (double c : us)
            out.coordinates.add().set(JAVA_DOUBLE, 0, c);
        long firstV = out.coordinates.count();
        for (double c : vs)
            out.coordinates.add().set(JAVA_DOUBLE, 0, c);
        var wordRange = out.words.longs(words);
        var value = out.faces.add();
        PrimeMcFace.id(value, id);
        new McSourceBatch.Range(firstU, us.size()).write(PrimeMcFace.u(value));
        new McSourceBatch.Range(firstV, vs.size()).write(PrimeMcFace.v(value));
        wordRange.write(PrimeMcFace.words(value));
        return id;
    }
    private static Field field(Class<?> type, String name) {
        try {
            var f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private static Object get(Field f, Object owner) {
        try {
            return f.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
