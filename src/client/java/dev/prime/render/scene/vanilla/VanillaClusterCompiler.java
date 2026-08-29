package dev.prime.render.scene.vanilla;

import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.terrain.CapturedCluster;
import dev.prime.render.terrain.ClusterSceneTranslator;
import dev.prime.render.terrain.ClusterTranslationInput;
import dev.prime.render.terrain.ClusterTranslationReplay;
import dev.prime.render.terrain.ClusterTranslationSettings;
import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.LabPbrMaterialSet;
import dev.prime.render.terrain.SectionCluster;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Captures and compiles one terrain cluster without exposing Minecraft assets to runtime. */
public final class VanillaClusterCompiler implements AutoCloseable {
    private final VanillaSceneInterpreter interpreter = new VanillaSceneInterpreter();
    private final TranslationReplayRecorder replayRecorder;

    public VanillaClusterCompiler() {
        this(TranslationReplayRecorder.fromSystemProperties());
    }

    VanillaClusterCompiler(TranslationReplayRecorder replayRecorder) {
        this.replayRecorder = Objects.requireNonNull(replayRecorder, "replayRecorder");
    }

    public CaptureSession beginCapture(Minecraft minecraft, ClientLevel level) {
        BlockStateModelSet blockModels = minecraft.getModelManager().getBlockStateModelSet();
        FluidStateModelSet fluidModels = minecraft.getModelManager().getFluidStateModelSet();
        BlockColors blockColors = minecraft.getBlockColors();
        TextureAtlas blockAtlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        SpriteFinder blockSpriteFinder = ((FabricTextureAtlas) (Object) blockAtlas).spriteFinder();
        VanillaAssetSnapshot assets = new VanillaAssetSnapshot(
                blockModels,
                fluidModels,
                blockColors,
                blockSpriteFinder,
                minecraft.options.cutoutLeaves().get());
        return new CaptureSession(level, assets);
    }

