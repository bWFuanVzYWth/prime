// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {
    @Unique
    private static final int PRIME_USAGE_STORAGE = 32;

    @ModifyArg(
            method = "allocateColorAttachment",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"),
            index = 1)
    private int prime$markMainColorStorage(int usage) {
        return usage | PRIME_USAGE_STORAGE;
    }
}
