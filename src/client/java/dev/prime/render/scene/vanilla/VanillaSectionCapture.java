package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.vertex.MeshData;
import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;

/**
 * Thread-confined side channel for one invocation of Minecraft's {@link SectionCompiler}.
 *
 * <p>Minecraft's mesh and vertex interfaces remain untouched. Small Mixins observe only geometry
 * that vanilla has already accepted and forward it here while the owning compile scope is active.
 * The inactive path is a single {@link ThreadLocal#get()} and does not allocate.
 */
public final class VanillaSectionCapture implements AutoCloseable {
    private static final ThreadLocal<VanillaSectionCapture> ACTIVE = new ThreadLocal<>();
    private static final int UNCACHED_TINT = Integer.MIN_VALUE;

    private final RenderSectionRegion region;
    private final BlockStateModelSet blockModels;
    private final BlockColors blockColors;
    private final SpriteFinder blockSpriteFinder;
    private final VanillaSpriteResolver spriteResolver;
    private final boolean cutoutLeaves;
    private final int sectionX;
    private final int sectionY;
    private final int sectionZ;
    private final int clusterMinimumX;
    private final int clusterMinimumY;
    private final int clusterMinimumZ;
    private final int clusterMaximumX;
    private final int clusterMaximumY;
    private final int clusterMaximumZ;
    private final CapturedSectionGeometry.Builder geometry =
            new CapturedSectionGeometry.Builder();
    private final CapturedSectionGeometry.MutableQuad blockQuad =
            new CapturedSectionGeometry.MutableQuad();
    private final CapturedSectionGeometry.MutableQuad peerQuad =
            new CapturedSectionGeometry.MutableQuad();
    private final Set<PeerFaceKey> capturedPeerFaces = new HashSet<>();
    private final ArrayList<BlockStateModelPart> peerParts = new ArrayList<>();
    private final RandomSource peerRandom = RandomSource.create();
    private final ArrayDeque<FluidCapture> fluidStack = new ArrayDeque<>();
    private long tintPosition = Long.MIN_VALUE;
    private int tintIndex = -1;
    private int tintValue = -1;
    private long blockPosition = Long.MIN_VALUE;
    private BlockState blockState;
    private boolean blockForceOpaque;
    private boolean blockFoliage;
    private boolean blockMergeable;
    private int blockMediumFamily;
    private boolean blockCollisionKnown;
    private boolean blockCollisionEmpty;
    private final int[] fabricBaseColors = new int[4];
    private final int[] fabricResolvedColors = new int[4];
    private int[] fabricTintCache = new int[4];
    private final IntArrayList fabricDynamicTints = new IntArrayList();
    private BlockAndTintGetter fabricLevel;
    private BlockPos fabricPosition;
    private BlockState fabricState;
    private boolean fabricMergeable;
    private int fabricMediumFamily;
    private List<BlockTintSource> fabricTintSources = List.of();
    private BlockTintsFactory fabricTintFactory;
    private boolean fabricDynamicTintsLoaded;
    private boolean fabricQuadPending;
    private int sourceQuadCount;
    private boolean finished;

    private VanillaSectionCapture(
            RenderSectionRegion region,
            BlockStateModelSet blockModels,
            BlockColors blockColors,
            SpriteFinder blockSpriteFinder,
            VanillaSpriteResolver spriteResolver,
            boolean cutoutLeaves,
            int sectionX,
            int sectionY,
            int sectionZ,
            int clusterX,
            int clusterY,
            int clusterZ) {
        this.region = region;
        this.blockModels = blockModels;
        this.blockColors = blockColors;
        this.blockSpriteFinder = blockSpriteFinder;
        this.spriteResolver = spriteResolver;
        this.cutoutLeaves = cutoutLeaves;
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.clusterMinimumX = clusterX << 4;
        this.clusterMinimumY = clusterY << 4;
        this.clusterMinimumZ = clusterZ << 4;
        this.clusterMaximumX = (clusterX + 4) << 4;
        this.clusterMaximumY = (clusterY + 4) << 4;
        this.clusterMaximumZ = (clusterZ + 4) << 4;
    }

