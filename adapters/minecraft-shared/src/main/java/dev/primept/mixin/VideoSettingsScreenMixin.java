package dev.primept.mixin;

import dev.primept.PrimeVideoOptions;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    @Shadow @Final private LinearLayout header;
    @Shadow private StringWidget restartWarning;
    @Unique private PrimeVideoOptions primept$options;

    protected VideoSettingsScreenMixin(Screen parent, Options options, Component title) {
        super(parent, options, title);
    }

    @Override
    protected void init() {
        // Screen rebuilds its widget registries; both vanilla layouts retain their children.
        layout.removeChildren();
        header.removeChildren();
        restartWarning = null;
        super.init();
    }

    @Inject(method = "addOptions()V", at = @At("HEAD"))
    private void primept$addOptions(CallbackInfo callback) {
        primept$options = PrimeVideoOptions.addTo(list, this, this::rebuildWidgets);
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void primept$tick(CallbackInfo callback) {
        if (primept$options != null)
            primept$options.tick();
    }

    @Inject(method = "removed()V", at = @At("TAIL"))
    private void primept$removed(CallbackInfo callback) {
        if (primept$options != null)
            primept$options.removed();
    }
}