    /** Returns {@code null} until the complete horizontal halo is loaded. */
    public Capture capture(
            CaptureSession session,
            int clusterX,
            int clusterY,
            int clusterZ,
            int minimumSectionY,
            int maximumSectionY) {
        if (!hasCompleteNeighborhood(session.level, clusterX, clusterZ)) {
            return null;
        }
        ArrayList<VanillaSectionSnapshot> snapshots =
                new ArrayList<>(SectionCluster.SECTION_COUNT);
        for (int sectionZ = clusterZ;
                sectionZ < clusterZ + SectionCluster.SECTION_SIZE;
                sectionZ++) {
            for (int sectionY = clusterY;
                    sectionY < clusterY + SectionCluster.SECTION_SIZE;
                    sectionY++) {
                if (sectionY < minimumSectionY || sectionY > maximumSectionY) {
                    continue;
                }
                for (int sectionX = clusterX;
                        sectionX < clusterX + SectionCluster.SECTION_SIZE;
                        sectionX++) {
                    LevelChunk chunk = session.level.getChunkSource().getChunk(
                            sectionX, sectionZ, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        throw new IllegalStateException(
                                "Complete cluster neighborhood lost a loaded chunk");
                    }
                    if (chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY))
                            .hasOnlyAir()) {
                        continue;
                    }
                    long sectionKey = SectionPos.asLong(sectionX, sectionY, sectionZ);
                    snapshots.add(new VanillaSectionSnapshot(
                            sectionX,
                            sectionY,
                            sectionZ,
                            session.regionCache.createRegion(session.level, sectionKey)));
                }
            }
        }
        return new Capture(
                clusterX,
                clusterY,
                clusterZ,
                session.assets,
                List.copyOf(snapshots));
    }

    public CpuClusterMesh compile(
            Capture capture,
            LabPbrMaterialSet materials,
            ClusterTranslationSettings settings,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        Stage stage = Stage.SETUP;
        int sectionX = 0;
        int sectionY = 0;
        int sectionZ = 0;
        try {
            throwIfCancelled(cancelled);
            VanillaSpriteResolver spriteResolver = new VanillaSpriteResolver(materials);
            CapturedCluster.Builder captured = new CapturedCluster.Builder(
                    capture.clusterX, capture.clusterY, capture.clusterZ);
            for (VanillaSectionSnapshot snapshot : capture.snapshots) {
                throwIfCancelled(cancelled);
                stage = Stage.SECTION_COMPILATION;
                sectionX = snapshot.sectionX();
                sectionY = snapshot.sectionY();
                sectionZ = snapshot.sectionZ();
                CapturedSectionGeometry section = this.interpreter.compileSection(
                        new VanillaSectionCompileInput(
                                snapshot,
                                capture.assets,
                                capture.clusterX,
                                capture.clusterY,
                                capture.clusterZ),
                        spriteResolver);
                captured.add(sectionX, sectionY, sectionZ, section);
            }
            stage = Stage.CLUSTER_TRANSLATION;
            return this.translate(
                    new ClusterTranslationInput(captured.build(), materials, settings),
                    cancelled);
        } catch (CompilationCancelledException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new CompilationException(
                    throwable, stage, sectionX, sectionY, sectionZ);
        }
    }

    private CpuClusterMesh translate(
            ClusterTranslationInput input, BooleanSupplier cancelled) {
        long started = System.nanoTime();
        try {
            CpuClusterMesh result = ClusterSceneTranslator.translate(
                    input, () -> throwIfCancelled(cancelled));
            this.replayRecorder.record(
                    input,
                    ClusterTranslationReplay.Metadata.success(elapsedNanos(started)));
            return result;
        } catch (CompilationCancelledException exception) {
            this.replayRecorder.record(
                    input,
                    ClusterTranslationReplay.Metadata.failure(
                            ClusterTranslationReplay.Outcome.CANCELLED,
                            elapsedNanos(started),
                            exception));
            throw exception;
        } catch (Throwable throwable) {
            this.replayRecorder.record(
                    input,
                    ClusterTranslationReplay.Metadata.failure(
                            ClusterTranslationReplay.Outcome.FAILED,
                            elapsedNanos(started),
                            throwable));
            throw throwable;
        }
    }

    private static long elapsedNanos(long started) {
        return Math.max(System.nanoTime() - started, 0L);
    }

    private static void throwIfCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CompilationCancelledException();
        }
    }

    public static ClusterTranslationSettings translationSettings(
            boolean opacityMicromapSupported,
            int segmentTriangleTarget,
            int maxOpacity2StateSubdivisionLevel,
            int maxOpacity4StateSubdivisionLevel,
            boolean voxelSurfaces,
            float voxelSurfaceMaximumHeight) {
        return new ClusterTranslationSettings(
                opacityMicromapSupported,
                segmentTriangleTarget,
                maxOpacity2StateSubdivisionLevel,
                maxOpacity4StateSubdivisionLevel,
                voxelSurfaces,
                voxelSurfaceMaximumHeight,
                VanillaGeometryPolicy.VANILLA_PARITY.closeCoveredFluidGap(),
                VanillaGeometryPolicy.VANILLA_PARITY.suppressFluidFaceAgainstFullCollision());
    }

    private static boolean hasCompleteNeighborhood(
            ClientLevel level, int clusterX, int clusterZ) {
        int minimumChunkX = clusterX - SectionCluster.SNAPSHOT_HALO;
        int minimumChunkZ = clusterZ - SectionCluster.SNAPSHOT_HALO;
        int maximumChunkX = clusterX + SectionCluster.SECTION_SIZE;
        int maximumChunkZ = clusterZ + SectionCluster.SECTION_SIZE;
        for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
            for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                if (level.getChunkSource().getChunk(
                                chunkX, chunkZ, ChunkStatus.FULL, false)
                        == null) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void close() {
        this.interpreter.close();
    }

    public static final class CaptureSession {
        private final ClientLevel level;
        private final VanillaAssetSnapshot assets;
        private final RenderRegionCache regionCache = new RenderRegionCache();

        private CaptureSession(ClientLevel level, VanillaAssetSnapshot assets) {
            this.level = Objects.requireNonNull(level, "level");
            this.assets = Objects.requireNonNull(assets, "assets");
        }
    }

    public static final class Capture {
        private final int clusterX;
        private final int clusterY;
        private final int clusterZ;
        private final VanillaAssetSnapshot assets;
        private final List<VanillaSectionSnapshot> snapshots;

        private Capture(
                int clusterX,
                int clusterY,
                int clusterZ,
                VanillaAssetSnapshot assets,
                List<VanillaSectionSnapshot> snapshots) {
            this.clusterX = clusterX;
            this.clusterY = clusterY;
            this.clusterZ = clusterZ;
            this.assets = Objects.requireNonNull(assets, "assets");
            this.snapshots = List.copyOf(snapshots);
        }

        public boolean isEmpty() {
            return this.snapshots.isEmpty();
        }
    }

    public enum Stage {
        SETUP,
        SECTION_COMPILATION,
        CLUSTER_TRANSLATION
    }

    /** Expected control flow when a newer cluster generation supersedes this build. */
    public static final class CompilationCancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CompilationCancelledException() {
            super("Terrain cluster build superseded");
        }
    }

    public static final class CompilationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Stage stage;
        private final int sectionX;
        private final int sectionY;
        private final int sectionZ;

        private CompilationException(
                Throwable cause,
                Stage stage,
                int sectionX,
                int sectionY,
                int sectionZ) {
            super(cause);
            this.stage = stage;
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
        }

        public Stage stage() {
            return this.stage;
        }

        public int sectionX() {
            return this.sectionX;
        }

        public int sectionY() {
            return this.sectionY;
        }

        public int sectionZ() {
            return this.sectionZ;
        }
    }
}