    public static VanillaSectionCapture open(
            RenderSectionRegion region,
            BlockStateModelSet blockModels,
            BlockColors blockColors,
            SpriteFinder blockSpriteFinder,
            VanillaSpriteResolver spriteResolver,
            boolean cutoutLeaves,
            int sectionX,
            int sectionY,
            int sectionZ,
            int clusterX,
            int clusterY,
            int clusterZ) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Nested vanilla Section capture is not supported");
        }
        VanillaSectionCapture capture = new VanillaSectionCapture(
                region,
                blockModels,
                blockColors,
                blockSpriteFinder,
                spriteResolver,
                cutoutLeaves,
                sectionX,
                sectionY,
                sectionZ,
                clusterX,
                clusterY,
                clusterZ);
        ACTIVE.set(capture);
        return capture;
    }

    public static void recordBlockQuad(
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos position,
            BakedQuad bakedQuad) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.addBlockQuad(x, y, z, level, state, position, bakedQuad);
        }
    }

    public static void recordBlockTint(BlockPos position, int tintIndex, int tint) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.tintPosition = position.asLong();
            capture.tintIndex = tintIndex;
            capture.tintValue = tint;
        }
    }

    public static void beginFabricBlock(
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockStateModel model) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.openFabricBlock(level, position, state, model);
        }
    }

    public static void endFabricBlock() {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.closeFabricBlock();
        }
    }

    public static void beginFabricQuad(MutableQuadView quad) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null && capture.fabricLevel != null) {
            capture.openFabricQuad(quad);
        }
    }

    public static void finishFabricQuad(MutableQuadView quad, boolean accepted) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null && capture.fabricLevel != null) {
            capture.closeFabricQuad(quad, accepted);
        }
    }

    public static void beginFluid(
            BlockAndTintGetter level,
            BlockPos position,
            BlockState blockState,
            FluidState fluidState,
            FluidModel model) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            // Fabric's default FluidRenderHandler deliberately calls FluidRenderer.tesselate a
            // second time under a ScopedValue guard. Keep both scopes: the outer scope covers
            // vertices emitted directly by a custom handler, while the inner scope owns vanilla's
            // vertices. A stack preserves the exact material context without depending on Fabric
            // internals or mistaking this supported delegation for recursive corruption.
            capture.fluidStack.push(
                    new FluidCapture(capture, level, position, blockState, fluidState, model));
        }
    }

    public static void recordFluidVertex(
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlay,
            int light,
            float normalX,
            float normalY,
            float normalZ) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null && !capture.fluidStack.isEmpty()) {
            capture.fluidStack.peek().addVertex(x, y, z, u, v);
        }
    }

    public static void endFluid() {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            if (capture.fluidStack.isEmpty()) {
                throw new IllegalStateException("Fluid capture scope was not opened");
            }
            capture.fluidStack.pop().finish();
        }
    }

    public CapturedSectionGeometry finish(SectionCompiler.Results results) {
        if (this.finished) {
            throw new IllegalStateException("Vanilla Section capture was already finished");
        }
        if (!this.fluidStack.isEmpty()) {
            throw new IllegalStateException("Fluid tessellation did not leave its capture scope");
        }
        if (this.fabricLevel != null || this.fabricQuadPending) {
            throw new IllegalStateException("Fabric tessellation did not leave its capture scope");
        }
        int vanillaQuadCount = 0;
        for (MeshData layer : results.renderedLayers.values()) {
            int vertexCount = layer.drawState().vertexCount();
            if ((vertexCount & 3) != 0) {
                throw new IllegalStateException("Vanilla Section layer is not quad aligned");
            }
            vanillaQuadCount += vertexCount / 4;
        }
        if (vanillaQuadCount != this.sourceQuadCount) {
            throw new IllegalStateException(
                    "Prime captured " + this.sourceQuadCount
                            + " of " + vanillaQuadCount + " vanilla Section quads");
        }
        this.finished = true;
        return this.geometry.build();
    }

    private void addBlockQuad(
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos position,
            BakedQuad bakedQuad) {
        if (level != this.region) {
            throw new IllegalStateException("Captured block quad belongs to a different region");
        }
        this.sourceQuadCount++;
        long packedPosition = position.asLong();
        if (this.blockPosition != packedPosition || this.blockState != state) {
            this.blockPosition = packedPosition;
            this.blockState = state;
            this.blockForceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);
            this.blockFoliage = !this.blockForceOpaque
                    && (state.is(BlockTags.LEAVES)
                            || state.getBlock() == Blocks.SHORT_GRASS
                            || state.getBlock() == Blocks.TALL_GRASS);
            // The selected quad, including random orientation, is compared exactly downstream.
            // Admit every built-in model shape but keep arbitrary renderer models conservative.
            this.blockMergeable = mergeableModel(this.blockModels.get(state));
            this.blockMediumFamily = mediumFamily(state);
            this.blockCollisionKnown = false;
        }
        ChunkSectionLayer layer = this.blockForceOpaque
                ? ChunkSectionLayer.SOLID
                : bakedQuad.materialInfo().layer();
        boolean foliage = this.blockFoliage;
        boolean alphaCutOverride = requiresAlphaCut(state);
        boolean needsCollision =
                layer == ChunkSectionLayer.TRANSLUCENT && !alphaCutOverride;
        if (needsCollision && !this.blockCollisionKnown) {
            this.blockCollisionEmpty = state.getCollisionShape(this.region, position).isEmpty();
            this.blockCollisionKnown = true;
        }
        int requestedTint = bakedQuad.materialInfo().tintIndex();
        int tint = -1;
        if (requestedTint >= 0) {
            if (this.tintPosition != position.asLong() || this.tintIndex != requestedTint) {
                throw new IllegalStateException("Vanilla block tint was not captured before its quad");
            }
            tint = this.tintValue;
        }
        TextureAtlasSprite sprite = bakedQuad.materialInfo().sprite();
        CapturedSectionGeometry.MutableQuad quad = this.blockQuad;
        Direction direction = bakedQuad.direction();
        quad.normalX = direction.getStepX();
        quad.normalY = direction.getStepY();
        quad.normalZ = direction.getStepZ();
        for (int index = 0; index < 4; index++) {
            Vector3fc vertex = bakedQuad.position(index);
            quad.x[index] = vertex.x() + x;
            quad.y[index] = vertex.y() + y;
            quad.z[index] = vertex.z() + z;
            long packedUv = bakedQuad.packedUV(index);
            quad.u[index] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packedUv);
            quad.v[index] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packedUv);
        }
        boolean rasterOverlay = isRasterOverlay(
                state.getBlock() == Blocks.GRASS_BLOCK,
                state.getBlock() == Blocks.REDSTONE_WIRE,
                requestedTint,
                quad.normalY);
        CapturedSprite capturedSprite = this.spriteResolver.resolve(sprite);
        localizeUv(quad, sprite);
        this.geometry.add(quad, CapturedSectionGeometry.Surface.uniform(
                tint,
                captureLayer(layer),
                (alphaCutOverride ? CapturedSectionGeometry.Surface.ALPHA_CUT_OVERRIDE : 0)
                        | (needsCollision && this.blockCollisionEmpty
                                ? CapturedSectionGeometry.Surface.COLLISION_EMPTY : 0)
                        | (sprite.contents().isAnimated()
                                ? CapturedSectionGeometry.Surface.ANIMATED : 0)
                        | (foliage ? CapturedSectionGeometry.Surface.FOLIAGE : 0)
                        | (this.blockMergeable ? CapturedSectionGeometry.Surface.MERGEABLE : 0)
                        | (rasterOverlay ? CapturedSectionGeometry.Surface.RASTER_OVERLAY : 0),
                Math.max(state.getLightEmission(), bakedQuad.materialInfo().lightEmission()),
                capturedSprite,
                new CapturedSectionGeometry.BlockFacts(
                        position.getX(), position.getY(), position.getZ(),
                        this.blockMediumFamily),
                VanillaMaterialClassifier.classify(state, capturedSprite.id())));
        this.captureClusterPeer(state, position, direction);
    }

    private void openFabricBlock(
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockStateModel model) {
        if (level != this.region) {
            throw new IllegalStateException("Captured Fabric block belongs to a different region");
        }
        if (this.fabricLevel != null || this.fabricQuadPending) {
            throw new IllegalStateException("Nested Fabric block tessellation during Section capture");
        }
        this.fabricLevel = level;
        this.fabricPosition = position;
        this.fabricState = state;
        // Indigo also routes ordinary vanilla models through this path. Preserve the same
        // built-in-model gate as direct capture instead of treating every Fabric-rendered quad as
        // custom geometry.
        this.fabricMergeable = mergeableModel(model);
        this.fabricMediumFamily = mediumFamily(state);
        this.fabricTintSources = this.blockColors.getTintSources(state);
        this.fabricTintFactory = this.fabricTintSources.isEmpty()
                ? BlockColorRegistry.getFactory(state)
                : null;
        this.fabricDynamicTints.clear();
        this.fabricDynamicTintsLoaded = false;
        Arrays.fill(this.fabricTintCache, UNCACHED_TINT);
    }

    private void closeFabricBlock() {
        if (this.fabricLevel == null) {
            throw new IllegalStateException("Fabric block capture scope was not opened");
        }
        if (this.fabricQuadPending) {
            throw new IllegalStateException("Fabric block capture ended with an unfinished quad");
        }
        this.fabricLevel = null;
        this.fabricPosition = null;
        this.fabricState = null;
        this.fabricMergeable = false;
        this.fabricMediumFamily = 0;
        this.fabricTintSources = List.of();
        this.fabricTintFactory = null;
        this.fabricDynamicTints.clear();
        this.fabricDynamicTintsLoaded = false;
    }

    private void openFabricQuad(MutableQuadView quad) {
        if (this.fabricQuadPending) {
            throw new IllegalStateException("Nested Fabric quad transformation during Section capture");
        }
        for (int index = 0; index < 4; index++) {
            this.fabricBaseColors[index] = quad.color(index);
        }
        this.fabricQuadPending = true;
    }

    private void closeFabricQuad(MutableQuadView source, boolean accepted) {
        if (!this.fabricQuadPending) {
            throw new IllegalStateException("Fabric quad capture was not opened");
        }
        this.fabricQuadPending = false;
        if (!accepted) {
            return;
        }
        if (source.atlas() != QuadAtlas.BLOCK) {
            throw new IllegalStateException("A world block emitted geometry from a non-block atlas");
        }

        this.sourceQuadCount++;
        BlockState state = this.fabricState;
        BlockPos position = this.fabricPosition;
        boolean forceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);
        boolean foliage = !forceOpaque
                && (state.is(BlockTags.LEAVES)
                        || state.getBlock() == Blocks.SHORT_GRASS
                        || state.getBlock() == Blocks.TALL_GRASS);
        ChunkSectionLayer layer = forceOpaque ? ChunkSectionLayer.SOLID : source.chunkLayer();
        boolean alphaCutOverride = requiresAlphaCut(state);
        boolean collisionEmpty =
                layer == ChunkSectionLayer.TRANSLUCENT
                && !alphaCutOverride
                && state.getCollisionShape(this.region, position).isEmpty();
        int[] colors = this.resolveFabricColors(source.tintIndex());
        TextureAtlasSprite sprite = this.blockSpriteFinder.find(source);

        CapturedSectionGeometry.MutableQuad quad = this.blockQuad;
        Vector3fc faceNormal = source.faceNormal();
        quad.normalX = faceNormal.x();
        quad.normalY = faceNormal.y();
        quad.normalZ = faceNormal.z();
        for (int index = 0; index < 4; index++) {
            quad.x[index] = source.x(index);
            quad.y[index] = source.y(index);
            quad.z[index] = source.z(index);
            quad.u[index] = source.u(index);
            quad.v[index] = source.v(index);
        }
        boolean rasterOverlay = isRasterOverlay(
                state.getBlock() == Blocks.GRASS_BLOCK,
                state.getBlock() == Blocks.REDSTONE_WIRE,
                source.tintIndex(),
                quad.normalY);
        CapturedSprite capturedSprite = this.spriteResolver.resolve(sprite);
        localizeUv(quad, sprite);
        this.geometry.add(quad, new CapturedSectionGeometry.Surface(
                colors[0],
                colors[1],
                colors[2],
                colors[3],
                captureLayer(layer),
                (alphaCutOverride ? CapturedSectionGeometry.Surface.ALPHA_CUT_OVERRIDE : 0)
                        | (collisionEmpty ? CapturedSectionGeometry.Surface.COLLISION_EMPTY : 0)
                        | (source.animated() || sprite.contents().isAnimated()
                                ? CapturedSectionGeometry.Surface.ANIMATED : 0)
                        | (foliage ? CapturedSectionGeometry.Surface.FOLIAGE : 0)
                        | (this.fabricMergeable ? CapturedSectionGeometry.Surface.MERGEABLE : 0)
                        | (rasterOverlay ? CapturedSectionGeometry.Surface.RASTER_OVERLAY : 0),
                Math.max(state.getLightEmission(), source.emissive() ? 15 : 0),
                capturedSprite,
                null,
                new CapturedSectionGeometry.BlockFacts(
                        position.getX(), position.getY(), position.getZ(),
                        this.fabricMediumFamily),
                VanillaMaterialClassifier.classify(state, capturedSprite.id())));
        Direction direction = cardinalDirection(
                quad.normalX, quad.normalY, quad.normalZ);
        if (direction != null) {
            this.captureClusterPeer(state, position, direction);
        }
    }

    private void captureClusterPeer(
            BlockState ownerState, BlockPos ownerPosition, Direction direction) {
        int peerX = ownerPosition.getX() + direction.getStepX();
        int peerY = ownerPosition.getY() + direction.getStepY();
        int peerZ = ownerPosition.getZ() + direction.getStepZ();
        if (peerX >= this.clusterMinimumX && peerX < this.clusterMaximumX
                && peerY >= this.clusterMinimumY && peerY < this.clusterMaximumY
                && peerZ >= this.clusterMinimumZ && peerZ < this.clusterMaximumZ) {
            return;
        }
        PeerFaceKey key = new PeerFaceKey(
                ownerPosition.getX(), ownerPosition.getY(), ownerPosition.getZ(), direction);
        if (!this.capturedPeerFaces.add(key)) {
            return;
        }

        BlockPos peerPosition = new BlockPos(peerX, peerY, peerZ);
        BlockState peerState = this.region.getBlockState(peerPosition);
        Direction peerDirection = direction.getOpposite();
        if (!net.minecraft.world.level.block.Block.shouldRenderFace(
                peerState, ownerState, peerDirection)) {
            return;
        }
        BlockStateModel model = this.blockModels.get(peerState);
        if (!mergeableModel(model)) {
            return;
        }
        this.peerRandom.setSeed(peerState.getSeed(peerPosition));
        this.peerParts.clear();
        model.collectParts(this.peerRandom, this.peerParts);
        try {
            for (BlockStateModelPart part : this.peerParts) {
                for (BakedQuad bakedQuad : part.getQuads(peerDirection)) {
                    this.addPeerQuad(peerState, peerPosition, bakedQuad);
                }
            }
        } finally {
            this.peerParts.clear();
        }
    }

    private void addPeerQuad(
            BlockState state, BlockPos position, BakedQuad bakedQuad) {
        boolean forceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);
        ChunkSectionLayer layer = forceOpaque
                ? ChunkSectionLayer.SOLID
                : bakedQuad.materialInfo().layer();
        boolean alphaCutOverride = requiresAlphaCut(state);
        boolean needsCollision = layer == ChunkSectionLayer.TRANSLUCENT
                && !alphaCutOverride;
        boolean collisionEmpty = needsCollision
                && state.getCollisionShape(this.region, position).isEmpty();
        int tint = this.resolvePeerTint(
                state, position, bakedQuad.materialInfo().tintIndex());
        TextureAtlasSprite sprite = bakedQuad.materialInfo().sprite();
        Direction direction = bakedQuad.direction();
        CapturedSectionGeometry.MutableQuad quad = this.peerQuad;
        quad.normalX = direction.getStepX();
        quad.normalY = direction.getStepY();
        quad.normalZ = direction.getStepZ();
        net.minecraft.world.phys.Vec3 offset = state.getOffset(position);
        float baseX = position.getX() - (this.sectionX << 4) + (float) offset.x;
        float baseY = position.getY() - (this.sectionY << 4) + (float) offset.y;
        float baseZ = position.getZ() - (this.sectionZ << 4) + (float) offset.z;
        for (int index = 0; index < 4; index++) {
            Vector3fc vertex = bakedQuad.position(index);
            quad.x[index] = baseX + vertex.x();
            quad.y[index] = baseY + vertex.y();
            quad.z[index] = baseZ + vertex.z();
            long packedUv = bakedQuad.packedUV(index);
            quad.u[index] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packedUv);
            quad.v[index] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packedUv);
        }
        boolean foliage = !forceOpaque
                && (state.is(BlockTags.LEAVES)
                        || state.getBlock() == Blocks.SHORT_GRASS
                        || state.getBlock() == Blocks.TALL_GRASS);
        boolean rasterOverlay = isRasterOverlay(
                state.getBlock() == Blocks.GRASS_BLOCK,
                state.getBlock() == Blocks.REDSTONE_WIRE,
                bakedQuad.materialInfo().tintIndex(),
                quad.normalY);
        CapturedSprite capturedSprite = this.spriteResolver.resolve(sprite);
        localizeUv(quad, sprite);
        this.geometry.addPeer(quad, CapturedSectionGeometry.Surface.uniform(
                tint,
                captureLayer(layer),
                (alphaCutOverride ? CapturedSectionGeometry.Surface.ALPHA_CUT_OVERRIDE : 0)
                        | (collisionEmpty ? CapturedSectionGeometry.Surface.COLLISION_EMPTY : 0)
                        | (sprite.contents().isAnimated()
                                ? CapturedSectionGeometry.Surface.ANIMATED : 0)
                        | (foliage ? CapturedSectionGeometry.Surface.FOLIAGE : 0)
                        | CapturedSectionGeometry.Surface.MERGEABLE
                        | (rasterOverlay ? CapturedSectionGeometry.Surface.RASTER_OVERLAY : 0),
                Math.max(state.getLightEmission(), bakedQuad.materialInfo().lightEmission()),
                capturedSprite,
                new CapturedSectionGeometry.BlockFacts(
                        position.getX(), position.getY(), position.getZ(),
                        mediumFamily(state)),
                VanillaMaterialClassifier.classify(state, capturedSprite.id())));
    }

    private int resolvePeerTint(
            BlockState state, BlockPos position, int tintIndex) {
        if (tintIndex < 0) {
            return -1;
        }
        List<BlockTintSource> sources = this.blockColors.getTintSources(state);
        if (tintIndex < sources.size()) {
            return sources.get(tintIndex).colorInWorld(state, this.region, position);
        }
        if (!sources.isEmpty()) {
            return -1;
        }
        BlockTintsFactory factory = BlockColorRegistry.getFactory(state);
        if (factory == null) {
            return -1;
        }
        IntArrayList dynamic = new IntArrayList();
        factory.collect(state, this.region, position, dynamic);
        return tintIndex < dynamic.size() ? dynamic.getInt(tintIndex) : -1;
    }

    private static Direction cardinalDirection(float x, float y, float z) {
        Direction direction = Direction.getApproximateNearest(x, y, z);
        float dot = x * direction.getStepX()
                + y * direction.getStepY()
                + z * direction.getStepZ();
        return dot > 0.9999F ? direction : null;
    }

    private static int mediumFamily(BlockState state) {
        if (state.getBlock() == Blocks.GLASS || state.getBlock() == Blocks.GLASS_PANE) {
            return 1;
        }
        if (state.getBlock() instanceof StainedGlassBlock glass) {
            return 2 + glass.getColor().getId();
        }
        if (state.getBlock() instanceof StainedGlassPaneBlock pane) {
            return 2 + pane.getColor().getId();
        }
        return 0;
    }

    static boolean isRasterOverlay(
            boolean grassBlock, boolean redstoneWire, int tintIndex, float normalY) {
        return (grassBlock && tintIndex >= 0 && Math.abs(normalY) < 0.5F)
                || (redstoneWire && tintIndex < 0);
    }

    static CapturedSectionGeometry.Layer captureLayer(ChunkSectionLayer layer) {
        if (layer == ChunkSectionLayer.SOLID) {
            return CapturedSectionGeometry.Layer.OPAQUE;
        }
        if (layer == ChunkSectionLayer.CUTOUT) {
            return CapturedSectionGeometry.Layer.CUTOUT;
        }
        if (layer == ChunkSectionLayer.TRANSLUCENT) {
            return CapturedSectionGeometry.Layer.TRANSLUCENT;
        }
        throw new IllegalArgumentException("Unsupported captured Section layer: " + layer);
    }

    static boolean requiresAlphaCut(BlockState state) {
        // Some packs replace these models without carrying Minecraft's raster layer metadata.
        // Their geometry still relies on binary texture coverage: treating the head planes as
        // solid turns transparent texels into an opaque box and also expands the emitter support.
        return state.getBlock() == Blocks.REDSTONE_WIRE
                || state.getBlock() == Blocks.REDSTONE_TORCH
                || state.getBlock() == Blocks.REDSTONE_WALL_TORCH;
    }

    private static void localizeUv(
            CapturedSectionGeometry.MutableQuad quad,
            TextureAtlasSprite sprite) {
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.u[vertex] = localCoordinate(quad.u[vertex], u0, u1);
            quad.v[vertex] = localCoordinate(quad.v[vertex], v0, v1);
        }
    }

    static float localCoordinate(float atlasCoordinate, float lower, float upper) {
        float local = (atlasCoordinate - lower) / (upper - lower);
        if (local >= 0.0F && local <= 1.0F) {
            return local;
        }
        // Atlas endpoints pass through several f32 interpolation and packing steps. Lower only
        // their rounding excursions; a materially out-of-range mapping still fails the Prime UV
        // contract instead of silently changing repeat or sub-region semantics.
        float endpointTolerance = 4.0F * Math.max(Math.ulp(lower), Math.ulp(upper));
        if (atlasCoordinate >= lower - endpointTolerance
                && atlasCoordinate <= upper + endpointTolerance) {
            return Math.max(0.0F, Math.min(1.0F, local));
        }
        return local;
    }

    private int[] resolveFabricColors(int tintIndex) {
        int tint = tintIndex < 0 ? -1 : this.resolveFabricTint(tintIndex);
        for (int index = 0; index < 4; index++) {
            this.fabricResolvedColors[index] = tintIndex < 0
                    ? this.fabricBaseColors[index]
                    : ARGB.multiply(this.fabricBaseColors[index], tint);
        }
        return this.fabricResolvedColors;
    }

    private int resolveFabricTint(int tintIndex) {
        if (tintIndex >= this.fabricTintCache.length) {
            int oldLength = this.fabricTintCache.length;
            this.fabricTintCache = Arrays.copyOf(
                    this.fabricTintCache,
                    Math.max(tintIndex + 1, oldLength * 2));
            Arrays.fill(this.fabricTintCache, oldLength, this.fabricTintCache.length, UNCACHED_TINT);
        }
        int cached = this.fabricTintCache[tintIndex];
        if (cached != UNCACHED_TINT) {
            return cached;
        }
        int value = -1;
        if (tintIndex < this.fabricTintSources.size()) {
            value = this.fabricTintSources.get(tintIndex)
                    .colorInWorld(this.fabricState, this.fabricLevel, this.fabricPosition);
        } else if (this.fabricTintSources.isEmpty() && this.fabricTintFactory != null) {
            if (!this.fabricDynamicTintsLoaded) {
                this.fabricTintFactory.collect(
                        this.fabricState,
                        this.fabricLevel,
                        this.fabricPosition,
                        this.fabricDynamicTints);
                this.fabricDynamicTintsLoaded = true;
            }
            if (tintIndex < this.fabricDynamicTints.size()) {
                value = this.fabricDynamicTints.getInt(tintIndex);
            }
        }
        this.fabricTintCache[tintIndex] = value;
        return value;
    }

    static boolean mergeableModel(BlockStateModel model) {
        return model instanceof SingleVariant
                || model instanceof WeightedVariants
                || model instanceof MultiPartModel;
    }

    @Override
    public void close() {
        if (ACTIVE.get() != this) {
            throw new IllegalStateException("Vanilla Section capture closed on the wrong thread");
        }
        ACTIVE.remove();
        this.fluidStack.clear();
        this.fabricLevel = null;
        this.fabricPosition = null;
        this.fabricState = null;
        this.fabricMergeable = false;
        this.fabricQuadPending = false;
    }

    /** Collects FluidRenderer vertices plus the world-query facts needed by cluster translation. */
    private static final class FluidCapture {
        private final VanillaSectionCapture owner;
        private final int localX;
        private final int localY;
        private final int localZ;
        private final int tint;
        private final int lightEmission;
        private final boolean transmissive;
        private final boolean water;
        private final boolean fullCeiling;
        private final int fullCollisionMask;
        private final TextureAtlasSprite stillSprite;
        private final TextureAtlasSprite flowingSprite;
        private final TextureAtlasSprite overlaySprite;
        private final CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        private int vertexCount;

        private FluidCapture(
                VanillaSectionCapture owner,
                BlockAndTintGetter level,
                BlockPos position,
                BlockState blockState,
                FluidState fluidState,
                FluidModel model) {
            if (level != owner.region) {
                throw new IllegalStateException("Captured fluid belongs to a different region");
            }
            this.owner = owner;
            this.localX = SectionPos.sectionRelative(position.getX());
            this.localY = SectionPos.sectionRelative(position.getY());
            this.localZ = SectionPos.sectionRelative(position.getZ());
            this.water = fluidState.is(FluidTags.WATER);
            this.transmissive = !fluidState.is(FluidTags.LAVA);
            this.tint = model.tintSource() == null
                    ? -1
                    : model.tintSource().colorInWorld(blockState, level, position);
            this.lightEmission = blockState.getLightEmission();
            this.stillSprite = model.stillMaterial().sprite();
            this.flowingSprite = model.flowingMaterial().sprite();
            this.overlaySprite = model.overlayMaterial() == null
                    ? null
                    : model.overlayMaterial().sprite();
            int worldX = position.getX();
            int worldY = position.getY();
            int worldZ = position.getZ();
            BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos(
                    worldX, worldY + 1, worldZ);
            this.fullCeiling = level.getBlockState(neighbor)
                    .isCollisionShapeFullBlock(level, neighbor);
            int collisionMask = 0;
            for (Direction direction : Direction.values()) {
                neighbor.set(
                        worldX + direction.getStepX(),
                        worldY + direction.getStepY(),
                        worldZ + direction.getStepZ());
                if (level.getBlockState(neighbor)
                        .isCollisionShapeFullBlock(level, neighbor)) {
                    collisionMask |= 1 << direction.ordinal();
                }
            }
            this.fullCollisionMask = collisionMask;
        }

        private void addVertex(float x, float y, float z, float u, float v) {
            int index = this.vertexCount;
            this.quad.x[index] = x;
            this.quad.y[index] = y;
            this.quad.z[index] = z;
            this.quad.u[index] = u;
            this.quad.v[index] = v;
            this.vertexCount++;
            if (this.vertexCount == 4) {
                this.owner.sourceQuadCount++;
                this.emitQuad();
                this.vertexCount = 0;
            }
        }

        private void finish() {
            if (this.vertexCount != 0) {
                throw new IllegalStateException("Fluid renderer emitted an incomplete quad");
            }
        }

        private void emitQuad() {
            float edgeOneX = this.quad.x[1] - this.quad.x[0];
            float edgeOneY = this.quad.y[1] - this.quad.y[0];
            float edgeOneZ = this.quad.z[1] - this.quad.z[0];
            float edgeTwoX = this.quad.x[2] - this.quad.x[0];
            float edgeTwoY = this.quad.y[2] - this.quad.y[0];
            float edgeTwoZ = this.quad.z[2] - this.quad.z[0];
            float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
            float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
            float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
            float squaredNormalLength = normalX * normalX + normalY * normalY + normalZ * normalZ;
            if (squaredNormalLength > 1.0e-20F) {
                float inverseNormalLength = 1.0F / (float) Math.sqrt(squaredNormalLength);
                this.quad.normalX = normalX * inverseNormalLength;
                this.quad.normalY = normalY * inverseNormalLength;
                this.quad.normalZ = normalZ * inverseNormalLength;
            } else {
                this.quad.normalX = 0.0F;
                this.quad.normalY = 0.0F;
                this.quad.normalZ = 0.0F;
            }

            TextureAtlasSprite sprite = this.selectSprite();
            localizeUv(this.quad, sprite);
            boolean animated = this.stillSprite.contents().isAnimated()
                    || this.flowingSprite.contents().isAnimated()
                    || this.overlaySprite != null && this.overlaySprite.contents().isAnimated();
            this.owner.geometry.add(this.quad, new CapturedSectionGeometry.Surface(
                    this.tint,
                    this.tint,
                    this.tint,
                    this.tint,
                    this.transmissive
                            ? CapturedSectionGeometry.Layer.TRANSLUCENT
                            : CapturedSectionGeometry.Layer.OPAQUE,
                    (animated ? CapturedSectionGeometry.Surface.ANIMATED : 0)
                            | (this.water ? CapturedSectionGeometry.Surface.WATER : 0),
                    this.lightEmission,
                    this.owner.spriteResolver.resolve(sprite),
                    new CapturedSectionGeometry.FluidFacts(
                            this.localX,
                            this.localY,
                            this.localZ,
                            this.fullCeiling,
                            this.fullCollisionMask),
                    new CapturedSectionGeometry.BlockFacts(
                            (this.owner.sectionX << 4) + this.localX,
                            (this.owner.sectionY << 4) + this.localY,
                            (this.owner.sectionZ << 4) + this.localZ),
                    BuiltinMaterialClass.DEFAULT));
        }

        private TextureAtlasSprite selectSprite() {
            float u = 0.25F * (this.quad.u[0] + this.quad.u[1] + this.quad.u[2] + this.quad.u[3]);
            float v = 0.25F * (this.quad.v[0] + this.quad.v[1] + this.quad.v[2] + this.quad.v[3]);
            if (contains(this.stillSprite, u, v)) {
                return this.stillSprite;
            }
            if (this.overlaySprite != null && contains(this.overlaySprite, u, v)) {
                return this.overlaySprite;
            }
            return this.flowingSprite;
        }

        private static boolean contains(TextureAtlasSprite sprite, float u, float v) {
            return u >= Math.min(sprite.getU0(), sprite.getU1())
                    && u <= Math.max(sprite.getU0(), sprite.getU1())
                    && v >= Math.min(sprite.getV0(), sprite.getV1())
                    && v <= Math.max(sprite.getV0(), sprite.getV1());
        }
    }

    private record PeerFaceKey(int x, int y, int z, Direction direction) {
    }
}
