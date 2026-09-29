package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.primept.PrimeSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    @ModifyExpressionValue(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/gui/layouts/GridLayout;createRowHelper(I)Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;"))
    private GridLayout.RowHelper
    primept$settings(GridLayout.RowHelper rows) {
        rows.addChild(Button.builder(Component.translatable("primept.settings.title"),
                                     button
                                     -> Minecraft.getInstance().gui.setScreen(
                                             new PrimeSettingsScreen((OptionsScreen)(Object)this)))
                              // Two vanilla 150px columns and their 8px gap.
                              .width(308)
                              .build(),
                      2);
        return rows;
    }
}
