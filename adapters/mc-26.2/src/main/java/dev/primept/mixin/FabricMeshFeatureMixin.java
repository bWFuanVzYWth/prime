package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.primept.PrimeClient;
import dev.primept.capture.FabricMeshCapture;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.submit.ExtendedBlockModelSubmit;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.ExtendedBlockModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ExtendedBlockModelFeatureRenderer.class)
public abstract class FabricMeshFeatureMixin {
    @Shadow private ExtendedBlockModelSubmit submit;
    @WrapOperation(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/Mesh;outputTo(Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;)V"))
    private void
    primept$mesh(Mesh mesh, QuadEmitter emitter, Operation<Void> original) {
        if (!FabricMeshCapture.output(submit, mesh, PrimeClient.exclusiveFrameReady()))
            original.call(mesh, emitter);
    }
}
