package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.primept.capture.BlockEntityMapAccess;
import dev.primept.capture.ExclusiveTerrainCapture;
import java.util.Map;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class BlockEntityMembershipMixin implements BlockEntityMapAccess.Membership {
    @Unique private boolean primept$escapedBlockEntities;
    @Override
    public boolean primept$escapedBlockEntities() {
        return primept$escapedBlockEntities;
    }
    @Inject(method = {"setBlockEntity", "removeBlockEntity", "clearAllBlockEntities"},
            at = @At("TAIL"))
    private void primept$membership(CallbackInfo callback) {
        ExclusiveTerrainCapture.blockEntitiesChanged((LevelChunk)(Object)this);
    }
    @Inject(method = "getBlockEntities", at = @At("HEAD"))
    private void primept$escape(CallbackInfoReturnable<?> callback) {
        if (!BlockEntityMapAccess.reading() && !primept$escapedBlockEntities) {
            primept$escapedBlockEntities = true;
            ExclusiveTerrainCapture.blockEntitiesChanged((LevelChunk)(Object)this);
        }
    }
    @WrapOperation(
            method =
                    "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;",
                     ordinal = 1))
    private Object
    primept$removedStale(Map<?, ?> map, Object key, Operation<Object> original) {
        Object value = original.call(map, key);
        if (value != null)
            ExclusiveTerrainCapture.blockEntitiesChanged((LevelChunk)(Object)this);
        return value;
    }
}
