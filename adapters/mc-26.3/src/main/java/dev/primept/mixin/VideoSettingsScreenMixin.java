package dev.primept.mixin;

import dev.primept.PrimeSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void primept$settings(CallbackInfo callback) {
        ((OptionsSubScreenAccessor)this)
                .primept$list()
                .addBig(Button.builder(Component.translatable("primept.settings.title"),
                                       button
                                       -> Minecraft.getInstance().gui.setScreen(
                                               new PrimeSettingsScreen(
                                                       (VideoSettingsScreen)(Object)this)))
                                .build());
    }
}
