package dev.primept.capture;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;

/** The geometry cache requires the known Fabric wrapper/state contract. */
public final class BlockGeometryCapabilities {
    private BlockGeometryCapabilities() {}
    private static final class Methods {
        static final boolean SUPPORTED =
                known(BlockStateModelWrapper.class,
                      "net.fabricmc.fabric.mixin.client.renderer.block.render.BlockStateModelWrapperMixin") &&
                known(BlockModelRenderState.class,
                      "net.fabricmc.fabric.mixin.client.renderer.block.render.BlockModelRenderStateMixin");
    }
    public static boolean supported() {
        return Renderer.get().getClass() == IndigoRenderer.class && Methods.SUPPORTED;
    }
    private static boolean known(Class<?> type, String fabricMixin) {
        for (var method : type.getDeclaredMethods())
            for (var annotation : method.getDeclaredAnnotations()) {
                if (!annotation.annotationType().getName().equals(
                            "org.spongepowered.asm.mixin.transformer.meta.MixinMerged"))
                    continue;
                try {
                    String origin = (String)annotation.annotationType().getMethod("mixin").invoke(
                            annotation);
                    if (!origin.startsWith("dev.primept.mixin.") && !origin.equals(fabricMixin) &&
                        !origin.equals(
                                "net.fabricmc.fabric.mixin.client.rendering.renderstate.RenderStateMixin") &&
                        !origin.equals(
                                "net.fabricmc.fabric.mixin.client.rendering.renderstate.BlockModelRenderStateMixin"))
                        return false;
                } catch (ReflectiveOperationException exception) {
                    return false;
                }
            }
        return true;
    }
}
