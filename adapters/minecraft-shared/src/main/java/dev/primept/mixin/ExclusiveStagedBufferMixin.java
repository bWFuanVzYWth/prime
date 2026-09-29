package dev.primept.mixin;

import dev.primept.capture.CaptureStagedBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;

@Mixin(StagedVertexBuffer.class)
public abstract class ExclusiveStagedBufferMixin implements CaptureStagedBuffer {
    @Shadow @Final private List<StagedVertexBuffer.Draw> draws;
    @Shadow protected abstract void finishLastVertexBuilder();

    @Override
    public void primept$drainCapturedSource() {
        // Draw.append is the source observation point. Finish the last builder BEFORE freeing any page.
        finishLastVertexBuilder();
        for (var draw : draws)
            ((ExclusiveStagedDrawAccess)draw).primept$freeVertexData();
        // PreparedFrame.close retains the real finishExecute/endDraw sequence.
    }
}
