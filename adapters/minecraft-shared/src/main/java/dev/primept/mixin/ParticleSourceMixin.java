package dev.primept.mixin;
import java.util.Map;
import dev.primept.capture.ParticleSource;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

@Mixin(QuadParticleRenderState.class)
public abstract class ParticleSourceMixin implements ParticleSource {
    @Shadow @Final private Map<SingleQuadParticle.Layer, ?> particles;
    public void primept$route(SingleQuadParticle.Layer layer,
                              QuadParticleRenderState.ParticleConsumer consumer) {
        Object storage = particles.get(layer);
        if (storage != null)
            ((ParticleStorageAccessor)storage).primept$forEach(consumer);
    }
}
