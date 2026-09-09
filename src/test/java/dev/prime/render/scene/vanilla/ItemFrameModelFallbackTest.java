// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

final class ItemFrameModelFallbackTest {
    @Test
    void populatedFallbackSubmitsAsBlockModel() {
        BlockModelRenderState output = new BlockModelRenderState();
        BlockStateModelPart part = (BlockStateModelPart) Proxy.newProxyInstance(
                BlockStateModelPart.class.getClassLoader(),
                new Class<?>[] {BlockStateModelPart.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getQuads" -> List.of();
                    case "useAmbientOcclusion" -> false;
                    case "materialFlags" -> 0;
                    default -> null;
                });
        BlockStateModel model = modelThatAdds(part);

        assertTrue(ItemFrameModelFallback.populate(output, model));

        AtomicBoolean submitted = new AtomicBoolean();
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("submitBlockModel")) {
                        submitted.set(true);
                    }
                    return null;
                });
        output.submitWithZOffset(new PoseStack(), collector, 0, 0, 0);

        assertTrue(submitted.get());
    }

    @Test
    void emptyFallbackRemainsNonSubmitting() {
        BlockModelRenderState output = new BlockModelRenderState();

        assertFalse(ItemFrameModelFallback.populate(output, modelThatAdds(null)));
    }

    private static BlockStateModel modelThatAdds(BlockStateModelPart part) {
        return new BlockStateModel() {
            @Override
            public void collectParts(
                    RandomSource random, List<BlockStateModelPart> output) {
                if (part != null) {
                    output.add(part);
                }
            }

            @Override
            public Material.Baked particleMaterial() {
                return null;
            }

            @Override
            public int materialFlags() {
                return 0;
            }
        };
    }
}
