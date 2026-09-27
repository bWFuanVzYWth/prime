package dev.primept.capture;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/** Version-local source arrays; no dependency crosses the native call. */
public interface ParticleSource {
    void primept$route(SingleQuadParticle.Layer layer,
                       QuadParticleRenderState.ParticleConsumer consumer);
}
