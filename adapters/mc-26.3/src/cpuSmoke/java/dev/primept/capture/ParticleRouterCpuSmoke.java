package dev.primept.capture;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.world.phys.Vec3;

final class ParticleRouterCpuSmoke {
    static void run() throws Exception {
        Class.forName("net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer");
        var camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        var layer = SingleQuadParticle.Layer.TRANSLUCENT;
        var input = new QuadParticleRenderState() {
            @Override
            public void buildLayer(SingleQuadParticle.Layer layer, VertexConsumer consumer) {
                throw new AssertionError("Downstream particle expansion must be cut off");
            }
        };
        var oracle = new QuadParticleRenderState();
        for (var state : List.of(input, oracle))
            for (int i = 0; i < 5000; ++i)
                state.add(layer, i * .002f, 2, -3, .2f, .3f, .4f, .5f, .125f, .1f, .8f, .2f, .9f,
                          0x8070903f, 123);
        byte[] actual;
        DynamicCapture.begin(camera);
        try (var staged = new StagedVertexBuffer(() -> "particle route CPU", 1024)) {
            var draw = staged.appendDraw(DefaultVertexFormat.PARTICLE, PrimitiveTopology.QUADS);
            var field = DynamicCapture.class.getDeclaredField("DRAWS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var materials = (Map<StagedVertexBuffer.Draw, DynamicCapture.Material>)field.get(null);
            materials.put(draw, new DynamicCapture.Material(0, 2, true, true));
            var consumer = staged.getVertexBuilder(draw);
            TerrainRouterCpuSmoke.check(DynamicCapture.particles(input, layer, consumer),
                                        "Particle source accepted");
            TerrainRouterCpuSmoke.check(
                    ((dev.primept.mixin.BufferBuilderAccessor)consumer).primept$vertices() == 0,
                    "No Java expanded particle vertices");
            field = DynamicCapture.class.getDeclaredField("frame");
            field.setAccessible(true);
            var frame = (DynamicFrame)field.get(null);
            TerrainRouterCpuSmoke.check(frame.spanCount() == 1 && frame.vertexCount() == 20000,
                                        "5000 particles form one native source span");
            actual = frame.seal().toArray(ValueLayout.JAVA_BYTE);
        } finally {
            DynamicCapture.end();
            DynamicCapture.close();
        }
        byte[] expected;
        try (var frame = new DynamicFrame()) {
            frame.begin(1, 1, 0, 0, 0);
            frame.beginSpan(0, 2, 4);
            float[] p = new float[3], uv = new float[2];
            VertexConsumer sink = (VertexConsumer)Proxy.newProxyInstance(
                    ParticleRouterCpuSmoke.class.getClassLoader(),
                    new Class<?>[] {VertexConsumer.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                        case "addVertex" -> {
                            for (int a = 0; a < 3; ++a)
                                p[a] = (float)args[a];
                        }
                        case "setUv" -> {
                            uv[0] = (float)args[0];
                            uv[1] = (float)args[1];
                        }
                        case "setColor" -> {
                            if (args.length == 1)
                                frame.vertex(p[0], p[1], p[2], (int)args[0], uv[0], uv[1]);
                            else
                                frame.vertex(p[0], p[1], p[2],
                                             ((int)args[3] << 24) | ((int)args[0] << 16) |
                                                     ((int)args[1] << 8) | (int)args[2],
                                             uv[0], uv[1]);
                        }
                        }
                        return method.getReturnType() == void.class ? null : proxy;
                    });
            oracle.buildLayer(layer, sink);
            frame.endSpan();
            expected = frame.seal().toArray(ValueLayout.JAVA_BYTE);
        }
        // Normalize fixture identity only; preserve every source/appearance byte.
        java.nio.ByteBuffer.wrap(actual)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(16, 1)
                .putLong(24, 1);
        var a = new CaptureInbox.Sealed(
                1, List.of(new CaptureInbox.Batch(1, 0, 0, false, List.of(actual), actual.length)),
                0);
        var b = new CaptureInbox.Sealed(
                1,
                List.of(new CaptureInbox.Batch(1, 0, 0, false, List.of(expected), expected.length)),
                0);
        TerrainRouterCpuSmoke.write("particles", a, b);
        System.out.println(
                "PRIME_PT_PARTICLE_ROUTER_CPU_OK: 5000 billboards, one parameter span, zero Java expanded vertices");
    }
}
