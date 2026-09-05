package dev.prime.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prime.client.PrimeRuntime;
import dev.prime.render.scene.vanilla.PrimeEntityFrustum;
import dev.prime.render.scene.vanilla.VanillaSceneBoundary;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.SortedSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adapts vanilla scene extraction to Prime's independently maintained terrain. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow private @Nullable ClientLevel level;

    @Redirect(
            method = "isEntityVisible",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean prime$routeEntityFrustum(
            EntityRenderDispatcher dispatcher,
            Entity entity,
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ) {
        return dispatcher.shouldRender(
                entity,
                PrimeRuntime.instance().shouldReplaceWorld()
                        ? PrimeEntityFrustum.INSTANCE
                        : frustum,
                cameraX,
                cameraY,
                cameraZ);
    }

    @Redirect(
            method = "isEntityVisible",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean prime$routeEntitySectionVisibility(
            LevelRenderer renderer, BlockPos position) {
        return VanillaSceneBoundary.includesEntitySection(
                PrimeRuntime.instance().shouldReplaceWorld(),
                renderer.isSectionCompiledAndVisible(position));
    }

    @Inject(method = "extractVisibleBlockEntities", at = @At("RETURN"))
    private void prime$supplementLoadedBlockEntities(
            Camera camera,
            float partialTick,
            LevelRenderState output,
            CallbackInfo ci) {
        if (!PrimeRuntime.instance().shouldReplaceWorld()) {
            return;
        }
        ClientLevel currentLevel = this.level;
        if (currentLevel == null) {
            return;
        }

        LongOpenHashSet extractedPositions = new LongOpenHashSet(
                output.blockEntityRenderStates.size());
        for (BlockEntityRenderState state : output.blockEntityRenderStates) {
            extractedPositions.add(state.blockPos.asLong());
        }
        Vec3 cameraPos = camera.position();
        int centerChunkX = (int) Math.floor(cameraPos.x()) >> 4;
        int centerChunkZ = (int) Math.floor(cameraPos.z()) >> 4;
        int radius = this.minecraft.options.getEffectiveRenderDistance();
        ClientChunkCache chunks = currentLevel.getChunkSource();
        BlockEntityRenderDispatcher dispatcher =
                this.levelRenderer.blockEntityRenderDispatcher();
        PoseStack poseStack = new PoseStack();
        for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                LevelChunk chunk = chunks.getChunk(
                        chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                var blockEntities = chunk.getBlockEntities();
                // Most loaded chunks have none. Avoid a values view and iterator per empty
                // chunk while still extracting every off-screen block entity needed by rays.
                if (blockEntities.isEmpty()) continue;
                for (BlockEntity blockEntity : blockEntities.values()) {
                    long position = blockEntity.getBlockPos().asLong();
                    if (extractedPositions.contains(position)) {
                        continue;
                    }
                    BlockEntityRenderState state = dispatcher.tryExtractRenderState(
                            blockEntity,
                            partialTick,
                            prime$crumblingOverlay(
                                    currentLevel, blockEntity, cameraPos, poseStack),
                            false);
                    if (state != null) {
                        output.blockEntityRenderStates.add(state);
                        extractedPositions.add(position);
                    }
                }
            }
        }
    }

    private static ModelFeatureRenderer.@Nullable CrumblingOverlay prime$crumblingOverlay(
            ClientLevel level,
            BlockEntity blockEntity,
            Vec3 cameraPos,
            PoseStack poseStack) {
        BlockPos position = blockEntity.getBlockPos();
        SortedSet<BlockDestructionProgress> progresses =
                level.destructionProgress().get(position.asLong());
        if (progresses == null || progresses.isEmpty()) {
            return null;
        }
        poseStack.pushPose();
        poseStack.translate(
                position.getX() - cameraPos.x(),
                position.getY() - cameraPos.y(),
                position.getZ() - cameraPos.z());
        ModelFeatureRenderer.CrumblingOverlay result =
                new ModelFeatureRenderer.CrumblingOverlay(
                        progresses.last().getProgress(), poseStack.last());
        poseStack.popPose();
        return result;
    }

    // setSectionDirty also reports light-only updates; Prime does not consume vanilla light data.
    @Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void prime$markBlockDirty(BlockPos position, int updateFlags, CallbackInfo ci) {
        PrimeRuntime.instance().invalidateBlocks(
                position.getX(),
                position.getY(),
                position.getZ(),
                position.getX(),
                position.getY(),
                position.getZ());
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void prime$markBlocksDirty(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ,
            CallbackInfo ci) {
        PrimeRuntime.instance().invalidateBlocks(
                minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    @Inject(method = "allChanged()V", at = @At("TAIL"))
    private void prime$invalidateTerrain(CallbackInfo ci) {
        PrimeRuntime.instance().invalidateAll();
    }
}
