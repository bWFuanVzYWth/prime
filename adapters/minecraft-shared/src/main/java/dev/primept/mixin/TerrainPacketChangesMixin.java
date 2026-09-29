package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.primept.capture.ExclusiveTerrainCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Retain the real invalidation call and its footprint, while preserving its diagnostic origin. */
@Mixin(ClientPacketListener.class)
public abstract class TerrainPacketChangesMixin {
    @WrapOperation(
            method = "readSectionList",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionDirtyWithNeighbors(III)V"))
    private void
    primept$lightPacket(ClientLevel level, int x, int y, int z, Operation<Void> original) {
        ExclusiveTerrainCapture.lightNotification(level,
                                                  ExclusiveTerrainCapture.LightNotification.PACKET);
        original.call(level, x, y, z);
    }
}
