package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.primept.capture.BlockEntityMapAccess;
import dev.primept.capture.BlockEntityCandidates;
import java.util.Map;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCopy.class)
public abstract class SectionCopyMixin {
    @Unique
    private static final boolean primept$readOnly = BlockEntityCandidates.known(SectionCopy.class);
    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntities()Ljava/util/Map;"))
    private Map<BlockPos, BlockEntity>
    primept$copySource(LevelChunk chunk, Operation<Map<BlockPos, BlockEntity>> original) {
        // Pinned constructor immediately copies the map to ImmutableMap. Unknown injected consumers escape normally.
        return primept$readOnly ? BlockEntityMapAccess.read(() -> original.call(chunk))
                                : original.call(chunk);
    }
}
