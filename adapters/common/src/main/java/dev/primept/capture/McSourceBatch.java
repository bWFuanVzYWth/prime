package dev.primept.capture;

import dev.primept.abi.PrimeAbi;
import dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Reusable native typed groups. Payload pointers are published only after all growth is complete. */
public final class McSourceBatch implements AutoCloseable {
    private final ArrayList<Group> groups = new ArrayList<>();
    public final Group states = group(PrimeMcState.SIZE, PrimeMcState.ALIGN);
    public final Group models = group(PrimeMcModel.SIZE, PrimeMcModel.ALIGN);
    public final Group quads = group(PrimeMcQuad.SIZE, PrimeMcQuad.ALIGN);
    public final Group children = group(PrimeMcModelChild.SIZE, PrimeMcModelChild.ALIGN);
    public final Group faces = group(PrimeMcFace.SIZE, PrimeMcFace.ALIGN);
    public final Group fluids = group(PrimeMcFluid.SIZE, PrimeMcFluid.ALIGN);
    public final Group sprites = group(PrimeMcSprite.SIZE, PrimeMcSprite.ALIGN);
    public final Group images = group(PrimeMcImage.SIZE, PrimeMcImage.ALIGN);
    public final Group frames = group(PrimeMcAnimationFrame.SIZE, PrimeMcAnimationFrame.ALIGN);
    public final Group coordinates = group(8, 8), words = group(8, 8), bytes = group(1, 1);
    public final Group sections = group(PrimeMcSection.SIZE, PrimeMcSection.ALIGN),
                       palette = group(4, 4);
    public final Group events = group(PrimeMcEvent.SIZE, PrimeMcEvent.ALIGN);
    public final Group recipes = group(PrimeMcColorRecipe.SIZE, PrimeMcColorRecipe.ALIGN);
    public final Group definitions =
            group(PrimeMcBiomeDefinitions.SIZE, PrimeMcBiomeDefinitions.ALIGN);
    public final Group colormaps = group(4, 4),
                       biomes = group(PrimeMcBiome.SIZE, PrimeMcBiome.ALIGN), indices = group(4, 4);
    private final Arena roots = Arena.ofConfined();
    private final MemorySegment resource = roots.allocate(PrimeMcResourceBatch.LAYOUT);
    private final MemorySegment section = roots.allocate(PrimeMcSectionBatch.LAYOUT);
    private final MemorySegment plan = roots.allocate(PrimeMcPlan.LAYOUT);
    private final MemorySegment color = roots.allocate(PrimeMcColorBatch.LAYOUT);
    private final MemorySegment biome = roots.allocate(PrimeMcBiomeBatch.LAYOUT);
    public record Range(long offset, long count) {
        public void write(MemorySegment destination) {
            PrimeMcRange.offset(destination, offset);
            PrimeMcRange.count(destination, count);
        }
    }
    public static final class Group implements AutoCloseable {
        private Arena arena;
        private MemorySegment storage = MemorySegment.NULL;
        private long count;
        public final long stride, alignment;
        Group(long stride, long alignment) {
            this.stride = stride;
            this.alignment = alignment;
        }
        public long count() {
            return count;
        }
        public MemorySegment data() {
            return count == 0 ? MemorySegment.NULL
                              : storage.asSlice(0, Math.multiplyExact(count, stride));
        }
        public MemorySegment get(long index) {
            if (index < 0 || index >= count)
                throw new IndexOutOfBoundsException();
            return storage.asSlice(index * stride, stride);
        }
        public MemorySegment add() {
            long index = count;
            reserve(Math.addExact(count, 1));
            count++;
            var result = get(index);
            result.fill((byte)0);
            return result;
        }
        private void reserve(long required) {
            long needed = Math.multiplyExact(required, stride);
            if (needed <= storage.byteSize())
                return;
            long capacity =
                    Math.max(4096, Math.max(needed, Math.multiplyExact(storage.byteSize(), 2)));
            var nextArena = Arena.ofConfined();
            var next = nextArena.allocate(capacity, Math.max(8, alignment));
            if (count != 0)
                MemorySegment.copy(storage, 0, next, 0, count * stride);
            if (arena != null)
                arena.close();
            arena = nextArena;
            storage = next;
        }
        public Range copy(MemorySegment source) {
            if (source.byteSize() % stride != 0)
                throw new IllegalArgumentException("Typed payload stride");
            long first = count, n = source.byteSize() / stride;
            reserve(Math.addExact(count, n));
            if (n != 0)
                MemorySegment.copy(source, 0, storage, count * stride, source.byteSize());
            count += n;
            return new Range(first, n);
        }
        public Range ints(int[] values) {
            return copy(MemorySegment.ofArray(values));
        }
        public Range longs(long[] values) {
            return copy(MemorySegment.ofArray(values));
        }
        public void clear() {
            count = 0;
        }
        public void close() {
            if (arena != null) {
                arena.close();
                arena = null;
            }
            storage = MemorySegment.NULL;
            count = 0;
        }
    }
    private Group group(long stride, long alignment) {
        var group = new Group(stride, alignment);
        groups.add(group);
        return group;
    }
    public void clear() {
        for (var group : groups)
            group.clear();
    }
    public long bytes() {
        long size = 0;
        for (var group : groups)
            size = Math.addExact(size, group.count() * group.stride);
        return size;
    }
    public Range text(String value) {
        return bytes.copy(MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8)));
    }
    public Range pixels(ByteBuffer value) {
        return bytes.copy(MemorySegment.ofBuffer(value));
    }
    public long image(int width, int height, ByteBuffer pixels) {
        var range = pixels(pixels);
        long index = images.count();
        var image = images.add();
        PrimeMcImage.width(image, width);
        PrimeMcImage.height(image, height);
        range.write(PrimeMcImage.pixels(image));
        return index;
    }
    public long emptyImage(int width, int height) {
        long index = images.count();
        var image = images.add();
        PrimeMcImage.width(image, width);
        PrimeMcImage.height(image, height);
        return index;
    }
    public void section(int x, int y, int z, int bits, int[] ids, long[] storage, boolean present) {
        var paletteRange = palette.ints(ids);
        var wordRange = words.longs(storage);
        var value = sections.add();
        PrimeMcSection.x(value, x);
        PrimeMcSection.y(value, y);
        PrimeMcSection.z(value, z);
        PrimeMcSection.present(value, present ? 1 : 0);
        PrimeMcSection.bits(value, bits);
        paletteRange.write(PrimeMcSection.palette(value));
        wordRange.write(PrimeMcSection.storage(value));
    }
    public static void identity(MemorySegment value, long size, int game, long generation,
                                long epoch, long batch) {
        PrimeMcIdentity.struct_size(value, Math.toIntExact(size));
        PrimeMcIdentity.abi_version(value, PrimeAbi.PRIME_ABI_VERSION);
        PrimeMcIdentity.source_version(value, PrimeAbi.PRIME_MC_SOURCE_VERSION);
        PrimeMcIdentity.game_version(value, game);
        PrimeMcIdentity.resource_generation(value, generation);
        PrimeMcIdentity.epoch(value, epoch);
        PrimeMcIdentity.batch(value, batch);
    }
    public MemorySegment resources(int game, long generation, long epoch, long batch, long tick,
                                   boolean replace, int atlasWidth, int atlasHeight, byte[] atlas) {
        resource.fill((byte)0);
        identity(PrimeMcResourceBatch.identity(resource), PrimeMcResourceBatch.SIZE, game,
                 generation, epoch, batch);
        PrimeMcResourceBatch.flags(resource, replace ? PrimeAbi.PRIME_MC_RESOURCE_REPLACE : 0);
        PrimeMcResourceBatch.tick(resource, tick);
        if (replace) {
            var range = bytes.copy(MemorySegment.ofArray(atlas));
            var value = PrimeMcResourceBatch.atlas(resource);
            PrimeMcImage.width(value, atlasWidth);
            PrimeMcImage.height(value, atlasHeight);
            range.write(PrimeMcImage.pixels(value));
        }
        PrimeMcResourceBatch.states(resource, states.data());
        PrimeMcResourceBatch.state_count(resource, states.count());
        PrimeMcResourceBatch.models(resource, models.data());
        PrimeMcResourceBatch.model_count(resource, models.count());
        PrimeMcResourceBatch.quads(resource, quads.data());
        PrimeMcResourceBatch.quad_count(resource, quads.count());
        PrimeMcResourceBatch.children(resource, children.data());
        PrimeMcResourceBatch.child_count(resource, children.count());
        PrimeMcResourceBatch.faces(resource, faces.data());
        PrimeMcResourceBatch.face_count(resource, faces.count());
        PrimeMcResourceBatch.fluids(resource, fluids.data());
        PrimeMcResourceBatch.fluid_count(resource, fluids.count());
        PrimeMcResourceBatch.sprites(resource, sprites.data());
        PrimeMcResourceBatch.sprite_count(resource, sprites.count());
        PrimeMcResourceBatch.images(resource, images.data());
        PrimeMcResourceBatch.image_count(resource, images.count());
        PrimeMcResourceBatch.frames(resource, frames.data());
        PrimeMcResourceBatch.frame_count(resource, frames.count());
        PrimeMcResourceBatch.coordinates(resource, coordinates.data());
        PrimeMcResourceBatch.coordinate_count(resource, coordinates.count());
        PrimeMcResourceBatch.words(resource, words.data());
        PrimeMcResourceBatch.word_count(resource, words.count());
        PrimeMcResourceBatch.bytes(resource, bytes.data());
        PrimeMcResourceBatch.byte_count(resource, bytes.count());
        return resource;
    }
    public boolean hasResources() {
        return states.count() + models.count() + faces.count() + fluids.count() + sprites.count() !=
                0;
    }
    public MemorySegment sections(int game, long generation, long epoch, long batch) {
        section.fill((byte)0);
        identity(PrimeMcSectionBatch.identity(section), PrimeMcSectionBatch.SIZE, game, generation,
                 epoch, batch);
        PrimeMcSectionBatch.sections(section, sections.data());
        PrimeMcSectionBatch.section_count(section, sections.count());
        PrimeMcSectionBatch.palette(section, palette.data());
        PrimeMcSectionBatch.palette_count(section, palette.count());
        PrimeMcSectionBatch.words(section, words.data());
        PrimeMcSectionBatch.word_count(section, words.count());
        return section;
    }
    public MemorySegment plan(int game, long generation, long epoch, long batch, double x, double z,
                              int radius, int minY, int maxY, int[] bounds, long tick) {
        plan.fill((byte)0);
        identity(PrimeMcPlan.identity(plan), PrimeMcPlan.SIZE, game, generation, epoch, batch);
        PrimeMcPlan.position(plan, 0, x);
        PrimeMcPlan.position(plan, 1, z);
        PrimeMcPlan.radius(plan, radius);
        PrimeMcPlan.min_y(plan, minY);
        PrimeMcPlan.max_y(plan, maxY);
        for (int i = 0; i < 4; i++)
            PrimeMcPlan.source(plan, i, bounds[i]);
        PrimeMcPlan.tick(plan, tick);
        PrimeMcPlan.events(plan, events.data());
        PrimeMcPlan.event_count(plan, events.count());
        return plan;
    }
    public MemorySegment colors(int game, long generation, long epoch, long batch, int radius) {
        color.fill((byte)0);
        identity(PrimeMcColorBatch.identity(color), PrimeMcColorBatch.SIZE, game, generation, epoch,
                 batch);
        PrimeMcColorBatch.radius(color, radius);
        PrimeMcColorBatch.recipes(color, recipes.data());
        PrimeMcColorBatch.recipe_count(color, recipes.count());
        PrimeMcColorBatch.definitions(color, definitions.data());
        PrimeMcColorBatch.definition_count(color, definitions.count());
        PrimeMcColorBatch.colormaps(color, colormaps.data());
        PrimeMcColorBatch.colormap_count(color, colormaps.count());
        return color;
    }
    public MemorySegment biomes(int game, long generation, long epoch, long batch) {
        biome.fill((byte)0);
        identity(PrimeMcBiomeBatch.identity(biome), PrimeMcBiomeBatch.SIZE, game, generation, epoch,
                 batch);
        PrimeMcBiomeBatch.biomes(biome, biomes.data());
        PrimeMcBiomeBatch.biome_count(biome, biomes.count());
        PrimeMcBiomeBatch.indices(biome, indices.data());
        PrimeMcBiomeBatch.index_count(biome, indices.count());
        return biome;
    }
    public void close() {
        for (var group : groups)
            group.close();
        roots.close();
    }
}
