package dev.primept.mixin;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.renderer.state.level.QuadParticleRenderState$Storage")
public interface ParticleStorageAccessor {
    @Invoker("forEachParticle")
    void primept$forEach(QuadParticleRenderState.ParticleConsumer consumer);
}
