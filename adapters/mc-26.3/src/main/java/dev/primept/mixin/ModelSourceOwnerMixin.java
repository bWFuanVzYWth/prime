package dev.primept.mixin;

import dev.primept.capture.ModelCapture;
import dev.primept.capture.ModelSourceOwner;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({Entity.class, BlockEntity.class})
public abstract class ModelSourceOwnerMixin implements ModelSourceOwner {
    @Unique private ModelCapture.Source primept$modelSource;
    public ModelCapture.Source primept$modelSource() {
        return primept$modelSource;
    }
    public void primept$modelSource(ModelCapture.Source source) {
        primept$modelSource = source;
    }
}
