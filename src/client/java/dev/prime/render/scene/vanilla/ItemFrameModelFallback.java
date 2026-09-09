// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import java.util.List;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Restores vanilla item-frame models when the fake block-state model resolves without parts. */
public final class ItemFrameModelFallback {
    private static final ModelEntry ITEM_FRAME = entry("item_frame");
    private static final ModelEntry ITEM_FRAME_MAP = entry("item_frame_map");
    private static final ModelEntry GLOW_ITEM_FRAME = entry("glow_item_frame");
    private static final ModelEntry GLOW_ITEM_FRAME_MAP = entry("glow_item_frame_map");

    private ItemFrameModelFallback() {
    }

    public static void register() {
        ModelLoadingPlugin.register(context -> {
            register(context, ITEM_FRAME);
            register(context, ITEM_FRAME_MAP);
            register(context, GLOW_ITEM_FRAME);
            register(context, GLOW_ITEM_FRAME_MAP);
        });
    }

    public static void restoreIfMissing(
            ItemFrameRenderState state,
            @Nullable List<BlockStateModelPart> modelParts,
            boolean hasSpecialRenderer) {
        if (state.isInvisible
                || hasSpecialRenderer
                || (modelParts != null && !modelParts.isEmpty())) {
            return;
        }
        FabricModelManager modelManager =
                (FabricModelManager) Minecraft.getInstance().getModelManager();
        BlockStateModel model = modelManager.getModel(entry(state).key);
        if (model != null) {
            populate(state.frameModel, model);
        }
    }

    static boolean populate(BlockModelRenderState output, BlockStateModel model) {
        List<BlockStateModelPart> parts =
                output.setupModel(new Matrix4f(), model.hasMaterialFlag(1));
        model.collectParts(output.scratchRandomSource(42L), parts);
        return !parts.isEmpty();
    }

    private static ModelEntry entry(ItemFrameRenderState state) {
        if (state.isGlowFrame) {
            return state.mapId == null ? GLOW_ITEM_FRAME : GLOW_ITEM_FRAME_MAP;
        }
        return state.mapId == null ? ITEM_FRAME : ITEM_FRAME_MAP;
    }

    private static ModelEntry entry(String name) {
        Identifier model = Identifier.withDefaultNamespace("block/" + name);
        return new ModelEntry(
                model,
                ExtraModelKey.create(() -> "prime:" + name + "_fallback"));
    }

    private static void register(
            ModelLoadingPlugin.Context context, ModelEntry entry) {
        context.addModel(
                entry.key,
                SimpleUnbakedExtraModel.blockStateModel(entry.model));
    }

    private record ModelEntry(
            Identifier model, ExtraModelKey<BlockStateModel> key) {
    }
}
