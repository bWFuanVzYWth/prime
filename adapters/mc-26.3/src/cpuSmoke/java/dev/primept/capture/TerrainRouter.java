package dev.primept.capture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/** Routes model definitions and appearance. Never constructs a raster compiler, lighter or builder. */
public final class TerrainRouter implements AutoCloseable {
    // This bounds host key associations, not native resident geometry or world capacity.
    private static final int SOURCE_KEYS = 4096;
    private static final Direction[] FACES = {Direction.DOWN,  Direction.UP,   Direction.NORTH,
                                              Direction.SOUTH, Direction.WEST, Direction.EAST};
    private final LegacyTerrainInbox inbox;
    private final long epoch;
    private final BlockStateModelSet models;
    private final FluidStateModelSet fluids;
    private final BlockColors colors;
    private final LinkedHashMap<Object, Geometry> handles = new LinkedHashMap<>(128, .75f, true);
    private final RandomSource random = RandomSource.createThreadLocalInstance(0);
    private final RouteBuffer definitions = new RouteBuffer(), draws = new RouteBuffer(),
                              fluidSources = new RouteBuffer(), packet = new RouteBuffer();
    private final ArrayList<Integer> tintIndices = new ArrayList<>(), tintFaces = new ArrayList<>();
    private int quadCount, faces;

    private record Geometry(long id, int[] tintIndices, int[] tintFaces, int faces) {}
    public TerrainRouter(LegacyTerrainInbox inbox, BlockStateModelSet models,
                         FluidStateModelSet fluids, BlockColors colors) {
        this.inbox = inbox;
        this.epoch = inbox.epoch();
        this.models = models;
        this.fluids = fluids;
        this.colors = colors;
    }

