package dev.primept.mixin;

import dev.primept.capture.ExclusiveRenderBuffers;
import dev.primept.capture.TerrainPoolLease;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderBuffers.class)
public abstract class ExclusiveRenderBuffersMixin implements ExclusiveRenderBuffers {
    @Shadow @Final @Mutable private SectionBufferBuilderPack fixedBufferPack;
    @Shadow @Final @Mutable private SectionBufferBuilderPool sectionBufferPool;
    @Shadow @Final private StagedVertexBuffer stagedVertexBuffer;
    @Unique private int primept$poolCapacity;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void primept$capacity(int requested, CallbackInfo callback) {
        primept$poolCapacity = sectionBufferPool.getFreeBufferCount();
    }
    @Override
    public void primept$retireTerrainBuffers() {
        if (sectionBufferPool != null) {
            ((TerrainPoolLease)sectionBufferPool).primept$retireAndAwait(5_000_000_000L);
            sectionBufferPool.close();
            sectionBufferPool = null;
        }
        if (fixedBufferPack != null) {
            fixedBufferPack.close();
            fixedBufferPack = null;
        }
    }
    @Override
    public void primept$restoreTerrainBuffers() {
        if (sectionBufferPool != null || fixedBufferPack != null)
            throw new IllegalStateException("Vanilla terrain buffers are already owned");
        var fixed = new SectionBufferBuilderPack();
        try {
            var pool = SectionBufferBuilderPool.allocate(primept$poolCapacity);
            fixedBufferPack = fixed;
            sectionBufferPool = pool;
        } catch (RuntimeException | Error failure) {
            fixed.close();
            throw failure;
        }
    }
    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void primept$closeRetired(CallbackInfo callback) {
        if (sectionBufferPool == null) {
            stagedVertexBuffer.close();
            callback.cancel();
        }
    }
}
