package dev.primept.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import dev.primept.abi.PrimeAbi.*;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.BitStorage;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Source dictionaries and necessary state-bound selection. Positional work belongs to Rust. */
final class SectionSources {
    static final int GAME_VERSION = 262;
    static final Class<?> BED_SEED_DECLARATION = net.minecraft.world.level.block.BedBlock.class;
    private static final Field DATA = field(PalettedContainer.class, "data");
    private static final Field STORAGE = field(DATA.getType(), "storage"),
                               PALETTE = field(DATA.getType(), "palette");
    private static final Field MODELS = field(BlockStateModelSet.class, "modelByState");
    private static final Field SINGLE = field(SingleVariant.class, "model"),
                               WEIGHTED = field(WeightedVariants.class, "list");
    private static final Field SHARED = field(MultiPartModel.class, "shared"),
                               SELECTED = field(MultiPartModel.class, "models"),
                               BLOCK_STATE = field(MultiPartModel.class, "blockState");
    private static final Method SELECT = method(SHARED.getType(), "selectModels", BlockState.class);
    private static final Field SOLID = field(BlockBehaviour.BlockStateBase.class, "solidRender");
    private static final Field[] QUADS = {
            field(QuadCollection.class, "down"),    field(QuadCollection.class, "up"),
            field(QuadCollection.class, "north"),   field(QuadCollection.class, "south"),
            field(QuadCollection.class, "west"),    field(QuadCollection.class, "east"),
            field(QuadCollection.class, "unculled")};
    private final Map<BlockState, BlockStateModel> models;
    private final SourceSprites sprites = new SourceSprites();
    private final SectionResources resources;
    private final SectionPlacementSources placements =
            new SectionPlacementSources(BED_SEED_DECLARATION);
    private final BitSet states = new BitSet();
    private final IdentityHashMap<Object, Integer> definitions = new IdentityHashMap<>();
    private int nextModel;
    private boolean globalPublished;