    public void route(SectionPos section, BlockAndTintGetter region) {
        var token = inbox.begin(section);
        if (token == null)
            return;
        draws.clear();
        fluidSources.clear();
        int placements = 0, fluidCount = 0;
        try {
            if (region != null) {
                var origin = section.origin();
                var neighbor = new BlockPos.MutableBlockPos();
                Object previousKey = null;
                Geometry previousGeometry = null;
                for (var pos : BlockPos.betweenClosed(origin, origin.offset(15, 15, 15))) {
                    var state = region.getBlockState(pos);
                    if (state.isAir())
                        continue;
                    var fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        if (FluidRouter.write(fluidSources, fluids, region, pos, state, fluid))
                            ++fluidCount;
                    }
                    if (state.getRenderShape() != RenderShape.MODEL)
                        continue;
                    var model = models.get(state);
                    long seed = state.getSeed(pos);
                    random.setSeed(seed);
                    Object key = model.createGeometryKey(region, pos, state, random);
                    // The real key callback still runs for every placement. An identical
                    // consecutive key is already MRU, so it needs no hash lookup or LRU write.
                    Geometry geometry = key == null          ? null
                                        : key == previousKey ? previousGeometry
                                                             : handles.get(key);
                    if (geometry == null) {
                        long id = inbox.routeIdentity();
                        definitions.header(13, epoch).i(1).i(0).l(id).i(0).i(0);
                        tintIndices.clear();
                        tintFaces.clear();
                        quadCount = faces = 0;
                        var emitter = Renderer.get().quadEmitter(this::quad);
                        random.setSeed(seed);
                        // FRAPI keys explicitly describe emission with cullTest=false. Actual model
                        // transforms remain source definitions; placement/lighting/buffering never run.
                        model.emitQuads(emitter, region, pos, state, random, ignored -> false);
                        definitions.integerAt(40, quadCount);
                        definitions.integerAt(44, tintIndices.size());
                        geometry = new Geometry(
                                id, tintIndices.stream().mapToInt(Integer::intValue).toArray(),
                                tintFaces.stream().mapToInt(Integer::intValue).toArray(), faces);
                        if (key != null) {
                            inbox.routeResource(epoch, definitions.seal());
                            if (handles.size() >= SOURCE_KEYS)
                                retire(handles.pollFirstEntry().getValue());
                            handles.put(key, geometry);
                        }
                    }
                    previousKey = key;
                    previousGeometry = geometry;
                    int visible = 64;
                    for (int face = 0; face < FACES.length; ++face) {
                        var direction = FACES[face];
                        int bit = 1 << face;
                        if ((geometry.faces & bit) != 0 &&
                            Block.shouldRenderFace(
                                    state,
                                    region.getBlockState(neighbor.setWithOffset(pos, direction)),
                                    direction))
                            visible |= bit;
                    }
                    // The actual model and visibility callbacks already ran. No emitted face
                    // survives: do not serialize a placement for native to discard again.
                    if ((geometry.faces & visible) == 0)
                        continue;
                    // A null-key definition has no future owner. Publish it only if this
                    // observation actually uses it; invisible temporary geometry never crosses FFM.
                    if (key == null)
                        inbox.routeResource(epoch, definitions.seal());
                    var offset = state.getOffset(pos);
                    draws.l(geometry.id)
                            .f((float)(SectionPos.sectionRelative(pos.getX()) + offset.x))
                            .f((float)(SectionPos.sectionRelative(pos.getY()) + offset.y))
                            .f((float)(SectionPos.sectionRelative(pos.getZ()) + offset.z))
                            .i(visible)
                            .i(geometry.tintIndices.length);
                    List<BlockTintSource> sources = null;
                    IntArrayList factoryColors = null;
                    for (int i = 0; i < geometry.tintIndices.length; ++i) {
                        int color = -1;
                        if ((geometry.tintFaces[i] & visible) != 0) {
                            if (sources == null) {
                                sources = colors.getTintSources(state);
                                if (sources.isEmpty()) {
                                    factoryColors = new IntArrayList();
                                    var factory = BlockColorRegistry.getFactory(state);
                                    if (factory != null)
                                        factory.collect(state, region, pos, factoryColors);
                                }
                            }
                            int index = geometry.tintIndices[i];
                            if (index < sources.size())
                                color = sources.get(index).colorInWorld(state, region, pos);
                            else if (factoryColors != null && index < factoryColors.size())
                                color = factoryColors.getInt(index);
                        }
                        draws.rgba(color);
                    }
                    ++placements;
                    if (key == null)
                        retire(geometry);
                }
            }
            packet.header(12, epoch)
                    .l(token.section())
                    .l(token.revision())
                    .d(token.x())
                    .d(token.y())
                    .d(token.z())
                    .i(placements)
                    .i(fluidCount)
                    .append(draws)
                    .append(fluidSources);
            inbox.route(token, packet.seal());
        } catch (RuntimeException exception) {
            inbox.captureFailed(token, exception);
            throw exception;
        } finally {
            inbox.complete(token);
        }
    }

    private void quad(QuadView quad) {
        if (quad.atlas() != QuadAtlas.BLOCK)
            throw new IllegalArgumentException("Terrain model must use the block atlas");
        int face = face(quad.cullFace());
        int tint = quad.tintIndex(), slot = -1;
        if (tint < -1)
            throw new IllegalArgumentException("Invalid model tint index");
        if (tint != -1) {
            slot = tintIndices.indexOf(tint);
            if (slot < 0) {
                slot = tintIndices.size();
                tintIndices.add(tint);
                tintFaces.add(0);
            }
            tintFaces.set(slot, tintFaces.get(slot) | (1 << face));
        }
        definitions.i(layer(quad.chunkLayer())).i(face).i(slot);
        for (int i = 0; i < 4; i++)
            definitions.f(quad.x(i))
                    .f(quad.y(i))
                    .f(quad.z(i))
                    .rgba(quad.color(i))
                    .f(quad.u(i))
                    .f(quad.v(i));
        ++quadCount;
        faces |= 1 << face;
    }
    static int face(Direction face) {
        if (face == null)
            return 6;
        return switch (face) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
        };
    }
    static int layer(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> 0;
            case CUTOUT -> 1;
            case TRANSLUCENT -> 2;
            default -> throw new IllegalArgumentException("Unsupported terrain layer");
        };
    }
    private void retire(Geometry geometry) {
        inbox.retireRouteResource(epoch, geometry.id);
    }
    @Override
    public void close() {
        for (var geometry : handles.values())
            retire(geometry);
        handles.clear();
    }
}
