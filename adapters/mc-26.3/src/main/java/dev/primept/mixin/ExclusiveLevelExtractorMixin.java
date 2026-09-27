package dev.primept.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.primept.capture.ExclusiveTerrainCapture;
import dev.primept.capture.ExclusiveLevelExtractorAccess;
import dev.primept.capture.BlockEntityCandidates;
import dev.primept.PrimeClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class ExclusiveLevelExtractorMixin implements ExclusiveLevelExtractorAccess {
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow private ClientLevel level;
    @Shadow private SectionUpdateTracker sectionUpdateTracker;
    @Unique
    private final BlockEntityCandidates primept$blockEntityCandidates = new BlockEntityCandidates();

    @Override
    public void primept$discardTerrainTracker() {
        sectionUpdateTracker = null;
        primept$blockEntityCandidates.clear();
    }

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void primept$window(DeltaTracker delta, Camera camera, float partial,
                                CallbackInfo callback) {
        if (PrimeClient.offlineActive()) {
            // No world extraction runs to clear the preceding frame. Keep only the host camera state.
            levelRenderState.reset();
            if (level != null)
                level.getChunkSource().flipUpdateTrackingSets();
            callback.cancel();
            return;
        }
        ExclusiveTerrainCapture.prepareWindow(camera.position());
    }
    @Inject(method = "extract", at = @At("TAIL"))
    private void primept$consumeChunkEvents(CallbackInfo callback) {
        // The inactive vanilla scheduling path is absent, so it cannot consume this shared cache journal.
        if (ExclusiveTerrainCapture.vanillaSuspended() && level != null)
            level.getChunkSource().flipUpdateTrackingSets();
    }
    @Inject(method = "allChanged", at = @At("TAIL"))
    private void primept$invalidate(CallbackInfo callback) {
        primept$blockEntityCandidates.clear();
        if (ExclusiveTerrainCapture.vanillaSuspended()) {
            sectionUpdateTracker = null;
            ExclusiveTerrainCapture.invalidateAll();
        }
    }
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void primept$dirty(int x, int y, int z, boolean playerChanged, CallbackInfo callback) {
        if (ExclusiveTerrainCapture.vanillaSuspended()) {
            ExclusiveTerrainCapture.dirtySection(x, y, z);
            callback.cancel();
        }
    }
    @Inject(method = "applyFrustum", at = @At("HEAD"), cancellable = true)
    private void primept$noRasterGraph(Frustum frustum, CallbackInfo callback) {
        if (ExclusiveTerrainCapture.vanillaSuspended())
            callback.cancel();
    }
    @Redirect(
            method = "isEntityVisible",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z"))
    private boolean
    primept$sourceVisibility(LevelRenderer renderer, BlockPos pos, long fade) {
        // Keep the actual dispatcher distance/frustum/passenger predicate; it must not consult a destroyed raster mesh.
        return ExclusiveTerrainCapture.vanillaSuspended()
                ? ExclusiveTerrainCapture.sourceSectionReady(SectionPos.asLong(pos))
                : renderer.isSectionCompiledAndVisible(pos, fade);
    }
    @Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"), cancellable = true)
    private void primept$sourceBlockEntities(Camera camera, float partial, LevelRenderState output,
                                             CallbackInfo callback) {
        if (!ExclusiveTerrainCapture.vanillaSuspended())
            return;
        callback.cancel();
        if (level == null)
            return;
        var dispatcher = levelRenderer.blockEntityRenderDispatcher();
        var globals = level.getGloballyRenderedBlockEntities();
        var pose = new PoseStack();
        var cameraPos = camera.position();
        primept$blockEntityCandidates.prepare(dispatcher, PrimeClient.CAPTURE.epoch());
        // PT coverage follows loaded source columns, not visibility of an absent raster mesh. The actual renderer's
        // distance predicate still runs in tryExtractRenderState. Global BEs retain vanilla's separate global path.
        for (var chunk : ExclusiveTerrainCapture.loadedChunks())
            for (var blockEntity : chunk.getBlockEntities().values()) {
                if (primept$blockEntityCandidates.outsideDefaultRange(blockEntity, cameraPos))
                    continue;
                if (blockEntity.isRemoved() || globals.contains(blockEntity))
                    continue;
                var pos = blockEntity.getBlockPos();
                var progresses = level.destructionProgress().get(pos.asLong());
                CrumblingOverlay overlay = null;
                if (progresses != null && !progresses.isEmpty()) {
                    pose.pushPose();
                    pose.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y,
                                   pos.getZ() - cameraPos.z);
                    overlay = new CrumblingOverlay(progresses.last().getProgress(), pose.last());
                    pose.popPose();
                }
                primept$blockEntityCandidates.recordTryExtract(false);
                var state = dispatcher.tryExtractRenderState(blockEntity, partial, overlay, false);
                if (state != null)
                    output.blockEntityRenderStates.add(state);
            }
        var iterator = globals.iterator();
        while (iterator.hasNext()) {
            var blockEntity = iterator.next();
            if (blockEntity.isRemoved())
                iterator.remove();
            else {
                primept$blockEntityCandidates.recordTryExtract(true);
                var state = dispatcher.tryExtractRenderState(blockEntity, partial, null, true);
                if (state != null)
                    output.blockEntityRenderStates.add(state);
            }
        }
        primept$blockEntityCandidates.finishFrame();
    }
}
