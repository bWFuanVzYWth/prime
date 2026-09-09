// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;

/** Capture bridge implemented on Minecraft model parts by the client adapter. */
public interface PrimeModelPart {
    Map<String, ModelPart> prime$children();

    void prime$compile(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int lightCoords,
            int overlayCoords,
            int color);
}
