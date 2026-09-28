package dev.primept.capture;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

/** Field serialization only. Model selection, defaults, visibility and geometry belong to prime_minecraft. */
final class SectionSources {
    static final int GAME_VERSION = 263;
    private static final Field DATA = field(PalettedContainer.class, "data");
    private static final Field STORAGE = field(DATA.getType(), "storage"),
                               PALETTE = field(DATA.getType(), "palette");
    private static final Field MODELS = field(BlockStateModelSet.class, "modelByState");
    private static final Field SINGLE = field(SingleVariant.class, "model"),
                               WEIGHTED = field(WeightedVariants.class, "list");
    private static final Field SHARED = field(MultiPartModel.class, "shared"),
                               SELECTORS = field(SHARED.getType(), "selectors");
    private static final Field OFFSET =
            field(BlockBehaviour.BlockStateBase.class, "offsetFunction");
    private static final Field SOLID = field(BlockBehaviour.BlockStateBase.class, "solidRender");
    private static final Field[] QUADS = {
            field(QuadCollection.class, "down"),    field(QuadCollection.class, "up"),
            field(QuadCollection.class, "north"),   field(QuadCollection.class, "south"),
            field(QuadCollection.class, "west"),    field(QuadCollection.class, "east"),
            field(QuadCollection.class, "unculled")};
    private final Map<BlockState, BlockStateModel> models;
    private final BitSet states = new BitSet();
    private final IdentityHashMap<Object, Integer> definitions = new IdentityHashMap<>();
    private int nextModel;
    private boolean globalPublished;

    @SuppressWarnings("unchecked")
    SectionSources(BlockStateModelSet modelSet) {
        models = (Map<BlockState, BlockStateModel>)get(MODELS, modelSet);
    }
    @SuppressWarnings("unchecked")
    void section(SourcePages out, int x, int y, int z, LevelChunkSection section) {
        if (section == null) {
            out.i(3).i(x).i(y).i(z).i(0);
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
            long[] words = storage.getRaw();
            out.i(3).i(x)
                    .i(y)
                    .i(z)
                    .i(1)
                    .i(storage.getBits())
                    .i(global ? 0 : palette.getSize())
                    .i(words.length);
            if (!global)
                for (int i = 0; i < palette.getSize(); ++i)
                    out.i(Block.getId(palette.valueFor(i)));
            out.longs(words);
        } finally {
            container.release();
        }
    }
    private void state(SourcePages out, BlockState state) {
        int id = Block.getId(state);
        if (states.get(id))
            return;
        states.set(id);
        int model = model(out, models.get(state));
        int flags = (state.isAir() ? 1 : 0) | (get(OFFSET, state) != null ? 2 : 0) |
                    ((boolean)get(SOLID, state) ? 4 : 0) |
                    (state.getRenderShape() != RenderShape.MODEL ? 16 : 0);
        out.i(1).i(id).i(flags).i(model).string(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
    @SuppressWarnings("unchecked")
    private int model(SourcePages out, Object value) {
        if (value == null)
            return 0;
        Integer known = definitions.get(value);
        if (known != null)
            return known;
        int id = Math.incrementExact(nextModel);
        nextModel = id;
        definitions.put(value, id);
        if (value.getClass() == SingleVariant.class) {
            int child = model(out, get(SINGLE, value));
            out.i(2).i(id).i(4).i(child);
        } else if (value.getClass() == WeightedVariants.class) {
            var entries = ((WeightedList<BlockStateModel>)get(WEIGHTED, value)).unwrap();
            int[] children = new int[entries.size()];
            for (int i = 0; i < children.length; ++i)
                children[i] = model(out, entries.get(i).value());
            out.i(2).i(id).i(2).i(children.length);
            for (int i = 0; i < children.length; ++i)
                out.i(entries.get(i).weight()).i(children[i]);
        } else if (value.getClass() == MultiPartModel.class) {
            var selectors = (List<MultiPartModel.Selector<BlockStateModel>>)get(SELECTORS,
                                                                                get(SHARED, value));
            int[] children = new int[selectors.size()];
            for (int i = 0; i < children.length; ++i)
                children[i] = model(out, selectors.get(i).model());
            // Conditions are deliberately not executed; Rust owns the prototype's default choice.
            out.i(2).i(id).i(3).i(children.length);
            for (int child : children)
                out.i(child);
        } else if (value instanceof SimpleModelWrapper simple) {
            var groups = new java.util.ArrayList<List<BakedQuad>>(7);
            int size = 0;
            for (Field face : QUADS) {
                var quads = (List<BakedQuad>)get(face, simple.quads());
                groups.add(quads);
                size = Math.addExact(size, quads.size());
            }
            out.i(2).i(id).i(1).i(size);
            for (int face = 0; face < 7; ++face)
                for (BakedQuad quad : groups.get(face)) {
                    var material = quad.materialInfo();
                    out.i(face).i(material.tintIndex()).i(material.layer().ordinal());
                    for (int i = 0; i < 4; ++i) {
                        var p = quad.position(i);
                        out.f(p.x()).f(p.y()).f(p.z()).l(quad.packedUV(i));
                    }
                }
        } else {
            out.i(2).i(id).i(0);
        }
        return id;
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
