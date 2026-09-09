// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.client.PrimeRuntime;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GuiRenderState guiRenderState;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void prime$appendRrDebugHud(
            DeltaTracker deltaTracker,
            boolean renderLevel,
            boolean renderScreens,
            CallbackInfo ci) {
        List<String> lines = PrimeRuntime.instance().debugLines();
        if (lines.isEmpty() || this.guiRenderState.isHudHidden) {
            return;
        }
        this.guiRenderState.nextStratum();
        Matrix3x2f pose = new Matrix3x2f();
        for (int index = 0; index < lines.size(); index++) {
            this.guiRenderState.addText(new GuiTextRenderState(
                    this.minecraft.font,
                    Component.literal(lines.get(index)).getVisualOrderText(),
                    pose,
                    5,
                    5 + index * 10,
                    0xffffffff,
                    0x90000000,
                    true,
                    false,
                    null));
        }
    }
}