    @SuppressWarnings("unchecked")
    SectionSources(BlockStateModelSet modelSet, FluidStateModelSet fluidModels) {
        models = (Map<BlockState, BlockStateModel>)get(MODELS, modelSet);
        resources = new SectionResources(fluidModels, sprites);
    }
    /** Immutable ready definitions only; unresolved multipart selection waits for actual demand. */
    void prepareResources(McSourceBatch out) {
        sprites.prepareAtlas(out);
        for (BlockState state : Block.BLOCK_STATE_REGISTRY)
            if (ready(models.get(state), new IdentityHashMap<>()))
                state(out, state);
    }
    @SuppressWarnings("unchecked")
    private boolean ready(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || definitions.containsKey(value))
            return true;
        if (visiting.put(value, true) != null)
            return false;
        if (value.getClass() == SingleVariant.class) {
            boolean result = ready(get(SINGLE, value), visiting);
            visiting.remove(value);
            return result;
        }
        if (value.getClass() == WeightedVariants.class) {
            for (var entry : ((WeightedList<BlockStateModel>)get(WEIGHTED, value)).unwrap())
                if (!ready(entry.value(), visiting))
                    return false;
        } else if (value.getClass() == MultiPartModel.class) {
            var selected = (List<BlockStateModel>)get(SELECTED, value);
            if (selected == null)
                return false;
            for (var child : selected)
                if (!ready(child, visiting))
                    return false;
        }
        visiting.remove(value);
        return true;
    }
    @SuppressWarnings("unchecked")
    void section(McSourceBatch out, McSourceBatch sections, int x, int y, int z,
                 LevelChunkSection section) {
        if (section == null) {
            sections.section(x, y, z, 0, new int[0], new long[0], false);
            return;
        }
        var container = section.getStates();
        container.acquire();
        try {
            Object data = get(DATA, container);
            var palette = (Palette<BlockState>)get(PALETTE, data);
            var storage = (BitStorage)get(STORAGE, data);
            boolean global = palette instanceof GlobalPalette<?>;
            if (global && !globalPublished) {
                // A global palette stores registry IDs directly. Forward its resource dictionary once.
                for (BlockState state : Block.BLOCK_STATE_REGISTRY)
                    state(out, state);
                globalPublished = true;
            } else if (!global) {
                for (int i = 0; i < palette.getSize(); ++i)
                    state(out, palette.valueFor(i));
            }
            int[] ids = new int[global ? 0 : palette.getSize()];
            if (!global)
                for (int i = 0; i < palette.getSize(); ++i)
                    ids[i] = Block.getId(palette.valueFor(i));
            sections.section(x, y, z, storage.getBits(), ids, storage.getRaw(), true);
        } finally {
            container.release();
        }
    }
    private void state(McSourceBatch out, BlockState state) {
        int id = Block.getId(state);
        if (states.get(id))
            return;
        states.set(id);
        int model = model(out, models.get(state));
        var source = resources.prepare(out, state);
        var placement = placements.prepare(state);
        int flags = (state.isAir() ? 1 : 0) | (placement.hasOffset() ? 2 : 0) |
                    ((boolean)get(SOLID, state) ? 4 : 0) |
                    (state.getRenderShape() != RenderShape.MODEL ? 16 : 0) |
                    SectionResources.flags(state);
        var name = out.text(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var value = out.states.add();
        PrimeMcState.id(value, id);
        PrimeMcState.flags(value, flags);
        PrimeMcState.model(value, model);
        name.write(PrimeMcState.name(value));
        source.write(out, value);
        placement.write(PrimeMcState.placement(value));
    }
    @SuppressWarnings("unchecked")
    private int model(McSourceBatch out, Object value) {
        if (value == null)
            return 0;
        Integer known = definitions.get(value);
        if (known != null)
            return known;
        int id = Math.incrementExact(nextModel);
        nextModel = id;
        definitions.put(value, id);
        int kind = 0, alias = 0;
        long childFirst = 0, childCount = 0, quadFirst = 0, quadCount = 0;
        if (value.getClass() == SingleVariant.class) {
            alias = model(out, get(SINGLE, value));
            kind = 4;
        } else if (value.getClass() == WeightedVariants.class) {
            var entries = ((WeightedList<BlockStateModel>)get(WEIGHTED, value)).unwrap();
            int[] children = new int[entries.size()];
            for (int i = 0; i < children.length; ++i)
                children[i] = model(out, entries.get(i).value());
            kind = 2;
            childFirst = out.children.count();
            childCount = children.length;
            for (int i = 0; i < children.length; ++i) {
                var child = out.children.add();
                PrimeMcModelChild.weight(child, entries.get(i).weight());
                PrimeMcModelChild.model(child, children[i]);
            }
        } else if (value.getClass() == MultiPartModel.class) {
            var selected = (List<BlockStateModel>)get(SELECTED, value);
            if (selected == null) {
                // Explicit resource preparation: evaluate each state-bound predicate once, in host
                // order, and retain its actual result in the host cache too. No positional replay.
                try {
                    selected = (List<BlockStateModel>)SELECT.invoke(get(SHARED, value),
                                                                    get(BLOCK_STATE, value));
                    SELECTED.set(value, selected);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Multipart source selection failed", failure);
                }
            }
            int[] children = new int[selected.size()];
            for (int i = 0; i < children.length; ++i)
                children[i] = model(out, selected.get(i));
            kind = 3;
            childFirst = out.children.count();
            childCount = children.length;
            for (int child : children) {
                var item = out.children.add();
                PrimeMcModelChild.weight(item, 1);
                PrimeMcModelChild.model(item, child);
            }
        } else if (value instanceof SimpleModelWrapper simple) {
            var groups = new java.util.ArrayList<List<BakedQuad>>(7);
            int size = 0;
            for (Field face : QUADS) {
                var quads = (List<BakedQuad>)get(face, simple.quads());
                groups.add(quads);
                size = Math.addExact(size, quads.size());
            }
            for (var group : groups)
                for (var quad : group)
                    sprites.prepare(out, quad.materialInfo().sprite());
            kind = 1;
            quadFirst = out.quads.count();
            quadCount = size;
            for (int face = 0; face < 7; ++face)
                for (BakedQuad quad : groups.get(face)) {
                    var material = quad.materialInfo();
                    var item = out.quads.add();
                    PrimeMcQuad.face(item, face);
                    PrimeMcQuad.tint(item, material.tintIndex());
                    PrimeMcQuad.layer(item, material.layer().ordinal());
                    PrimeMcQuad.sprite(item, sprites.prepare(out, material.sprite()));
                    PrimeMcQuad.emission(item, material.lightEmission());
                    for (int i = 0; i < 4; ++i) {
                        var p = quad.position(i);
                        PrimeMcQuad.positions(item, i * 3, p.x());
                        PrimeMcQuad.positions(item, i * 3 + 1, p.y());
                        PrimeMcQuad.positions(item, i * 3 + 2, p.z());
                        PrimeMcQuad.uv_pairs(item, i, quad.packedUV(i));
                    }
                }
        }
        var record = out.models.add();
        PrimeMcModel.id(record, id);
        PrimeMcModel.kind(record, kind);
        PrimeMcModel.alias(record, alias);
        new McSourceBatch.Range(childFirst, childCount).write(PrimeMcModel.children(record));
        new McSourceBatch.Range(quadFirst, quadCount).write(PrimeMcModel.quads(record));
        return id;
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) {
        try {
            var m = type.getDeclaredMethod(name, arguments);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    private static Field field(Class<?> type, String name) {
        try {
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    private static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Minecraft source field is inaccessible", failure);
        }
    }
}
