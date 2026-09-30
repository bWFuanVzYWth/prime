package dev.primept.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.NativeImage;
import dev.primept.mixin.SpriteConsumerAccessor;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;

/** Real transformed sprite bindings from both host versions, without a graphics device. */
final class ModelSpriteCpuSmoke {
    static void run() throws Exception {
        var contents = new SpriteContents(Identifier.withDefaultNamespace("prime_sprite_binding"),
                                          new FrameSize(16, 16), new NativeImage(16, 16, true));
        try {
            var constructor = TextureAtlasSprite.class.getDeclaredConstructor(
                    Identifier.class, SpriteContents.class, int.class, int.class, int.class,
                    int.class, int.class);
            constructor.setAccessible(true);
            var sprite = constructor.newInstance(TextureAtlas.LOCATION_BLOCKS, contents, 32, 64, 4,
                                                 8, 0);
            var delegate = (VertexConsumer)java.lang.reflect.Proxy.newProxyInstance(
                    VertexConsumer.class.getClassLoader(), new Class<?>[] {VertexConsumer.class},
                    (owner, method, arguments) -> {
                        throw new AssertionError("Binding must not invoke downstream output");
                    });
            var wrapper = new SpriteCoordinateExpander(delegate, sprite);
            var binding = (SpriteConsumerAccessor)(Object)wrapper;
            if (binding.primept$mapping() != sprite || binding.primept$delegate() != delegate)
                throw new AssertionError("Sprite source and downstream delegate must be preserved");
        } finally {
            contents.close();
        }
    }
}
